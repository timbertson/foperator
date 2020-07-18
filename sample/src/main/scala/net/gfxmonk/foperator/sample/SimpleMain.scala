package net.gfxmonk.foperator.sample

import cats.effect.ExitCode
import monix.eval.{Task, TaskApp}
import net.gfxmonk.foperator.{Controller, ControllerInput, Operations, Operator, Reconciler, ResourceMirror}
import net.gfxmonk.foperator.implicits._
import skuber.api.client.KubernetesClient
import skuber.apiextensions.CustomResourceDefinition

object SimpleMain extends TaskApp {
  implicit val _scheduler = scheduler
  import Implicits._
  import Models._

  def install()(implicit client: KubernetesClient) = {
    Operations.write[CustomResourceDefinition]((res, meta) => res.copy(metadata=meta))(greetingCrd).void
  }

  def expectedStatus(greeting: Greeting): GreetingStatus =
    GreetingStatus(s"hello, ${greeting.spec.name.getOrElse("UNKNOWN")}", people = Nil)

  override def run(args: List[String]): Task[ExitCode] = {
    val operator = Operator[Greeting](
      reconciler = Reconciler.customResourceUpdater { greeting =>
        // Always return the expected status, Reconciler.customResourceUpdater
        // will make this a no-op without any API calls if it is unchanged.
        Task.pure(greeting.statusUpdate(expectedStatus(greeting)))
      }
    )

    install() >> ResourceMirror.all[Greeting].use { mirror =>
      val controller = new Controller[Greeting](operator, ControllerInput(mirror))
      controller.run.map(_ => ExitCode.Success)
    }
  }
}
