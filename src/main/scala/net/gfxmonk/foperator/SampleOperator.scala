package net.gfxmonk.foperator.sample

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import cats.effect.ExitCode
import cats.implicits._
import monix.eval.{Task, TaskApp}
import net.gfxmonk.foperator._
import net.gfxmonk.foperator.sample.AdvancedMain.scheduler
import play.api.libs.json.Json
import skuber.ResourceSpecification.{Names, Scope}
import skuber.api.client.KubernetesClient
import skuber.apiextensions.CustomResourceDefinition
import skuber.{CustomResource, ObjectMeta, ResourceDefinition, ResourceSpecification, k8sInit}

object Implicits {
  implicit val system = ActorSystem()
  implicit val materializer = ActorMaterializer()
  implicit val dispatcher = system.dispatcher
}

object SimpleMain extends TaskApp {
  import Implicits._
  implicit val _scheduler = scheduler

  case class Spec(name: Option[String])
  case class Status(message: String)
  type Greeting = CustomResource[Spec,Status]

  val spec = CustomResourceDefinition.Spec(
    apiGroup="timtest.storage.gfxmonk.net",
    version="v1alpha1",
    names=Names(
      plural = "greetings",
      singular = "greeting",
      kind = "Greeting",
      listKind = Some("Greetings"),
      shortNames = Nil
    ),
    scope=Scope.Namespaced,
  ).copy(subresources = Some(ResourceSpecification.Subresources().withStatusSubresource()))

  val crd = CustomResourceDefinition(
    metadata=ObjectMeta(name="greetings.timtest.storage.gfxmonk.net"),
    spec=spec)

  implicit val rd: ResourceDefinition[Greeting] = ResourceDefinition(crd)

  def install()(implicit client: KubernetesClient) = {
    Operations.write[CustomResourceDefinition]((res, meta) => res.copy(metadata=meta))(crd).void
  }

  override def run(args: List[String]): Task[ExitCode] = {
    implicit val client = k8sInit
    implicit val statusFmt = Json.format[Status]
    implicit val specFmt = Json.format[Spec]
    implicit val fmt = CustomResource.crFormat[Spec,Status]
    implicit val st: skuber.HasStatusSubresource[Greeting] = CustomResource.statusMethodsEnabler[Greeting]
    val operator = Operator[Greeting](
      reconciler = Reconciler.updater { hello => Task.pure {
        val expected = s"hello, ${hello.spec.name.getOrElse("UNKNOWN")}"
        val current = hello.status.map(_.message)
        if (current === Some(expected)) {
          None
        } else {
          Some(Update.Status(Status(expected)))
        }
      }}
    )

    val test = Id.unsafeCreate(classOf[Greeting], "default", "test")

    install() >> ResourceTracker.all[Greeting].use { tracker =>
      val controller = new Controller[Greeting](operator, tracker)
      controller.run.map(_ => ExitCode.Success)
    }
  }
}

object AdvancedMain extends TaskApp {
  import Implicits._
  implicit val _scheduler = scheduler

  val personSpec = CustomResourceDefinition.Spec(
    apiGroup="timtest.storage.gfxmonk.net",
    version="v1alpha1",
    names=Names(
      plural = "people",
      singular = "person",
      kind = "Person",
      listKind = Some("Greetings"),
      shortNames = Nil
    ),
    scope=Scope.Namespaced,
  ).copy(subresources = Some(ResourceSpecification.Subresources().withStatusSubresource()))

  val crd = CustomResourceDefinition(
    metadata=ObjectMeta(name="greetings.timtest.storage.gfxmonk.net"),
    spec=spec)

  implicit val rd: ResourceDefinition[Hello] = ResourceDefinition(crd)
  def install()(implicit client: KubernetesClient) = {
    SimpleMain.install() >>
    Operations.write[CustomResourceDefinition]((res, meta) => res.copy(metadata=meta))(crd).void
  }

  override def run(args: List[String]): Task[ExitCode] = {
    implicit val client = k8sInit
    implicit val statusFmt = Json.format[Status]
    implicit val specFmt = Json.format[Spec]
    implicit val fmt = CustomResource.crFormat[Spec,Status]
    implicit val st: skuber.HasStatusSubresource[Hello] = CustomResource.statusMethodsEnabler[Hello]
    val operator = Operator[Hello](
      reconciler = Reconciler.updater { hello => Task.pure {
        val expected = s"hello, ${hello.spec.name}"
        val current = hello.status.map(_.message)
        if (current === Some(expected)) {
          None
        } else {
          Some(Update.Status(Status(expected)))
        }
      }}
    )

    val test = Id.unsafeCreate(classOf[Hello], "default", "test")

    install() >> ResourceTracker.all[Hello].use { tracker =>
      val controller = new Controller[Hello](operator, tracker)
      controller.run.map(_ => ExitCode.Success)
    }
  }
}
