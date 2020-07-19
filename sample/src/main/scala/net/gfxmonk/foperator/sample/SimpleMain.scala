package net.gfxmonk.foperator.sample

import cats.effect.ExitCode
import monix.eval.{Task, TaskApp}
import net.gfxmonk.foperator.{Controller, ControllerInput, Operations, Operator, Reconciler, ResourceMirror}
import net.gfxmonk.foperator.implicits._
import net.gfxmonk.foperator.sample.AdvancedMain.{greetingController, personController}
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

  def runWith(mirror: ResourceMirror[Greeting]): Task[Unit] = {
    val operator = Operator[Greeting](
      reconciler = Reconciler.updater { greeting =>
        // Always return the expected status, Reconciler.customResourceUpdater
        // will make this a no-op without any API calls if it is unchanged.
        Task.pure(greeting.statusUpdate(expectedStatus(greeting)))
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
