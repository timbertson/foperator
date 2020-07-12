package net.gfxmonk.foperator.sample

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import monix.execution.Scheduler
import net.gfxmonk.foperator.Update.{Metadata, Spec, Status, Unchanged}
import net.gfxmonk.foperator.sample.Models.{GreetingSpec, GreetingStatus, PersonSpec, PersonStatus}
import net.gfxmonk.foperator.sample.Mutator.Action
import net.gfxmonk.foperator.{CustomResourceUpdate, ResourceState}
import skuber.api.client.KubernetesClient
import skuber.{CustomResource, ObjectMeta, ResourceDefinition, k8sInit}

import scala.concurrent.ExecutionContext

object Implicits {
  implicit val prettyPrintObjectMeta: PrettyPrint[ObjectMeta] = new PrettyPrint[ObjectMeta] {
    override def pretty(value: ObjectMeta): String = s"Meta(v${value.resourceVersion}, finalizers=${value.finalizers.getOrElse(Nil)}, deletionTimestamp=${value.deletionTimestamp})"
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

  implicit def prettyPrintUpdate[Sp,St](implicit ppSp: PrettyPrint[Sp], ppSt: PrettyPrint[St]): PrettyPrint[CustomResourceUpdate[Sp,St]] = new PrettyPrint[CustomResourceUpdate[Sp, St]] {
    override def pretty(update: CustomResourceUpdate[Sp, St]): String = {
      update match {
        case Status(initial, status) => s"Update.Status(${update.id}, ${initial.status.map(ppSt.pretty).getOrElse("None")} -> ${ppSt.pretty(status)})"
        case Spec(initial, spec) => s"Update.Spec(${update.id}, ${ppSp.pretty(initial.spec)} -> ${ppSp.pretty(spec)})"
        case Metadata(initial, metadata) => s"Update.Metadata(${update.id}, ${prettyPrintObjectMeta.pretty(initial.metadata)} -> ${prettyPrintObjectMeta.pretty(metadata)})"
        case Unchanged(_) => s"Update.Unchanged(${update.id})"
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

// There's a few implicits that are all tied to the given scheduler.
// We don't want to have to write them all up in every Main class, so we bundle them here
class SchedulerImplicits(s: Scheduler, client: Option[KubernetesClient]) {
  implicit val scheduler: Scheduler = s
  implicit val system: ActorSystem = ActorSystem(
    name = "operatorActorSystem",
    config = None,
    classLoader = None,
    defaultExecutionContext = Some[ExecutionContext](scheduler)
  )
  implicit val materializer = ActorMaterializer()

  implicit val k8sClient: KubernetesClient = client.getOrElse(k8sInit)
}

object SchedulerImplicits {
  def apply(scheduler: Scheduler) = new SchedulerImplicits(scheduler, None)
  def full(scheduler: Scheduler, client: KubernetesClient) = new SchedulerImplicits(scheduler, Some(client))
  def global: SchedulerImplicits = apply(Scheduler.global)
}
