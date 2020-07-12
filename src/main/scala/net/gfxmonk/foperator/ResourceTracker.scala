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
import net.gfxmonk.foperator.internal.Id
import play.api.libs.json.Format
import skuber.api.client.{EventType, KubernetesClient, LoggingContext, WatchEvent}
import skuber.json.format.ListResourceFormat
import skuber.{ListOptions, ListResource, ObjectResource, ResourceDefinition}

import scala.collection.immutable.Queue
import scala.util.{Failure, Success}

object ResourceTracker {
  def resource[T<: ObjectResource](listOptions: ListOptions)(
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

  /**
   * IdSubscriber is a buffer in front of a downstream Subscriber.
   * It never backpressures the source, with an infinite buffer which won't
   * grow large in practice, since it only includes each ID at most once.
   */
  private class IdSubscriber(subscriber: Subscriber[Id])(implicit scheduler: Scheduler) {
    private val pending = MVar[Task].empty[Option[Queue[Id]]]().runSyncUnsafe()

    def emitAll(queue: Queue[Id]): Task[Unit] = {
      queue.dequeueOption match {
        case None => pending.put(None)
        case Some((head, tail)) => emit(head, tail)
      }
    }

    private def emit(elem: Id, tail: Queue[Id]): Task[Unit] = {
      subscriber.onNext(elem).onComplete {
        case Success(Ack.Stop) => ???
        case Failure(_) => ???
        case Success(Ack.Continue) => pending.take.flatMap {
          case None => pending.put(None)
          case Some(pending) => emitAll(pending)
        }
      }
      pending.put(Some(tail))
    }

    def onNext(elem: Id): Task[Unit] = {
      pending.take.flatMap {
        case None => emit(elem, Queue.empty)
        case Some(queue) => {
          val newQueue = if (queue.contains(elem)) {
            queue
          } else {
            queue.enqueue(elem)
          }
          pending.put(Some(newQueue))
        }
      }
    }

    def onError(ex: Throwable): Unit = {
      subscriber.onError(ex)
    }
  }
}

/**
 * ResourceTracker provides:
 *  - A snapshot of the current state of a set of resources
 *    TODO: we might want to store derived state, not the full resource
 *  - An observable (supprting multi-subscription) tracking the ID of changed resources.
 *    The observable is deduping - i.e. while the downstream is busy, it will buffer
 *    modified IDs and ignore duplicates.
 */
class ResourceTracker[T<: ObjectResource](initial: List[T], updates: Observable[WatchEvent[T]])(implicit scheduler: Scheduler)
  extends AutoCloseable {
  private val state: Atomic[Map[Id,T]] = Atomic(initial.map(obj => Id.of(obj) -> obj).toMap)
  private val subscribers: MVar[Task, Set[IdSubscriber]] = MVar[Task].of(Set.empty[IdSubscriber]).runSyncUnsafe()

  private def transformSubscribers(fn: Set[IdSubscriber] => Set[IdSubscriber]): Task[Unit] = {
    subscribers.take.flatMap(subs => subscribers.put(fn(subs)))
  }

  private val future = updates.consumeWith(Consumer.foreachEval { (event:WatchEvent[T]) =>
    val id = Id.of(event._object)
    event._type match {
      case EventType.ERROR => ???
      case EventType.DELETED => state.transform(_.removed(id))
      case EventType.ADDED | EventType.MODIFIED => state.transform(_.updated(id, event._object))
    }
    subscribers.read.flatMap { subs =>
      // TODO: future dance is awkward, use Fiber?
      subs.toList.traverse(sub => Task.fromFuture(sub.onNext(id).runToFuture)).void
    }
  }).onError {
    case err: Throwable => subscribers.take.map { subs =>
      subs.foreach(_.onError(err))
    }
  }.runToFuture

  def ids: Observable[Id] = {
    new Observable[Id] {
      override def unsafeSubscribeFn(subscriber: Subscriber[Id]): Cancelable = {
        val channel = new ResourceTracker.IdSubscriber(subscriber)
        (for {
          // take subscribers mutex while creating channel, so that
          // concurrent updates are guaranteed to see the new subscriber
          subs <- subscribers.take
          _ <- channel.emitAll(Queue.from(state.get.keys))
          _ <- subscribers.put(subs + channel)
          _ <- Task.never[Unit]
        } yield ())
          .doOnCancel(transformSubscribers(s => s - channel))
          .runToFuture
      }
    }
  }

  def get(id: Id) = state.get.get(id)

  def all = state.get

  override def close(): Unit = future.cancel()
}
