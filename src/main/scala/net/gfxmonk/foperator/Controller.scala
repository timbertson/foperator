package net.gfxmonk.foperator

import akka.stream.ActorMaterializer
import net.gfxmonk.foperator.internal.Dispatcher
import monix.eval.Task
import monix.execution.Scheduler
import play.api.libs.json.Format
import skuber.api.client.{KubernetesClient, LoggingContext}
import skuber.{LabelSelector, ListOptions, ObjectResource, ResourceDefinition}

import scala.concurrent.duration._

case class Operator[T](
                        labelSelector: Option[LabelSelector] = None,
                        finalizer: Option[Finalizer[T]] = None,
                        reconciler: Reconciler[T],
                        refreshInterval: FiniteDuration = 300.seconds,
                        concurrency: Int = 1
                      )

class Controller[T<:ObjectResource](operator: Operator[T])(
  implicit fmt: Format[T], rd: ResourceDefinition[T], lc: LoggingContext,
  scheduler: Scheduler,
  materializer: ActorMaterializer,
  client: KubernetesClient
) {
  import Operations._
  def run: Task[Unit] = {
    val listOptions = ListOptions(
      labelSelector = operator.labelSelector,
    )
    val inputs = listAndWatch[T](listOptions)

    val dispatcher = new Dispatcher[T](operator)
    dispatcher.run(inputs)
  }
}
