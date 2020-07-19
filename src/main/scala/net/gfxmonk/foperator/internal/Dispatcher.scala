package net.gfxmonk.foperator.internal

import monix.catnap.Semaphore
import monix.eval.Task
import monix.execution.Scheduler
import monix.reactive.Observable
import net.gfxmonk.foperator._
import skuber.ObjectResource

object Dispatcher {
  trait PermitScope {
    def withPermit[A](task: Task[A]): Task[A]
  }

  def apply[T<:ObjectResource](operator: Operator[T], input: ControllerInput[T])(implicit scheduler: Scheduler): Task[Dispatcher[ResourceLoop,T]] = {
    val reconciler = Finalizer.reconciler(operator.finalizer, operator.reconciler)
    val manager = ResourceLoop.manager[T](operator.refreshInterval)

    Semaphore[Task](operator.concurrency.toLong).map { semaphore =>
      val permitScope = new PermitScope {
        override def withPermit[A](task: Task[A]): Task[A] = semaphore.withPermit(task)
      }
      new Dispatcher[ResourceLoop, T](reconciler, input.get, manager, permitScope)
    }
  }
}

// Fans out a single stream of Input[Id] to a Loop instance per Id
class Dispatcher[Loop[_], T<:ObjectResource](
  reconciler: Reconciler[ResourceState[T]],
  getResource: Id[T] => Task[Option[ResourceState[T]]],
  manager: ResourceLoop.Manager[Loop],
  permitScope: Dispatcher.PermitScope
) extends Logging {
  def run(input: Observable[Input[Id[T]]]): Task[Unit] = {
    input.mapAccumulate(Map.empty[Id[T],Loop[T]]) { (map:Map[Id[T],Loop[T]], input) =>
      val result: (Map[Id[T],Loop[T]], Task[Unit]) = input match {
        case Input.HardDeleted(id) => {
          logger.trace(s"Removing resource loop for ${id}")
          (map - id, map.get(id).map(manager.destroy).getOrElse(Task.unit))
        }
        case Input.Updated(id) => {
          map.get(id) match {
            case Some(loop) => {
              logger.trace(s"Triggering update for ${id}")
              (map, manager.update(loop))
            }
            case None => {
              logger.trace(s"Creating resource loop for ${id}")
              val loop = manager.create[T](getResource(id), reconciler, permitScope)
              (map.updated(id, loop), Task.unit)
            }
          }
        }
      }
      result
    }.mapEval(identity).completedL
  }
}
