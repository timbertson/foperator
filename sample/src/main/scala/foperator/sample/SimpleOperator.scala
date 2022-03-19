package foperator.sample

import cats.effect.{ExitCode, IO, IOApp}
import foperator.backend.Skuber
import foperator.backend.skuber.implicits._
import foperator.sample.Models.Skuber._
import foperator.sample.Models._
import skuber.apiextensions.CustomResourceDefinition

object SimpleOperator extends IOApp.Simple {
  override def run: IO[Unit] = {
    Skuber[IO].default(runtime.compute).use { skuber =>
      new SimpleOperator(skuber).run
    }
  }

  // These would commonly be defined on `class SimpleOperator` instead,
  // but we want to expose them for reuse by AdvancedOperator
  def expectedStatus(greeting: Greeting): GreetingStatus =
    GreetingStatus(s"Hello, ${greeting.spec.name.getOrElse("UNKNOWN")}", people = Nil)

  val reconciler = Skuber[IO].Reconciler[Greeting].status { greeting =>
    // Always return the expected status, Reconciler.customResourceUpdater
    // will make this a no-op without any API calls if it is unchanged.
    IO.pure(expectedStatus(greeting))
  }
}

class SimpleOperator[C](client: Skuber[IO]) {
  import SimpleOperator._

  def install = {
    client.apply[CustomResourceDefinition].forceWrite(greetingCrd).void
  }

  def run: IO[Unit] = {
    install >> client.apply[Greeting].runReconciler(reconciler)
  }
}
