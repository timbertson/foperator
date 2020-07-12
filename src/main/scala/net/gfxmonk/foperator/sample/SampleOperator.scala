package net.gfxmonk.foperator.sample

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import cats.Eq
import cats.effect.ExitCode
import cats.implicits._
import monix.eval.{Task, TaskApp}
import net.gfxmonk.foperator._
import play.api.libs.json.Json
import skuber.ResourceSpecification.{Names, Scope}
import skuber.api.client.KubernetesClient
import skuber.apiextensions.CustomResourceDefinition
import skuber.{CustomResource, ObjectMeta, ResourceDefinition, ResourceSpecification, k8sInit}

object Models {
  /** Greeting */
  case class GreetingSpec(name: Option[String], surname: Option[String])
  case class GreetingStatus(message: String, people: List[String])
  implicit def greetingStatusEq = Eq.fromUniversalEquals[GreetingStatus]
  type Greeting = CustomResource[GreetingSpec,GreetingStatus]

  implicit val statusFmt = Json.format[GreetingStatus]
  implicit val specFmt = Json.format[GreetingSpec]
  implicit val fmt = CustomResource.crFormat[GreetingSpec,GreetingStatus]
  implicit val st: skuber.HasStatusSubresource[Greeting] = CustomResource.statusMethodsEnabler[Greeting]

  val greetingSpec = CustomResourceDefinition.Spec(
    apiGroup="sample.foperator.gfxmonk.net",
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

  val greetingCrd = CustomResourceDefinition(
    metadata=ObjectMeta(name="greetings.sample.foperator.gfxmonk.net"),
    spec=greetingSpec)

  implicit val greetingRd: ResourceDefinition[Greeting] = ResourceDefinition(greetingCrd)

  /** Person */
  case class PersonSpec(firstName: String, surname: String)
  case class PersonStatus(value: Option[String] = None)

  type Person = CustomResource[PersonSpec,PersonStatus]
  val personSpec = CustomResourceDefinition.Spec(
    apiGroup="sample.foperator.gfxmonk.net",
    version="v1alpha1",
    names=Names(
      plural = "people",
      singular = "person",
      kind = "Person",
      listKind = Some("People"),
      shortNames = Nil
    ),
    scope=Scope.Namespaced,
  ).copy(subresources = Some(ResourceSpecification.Subresources().withStatusSubresource()))

  val personCrd = CustomResourceDefinition(
    metadata=ObjectMeta(name="people.sample.foperator.gfxmonk.net"),
    spec=personSpec)

  implicit val personRd: ResourceDefinition[Person] = ResourceDefinition(personCrd)

  implicit val personStatusFmt = Json.format[PersonStatus]
  implicit val personSpecFmt = Json.format[PersonSpec]
  implicit val personFmt = CustomResource.crFormat[PersonSpec,PersonStatus]
  implicit val personHasStatus: skuber.HasStatusSubresource[Person] = CustomResource.statusMethodsEnabler[Person]
}

object Implicits {
  implicit val system = ActorSystem()
  implicit val materializer = ActorMaterializer()
  implicit val dispatcher = system.dispatcher
  implicit val client = k8sInit
}

object SimpleMain extends TaskApp {
  implicit val _scheduler = scheduler
  import Implicits._
  import Models._

  def install()(implicit client: KubernetesClient) = {
    Operations.write[CustomResourceDefinition]((res, meta) => res.copy(metadata=meta))(greetingCrd).void
  }

  override def run(args: List[String]): Task[ExitCode] = {
    val operator = Operator[Greeting](
      reconciler = Reconciler.updater { greeting => Task.pure {
        val expected = GreetingStatus(s"hello, ${greeting.spec.name.getOrElse("UNKNOWN")}", people = Nil)

        // TODO this is a common pattern, pull out?
        if (greeting.status === Some(expected)) {
          None
        } else {
          Some(Update.Status(expected))
        }
      }}
    )

    install() >> ResourceTracker.all[Greeting].use { tracker =>
      val controller = new Controller[Greeting](operator, tracker)
      controller.run.map(_ => ExitCode.Success)
    }
  }
}

object AdvancedMain extends TaskApp {
  implicit val _scheduler = scheduler
  import Implicits._
  import Models._

  def install()(implicit client: KubernetesClient) = {
    SimpleMain.install() >>
    Operations.write[CustomResourceDefinition]((res, meta) => res.copy(metadata=meta))(personCrd).void
  }

  private def runWith(
    greetingTracker: ResourceTracker[Greeting],
    personTracker: ResourceTracker[Person]
  ) = {
    val operator = Operator[Greeting](
      reconciler = Reconciler.updater { greeting => Task.pure {
        greeting.spec.surname match {
          case None => None
          case Some(surname) => {
            val people = personTracker.all.values.filter { person =>
              person.spec.surname == surname
            }.toList.sortBy(_.spec.firstName)

            val expected = GreetingStatus(
              message = s"Hello to ${people.map(_.spec.firstName).mkString(", ")}",
              people = people.map(_.metadata.name)
            )
            if (greeting.status === Some(expected)) {
              None
            } else {
              Some(Update.Status(expected))
            }
          }
        }
      }}
    )

    // Given a new person state, generate the list of greetings which
    // need to be reconciled
    def relatedGreetings(person: Person): Iterable[Id[Greeting]] = {
      // should this greeting match this person?
      def matchesPerson(greeting: Greeting) = greeting.spec.surname === Some(person.spec.surname)

      // does this greeting's status currently reference this person?
      def referencesPerson(greeting: Greeting) = !greeting.status.map(_.people).getOrElse(Nil).contains_(person.metadata.name)

      greetingTracker.all.values.filter { greeting =>
        val shouldAdd = matchesPerson(greeting) && !referencesPerson(greeting)
        val shouldRemove = !matchesPerson(greeting) && referencesPerson(greeting)
        shouldAdd || shouldRemove
      }.map(Id.of)
    }

    val updateForPersonChanges = personTracker.relatedIds(relatedGreetings)
    val controller = new Controller[Greeting](
      operator,
      greetingTracker,
      updateTriggers = List(updateForPersonChanges))
    controller.run.map(_ => ExitCode.Success)
  }

  override def run(args: List[String]): Task[ExitCode] = {
    install() >> ResourceTracker.all[Greeting].use { greetingTracker =>
      ResourceTracker.all[Person].use { personTracker =>
        runWith(greetingTracker, personTracker)
      }
    }
  }
}
