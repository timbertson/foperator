package net.gfxmonk.foperator

import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Sink
import cats.effect.Resource
import cats.implicits._
import monix.catnap.MVar
import monix.eval.Task
import monix.execution.atomic.Atomic
import monix.execution.{Ack, Cancelable, Scheduler}
import monix.reactive.observers.Subscriber
import monix.reactive.{Consumer, Observable}
import net.gfxmonk.foperator.ResourceMirrorImpl.IdSubscriber
import net.gfxmonk.foperator.internal.Logging
import play.api.libs.json.Format
import skuber.api.client.{EventType, KubernetesClient, LoggingContext, WatchEvent}
import skuber.json.format.ListResourceFormat
import skuber.{LabelSelector, ListOptions, ListResource, ObjectResource, ResourceDefinition}

trait ResourceUpdates[T] {
  def ids: Observable[Event[Id[T]]]
}

trait ResourceMirror[T] extends ResourceUpdates[T] {
  def all: Task[Map[Id[T], ResourceState[T]]]
  def active: Task[Map[Id[T], T]] = all.map(_.mapFilter(ResourceState.active))

  def allValues: Observable[ResourceState[T]] = Observable.fromTask(all).concatMap(map => Observable.from(map.values))
  def activeValues: Observable[T] = allValues.mapFilter(ResourceState.active)

  def get(id: Id[T]): Task[Option[ResourceState[T]]] = {
    all.map(_.get(id))
  }

  def getActive(id: Id[T]): Task[Option[T]] = get(id).map {
    case Some(ResourceState.Active(value)) => Some(value)
    case Some(ResourceState.SoftDeleted(_)) | None => None
  }
}

object ResourceMirror extends Logging {
  type ResourceMap[T] = Map[Id[T], T]
  type ResourceStateMap[T] = Map[Id[T], ResourceState[T]]

  trait Builder[T<: ObjectResource] {
    // This could almost be a cats Resource, except our `use` ensures that asynchronous failure in the watch
    // process cancels the user and results in an error
    def use[R](consume: ResourceMirror[T] => Task[R]): Task[R]
  }

  def all[T<: ObjectResource](
    implicit fmt: Format[T], rd: ResourceDefinition[T], lc: LoggingContext, materializer: ActorMaterializer,
    client: KubernetesClient
  ): Builder[T] = providerImpl(ListOptions())

  def forSelector[T<: ObjectResource](labelSelector: LabelSelector)(
    implicit fmt: Format[T], rd: ResourceDefinition[T], lc: LoggingContext, materializer: ActorMaterializer,
    client: KubernetesClient
  ): Builder[T] = providerImpl(ListOptions(labelSelector = Some(labelSelector)))

  def forOptions[T<: ObjectResource](listOptions: ListOptions)(
    implicit fmt: Format[T], rd: ResourceDefinition[T], lc: LoggingContext, materializer: ActorMaterializer,
    client: KubernetesClient
  ): Builder[T] = providerImpl(listOptions)

  private def providerImpl[T<: ObjectResource](listOptions: ListOptions)(
    implicit fmt: Format[T], rd: ResourceDefinition[T], lc: LoggingContext, materializer: ActorMaterializer,
    client: KubernetesClient
  ): Builder[T] = new Builder[T] {
    override def use[R](consume: ResourceMirror[T] => Task[R]): Task[R] = {
      resource(listOptions).use { tracker =>
        // tracker.future never completes except for error, which will
        // cancel `consume()` and raise an error
        Task.race(Task.fromFuture(tracker.future), consume(tracker)).flatMap {
          case Left(_) => Task.raiseError(new IllegalStateException("infinite loop terminated"))
          case Right(result) => Task.pure(result)
        }
      }
    }
  }

  private def resource[T<: ObjectResource](listOptions: ListOptions)(
    implicit fmt: Format[T], rd: ResourceDefinition[T], lc: LoggingContext, materializer: ActorMaterializer,
    client: KubernetesClient
  ): Resource[Task,ResourceMirrorImpl[T]] = {
    Resource.fromAutoCloseable[Task, ResourceMirrorImpl[T]] {
      ResourceMirrorImpl.mvar[T].flatMap { listeners =>
        Task.deferFutureAction { implicit scheduler =>
          implicit val lrf = ListResourceFormat[T]
          client.listWithOptions[ListResource[T]](listOptions).map { listResource =>
            val source = client.watchWithOptions[T](listOptions.copy(
              resourceVersion = Some(listResource.resourceVersion),
              timeoutSeconds = Some(30) // TODO
            ))
            val updates = Observable.fromReactivePublisher(source.runWith(Sink.asPublisher(fanout=false)))
            logger.debug(s"ResourceMirror[${rd.spec.names.kind}] in sync, watching for updates")
            new ResourceMirrorImpl[T](listResource.toList, updates, listeners)
          }
        }
      }
    }
  }
}

object ResourceMirrorImpl {
  private [foperator] type IdSubscriber[T] = Event[Id[T]] => Task[Unit]

  private [foperator] def mvar[T] = MVar[Task].of(Set.empty[IdSubscriber[T]])

  private [foperator] def apply[T<: ObjectResource](initial: List[T], updates: Observable[WatchEvent[T]])(implicit scheduler: Scheduler, rd: ResourceDefinition[T]) =
    mvar[T].map(listeners => new ResourceMirrorImpl(initial, updates, listeners))
}

/**
 * ResourceMirror provides:
 *  - A snapshot of the current state of a set of resources
 *  - An observable tracking the ID of changed resources.
 *    Subscribers to this observer MUST NOT backpressure, as that could cause the
 *    watcher (and other observers) to fall behind.
 *    In practice, this is typically consumed by Dispatcher, which doesn't backpressure.
 */
private [foperator] class ResourceMirrorImpl[T<: ObjectResource](
  initial: List[T],
  updates: Observable[WatchEvent[T]],
  listeners: MVar[Task, Set[IdSubscriber[T]]]
)(implicit scheduler: Scheduler, rd: ResourceDefinition[T])
  extends AutoCloseable with ResourceMirror[T] with Logging {
  import ResourceMirrorImpl._
  private val state = Atomic(initial.map(obj => Id.of(obj) -> ResourceState.of(obj)).toMap)
  private val logIdCommon = s"${rd.spec.names.kind}-${this.hashCode}"

  private def transformSubscribers(fn: Set[IdSubscriber[T]] => Set[IdSubscriber[T]]): Task[Unit] = {
    listeners.take.flatMap(subs => listeners.put(fn(subs)))
  }

  private [foperator] val future = updates.consumeWith(Consumer.foreachEval { (event:WatchEvent[T]) =>
    val id = Id.of(event._object)
    logger.debug(s"[${logIdCommon}] Saw event ${event._type} on ${id}")
    val input = event._type match {
      case EventType.ERROR =>
        logger.error(s"Error event in kubernetes watch: ${event}")
        state.transform(_.removed(id))
        None

      case EventType.DELETED => {
        state.transform(_.removed(id))
        Some(Event.HardDeleted(id))
      }
      case EventType.ADDED | EventType.MODIFIED => {
        state.transform(_.updated(id, ResourceState.of(event._object)))
        Some(Event.Updated(id))
      }
    }
    input.map { input =>
      listeners.read.flatMap { listenerFns =>
        logger.trace(s"[$logIdCommon] Emitting to ${listenerFns.size} listeners")
        listenerFns.toList.traverse(_(input)).void
      }
    }.getOrElse(Task.unit)
  }).runToFuture

  def ids: Observable[Event[Id[T]]] = {
    new Observable[Event[Id[T]]] {
      override def unsafeSubscribeFn(subscriber: Subscriber[Event[Id[T]]]): Cancelable = {
        val logId = s"[${logIdCommon}-${subscriber.hashCode}]"
        logger.trace(s"$logId Adding subscriber")
        def emit(id: Event[Id[T]]): Task[Unit] = {
          Task.defer {
            logger.trace(s"$logId Emitting item $id")
            val future = subscriber.onNext(id)
            future.value match {
              case Some(value) => Task.fromTry(value)
              case None => {
                logger.warn(s"$logId Subscriber not synchronously accepting new items." +
                  " This will delay updates to every subscriber, you should make this synchronous (buffering if necessary).")
                Task.fromFuture(future)
              }
            }
          }.flatMap {
            case Ack.Continue => Task.unit
            case Ack.Stop => cancel
          }
        }
        def cancel = transformSubscribers { s =>
          logger.trace(s"$logId Removing subscriber")
          s - emit
        }

        (for {
          // take listeners mutex while sending initial set, so that
          // concurrent updates are guaranteed to see the new subscriber
          listenerFns <- listeners.take
          _ <- state.get.keys.toList.traverse(id => emit(Event.Updated(id))).void
          _ <- listeners.put(listenerFns + emit)
          _ <- Task.never[Unit]
        } yield ())
          .doOnCancel(cancel)
          .runToFuture
      }
    }
  }

  def relatedIds[R](fn: ResourceState[T] => Iterable[Id[R]]): Observable[Id[R]] = {
    def handle(obj: Option[ResourceState[T]]): Observable[Id[R]] = {
      Observable.from(obj.map(fn).getOrElse(Nil))
    }
    ids.map {
      case Event.Updated(id) => Observable.fromTask(get(id)).concatMap(handle)
      case Event.HardDeleted(_) => Observable.empty
    }.concat
  }

  override def all: Task[Map[Id[T], ResourceState[T]]] = Task(state.get)

  override def close(): Unit = future.cancel()
}

