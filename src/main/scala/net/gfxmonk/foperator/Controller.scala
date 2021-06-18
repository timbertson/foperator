package net.gfxmonk.foperator

import monix.eval.Task
import monix.execution.Scheduler
import net.gfxmonk.foperator.internal.Dispatcher
import skuber.ObjectResource

import scala.concurrent.duration._

case class Operator[T](
                        finalizer: Finalizer[T] = Finalizer.empty[T],
                        reconciler: Reconciler[T] = Reconciler.empty,
                        refreshInterval: Option[FiniteDuration] = Some(5.minutes),
                        concurrency: Int = 1
                      )

// TODO automatically install finalizer if non-None
class Controller[T<:ObjectResource](operator: Operator[T], input: ControllerInput[T])(implicit scheduler: Scheduler) {
  def run: Task[Unit] = Dispatcher[T](operator, input).flatMap(_.run(input.events))
}
