package net.gfxmonk.foperator.internal

import monix.catnap.Semaphore
import monix.eval.Task
import monix.execution.Scheduler
import monix.reactive.Observable
import net.gfxmonk.foperator._
import skuber.ObjectResource

import scala.concurrent.Promise

object Dispatcher {
  def apply[T<:ObjectResource](operator: Operator[T], input: ControllerInput[T])(implicit scheduler: Scheduler): Task[Dispatcher[ResourceLoop,T]] = {
    val reconciler = operator.finalizer match {
      case Some(finalizer) => Finalizer.merge(finalizer, operator.reconciler)
      case None => Finalizer.lift(operator.reconciler)
    }
    val manager = ResourceLoop.manager[T](operator.refreshInterval)

    Semaphore[Task](operator.concurrency.toLong).map { semaphore =>
      new Dispatcher[ResourceLoop, T](reconciler, input.get, manager, PermitScope.semaphore(semaphore))
    }
  }
}

// Fans out a single stream of Input[Id] to a Loop instance per Id
class Dispatcher[Loop[_], T<:ObjectResource](
  reconciler: Reconciler[ResourceState[T]],
  getResource: Id[T] => Task[Option[ResourceState[T]]],
  manager: ResourceLoop.Manager[Loop, T],
  permitScope: PermitScope
) extends Logging {
  private val error = Promise[Unit]()
  private def onError(throwable: Throwable) = Task { error.tryFailure(throwable) }.void

  def run(input: Observable[Event[Id[T]]]): Task[Unit] = {
    Task.race(Task.fromFuture(error.future), runloop(input)).void
  }

  private def runloop(input: Observable[Event[Id[T]]]): Task[Unit] = {
    Task(logger.trace("Starting runloop")) >>
    input.mapAccumulate(Map.empty[Id[T],Loop[T]]) { (map:Map[Id[T],Loop[T]], input) =>
      val result: (Map[Id[T],Loop[T]], Task[Unit]) = input match {
        case Event.HardDeleted(id) => {
          logger.trace(s"Removing resource loop for ${id}")
          (map - id, map.get(id).map(manager.destroy).getOrElse(Task.unit))
        }
        case Event.Updated(id) => {
          map.get(id) match {
            case Some(loop) => {
              logger.trace(s"Triggering update for ${id}")
              (map, manager.update(loop))
            }
            case None => {
              logger.trace(s"Creating resource loop for ${id}")
              val loop = manager.create(getResource(id), reconciler, permitScope, onError)
              (map.updated(id, loop), Task.unit)
            }
          }
        }
      }
      result
    }.mapEval(identity).completedL
  }
}
