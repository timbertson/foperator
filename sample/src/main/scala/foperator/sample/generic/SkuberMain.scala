package foperator.sample.generic

import cats.effect.ExitCode
import cats.implicits._
import foperator.backend.skuber_backend.Skuber
import foperator.backend.skuber_backend.implicits._
import monix.eval.{Task, TaskApp}
import foperator.sample.Models
import foperator.sample.Models.Skuber._
import skuber.CustomResource
import skuber.apiextensions.CustomResourceDefinition

object SkuberMain extends TaskApp {
  override def run(args: List[String]): Task[ExitCode] = {
    // Note: the hard work of making the require implicits is done in Models
    new GenericOperator[Task, Skuber, CustomResourceDefinition, CustomResource](Skuber().ops, greetingCrd).run.as(ExitCode.Success)
  }
}
