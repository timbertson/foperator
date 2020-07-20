package net.gfxmonk.foperator.sample

import cats.effect.ExitCode
import monix.eval.{Task, TaskApp}
import monix.execution.Scheduler
import net.gfxmonk.foperator.implicits._
import net.gfxmonk.foperator._
import net.gfxmonk.foperator.sample.Models.{Greeting, GreetingStatus}
import skuber.api.client.KubernetesClient
import skuber.apiextensions.CustomResourceDefinition
import skuber.k8sInit

object SimpleMain {
  def main(args: Array[String]): Unit = {
    import Implicits._
    implicit val scheduler: Scheduler = Scheduler.global
    implicit val client: KubernetesClient = k8sInit
    (new SimpleOperator).main(args)
  }
}

object SimpleOperator {
  def expectedStatus(greeting: Greeting): GreetingStatus =
    GreetingStatus(s"hello, ${greeting.spec.name.getOrElse("UNKNOWN")}", people = Nil)
}

class SimpleOperator(implicit scheduler: Scheduler, client: KubernetesClient) extends TaskApp {
  import Implicits._
  import Models._

  def install() = {
    Operations.write[CustomResourceDefinition]((res, meta) => res.copy(metadata=meta))(greetingCrd).void
  }

  def runWith(mirror: ResourceMirror[Greeting]): Task[Unit] = {
    val operator = Operator[Greeting](
      reconciler = Reconciler.updater { greeting =>
        // Always return the expected status, Reconciler.customResourceUpdater
        // will make this a no-op without any API calls if it is unchanged.
        Task.pure(greeting.statusUpdate(SimpleOperator.expectedStatus(greeting)))
      }
    )
    new Controller[Greeting](operator, ControllerInput(mirror)).run
  }

  override def run(args: List[String]): Task[ExitCode] = {
    install() >> ResourceMirror.all[Greeting].use { mirror =>
      runWith(mirror).map(_ => ExitCode.Success)
    }
  }
}
