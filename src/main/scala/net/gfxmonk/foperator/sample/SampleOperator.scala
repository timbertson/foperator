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
  object GreetingStatus {
    def peopleIds(update: Update.Status[Greeting, GreetingStatus]): List[Id[Person]] = update.status.people.map { name =>
      Id.unsafeCreate(classOf[CustomResource[PersonSpec,PersonStatus]], namespace = update.initial.metadata.namespace, name = name)
    }
  }

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

  def expectedStatus(greeting: Greeting): GreetingStatus =
    GreetingStatus(s"hello, ${greeting.spec.name.getOrElse("UNKNOWN")}", people = Nil)

  override def run(args: List[String]): Task[ExitCode] = {
    val operator = Operator[Greeting](
      reconciler = Reconciler.customResourceUpdater { greeting =>
        // Always return the expected status, Reconciler.customResourceUpdater
        // will make this a no-op without any API calls if it is unchanged.
        Task.pure(greeting.statusUpdate(expectedStatus(greeting)))
      }
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

  // Given a greeting update, apply it to the cluster.
  // This differs from the default Operations.applyUpdate because
  // it also installs finalizers on referenced people.
  private def greetingUpdater(peopleMirror: ResourceMirror[Person])(update: Update.Status[Greeting, GreetingStatus]): Task[ReconcileResult] = {
    val ids = GreetingStatus.peopleIds(update)
    val findPeople: Task[List[Person]] = ids.traverse(id => peopleMirror.getActive(id).map(Task.pure).getOrElse(Task.raiseError(new RuntimeException("noooo"))))

    val updateGreeting = Operations.applyUpdate(update).map(_ => ReconcileResult.Ok)

    def addFinalizers(people: List[Person]) = people.traverse { person =>
      val meta = ResourceState.withoutFinalizer(finalizerName)(person.metadata)
      Operations.applyUpdate(person.metadataUpdate(meta))
    }.void

    for {
      // Only proceed if referenced people are still found
      people <- findPeople
      // update the greeting first
      result <- updateGreeting
      // Add finalizers last. If any of these fail due to concurrent modifications,
      // the reconcile will fail and this greeting will be reconciled again shortly.
      _ <- addFinalizers(people)
    } yield result
  }

  private def greetingController(
                       greetingsMirror: ResourceMirror[Greeting],
                       peopleMirror: ResourceMirror[Person]
  ): Controller[Greeting] = {
    val operator = Operator[Greeting](
      reconciler = Reconciler.updater(greetingUpdater(peopleMirror)) { greeting => Task.pure {
        val newStatus = greeting.spec.surname match {
          case None => greeting.status.getOrElse(SimpleMain.expectedStatus(greeting))
          case Some(surname) => {
            val people = peopleMirror.active().values.filter { person =>
              person.spec.surname == surname
            }.toList.sortBy(_.spec.firstName)

            GreetingStatus(
              message = s"Hello to ${people.map(_.spec.firstName).mkString(", ")}",
              people = people.map(_.metadata.name)
            )
          }
        }
        greeting.statusUpdate(newStatus)
      }}
    )

    val controllerInput = ControllerInput(greetingsMirror).withActiveResourceTrigger(peopleMirror) { person =>
      // Given a Person's latest state, figure out what greetings need an update
      val needsUpdate = { greeting: Greeting =>
        val shouldAdd = matches(person, greeting) && !references(person, greeting)
        val shouldRemove = !matches(person, greeting) && references(person, greeting)
        shouldAdd || shouldRemove
      }
      greetingsMirror.active().values.filter(needsUpdate).map(Id.of)
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
