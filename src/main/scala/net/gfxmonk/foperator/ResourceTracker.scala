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
import play.api.libs.json.Format
import skuber.api.client.{EventType, KubernetesClient, LoggingContext, WatchEvent}
import skuber.json.format.ListResourceFormat
import skuber.{LabelSelector, ListOptions, ListResource, ObjectResource, ResourceDefinition}
import scala.reflect.runtime.universe.{TypeTag, typeOf}

trait ResourceUpdates[T] {
  def ids: Observable[Input[Id[T]]]
}

trait ResourceMirror[T] extends ResourceUpdates[T] {
  // TODO: make all of these Task?
  def all(): Map[Id[T], ResourceState[T]]

  def active(): Map[Id[T], T] = all().mapFilter(ResourceState.active)

  def get(id: Id[T]): Option[ResourceState[T]] = {
    all.get(id)
  }

  def getActive(id: Id[T]): Option[T] = get(id) match {
    case Some(ResourceState.Active(value)) => Some(value)
    case Some(ResourceState.SoftDeleted(_)) | None => None
  }
}

object ResourceMirror {
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
      Task.deferFutureAction { implicit scheduler =>
        implicit val lrf = ListResourceFormat[T]
        client.listWithOptions[ListResource[T]](listOptions).map { listResource =>
          val source = client.watchWithOptions[T](listOptions.copy(
            resourceVersion = Some(listResource.resourceVersion),
            timeoutSeconds = Some(30) // TODO
          ))
          // TODO: check that watch ignores status updates to avoid loops (does it depend on whether there's a status subresource?)
          val updates = Observable.fromReactivePublisher(source.runWith(Sink.asPublisher(fanout=false)))
          new ResourceMirrorImpl[T](listResource.toList, updates)
        }
      }
    }
  }
}

object ResourceMirrorImpl {
  private [foperator] type IdSubscriber[T] = Input[Id[T]] => Task[Unit]
}

/**
 * ResourceMirror provides:
 *  - A snapshot of the current state of a set of resources
 *  - An observable tracking the ID of changed resources.
 *    Subscribers to this observer MUST NOT backpressure, as that could cause the
 *    watcher (and other observers) to fall behind.
 *    In practice, this is typically consumed by Dispatcher, which doesn't backpressure.
 */
private class ResourceMirrorImpl[T<: ObjectResource : TypeTag](initial: List[T], updates: Observable[WatchEvent[T]])(implicit scheduler: Scheduler)
  extends AutoCloseable with ResourceMirror[T] {
  import ResourceMirrorImpl._
  private val state = Atomic(initial.map(obj => Id.of(obj) -> ResourceState.of(obj)).toMap)
  private val listeners: MVar[Task, Set[IdSubscriber[T]]] = MVar[Task].of(Set.empty[IdSubscriber[T]]).runSyncUnsafe()

  private def transformSubscribers(fn: Set[IdSubscriber[T]] => Set[IdSubscriber[T]]): Task[Unit] = {
    listeners.take.flatMap(subs => listeners.put(fn(subs)))
  }

  private [foperator] val future = updates.consumeWith(Consumer.foreachEval { (event:WatchEvent[T]) =>
    val id = Id.of(event._object)
    val input = event._type match {
      case EventType.ERROR => ??? // TODO log and ignore?
      case EventType.DELETED => {
        state.transform(_.removed(id))
        Input.HardDeleted(id)
      }
      case EventType.ADDED | EventType.MODIFIED => {
        state.transform(_.updated(id, ResourceState.of(event._object)))
        Input.Updated(id)
      }
    }
    listeners.read.flatMap { listenerFns =>
      listenerFns.toList.traverse(_(input)).void
    }
  }).runToFuture

  def ids: Observable[Input[Id[T]]] = {
    new Observable[Input[Id[T]]] {
      override def unsafeSubscribeFn(subscriber: Subscriber[Input[Id[T]]]): Cancelable = {
        def emit(id: Input[Id[T]]): Task[Unit] = {
          Task.defer {
            val future = subscriber.onNext(id)
            future.value match {
              case Some(value) => Task.fromTry(value)
              case None => {
                println(s"WARN: a subscriber to ResourceMirror[${typeOf[T]}].ids is not synchronously accepting new items." +
                  "\nThis will delay updates to every subscriber, you should make this synchronous (buffering if necessary).")
                Task.fromFuture(future)
              }
            }
          }.flatMap {
            case Ack.Continue => Task.unit
            case Ack.Stop => cancel
          }
        }
        def cancel = transformSubscribers(s => s - emit)

        (for {
          // take listeners mutex while sending initial set, so that
          // concurrent updates are guaranteed to see the new subscriber
          listenerFns <- listeners.take
          _ <- state.get.keys.toList.traverse(id => emit(Input.Updated(id))).void
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
      case Input.Updated(id) => handle(get(id))
      case Input.HardDeleted(_) => Observable.empty
    }.concat
  }

  override def all(): Map[Id[T], ResourceState[T]] = state.get

  override def close(): Unit = future.cancel()
}

