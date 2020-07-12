package net.gfxmonk.foperator

import akka.stream.ActorMaterializer
import net.gfxmonk.foperator.internal.Dispatcher
import monix.eval.Task
import monix.execution.Scheduler
import monix.reactive.Observable
import play.api.libs.json.Format
import skuber.api.client.{KubernetesClient, LoggingContext}
import skuber.{LabelSelector, ListOptions, ObjectResource, ResourceDefinition}

import scala.concurrent.duration._

case class Operator[T](
                        finalizer: Option[Finalizer[T]] = None,
                        reconciler: Reconciler[T],
                        refreshInterval: FiniteDuration = 300.seconds,
                        concurrency: Int = 1
                      )

class Controller[T<:ObjectResource](operator: Operator[T], tracker: ResourceTracker[T], updateTriggers: List[Observable[Id[T]]] = Nil)(
  implicit fmt: Format[T], rd: ResourceDefinition[T], lc: LoggingContext,
  scheduler: Scheduler,
  materializer: ActorMaterializer,
  client: KubernetesClient
) {
  def run: Task[Unit] = {
    val updateInputs: List[Observable[Input[Id[T]]]] = updateTriggers.map(obs => obs.map(Input.Updated.apply))
    val allInputs: Observable[Input[Id[T]]] = Observable.from(tracker.ids :: updateInputs).merge
    Dispatcher[T](operator, tracker).flatMap(_.run(allInputs))
  }
}
