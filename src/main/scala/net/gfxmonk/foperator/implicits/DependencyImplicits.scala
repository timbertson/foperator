package net.gfxmonk.foperator.implicits

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import monix.execution.Scheduler
import net.gfxmonk.foperator.FoperatorContext
import skuber.api.client.KubernetesClient

private [foperator] trait DependencyImplicits {
  implicit def actorSystemFromContext(implicit context: FoperatorContext): ActorSystem = context.actorSystem

  implicit def materializerFromContext(implicit context: FoperatorContext): ActorMaterializer = context.materializer

  implicit def k8sClientFromContext(implicit context: FoperatorContext): KubernetesClient = context.client

  implicit def schedulerFromContext(implicit context: FoperatorContext): Scheduler = context.scheduler
}
