package net.gfxmonk.foperator.internal

import monix.eval.Task
import monix.execution.{Cancelable, Scheduler}
import net.gfxmonk.foperator.internal.Dispatcher.PermitScope
import net.gfxmonk.foperator.internal.ResourceLoop.ErrorCount
import net.gfxmonk.foperator.{ReconcileResult, Reconciler, ResourceState}

import scala.concurrent.Promise
import scala.concurrent.duration._
import scala.util.{Failure, Success}

object ResourceLoop {
  trait Manager[Loop[_]] {
    def create[T](currentState: Task[Option[ResourceState[T]]], reconciler: Reconciler[ResourceState[T]], permitScope: PermitScope): Loop[T]
    def update[T](loop: Loop[T]): Task[Unit]
    def destroy[T](loop: Loop[T]): Task[Unit]
  }

  case class ErrorCount(value: Int) extends AnyVal {
    def increment = ErrorCount(value + 1)
    def nonzero = value > 0
  }
  object ErrorCount {
    def zero = ErrorCount(0)
  }

  def manager[T<:AnyRef](refreshInterval: FiniteDuration)(implicit scheduler: Scheduler): Manager[ResourceLoop] =
    new Manager[ResourceLoop] {
      override def create[T](currentState: Task[Option[ResourceState[T]]], reconciler: Reconciler[ResourceState[T]], permitScope: PermitScope): ResourceLoop[T] = {
        def backoffTime(errorCount: ErrorCount) = Math.pow(1.2, errorCount.value.toDouble).seconds
        new ResourceLoop[T](currentState, reconciler, refreshInterval, permitScope, backoffTime)
      }
      override def update[T](loop: ResourceLoop[T]): Task[Unit] = loop.update
      override def destroy[T](loop: ResourceLoop[T]): Task[Unit] = Task(loop.cancel())
    }
}

// Encapsulates the (infinite) reconcile loop for a single resource.
class ResourceLoop[T](
                       currentState: Task[Option[ResourceState[T]]],
                       reconciler: Reconciler[ResourceState[T]],
                       refreshInterval: FiniteDuration,
                       permitScope: PermitScope,
                       backoffTime: ErrorCount => FiniteDuration
                     )(implicit scheduler: Scheduler) extends Cancelable with Logging {
  // loop is:
  // busy (schedule another when ready)
  // quiet (nothing to do)
  // waiting (has reconcile to do, but no semaphore available)

  import ResourceLoop._
  @volatile private var modified = Promise[Unit]()
  private val cancelable = reconcileNow(ErrorCount.zero).runToFuture

  override def cancel(): Unit = cancelable.cancel()

  def update: Task[Unit] = Task {
    val _: Boolean = modified.trySuccess(())
  }

  private def reconcileNow(errorCount: ErrorCount): Task[Unit] = {
    val result = permitScope.withPermit {
      // reset `modified` right before reading the current state. Updates occur in
      // the opposite order (state is changed then promise is fulfilled), which
      // ensures we never miss a change (but we could double-process a concurrent change)
      modified = Promise[Unit]()
      // TODO return Option[Task], then we can log it differently
      currentState.flatMap {
        case None => Task.pure(Success(ReconcileResult.Ok))
        case Some(obj) => {
          reconciler.reconcile(obj).materialize
        }
      }
    }

    result.flatMap {
      case Success(result) => {
        val delay = result match {
          case ReconcileResult.RetryAfter(delay) => delay
          case _ => refreshInterval
        }
        logger.info(s"Reconcile completed successfully, retrying in ${delay.toSeconds}s")
        scheduleReconcile(ErrorCount.zero, delay)
      }

      case Failure(error) => {
        val nextCount = errorCount.increment
        val delay = backoffTime(nextCount).min(refreshInterval)
        logger.warn(s"Reconcile failed: ${error}, retrying in ${delay.toSeconds}s")
        scheduleReconcile(nextCount, delay)
      }
    }
  }

  private def scheduleReconcile(errorCount: ErrorCount, delay: FiniteDuration): Task[Unit] = {
    Task.race(
      Task.fromFuture(modified.future),
      Task.pure(errorCount).delayExecution(delay)
    ).map {
      case Right(errorCount) => errorCount
      case Left(()) => ErrorCount.zero
    }.flatMap(reconcileNow)
  }
}
