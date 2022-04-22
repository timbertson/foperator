package foperator.internal

import cats.effect.kernel._
import cats.effect.std.Semaphore
import cats.implicits._
import foperator._
import foperator.types.ObjectResource
import fs2.Stream

// Kicks off reconcile actions for all tracked resources,
// supporting periodic reconciles and a concurrency limit
private[foperator] object Dispatcher extends Logging {
  def run[F[_]: Async, C, T: ObjectResource](
    client: C,
    input: ReconcileSource[F, T],
    reconcile: Reconciler.Fn[F, C, T],
    opts: ReconcileOptions,
  ): F[Unit] = {
    Dispatcher.resource[F, C, T](
      client,
      input,
      reconcile,
      opts
    ).use(identity)
  }

  private [foperator] def main[F[_], K](
    state: IORef[F, StateMap[F, K]],
    error: Deferred[F, Throwable],
    loop: ReconcileLoop[F, K],
    input: Stream[F, K]
  )(implicit io: Concurrent[F]): F[Unit] = {
    val process: F[Unit] = {
      input.evalMap[F, Unit] { id =>
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
              val task = io.handleErrorWith(loop.run(id))(t => error.complete(t).void)
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

  def resource[F[_], C, T](
    client: C,
    input: ReconcileSource[F, T],
    reconcile: Reconciler.Fn[F, C, T],
    opts: ReconcileOptions,

    // used in tests:
    stateOverride: Option[IORef[F, StateMap[F, Id[T]]]] = None
  )(implicit io: Async[F], res: ObjectResource[T]): Resource[F, F[Unit]] =
  {
    def retryDelay(count: ErrorCount) = opts.retryDelay(count.value)
    val acquire = for {
      state <- stateOverride.fold(IORef[F].of(Map.empty: StateMap[F, Id[T]]))(io.pure)
      error <- Deferred[F, Throwable]
      semaphore <- Semaphore[F](opts.concurrency.toLong)
    } yield {
      val updater = new Updater(state)
      def action(id: Id[T]): F[Option[ReconcileResult]] = semaphore.permit.use { _ =>
        input.get(id).flatMap {
          case None => io.pure(None)
          case Some(r) => for {
            _ <- io.delay(logger.info("[{}] Reconciling {} v{}", res.kindDescription, id, res.version(r.raw).getOrElse("0")))
            result <- reconcile(client, r)
          } yield Some(result)
        }
      }

      val resourceLoop = new ReconcileLoop.Impl[F, Id[T]](action, updater, retryDelay)
      val run = io.delay(logger.info("[{}] Starting reconciler", res.kindDescription)) >> main(state, error, resourceLoop, input.ids)
      (run, cancel(state))
    }
    Resource(acquire)
  }

  // NewState is State plus the Terminate option, which
  // removes this resource from being tracked
  sealed trait NewState[+F[_]]
  case object Terminate extends NewState[Nothing]

  sealed trait State[+F[_]] extends NewState[F]
  case object Reconciling extends State[Nothing] with NewState[Nothing]
  case object Dirty extends State[Nothing] with NewState[Nothing]
  case class Waiting[F[_]](wakeup: F[Unit]) extends State[F]

  // updater for an individual resource
  trait StateUpdater[F[_], K] {
    def apply[T](key: K, fn: State[F] => F[(NewState[F], T)]): F[T]
  }

  // dispatcher state
  type StateMap[F[_], K] = Map[K, (State[F], Fiber[F, Throwable, Unit])]


  // adapt the full dispatcher state to provide an individual updater
  private [foperator] class Updater[F[_], K](state: IORef[F, StateMap[F, K]])
    (implicit io: Concurrent[F]) extends StateUpdater[F, K]
  {
    override def apply[T](key: K, fn: State[F] => F[(NewState[F], T)]): F[T] = {
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
              case (newState: State[F], ret) => {
                logger.debug("State ({}): {} -> {}", key, current, newState)
                (stateMap.updated(key, (newState, fiber)), ret)
              }
            }
          }
        }
      }
    }
  }

  private def cancel[F[_], K](state: IORef[F, StateMap[F, K]])(implicit io: Concurrent[F], res: ObjectResource[_]): F[Unit] = {
    state.readLast.flatMap { stateMap =>
      logger.info("[{}] Cancelling dispatcher loop ({} active fibers)", res.kindDescription, stateMap.size)
      val fibers = stateMap.values.map(_._2).toList
      fibers.traverse_(f => f.cancel)
    }
  }
}