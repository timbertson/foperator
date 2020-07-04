package net.gfxmonk.foperator

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import cats.effect.ExitCode
import cats.implicits._
import monix.eval.{Task, TaskApp}
import play.api.libs.json.Json
import skuber.ResourceSpecification.{Names, Scope}
import skuber.api.client.KubernetesClient
import skuber.apiextensions.CustomResourceDefinition
import skuber.{CustomResource, ObjectMeta, ResourceDefinition, ResourceSpecification, k8sInit}

object SampleMain extends TaskApp {
  implicit val system = ActorSystem()
  implicit val _scheduler = scheduler
  implicit val materializer = ActorMaterializer()
  implicit val dispatcher = system.dispatcher

  case class Spec(name: String)
  case class Status(message: String)
  type Hello = CustomResource[Spec,Status]

  val spec = CustomResourceDefinition.Spec(
    apiGroup="timtest.storage.zende.sk",
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
    metadata=ObjectMeta(name="greetings.timtest.storage.zende.sk"),
    spec=spec)

  implicit val rd: ResourceDefinition[Hello] = ResourceDefinition(crd)

  def install()(implicit client: KubernetesClient) = {
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

    val controller = new Controller[Hello](operator)
    install() >> controller.run.map(_ => ExitCode.Success)
  }
}
