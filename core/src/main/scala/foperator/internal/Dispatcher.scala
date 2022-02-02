package foperator.internal

import cats.effect.concurrent.{Deferred, MVar, MVar2, Semaphore}
import cats.effect.{Concurrent, Fiber, Resource, Timer}
import cats.implicits._
import foperator._
import foperator.types.ObjectResource
import fs2.Stream

// Kicks off reconcile actions for all tracked resources,
// supporting periodic reconciles and a concurrency limit
private[foperator] object Dispatcher extends Logging {
  def run[IO[_], C, T](
    client: C,
    input: ReconcileSource[IO, T],
    reconcile: Reconciler.Fn[IO, C, T],
    opts: ReconcileOptions,
  )(implicit io: Concurrent[IO], timer: Timer[IO], res: ObjectResource[T]): IO[Unit] = {
    Dispatcher.resource[IO, C, T](
      client,
      input,
      reconcile,
      opts
    ).use(identity)
  }

  private [foperator] def main[IO[_], K](
    state: MVar2[IO, StateMap[IO, K]],
    error: Deferred[IO, Throwable],
    loop: ReconcileLoop[IO, K],
    input: Stream[IO, K]
  )(implicit io: Concurrent[IO]): IO[Unit] = {
    val process: IO[Unit] = {
      input.evalMap[IO, Unit] { id =>
        logger.debug(s"changed: $id")
        state.modify_ { stateMap =>
          (stateMap.get(id) match {
            // if running, mark dirty. otherwise, spawn a reconcile loop
            case Some((state, fiber)) => {
              loop.markDirty(state).map { s =>
                logger.debug("State ({}): {} -> {}", id, state, s)
                (s, fiber)
              }
            }
            case None => {
              logger.debug("Spawning reconcile loop for {}", id)
              val task = io.handleErrorWith(loop.run(id))(error.complete)
              io.start(task).map(fiber => (Reconciling, fiber))
            }
          }).map { newState =>
            stateMap.updated(id, newState)
          }
        }
      }.compile.drain
    }
    io.race(error.get.flatMap(io.raiseError[Unit]), process).void
  }

  def resource[IO[_], C, T](
    client: C,
    input: ReconcileSource[IO, T],
    reconcile: Reconciler.Fn[IO, C, T],
    opts: ReconcileOptions,

    // used in tests:
    stateOverride: Option[MVar2[IO, StateMap[IO, Id[T]]]] = None,
  )(implicit io: Concurrent[IO], timer: Timer[IO], res: ObjectResource[T]): Resource[IO, IO[Unit]] =
  {
    def retryDelay(count: ErrorCount) = opts.retryDelay(count.value)
    val acquire = for {
      state <- stateOverride.fold(MVar[IO].of(Map.empty: StateMap[IO, Id[T]]))(io.pure)
      error <- Deferred[IO, Throwable]
      semaphore <- Semaphore[IO](opts.concurrency.toLong)
    } yield {
      val updater = new Updater(state)
      def action(id: Id[T]): IO[Option[ReconcileResult]] =
        semaphore.withPermit(input.get(id).flatMap {
          case None => io.pure(None)
          case Some(r) => for {
            _ <- io.delay(logger.info("[{}] Reconciling {} v{}", res.kind, id, res.version(r.raw).getOrElse("0")))
            result <- reconcile(client, r)
          } yield Some(result)
        })

      val resourceLoop = new ReconcileLoop.Impl[IO, Id[T]](action, updater, retryDelay)
      val run = io.delay(logger.info("[{}] Starting reconciler", res.kind)) >> main(state, error, resourceLoop, input.ids)
      (run, cancel(state))
    }
    Resource(acquire)
  }

  // NewState is State plus the Terminate option, which
  // removes this resource from being tracked
  sealed trait NewState[+IO[_]]
  case object Terminate extends NewState[Nothing]

  sealed trait State[+IO[_]] extends NewState[IO]
  case object Reconciling extends State[Nothing] with NewState[Nothing]
  case object Dirty extends State[Nothing] with NewState[Nothing]
  case class Waiting[IO[_]](wakeup: IO[Unit]) extends State[IO]

  // updater for an individual resource
  trait StateUpdater[IO[_], K] {
    def apply[T](key: K, fn: State[IO] => IO[(NewState[IO], T)]): IO[T]
  }

  // dispatcher state
  type StateMap[IO[_], K] = Map[K, (State[IO], Fiber[IO, Unit])]


  // adapt the full dispatcher state to provide an individual updater
  private [foperator] class Updater[IO[_], K](state: MVar2[IO, StateMap[IO, K]])
    (implicit io: Concurrent[IO]) extends StateUpdater[IO, K]
  {
    override def apply[T](key: K, fn: State[IO] => IO[(NewState[IO], T)]): IO[T] = {
      state.modify[T] { stateMap =>
        stateMap.get(key) match {
          case None => io.raiseError(new RuntimeException(s"state ${key} missing from dispatcher map"))
          case Some((current, fiber)) => {
            fn(current).map {
              // NOTE: when terminating, we expect the fiber to also complete.
              // we can't join it here because it corresponds to the fiber calling
              // this method, causing a deadlock
              case (Terminate, ret) => {
                logger.debug("State ({}): {} -> {}", key, current, Terminate)
                (stateMap.removed(key), ret)
              }
              case (newState: State[IO], ret) => {
                logger.debug("State ({}): {} -> {}", key, current, newState)
                (stateMap.updated(key, (newState, fiber)), ret)
              }
            }
          }
        }
      }
    }
  }

  private def cancel[IO[_], K](state: MVar2[IO, StateMap[IO, K]])(implicit io: Concurrent[IO], res: ObjectResource[_]): IO[Unit] = {
    state.tryRead.flatMap {
      case None => io.delay(logger.warn("[{}] Can't cancel active fibers; state is empty", res.kind))
      case Some(stateMap) => {
        logger.info("[{}] Cancelling dispatcher loop ({} active fibers)", res.kind, stateMap.size)
        val fibers = stateMap.values.map(_._2).toList
        fibers.traverse_(f => f.cancel)
      }
    }
  }
}