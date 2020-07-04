package net.gfxmonk.foperator.internal

import net.gfxmonk.foperator._
import monix.catnap.Semaphore
import monix.eval.Task
import monix.execution.{Cancelable, Scheduler}
import monix.reactive.Observable
import skuber.ObjectResource

import scala.concurrent.Promise
import scala.concurrent.duration._
import scala.util.{Failure, Success}

object Dispatcher {
  type State[T] = Map[Id,T]
  trait PermitScope {
    def withPermit[A](task: Task[A]): Task[A]
  }

  case class ErrorCount(value: Int) extends AnyVal {
    def increment = ErrorCount(value + 1)
    def nonzero = value > 0
  }
  object ErrorCount {
    def zero = ErrorCount(0)
  }

  // Encapsulates the (infinite) reconcile loop for a single resource.
  class ResourceLoop[T<:AnyRef](
      initial: T,
      reconciler: Reconciler[T],
      refreshInterval: FiniteDuration,
      permitScope: PermitScope,
  )(implicit scheduler: Scheduler) extends Cancelable {
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
          val delay = Math.pow(1.2, nextCount.value.toDouble).seconds
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
}

class Dispatcher[T<:ObjectResource](operator: Operator[T]) {
  import Dispatcher._

  private val reconciler = Reconciler.sequence[T](
    operator.finalizer.map(_.reconciler).toList ++
      List(Reconciler.ignoreDeleted[T], operator.reconciler)
  )

  def run(input: Observable[Input[T]])(implicit scheduler: Scheduler): Task[Unit] = {
    // TODO controller-runtime has a token bucket ratelimit, but it's not clear whether it's applied to updates
    Semaphore[Task](operator.concurrency.toLong).flatMap { semaphore =>
      val permitScope = new PermitScope {
        override def withPermit[A](task: Task[A]): Task[A] = semaphore.withPermit(task)
      }
      runTask(input, permitScope)
    }
  }

  private def runTask(input: Observable[Input[T]], permitScope: PermitScope)(implicit scheduler: Scheduler): Task[Unit] = {
    input.mapAccumulate(Map.empty[Id,ResourceLoop[T]]) { (map:Map[Id,ResourceLoop[T]], input) =>
      input match {
        case Input.HardDeleted(resource) => {
          val id = Id.of(resource)
          (map - id, Task(map.get(id).foreach(_.cancel())))
        }
        case Input.Updated(resource) => {
          val id = Id.of(resource)
          map.get(id) match {
            case Some(loop) => (map, loop.update(resource))
            case None => {
              val loop = new ResourceLoop(resource, reconciler, operator.refreshInterval, permitScope)
              (map.updated(id, loop), Task.unit)
            }
          }
        }
      }
    }.mapEval(identity).completedL
  }
}

