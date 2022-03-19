package foperator.sample.generic

import cats.effect.{ExitCode, IO, IOApp}
import foperator.backend.Skuber
import foperator.backend.skuber.implicits._
import skuber.CustomResource
import skuber.apiextensions.CustomResourceDefinition

object SkuberMain extends IOApp.Simple {
  override def run: IO[Unit] = {
    import foperator.sample.Models.Skuber._
    Skuber[IO].default().use { skuber =>
      new GenericOperator[IO, Skuber[IO], CustomResourceDefinition, CustomResource](skuber, greetingCrd ).run
    }
  }
}
