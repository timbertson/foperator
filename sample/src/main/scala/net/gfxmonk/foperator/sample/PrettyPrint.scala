package net.gfxmonk.foperator.sample

import net.gfxmonk.foperator.sample.Models.{GreetingSpec, GreetingStatus, PersonSpec}
import net.gfxmonk.foperator.sample.mutator.Mutator.Action
import net.gfxmonk.foperator.{Id, ResourceState}
import skuber.{CustomResource, ObjectMeta, ResourceDefinition}

trait PrettyPrint[T] {
  def pretty(value: T): String
}

object PrettyPrint {
  def fromString[T]: PrettyPrint[T] = new PrettyPrint[T] {
    override def pretty(value: T): String = value.toString
  }

  object Implicits {
    implicit val prettyPrintObjectMeta: PrettyPrint[ObjectMeta] = new PrettyPrint[ObjectMeta] {
      override def pretty(value: ObjectMeta): String = s"Meta(v${value.resourceVersion}, finalizers=${value.finalizers.getOrElse(Nil)}, deletionTimestamp=${value.deletionTimestamp})"
    }

    implicit def prettyPrintId[T]: PrettyPrint[Id[T]] = new PrettyPrint[Id[T]] {
      override def pretty(value: Id[T]): String = value.toString
    }

    implicit def prettyPrintCustomResource[Sp, St](
      implicit ppSp: PrettyPrint[Sp],
      ppSt: PrettyPrint[St],
      ppMeta: PrettyPrint[ObjectMeta],
      rd: ResourceDefinition[CustomResource[Sp, St]]
    ): PrettyPrint[CustomResource[Sp, St]] = new PrettyPrint[CustomResource[Sp, St]] {
      override def pretty(value: CustomResource[Sp, St]): String = s"${rd.spec.names.kind}(${value.name}, ${ppSp.pretty(value.spec)}, ${value.status.map(ppSt.pretty).getOrElse("None")}, ${ppMeta.pretty(value.metadata)})"
    }

    implicit def prettyOption[T](implicit pp: PrettyPrint[T]): PrettyPrint[Option[T]] = new PrettyPrint[Option[T]] {
      override def pretty(value: Option[T]): String = value.map(value => s"Some(${pp.pretty(value)})").getOrElse("None")
    }

    implicit def prettyResourceState[T](implicit pp: PrettyPrint[T]): PrettyPrint[ResourceState[T]] = new PrettyPrint[ResourceState[T]] {
      override def pretty(value: ResourceState[T]): String = value match {
        case ResourceState.SoftDeleted(value) => s"[SOFT_DELETE] ${pp.pretty(value)}"
        case ResourceState.Active(value) => pp.pretty(value)
      }
    }

    implicit val prettyPrintAction: PrettyPrint[Action] = new PrettyPrint[Action] {
      override def pretty(value: Action): String = value.pretty
    }

    implicit val prettyPersonSpec: PrettyPrint[PersonSpec] = PrettyPrint.fromString[PersonSpec]
    implicit val prettyUnit: PrettyPrint[Unit] = PrettyPrint.fromString[Unit]

    implicit val prettyGreetingSpec: PrettyPrint[GreetingSpec] = PrettyPrint.fromString[GreetingSpec]
    implicit val prettyGreetingStatus: PrettyPrint[GreetingStatus] = PrettyPrint.fromString[GreetingStatus]
  }
}

