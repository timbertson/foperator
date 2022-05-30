package foperator

import cats.Monad
import cats.effect.kernel.Deferred
import cats.effect.{Async, Sync}
import cats.implicits._
import foperator.StateChange.ResetState
import foperator.internal.{IORef, IOUtil, Logging}
import foperator.types._
import fs2.concurrent.Topic
import fs2.{Chunk, Pull, Stream}

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
    val listAndWatch = e.listAndWatch(client, opts).handleErrorWith(e =>
      Stream.eval(io.raiseError(new RuntimeException(s"Error watching ${res.kindDescription} resources", e)))
    )
    forStateStream(listAndWatch)(block)
  }

  private def pullOfStream[IO[_], O](s: Stream[IO, O]): Pull[IO, O, Unit] = {
    // pass entire stream through a pull. Seems a bit odd there's no builtin for this.
    s.pull.uncons.flatMap {
      case None           => Pull.done
      case Some((chunk, tl)) => Pull.output(chunk) >> pullOfStream(tl)
    }
  }

  private [foperator] def forStateStream[IO[_], C, T, R](listAndWatch: Stream[IO, StateChange[T]])(block: ResourceMirror[IO, T] => IO[R])
    (implicit io: Async[IO], res: ObjectResource[T]): IO[R] = {
    // listAndWatch can return EOF after a while; keep running it forever
    val listAndWatchForever = (listAndWatch ++ Stream.evalUnChunk {
      io.delay(logger.info("listAndWatch ended; restarting")).as(Chunk.empty)
    }).repeat

    for {
      state <- IORef[IO].of(Map.empty[Id[T], ResourceState[T]])
      ready <- Deferred[IO, Unit]
      topic <- Topic[IO, Id[T]]
      _ <- io.delay(logger.info("[{}]: Starting ResourceMirror", res.kindDescription))

      updates = listAndWatchForever.pull.uncons1.flatMap {
        // we use the head of the stream to set the initial state, and emit subsequent state changes
        case Some((head @ StateChange.ResetState(_), tail)) => {
          val setup = Pull.eval[IO, Unit] {
            for {
              _ <- io.delay(logger.info(s"[{}]: Initial state contains {} items", res.kindDescription, head.all.size))
              _ <- resetState(state, head)
              _ <- ready.complete(())
            } yield ()
          }
          setup >> pullOfStream(tail)
        }
        case other => Pull.raiseError[IO](new RuntimeException(s"Unexpected initial listAndWatch element: ${other}"))
      }.stream

      // maintain `state` as updates flow through, then publish changed IDs
      trackedUpdates = trackState(state, updates).map(change => res.id(change.raw))
      consume = trackedUpdates.through(topic.publish).compile.drain

      initial = state.readLast.map(_.keys.toList)
      ids = injectInitial(initial, topic)
      impl = new Impl(state, ids)
      result <- IOUtil.withBackground(IOUtil.nonTerminating(consume), ready.get *> block(impl))
    } yield result
  }

  private def injectInitial[IO[_], T](initial: IO[List[T]], topic: Topic[IO, T])(implicit io: Sync[IO]): Stream[IO, T] = {
    Stream.resource(topic.subscribeAwait(1)).flatMap { updates =>
      // first emit initial IDs, then follow with updates
      Stream.evalUnChunk(io.map(initial) { items =>
        logger.debug("updates: injecting initial chunk of {} items", items.length)
        Chunk.seq(items)
      }).append(updates)
    }
  }

  private def trackState[IO[_], T](
    state: IORef[IO, ResourceMap[T]],
    input: Stream[IO, StateChange[T]]
  )(implicit
    io: Sync[IO],
    res: ObjectResource[T],
  ): Stream[IO, ResourceChange[T]] = {

    def logChange(change: ResourceChange[T]) = {
      val id = res.id(change.raw)
      val desc = s"${StateChange.desc(change)}($id, v${res.version(change.raw).getOrElse("")})"
      io.delay(logger.debug("[{}] Applying {}", res.kindDescription, desc))
    }

    input.evalMap {
      case reset: StateChange.ResetState[T] => {
        for {
          _ <- io.delay(logger.info("[{}]: Resetting state with {} resources", res.kindDescription, reset.all.size))
          changes <- resetState(state, reset)
          _ <- changes.traverse_(logChange)
        } yield Chunk.seq(changes)
      }
      case change: ResourceChange[T] => {
        for {
          _ <- logChange(change)
          _ <- change match {
            case StateChange.Deleted(t) => state.update_(s => s.removed(res.id(t)))
            case StateChange.Updated(t) => state.update_(s => s.updated(res.id(t), ResourceState.of(t)))
          }
        } yield Chunk.singleton(change)
      }
    }.flatMap(Stream.chunk)
  }

  // Reset state and emit minimal changes (i.e. only resources which actually changed)
  private def resetState[IO[_], T](
    state: IORef[IO, ResourceMap[T]],
    reset: ResetState[T]
  )(implicit
    io: Sync[IO],
    res: ObjectResource[T],
  ): IO[List[ResourceChange[T]]] = {
    val newState: ResourceMap[T] = reset.all.map(r => (Id.of(r), ResourceState.of(r))).toMap
    val doReconcile = state.modify { prevState =>
      val allIds = (prevState.keys ++ newState.keys).toSet
      val changes: List[ResourceChange[T]] = allIds.toList.flatMap[ResourceChange[T]] { id =>
        (prevState.get(id), newState.get(id)) match {
          case (None, Some(current)) => List(StateChange.Updated(current.raw))
          case (Some(prev), None) => List(StateChange.Deleted(prev.raw))
          case (Some(prev), Some(current)) => {
            if (res.version(prev.raw) === res.version(current.raw)) {
              Nil
            } else {
              List(StateChange.Updated(current.raw))
            }
          }
          case (None, None) => Nil // impossible, but humor the compiler
        }
      }
      io.pure((newState, changes))
    }

    for {
      changes <- doReconcile
    } yield changes
  }
}