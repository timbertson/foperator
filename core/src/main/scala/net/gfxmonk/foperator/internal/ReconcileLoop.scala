package net.gfxmonk.foperator.internal

import cats.implicits._
import cats.effect.concurrent.Deferred
import cats.effect.{Concurrent, Timer}
import net.gfxmonk.foperator.ReconcileResult
import net.gfxmonk.foperator.internal.Dispatcher.{Dirty, Reconciling, State, StateUpdater, Terminate, Waiting}

import scala.concurrent.duration.FiniteDuration
import scala.util.{Failure, Success}

trait ReconcileLoop[IO[_], K] {
  def markDirty: State[IO] => IO[State[IO]]
  def run(k: K): IO[Unit]
}

object ReconcileLoop extends Logging {
  class Impl[IO[_], K](
    action: K => IO[Option[ReconcileResult]],
    updateState: StateUpdater[IO, K],
    retryTime: ErrorCount.RetryDelay,
  )(implicit io: Concurrent[IO], timer: Timer[IO]) extends ReconcileLoop[IO, K] {
    def impossibleState[R](desc: String, k: K, state: State[IO]) =
      io.raiseError[R](new RuntimeException(s"[{$k}] impossible state while $desc: ${state}"))

    override def markDirty: State[IO] => IO[State[IO]] = {
      case Reconciling | Dirty => io.pure(Dirty)
      case Waiting(wakeup) => wakeup.as(Dirty)
    }

    private def maybeReschedule(k: K, explicitDelay: Option[FiniteDuration], errorCount: ErrorCount) = updateState[IO[Unit]](k, {
      case Reconciling => { // that's us
        explicitDelay.orElse(retryTime(errorCount)) match {
          case None => {
            logger.debug("[{}] Reconcile loop terminating (retryTime is None)", k)
            io.pure((Terminate, io.unit))
          }
          case Some(delay) => {
            // reschedule for later, with a deferred to
            // allow premature wakeup
            Deferred[IO, Unit].map { wakeup =>
              val wokeup = wakeup.get.map { _ =>
                logger.debug("[{}] Reconcile loop woken from sleep", k)
              }
              val delayCompleted = timer.sleep(delay).map { _ =>
                logger.debug("[{}] Reconcile loop rescheduled after sleeping {}", k, delay)
              }
              val task = io.race(wokeup, delayCompleted) >>
                updateState[IO[Unit]](k, {
                  case Reconciling => impossibleState("post-wakeup", k, Reconciling)
                  case Dirty | Waiting(_) => io.pure((Reconciling, reconcileN(k, errorCount)))
                }).flatMap(identity)
              logger.debug("[{}] Sleeping for {}", k, delay)
              (Waiting[IO](wakeup.complete(())), task)
            }
          }
        }
      }
      case state@Waiting(_) => impossibleState("rescheduling", k, state)

      // TODO should we rate limit this so we're not at the mercy of some resource update loop?
      case Dirty => io.delay {
        logger.debug("[{}] Reconciling again as it's still dirty", k)
        (Reconciling, run(k))
      }
    }).flatMap(identity)

    def run(k: K) = reconcileN(k, ErrorCount.zero)

    private def reconcileN(k: K, errorCount: ErrorCount): IO[Unit] = {
      io.attempt(action(k)).map(_.toTry).flatMap {
        case Success(None) => {
          updateState[IO[Unit]](k, {
            case Reconciling => {
              io.delay(logger.trace("[{}] no longer present, stopping reconcile", k))
                .as((Terminate, io.unit))
            }
            case Dirty => io.pure((Reconciling, reconcileN(k, errorCount))) // guess it got recreated?
            case state@Waiting(_) => impossibleState("post-reconcile", k, state)
          }).flatMap(identity)
        }

        case Success(Some(result)) => {
          logger.info("[{}] Reconcile completed: {}", k, result)
          val delay = result match {
            case ReconcileResult.RetryAfter(delay) => Some(delay)
            case ReconcileResult.Ok => None
          }
          maybeReschedule(k, delay, ErrorCount.zero)
        }

        case Failure(error) => {
          val nextCount = errorCount.increment
          logger.info("[{}] Reconcile failed: {} (attempt #{})", k, error, nextCount.value, error)
          maybeReschedule(k, None, nextCount)
        }
      }
    }
  }
}
