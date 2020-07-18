package net.gfxmonk.foperator.sample

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import net.gfxmonk.foperator.Update.{Metadata, None, Spec, Status}
import net.gfxmonk.foperator.{CustomResourceUpdate, Id, Update}
import net.gfxmonk.foperator.sample.Models.{GreetingSpec, GreetingStatus, PersonSpec, PersonStatus}
import net.gfxmonk.foperator.sample.MutatorMain.{Action, Modify}
import skuber.{CustomResource, ObjectMeta, ObjectResource, k8sInit}

object Implicits {
  implicit val system = ActorSystem()
  implicit val materializer = ActorMaterializer()
  implicit val dispatcher = system.dispatcher
  implicit val client = k8sInit

  implicit val prettyPrintObjectMeta: PrettyPrint[ObjectMeta] = new PrettyPrint[ObjectMeta] {
    override def pretty(value: ObjectMeta): String = s"Meta(finalizers=${value.finalizers.getOrElse(Nil)}, deletionTimestamp=${value.deletionTimestamp})"
  }

  implicit def prettyPrintCustomResource[Sp,St](implicit ppSp: PrettyPrint[Sp], ppSt: PrettyPrint[St]): PrettyPrint[CustomResource[Sp,St]] = new PrettyPrint[CustomResource[Sp, St]] {
    override def pretty(value: CustomResource[Sp, St]): String = s"CustomResource(${value.name} v${value.metadata.resourceVersion}, spec=${ppSp.pretty(value.spec)}, status=${value.status.map(ppSt.pretty).getOrElse("None")})"
  }

  implicit def prettyPrintUpdate[Sp,St](implicit ppSp: PrettyPrint[Sp], ppSt: PrettyPrint[St]): PrettyPrint[CustomResourceUpdate[Sp,St]] = new PrettyPrint[CustomResourceUpdate[Sp, St]] {
    override def pretty(update: CustomResourceUpdate[Sp, St]): String = {
      update match {
        case Status(initial, status) => s"Status(${Id.of(initial)}, ${ppSt.pretty(status)})"
        case Spec(initial, spec) => s"Spec(${Id.of(initial)}, ${ppSp.pretty(spec)})"
        case Metadata(initial, metadata) => s"Metadata(${Id.of(initial)}, ${prettyPrintObjectMeta.pretty(metadata)})"
        case None(initial) => s"None(${Id.of(initial)})"
      }
    }
  }

  implicit val prettyPrintAction: PrettyPrint[Action] = new PrettyPrint[Action] {
    override def pretty(value: Action): String = value.pretty
  }

  implicit val prettyPersonSpec: PrettyPrint[PersonSpec] = PrettyPrint.fromString[PersonSpec]
  implicit val prettyPersonStatus: PrettyPrint[PersonStatus] = PrettyPrint.fromString[PersonStatus]

  implicit val prettyGreetingSpec: PrettyPrint[GreetingSpec] = PrettyPrint.fromString[GreetingSpec]
  implicit val prettyGreetingStatus: PrettyPrint[GreetingStatus] = PrettyPrint.fromString[GreetingStatus]
}
