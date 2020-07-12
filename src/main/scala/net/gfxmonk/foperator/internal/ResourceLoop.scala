package net.gfxmonk.foperator.internal

import monix.eval.Task
import monix.execution.{Cancelable, Scheduler}
import net.gfxmonk.foperator.{ReconcileResult, Reconciler}
import net.gfxmonk.foperator.internal.Dispatcher.PermitScope
import net.gfxmonk.foperator.internal.ResourceLoop.ErrorCount

import scala.concurrent.Promise
import scala.concurrent.duration._
import scala.util.{Failure, Success}

object ResourceLoop {
  trait Manager[Loop[_]] {
    def create[T](initial: T, reconciler: Reconciler[T], permitScope: PermitScope): Loop[T]
    def update[T](state: Loop[T], current: T): Task[Unit]
    def destroy[T](state: Loop[T]): Task[Unit]
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
      override def create[T](initial: T, reconciler: Reconciler[T], permitScope: PermitScope): ResourceLoop[T] = {
        def backoffTime(errorCount: ErrorCount) = Math.pow(1.2, errorCount.value.toDouble).seconds
        new ResourceLoop[T](initial, reconciler, refreshInterval, permitScope, backoffTime)
      }
      override def update[T](loop: ResourceLoop[T], current: T): Task[Unit] = loop.update(current)
      override def destroy[T](loop: ResourceLoop[T]): Task[Unit] = Task(loop.cancel())
    }
}

// Encapsulates the (infinite) reconcile loop for a single resource.
class ResourceLoop[T](
                       initial: T,
                       reconciler: Reconciler[T],
                       refreshInterval: FiniteDuration,
                       permitScope: PermitScope,
                       backoffTime: ErrorCount => FiniteDuration,
                     )(implicit scheduler: Scheduler) extends Cancelable {
  import ResourceLoop._
  @volatile private var state: T = initial
  @volatile private var modified = Promise[Unit]()
  private val cancelable = reconcileNow(ErrorCount.zero).runToFuture

  override def cancel(): Unit = cancelable.cancel()

  def update(resource: T): Task[Unit] = Task {
    state = resource
    val _: Boolean = modified.trySuccess(())
  }

  private def reconcileNow(errorCount: ErrorCount): Task[Unit] = {
    val result = permitScope.withPermit {
      // reset `modified` right before reading `state`, so either we'll see the new `state` from a concurrent
      // call to `update`, or `modified` will be left completed, triggering immediate re-reconciliation
      modified = Promise[Unit]()
      reconciler.reconcile(state).materialize
    }

    result.flatMap {
      case Success(_) => {
        println("Reconcile completed successfully")
        scheduleReconcile(ErrorCount.zero, refreshInterval)
      }

      case Failure(error) => {
        val nextCount = errorCount.increment
        val delay = backoffTime(nextCount).min(refreshInterval)
        println(s"Reconcile failed: ${error}, retrying in ${delay.toSeconds}s")
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

// Encapsulates the (infinite) reconcile loop for a single resource.
class ResourceLoop2[T](
                       currentState: Task[Option[T]],
                       reconciler: Reconciler[T],
                       refreshInterval: FiniteDuration,
                       permitScope: PermitScope,
                       backoffTime: ErrorCount => FiniteDuration
                     )(implicit scheduler: Scheduler) extends Cancelable {
  // loop is:
  // busy (schedule another when ready)
  // quiet (nothing to do)
  // waiting (has reconcile to do, but no semaphore available)

  import ResourceLoop._
  @volatile private var modified = Promise[Unit]()
  private val cancelable = reconcileNow(ErrorCount.zero).runToFuture

  override def cancel(): Unit = cancelable.cancel()

  def markModifiedSync(): Unit = {
    val _: Boolean = modified.trySuccess(())
  }

  def markModified: Task[Unit] = Task {
    val _: Boolean = modified.trySuccess(())
  }

  private def reconcileNow(errorCount: ErrorCount): Task[Unit] = {
    val result = permitScope.withPermit {
      // reset `modified` right before reading the current state. Updates occur in
      // the opposite order (state is changed then promise is fulfilled), which
      // ensures we never miss a change (but we could double-process a concurrent change)
      modified = Promise[Unit]()
      currentState.flatMap {
        case None => Task.pure(Success(ReconcileResult.Ignore))
        case Some(obj) => reconciler.reconcile(obj).materialize
      }
    }

    result.flatMap {
      case Success(_) => {
        println("Reconcile completed successfully")
        scheduleReconcile(ErrorCount.zero, refreshInterval)
      }

      case Failure(error) => {
        val nextCount = errorCount.increment
        val delay = backoffTime(nextCount).min(refreshInterval)
        println(s"Reconcile failed: ${error}, retrying in ${delay.toSeconds}s")
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
