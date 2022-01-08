package foperator.sample.generic

import cats.effect.ExitCode
import cats.implicits._
import foperator.backend.Skuber
import foperator.backend.skuber.implicits._
import monix.eval.{Task, TaskApp}
import skuber.CustomResource
import skuber.apiextensions.CustomResourceDefinition

object SkuberMain extends TaskApp {
  override def run(args: List[String]): Task[ExitCode] = {
    import foperator.sample.Models.Skuber._
    Skuber().use { skuber =>
      new GenericOperator[Task, Skuber, CustomResourceDefinition, CustomResource](skuber, greetingCrd ).run
    }.as(ExitCode.Success)
  }
}
