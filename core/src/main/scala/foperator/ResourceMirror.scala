package foperator

import cats.Monad
import cats.effect.{Async, Concurrent, Sync}
import cats.implicits._
import foperator.internal.{IORef, IOUtil, Logging}
import foperator.types._
import fs2.{BroadcastTopic, Chunk, Stream}

/**
 * ResourceMirror provides:
 *  - A snapshot of the current state of a set of resources
 *  - A stream tracking the ID of changed resources.
 *    Subscribers to this stream MUST NOT backpressure, as that could cause the
 *    watcher (and other observers) to fall behind.
 *    In practice, this is typically consumed by Dispatcher, which doesn't backpressure.
 */
trait ResourceMirror[IO[_], T] extends ReconcileSource[IO, T] {
  def all: IO[ResourceMirror.ResourceMap[T]]
  def active: IO[Map[Id[T], T]]
  def allValues: IO[List[ResourceState[T]]]
  def activeValues: IO[List[T]]
  def get(id: Id[T]): IO[Option[ResourceState[T]]]
  def getActive(id: Id[T]): IO[Option[T]]
}

object ResourceMirror extends Logging {
  type ResourceMap[T] = Map[Id[T], ResourceState[T]]

  private class Impl[IO[_], T](
    // TODO could this just be a Ref?
    state: IORef[IO, ResourceMap[T]],
    idStream: Stream[IO, Id[T]],
  )(implicit io: Monad[IO]) extends ResourceMirror[IO, T] {
    override def all: IO[Map[Id[T], ResourceState[T]]] = state.readLast
    override def active: IO[Map[Id[T], T]] = io.map(all)(_.mapFilter(ResourceState.active))
    override def allValues: IO[List[ResourceState[T]]] = all.map(_.values.toList)
    override def activeValues: IO[List[T]] = active.map(_.values.toList)
    override def get(id: Id[T]): IO[Option[ResourceState[T]]] = all.map(m => m.get(id))
    override def getActive(id: Id[T]): IO[Option[T]] = active.map(m => m.get(id))
    override def ids: Stream[IO, Id[T]] = idStream
  }

  private [foperator] def apply[IO[_], C, T, R](client: C, opts: ListOptions)(block: ResourceMirror[IO, T] => IO[R])
    (implicit io: Async[IO], res: ObjectResource[T], e: Engine[IO, C, T]): IO[R] = {
    for {
      _ <- io.delay(logger.info("[{}]: Starting ResourceMirror", res.kind))
      listAndWatch <- e.listAndWatch(client, opts).adaptError(new RuntimeException(s"Error listing / watching ${res.kind}", _))
      (initial, rawUpdates) = listAndWatch
      _ <- io.delay(logger.info("[{}]: List returned {} initial resources", res.kind, initial.size))
      updates = rawUpdates.handleErrorWith(e =>
        Stream.eval(io.raiseError(new RuntimeException(s"Error watching ${res.kind} resources", e)))
      )
      state <- IORef[IO].of(initial.map(obj => res.id(obj) -> ResourceState.of(obj)).toMap)
      trackedUpdates = trackState(state, updates).map(e => res.id(e.raw))
      topic <- BroadcastTopic[IO, Id[T]]
      consume = trackedUpdates.through(topic.publish).compile.drain
      ids = injectInitial(state.readLast.map(_.keys.toList), topic)
      impl = new Impl(state, ids)
      result <- IOUtil.withBackground(IOUtil.nonTerminating(consume), block(impl))
    } yield result
  }

  private def injectInitial[IO[_], T](initial: IO[List[T]], topic: BroadcastTopic[IO, T])(implicit io: Sync[IO]): Stream[IO, T] = {
    Stream.resource(topic.subscribeAwait(1)).flatMap { updates =>
      // first emit initial IDs, then follow with updates
      Stream.evalUnChunk(io.map(initial) { items =>
        logger.debug("updates: injecting initial chunk of {} items", items.length)
        Chunk.seq(items)
      }).append(updates)
    }
  }

  private def trackState[IO[_], T]
    (state: IORef[IO, ResourceMap[T]], input: Stream[IO, Event[T]])(implicit
      io: Sync[IO],
      res: ObjectResource[T],
    ): Stream[IO, Event[T]] = {
    input.evalTap[IO, Unit] { event =>
      val id = res.id(event.raw)
      val desc = s"${Event.desc(event)}($id, v${res.version(event.raw).getOrElse("")})"
      io.delay(logger.debug("[{}] Updating state for: {}", res.kind, desc))
    }.evalTap {
      case Event.Deleted(t) => state.update_(s => s.removed(res.id(t)))
      case Event.Updated(t) => state.update_(s => s.updated(res.id(t), ResourceState.of(t)))
    }
  }
}