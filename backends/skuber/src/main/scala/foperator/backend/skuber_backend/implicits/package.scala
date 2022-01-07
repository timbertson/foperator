package foperator.backend.skuber_backend

import cats.Eq
import cats.implicits._
import foperator.Id
import foperator.types._
import skuber.apiextensions.CustomResourceDefinition
import skuber.{CustomResource, NonCoreResourceSpecification, ObjectMeta, ResourceDefinition, objResourceToRef}

import java.time.{Instant, ZoneOffset, ZonedDateTime}

package object implicits extends foperator.CommonImplicits {
  // implicits that don't have a better place

  implicit val metadataEq: Eq[ObjectMeta] = Eq.fromUniversalEquals

  implicit def customResourceEq[Sp, St](implicit eqSp: Eq[Sp], eqSt: Eq[St]): Eq[CustomResource[Sp,St]] = new Eq[CustomResource[Sp,St]] {
    override def eqv(x: CustomResource[Sp, St], y: CustomResource[Sp, St]): Boolean = {
      // use a full unapply to extract fields so that this fails to compile if CustomResource gains new fields
      (x, y) match {
        case (
          CustomResource(kind: String, apiVersion: String, metadata: ObjectMeta, spec, status: Option[St]),
          CustomResource(otherKind: String, otherApiVersion: String, otherMetadata: ObjectMeta, otherSpec, otherStatus: Option[St])
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

  implicit val crdEq: Eq[CustomResourceDefinition] = Eq.fromUniversalEquals[CustomResourceDefinition]

  implicit def skuberObjectResource[T<:skuber.ObjectResource](implicit rd: ResourceDefinition[T], ed: skuber.ObjectEditor[T]): ObjectResource[T] = new ObjectResource[T] {
    override def id(t: T): Id[T] = Id.apply[T](t.metadata.namespace, t.metadata.name)

    override def kind: String = rd.spec.names.kind

    override def finalizers(t: T): List[String] = t.metadata.finalizers.getOrElse(Nil)

    override def replaceFinalizers(t: T, f: List[String]): T = ed.updateMetadata(t, t.metadata.copy(finalizers = Some(f)))

    override def version(t: T): String = t.metadata.resourceVersion

    override def withVersion(t: T, newVersion: String): T = ed.updateMetadata(t, t.metadata.copy(resourceVersion = newVersion))

    override def isSoftDeleted(t: T): Boolean = t.metadata.deletionTimestamp.isDefined

    override def softDeletedAt(t: T, timestamp: Instant): T = ed.updateMetadata(t, t.metadata.copy(deletionTimestamp = Some(timestamp.atZone(ZoneOffset.UTC))))
  }

  // TODO shouldn't skuber provide this?
  implicit def crEditor[Sp,St]: skuber.ObjectEditor[CustomResource[Sp,St]] = new skuber.ObjectEditor[CustomResource[Sp,St]] {
    override def updateMetadata(obj: CustomResource[Sp, St], newMetadata: ObjectMeta): CustomResource[Sp, St] = obj.copy(metadata = newMetadata)
  }

  implicit def defEditor[Sp,St]: skuber.ObjectEditor[CustomResourceDefinition] = new skuber.ObjectEditor[CustomResourceDefinition] {
    override def updateMetadata(obj: CustomResourceDefinition, newMetadata: ObjectMeta): CustomResourceDefinition = obj.copy(metadata = newMetadata)
  }

  // TODO is there a nice way to provide subresources for non-custom resources?
  implicit def crSubresources[Sp,St](
    implicit rd: skuber.ResourceDefinition[skuber.CustomResource[Sp, St]],
    eqSt: Eq[St],
  )
  : HasStatus[skuber.CustomResource[Sp, St], St]
    with HasSpec[skuber.CustomResource[Sp,St], Sp] =
  {
    CustomResource.statusMethodsEnabler[skuber.CustomResource[Sp, St]] // runtime check
    new HasStatus[skuber.CustomResource[Sp, St], St] with HasSpec[skuber.CustomResource[Sp,St], Sp] {
      override val eqStatus: Eq[St] = eqSt

      override def status(obj: CustomResource[Sp, St]): Option[St] = obj.status

      override def withStatus(obj: CustomResource[Sp, St], status: St): CustomResource[Sp, St] = obj.copy(status=Some(status))

      override def spec(obj: CustomResource[Sp, St]): Sp = obj.spec

      override def withSpec(obj: CustomResource[Sp, St], spec: Sp): CustomResource[Sp, St] = obj.copy(spec=spec)
    }
  }
}
