package net.gfxmonk.foperator

import monix.eval.Task
import monix.execution.Scheduler
import net.gfxmonk.foperator.internal.Dispatcher
import skuber.ObjectResource

import scala.concurrent.duration._

case class Operator[T](
                        finalizer: Finalizer[T] = Finalizer.empty,
                        reconciler: Reconciler[T] = Reconciler.empty,
                        refreshInterval: FiniteDuration = 300.seconds,
                        concurrency: Int = 1
                      )

class Controller[T<:ObjectResource](operator: Operator[T], input: ControllerInput[T])(implicit scheduler: Scheduler) {
  def run: Task[Unit] = Dispatcher[T](operator, input).flatMap(_.run(input.inputs))
}
