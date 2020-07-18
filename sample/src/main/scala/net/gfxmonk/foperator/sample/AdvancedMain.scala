package net.gfxmonk.foperator.sample

import cats.effect.ExitCode
import cats.implicits._
import monix.eval.{Task, TaskApp}
import net.gfxmonk.foperator._
import net.gfxmonk.foperator.implicits._
import skuber.api.client.KubernetesClient
import skuber.apiextensions.CustomResourceDefinition

object AdvancedMain extends TaskApp {
  implicit val _scheduler = scheduler

  import Implicits._
  import Models._

  val finalizerName = s"AdvancedMain.${greetingSpec.apiGroup}"

  def install()(implicit client: KubernetesClient) = {
    SimpleMain.install() >>
      Operations.write[CustomResourceDefinition]((res, meta) => res.copy(metadata = meta))(personCrd).void
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

    val updateGreeting = Operations.apply(update).map(_ => ReconcileResult.Ok)

    def addFinalizers(people: List[Person]) = people.traverse { person =>
      val meta = ResourceState.withoutFinalizer(finalizerName)(person.metadata)
      Operations.apply(person.metadataUpdate(meta))
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
      reconciler = Reconciler.updater(greetingUpdater(peopleMirror)) { greeting =>
        Task.pure {
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
        }
      }
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
      Operations.applyMany(greetings.map { greeting =>
        greeting.status.map { status =>
          greeting.statusUpdate(status.copy(people = status.people.filterNot(_ === person.name)))
        }.getOrElse(greeting.unchanged)
      }
      )
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