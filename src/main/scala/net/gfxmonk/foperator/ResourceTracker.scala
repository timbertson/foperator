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
import net.gfxmonk.foperator.ResourceTracker.IdSubscriber
import play.api.libs.json.Format
import skuber.api.client.{EventType, KubernetesClient, LoggingContext, WatchEvent}
import skuber.json.format.ListResourceFormat
import skuber.{LabelSelector, ListOptions, ListResource, ObjectResource, ResourceDefinition}

trait ResourceTrackerProvider[T<: ObjectResource] {
  // This could almost be a resource, except our `use` ensures that asynchronous failure in the watch
  // process cancels the user and results in an error
  def use[R](consume: ResourceTracker[T] => Task[R]): Task[R]
}

object ResourceTracker {
  def all[T<: ObjectResource](
    implicit fmt: Format[T], rd: ResourceDefinition[T], lc: LoggingContext, materializer: ActorMaterializer,
    client: KubernetesClient
  ): ResourceTrackerProvider[T] = providerImpl(ListOptions())

  def forSelector[T<: ObjectResource](labelSelector: LabelSelector)(
    implicit fmt: Format[T], rd: ResourceDefinition[T], lc: LoggingContext, materializer: ActorMaterializer,
    client: KubernetesClient
  ): ResourceTrackerProvider[T] = providerImpl(ListOptions(labelSelector = Some(labelSelector)))

  def forOptions[T<: ObjectResource](listOptions: ListOptions)(
    implicit fmt: Format[T], rd: ResourceDefinition[T], lc: LoggingContext, materializer: ActorMaterializer,
    client: KubernetesClient
  ): ResourceTrackerProvider[T] = providerImpl(listOptions)

  private def providerImpl[T<: ObjectResource](listOptions: ListOptions)(
    implicit fmt: Format[T], rd: ResourceDefinition[T], lc: LoggingContext, materializer: ActorMaterializer,
    client: KubernetesClient
  ): ResourceTrackerProvider[T] = new ResourceTrackerProvider[T] {
    override def use[R](consume: ResourceTracker[T] => Task[R]): Task[R] = {
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

  def share[T<: ObjectResource](instance: ResourceTracker[T]): ResourceTrackerProvider[T] = new ResourceTrackerProvider[T] {
    override def use[R](consume: ResourceTracker[T] => Task[R]): Task[R] = consume(instance)
  }

  private [foperator] type IdSubscriber[T] = Input[Id[T]] => Task[Unit]

  private def resource[T<: ObjectResource](listOptions: ListOptions)(
    implicit fmt: Format[T], rd: ResourceDefinition[T], lc: LoggingContext, materializer: ActorMaterializer,
    client: KubernetesClient
  ): Resource[Task,ResourceTracker[T]] = {
    Resource.fromAutoCloseable[Task, ResourceTracker[T]] {
      Task.deferFutureAction { implicit scheduler =>
        implicit val lrf = ListResourceFormat[T]
        client.listWithOptions[ListResource[T]](listOptions).map { listResource =>
          val source = client.watchWithOptions[T](listOptions.copy(
            resourceVersion = Some(listResource.resourceVersion),
            timeoutSeconds = Some(30) // TODO
          ))
          // TODO: check that watch ignores status updates to avoid loops (does it depend on whether there's a status subresource?)
          val updates = Observable.fromReactivePublisher(source.runWith(Sink.asPublisher(fanout=false)))
          new ResourceTracker(listResource.toList, updates)
        }
      }
    }
  }
}

/**
 * ResourceTracker provides:
 *  - A snapshot of the current state of a set of resources
 *  - An observable tracking the ID of changed resources.
 *    Subscribers to this observer MUST NOT backpressure, as that could cause the
 *    watcher (and other observers) to fall behind.
 *    In practice, this is typically consumed by Dispatcher, which doesn't backpressure.
 */
class ResourceTracker[T<: ObjectResource] private (initial: List[T], updates: Observable[WatchEvent[T]])(implicit scheduler: Scheduler)
  extends AutoCloseable {
  private val state: Atomic[Map[Id[T],T]] = Atomic(initial.map(obj => Id.of(obj) -> obj).toMap)
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
        state.transform(_.updated(id, event._object))
        Input.Updated(id)
      }
    }
    listeners.read.flatMap { listenerFns =>
      // TODO: future dance is awkward, use Fiber?
      listenerFns.toList.traverse(_(input)).void
    }
  }).runToFuture

  def ids: Observable[Input[Id[T]]] = {
    new Observable[Input[Id[T]]] {
      override def unsafeSubscribeFn(subscriber: Subscriber[Input[Id[T]]]): Cancelable = {
        def emit(id: Input[Id[T]]): Task[Unit] = {
          Task.deferFuture(subscriber.onNext(id)).flatMap {
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

  def relatedIds(fn: T => List[Id[T]]): Observable[Id[T]] = {
    def handle(obj: Option[T]): Observable[Id[T]] = {
      Observable.from(obj.map(fn).getOrElse(Nil))
    }
    ids.map {
      case Input.Updated(id) => handle(get(id))
      case Input.HardDeleted(_) => Observable.empty
    }.concat
  }

  def all(): Map[Id[T], T] = state.get

  def get(id: Id[T]): Option[T] = all.get(id)

  override def close(): Unit = future.cancel()
}

