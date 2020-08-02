package net.gfxmonk.foperator

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import cats.Eq
import cats.implicits._
import monix.execution.Scheduler
import skuber.api.client.{KubernetesClient, LoggingContext}
import skuber.{CustomResource, ObjectMeta, ObjectResource}

package object implicits {
  implicit class UpdateExt[T<:ObjectResource](val resource: T) extends AnyVal {
    def specUpdate[Sp](sp: Sp): Update.Spec[T,Sp] = Update.Spec(resource, sp)
    def statusUpdate[St](st: St): Update.Status[T,St] = Update.Status(resource, st)
    def metadataUpdate(metadata: ObjectMeta): Update.Metadata[T] = Update.Metadata(resource, metadata)
    def unchanged: Update.Unchanged[T] = Update.Unchanged(resource)
  }

  implicit val metadataEq: Eq[ObjectMeta] = Eq.fromUniversalEquals

  implicit def customResourceEq[Sp,St](implicit eqSp: Eq[Sp], eqSt: Eq[St]): Eq[CustomResource[Sp,St]] = new Eq[CustomResource[Sp,St]] {
    override def eqv(x: CustomResource[Sp, St], y: CustomResource[Sp, St]): Boolean = {
      // use a full unapply to extract fields so that this fails to compile if CustomResource gains new fields
      (x, y) match {
        case (
          CustomResource(kind: String, apiVersion: String, metadata: ObjectMeta, spec: Sp, status: Option[St]),
          CustomResource(otherKind: String, otherApiVersion: String, otherMetadata: ObjectMeta, otherSpec: Sp, otherStatus: Option[St])
        ) => (
          kind === otherKind
            && apiVersion === otherApiVersion
            && metadata === otherMetadata
            && spec === otherSpec
            && status === otherStatus
        )
      }
    }
  }

  implicit def actorSystemFromContext(implicit context: FoperatorContext): ActorSystem = context.actorSystem

  implicit def materializerFromContext(implicit context: FoperatorContext): ActorMaterializer = context.materializer

  implicit def k8sClientFromContext(implicit context: FoperatorContext): KubernetesClient = context.client

  implicit def schedulerFromContext(implicit context: FoperatorContext): Scheduler = context.scheduler
}
