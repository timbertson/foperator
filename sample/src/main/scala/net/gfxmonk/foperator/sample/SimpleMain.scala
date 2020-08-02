package net.gfxmonk.foperator.sample

import cats.effect.ExitCode
import monix.eval.{Task, TaskApp}
import net.gfxmonk.foperator._
import net.gfxmonk.foperator.implicits._
import net.gfxmonk.foperator.sample.Models.{Greeting, GreetingStatus}
import skuber.apiextensions.CustomResourceDefinition

object SimpleMain {
  def main(args: Array[String]): Unit = {
    new SimpleOperator(FoperatorContext.global).main(args)
  }
}

object SimpleOperator {
  def expectedStatus(greeting: Greeting): GreetingStatus =
    GreetingStatus(s"hello, ${greeting.spec.name.getOrElse("UNKNOWN")}", people = Nil)
}

class SimpleOperator(ctx: FoperatorContext) extends TaskApp {
  import Models._
  implicit val _ctxImplicit = ctx

  def install() = {
    Operations.write[CustomResourceDefinition]((res, meta) => res.copy(metadata=meta))(greetingCrd).void
  }

  def runWith(mirror: ResourceMirror[Greeting]): Task[Unit] = {
    val operator = Operator[Greeting](
      refreshInterval = None,
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
