package net.gfxmonk.foperator.sample

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import cats.Eq
import cats.effect.ExitCode
import cats.implicits._
import monix.eval.{Task, TaskApp}
import net.gfxmonk.foperator._
import net.gfxmonk.foperator.Update.Implicits._ // TODO expose toplevel
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
      reconciler = Reconciler.customResourceUpdater { greeting => Task.pure {
        val expected = GreetingStatus(s"hello, ${greeting.spec.name.getOrElse("UNKNOWN")}", people = Nil)

        // TODO this is a common pattern, pull out?
        if (greeting.status === Some(expected)) {
          greeting.unchanged
        } else {
          greeting.statusUpdate(expected)
        }
      }}
    )

    install() >> ResourceMirror.all[Greeting].use { mirror =>
      val controller = new Controller[Greeting](operator, ControllerInput(mirror))
      controller.run.map(_ => ExitCode.Success)
    }
  }
}

object AdvancedMain extends TaskApp {
  implicit val _scheduler = scheduler
  import Implicits._
  import Models._

  val finalizerName = s"AdvancedMain.${greetingSpec.apiGroup}"

  def install()(implicit client: KubernetesClient) = {
    SimpleMain.install() >>
    Operations.write[CustomResourceDefinition]((res, meta) => res.copy(metadata=meta))(personCrd).void
  }

  // should this greeting match this person?
  private def matches(person: Person, greeting: Greeting) = greeting.spec.surname === Some(person.spec.surname)

  // does this greeting's status currently reference this person?
  private def references(person: Person, greeting: Greeting) = !greeting.status.map(_.people).getOrElse(Nil).contains_(person.metadata.name)

  // every update can set a new status and remove finalizers
  // (finalizers are automatically added for all referenced people)
  case class GreetingUpdate(
    resource: Update[Greeting, GreetingStatus],
    removeFinalizers: List[Person]
  )

  object GreetingUpdate {
    def run(update: GreetingUpdate): Task[ReconcileResult] = {
      // first, clear all finalizers
      val finalizersRemoved = update.removeFinalizers.traverse { (person: Person) =>
        val meta = ResourceState.withoutFinalizer(finalizerName)(person.metadata)
        Operations.applyUpdate(person.metadataUpdate(meta))
      }.void

      val updateGreeting = Operations.applyUpdate(update.resource).map(_ => ReconcileResult.Ok)

      finalizersRemoved >> updateGreeting
    }
  }

  private def greetingController(
                       greetingsMirror: ResourceMirror[Greeting],
                       peopleMirror: ResourceMirror[Person]
  ): Controller[Greeting] = {
    val operator = Operator[Greeting](
      reconciler = Reconciler.updater(GreetingUpdate.run) { greeting => Task.pure {
        // when a person is soft-deleted, we schedule a reconcile against
        // all active greetings. After they reconcile, they will have dropped the
        // person from their status.

        // So whenever we see a deleted resource with no greetings referencing it,
        // we can clear our finalizer from it

        // TODO this is racey, everyone is going to be trying to remove people.
        // Should we have two reconcilers, a fan-up and fan-down?
        val allowDelete = peopleMirror.all().mapFilter(ResourceState.awaitingFinalizer(finalizerName)).values.flatMap { person =>
          val stillGreeting = greetingsMirror.active().values.filter { greeting =>
            greeting.status.map(_.people).getOrElse(Nil).contains(person.metadata.name)
          }
          if (stillGreeting.isEmpty) {
            List(person)
          } else {
            Nil
          }
        }.toList

        val update = greeting.spec.surname match {
          case None => greeting.unchanged
          case Some(surname) => {
            val people = peopleMirror.active().values.filter { person =>
              person.spec.surname == surname
            }.toList.sortBy(_.spec.firstName)

            val expected = GreetingStatus(
              message = s"Hello to ${people.map(_.spec.firstName).mkString(", ")}",
              people = people.map(_.metadata.name)
            )
            if (greeting.status === Some(expected)) {
              greeting.unchanged
            } else {
              greeting.statusUpdate(expected)
            }
          }
        }

        GreetingUpdate(update, allowDelete)
      }}
    )

    val controllerInput = ControllerInput(greetingsMirror).withResourceTrigger(peopleMirror) { person =>
      // Given a Person has changed, figure out what greetings might need an update
      val predicate = person match {
        case ResourceState.SoftDeleted(person) => greeting: Greeting => references(person, greeting)
        case ResourceState.Active(person) => greeting: Greeting =>
            val shouldAdd = matches(person, greeting) && !references(person, greeting)
            val shouldRemove = !matches(person, greeting) && references(person, greeting)
            shouldAdd || shouldRemove
      }
      greetingsMirror.active().values.filter(predicate).map(Id.of)
    }

    new Controller[Greeting](operator, controllerInput)
  }

  private def personController(
                                  greetingsMirror: ResourceMirror[Greeting],
                                  peopleMirror: ResourceMirror[Person]
                                ): Controller[Person] = {
    def finalize(person: Person) = {
      // Before deleting a person, we need to remove references from any greeting that
      // currently refers to this person
      val greetings = greetingsMirror.active().values.filter(g => references(person, g)).toList
      Operations.applyUpdates(greetings.map { greeting =>
        greeting.status.map { status =>
          greeting.statusUpdate(status.copy(people = status.people.filterNot(_ === person.name)))
        }.getOrElse(greeting.unchanged)
      })
    }

    val operator = Operator[Person](finalizer = Finalizer(finalizerName)(finalize))

    new Controller[Person](operator, ControllerInput(peopleMirror))
  }

  override def run(args: List[String]): Task[ExitCode] = {
    install() >> ResourceMirror.all[Greeting].use { greetings =>
      ResourceMirror.all[Person].use { people =>
        Task.parZip2(
          greetingController(greetings, people).run,
          personController(greetings, people).run
        ).map(_ => ExitCode.Success)
      }
    }
  }
}
