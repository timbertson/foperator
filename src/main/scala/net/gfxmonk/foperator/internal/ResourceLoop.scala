package net.gfxmonk.foperator.internal

import monix.eval.Task
import monix.execution.{Cancelable, Scheduler}
import net.gfxmonk.foperator.internal.ResourceLoop.ErrorCount
import net.gfxmonk.foperator.{ReconcileResult, Reconciler, ResourceState}

import scala.concurrent.Promise
import scala.concurrent.duration._
import scala.util.{Failure, Success}

object ResourceLoop {
  trait Manager[Loop[_], T] {
    def create(currentState: Task[Option[ResourceState[T]]], reconciler: Reconciler[ResourceState[T]], permitScope: PermitScope, onError: Throwable => Task[Unit]): Loop[T]
    def update(loop: Loop[T]): Task[Unit]
    def destroy(loop: Loop[T]): Task[Unit]
  }

  case class ErrorCount(value: Int) extends AnyVal {
    def increment = ErrorCount(value + 1)
    def nonzero = value > 0
  }
  object ErrorCount {
    def zero = ErrorCount(0)
  }

  def manager[T<:AnyRef](refreshInterval: Option[FiniteDuration])(implicit scheduler: Scheduler): Manager[ResourceLoop, T] =
    new Manager[ResourceLoop, T] {
      override def create( currentState: Task[Option[ResourceState[T]]],
                              reconciler: Reconciler[ResourceState[T]],
                              permitScope: PermitScope,
                              onError: Throwable => Task[Unit]): ResourceLoop[T] = {
        def backoffTime(errorCount: ErrorCount) = Math.pow(1.2, errorCount.value.toDouble).seconds
        new ResourceLoop[T](currentState, reconciler, refreshInterval, permitScope, backoffTime, onError)
      }
      override def update(loop: ResourceLoop[T]): Task[Unit] = loop.update
      override def destroy(loop: ResourceLoop[T]): Task[Unit] = Task(loop.cancel())
    }
}

// Encapsulates the (infinite) reconcile loop for a single resource.
class ResourceLoop[T](
                       currentState: Task[Option[ResourceState[T]]],
                       reconciler: Reconciler[ResourceState[T]],
                       refreshInterval: Option[FiniteDuration],
                       permitScope: PermitScope,
                       backoffTime: ErrorCount => FiniteDuration,
                       onError: Throwable => Task[Unit]
                     )(implicit scheduler: Scheduler) extends Cancelable with Logging {
  // loop is:
  // busy (schedule another when ready)
  // quiet (nothing to do)
  // waiting (has reconcile to do, but no semaphore available)

  import ResourceLoop._
  private val logId = s"[${reconciler.hashCode}-${currentState.hashCode}]"
  @volatile private var modified = Promise[Unit]()
  private val resetPromise = Task {
    // reset `modified` right before reading the current state. Updates occur in
    // the opposite order (state is changed then promise is fulfilled), which
    // ensures we never miss a change (but we could double-process a concurrent change)
    modified = Promise[Unit]()
    logger.trace(s"$logId reset `modified` to fresh promise")
  }

  private val cancelable = reconcileNow(ErrorCount.zero).onErrorHandleWith(onError).runToFuture

  override def cancel(): Unit = cancelable.cancel()

  def update: Task[Unit] = Task {
    logger.trace(s"$logId marking as modified")
    if (!modified.trySuccess(())) {
      logger.trace(s"$logId (an update is already pending)")
    }
  }

  private def reconcileNow(errorCount: ErrorCount): Task[Unit] = {
    val runReconcile = resetPromise >> currentState.flatMap {
      case None => {
        logger.trace(s"$logId no longer present, skipping reconcile")
        Task.pure(Success(ReconcileResult.Ok))
      }
      case Some(obj) => {
        logger.trace(s"$logId performing reconcile")
        reconciler.reconcile(obj).materialize
      }
    }

    Task(logger.trace(s"$logId Acquiring permit")) >>
    permitScope.withPermit(runReconcile).flatMap {
      case Success(result) => {
        val delay = result match {
          case ReconcileResult.RetryAfter(delay) => Some(delay)
          case _ => refreshInterval
        }
        val retryMsg = delay.fold("")(delay => s", retrying in ${delay}s")
        logger.info(s"$logId Reconcile completed successfully${retryMsg}")
        scheduleReconcile(ErrorCount.zero, delay)
      }

      case Failure(error) => {
        val nextCount = errorCount.increment
        val errorDelay = backoffTime(nextCount)
        val delay = refreshInterval.fold(errorDelay)(errorDelay.min)
        logger.warn(s"$logId Reconcile failed: ${error} (attempt #${nextCount.value}), retrying in ${delay.toSeconds}s")
        scheduleReconcile(nextCount, Some(delay))
      }
    }
  }

  private def scheduleReconcile(errorCount: ErrorCount, delay: Option[FiniteDuration]): Task[Unit] = {
    Task.race(
      Task.fromFuture(modified.future),
      delay.fold(Task.never[ErrorCount])(delay => Task.pure(errorCount).delayExecution(delay))
    ).map {
      case Right(errorCount) => errorCount
      case Left(()) => ErrorCount.zero
    }.flatMap(reconcileNow)
  }
}
