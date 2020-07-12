package net.gfxmonk.foperator.internal

import monix.catnap.Semaphore
import monix.eval.Task
import monix.execution.Scheduler
import monix.reactive.Observable
import net.gfxmonk.foperator._
import net.gfxmonk.foperator.internal.ResourceLoop.ErrorCount
import skuber.ObjectResource
import scala.concurrent.duration._

object Dispatcher {
  trait PermitScope {
    def withPermit[A](task: Task[A]): Task[A]
  }

  def apply[T<:ObjectResource](operator: Operator[T])(implicit scheduler: Scheduler): Task[Dispatcher[ResourceLoop,T]] = {
    val reconciler = Reconciler.sequence[T](
      operator.finalizer.map(_.reconciler).toList ++
        List(Reconciler.ignoreDeleted[T], operator.reconciler)
    )
    val manager = ResourceLoop.manager[T](operator.refreshInterval)

    Semaphore[Task](operator.concurrency.toLong).map { semaphore =>
      // TODO controller-runtime has a token bucket ratelimit, but it's not clear whether it's applied to updates
      val permitScope = new PermitScope {
        override def withPermit[A](task: Task[A]): Task[A] = semaphore.withPermit(task)
      }
      new Dispatcher[ResourceLoop, T](reconciler, manager, permitScope)
    }
  }
}

class Dispatcher[Loop[_], T<:ObjectResource](reconciler: Reconciler[T], manager: ResourceLoop.Manager[Loop], permitScope: Dispatcher.PermitScope) {
  def run(input: Observable[Input[T]])(implicit scheduler: Scheduler): Task[Unit] = {

    input.mapAccumulate(Map.empty[Id,Loop[T]]) { (map:Map[Id,Loop[T]], input) =>
      val result: (Map[Id,Loop[T]], Task[Unit]) = input match {
        case Input.HardDeleted(resource) => {
          val id = Id.of(resource)
          (map - id, map.get(id).map(manager.destroy).getOrElse(Task.unit))
        }
        case Input.Updated(resource) => {
          val id = Id.of(resource)
          map.get(id) match {
            case Some(loop) => (map, manager.update(loop, resource))
            case None => {
              val loop = manager.create[T](resource, reconciler, permitScope)
              (map.updated(id, loop), Task.unit)
            }
          }
        }
      }
      result
    }.mapEval(identity).completedL
  }
}


class Dispatcher2[Loop[_], T<:ObjectResource](reconciler: Reconciler[T], manager: ResourceLoop.Manager[Loop], permitScope: Dispatcher.PermitScope) {
  def run(input: Observable[Input[T]])(implicit scheduler: Scheduler): Task[Unit] = {

    input.mapAccumulate(Map.empty[Id,Loop[T]]) { (map:Map[Id,Loop[T]], input) =>
      val result: (Map[Id,Loop[T]], Task[Unit]) = input match {
        case Input.HardDeleted(resource) => {
          val id = Id.of(resource)
          (map - id, map.get(id).map(manager.destroy).getOrElse(Task.unit))
        }
        case Input.Updated(resource) => {
          val id = Id.of(resource)
          map.get(id) match {
            case Some(loop) => (map, manager.update(loop, resource))
            case None => {
              val loop = manager.create[T](resource, reconciler, permitScope)
              (map.updated(id, loop), Task.unit)
            }
          }
        }
      }
      result
    }.mapEval(identity).completedL
  }
}
