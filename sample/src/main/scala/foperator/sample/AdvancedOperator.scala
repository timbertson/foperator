package foperator.sample

import cats.effect.{IO, IOApp}
import cats.implicits._
import foperator._
import foperator.backend.Skuber
import foperator.backend.skuber.implicits._
import foperator.internal.Logging
import foperator.sample.Models.Skuber._
import foperator.sample.PrettyPrint.Implicits._
import foperator.types.Engine
import skuber.apiextensions.CustomResourceDefinition

import scala.util.Try

object AdvancedOperator extends IOApp.Simple {
  val finalizerName = s"AdvancedMain.${Models.apiGroup}"

  override def run: IO[Unit] = {
    Skuber[IO].default(runtime.compute).use { skuber =>
      new AdvancedOperator(skuber).run
    }
  }
}

class AdvancedOperator[C](client: Client[IO, C])
  (implicit
    // note: this individual engine listing is only needed when you're
    // writing a client-agnostic operator (which we do use for tests)
    engineP: Engine[IO, C, Person],
    engineG: Engine[IO, C, Greeting],
    engineD: Engine[IO, C, CustomResourceDefinition],
  )
  extends Logging {
  import AdvancedOperator._
  import Models._

  val reconcileOpts = ReconcileOptions(refreshInterval = None, concurrency = 5)

  def run: IO[Unit] = {
    install >>
    client[Greeting].mirror { greetings =>
      client[Person].mirror { people =>
        runWith(greetings, people)
      }
    }
  }

  def runWith(greetings: ResourceMirror[IO, Greeting], people: ResourceMirror[IO, Person]): IO[Unit] = {
    (
      greetingController(greetings, people),
      personController(greetings, people)
    ).parTupled.void
  }

  def install = {
    client[CustomResourceDefinition].forceWrite(greetingCrd) >>
    client[CustomResourceDefinition].forceWrite(personCrd)
  }

  // should this greeting match this person?
  private def matches(person: Person, greeting: Greeting) = greeting.spec.surname === Some(person.spec.surname)

  // does this greeting's status currently reference this person?
  private def references(person: Person, greeting: Greeting) = greeting.status.map(_.people).getOrElse(Nil).contains_(person.metadata.name)

  private def findPerson(all: Map[Id[Person], Person], id: Id[Person]): Try[Person] = {
    all.get(id).toRight(new RuntimeException(s"Person not active: $id")).toTry
  }

  def peopleIds(id: Id[Greeting], status: GreetingStatus): List[Id[Person]] = status.people.map { name =>
    Id[Person](namespace = id.namespace, name = name)
  }

  // Given a greeting update, apply it to the cluster.
  // This differs from the default Operations.applyUpdate because
  // it also installs finalizers on referenced people.
  private def greetingUpdater(peopleMirror: ResourceMirror[IO, Person])
    (fn: Greeting => IO[GreetingStatus])
    (implicit pp: PrettyPrint[GreetingStatus]): Greeting => IO[GreetingStatus] = { greeting =>
  fn(greeting).flatMap { status =>
    logger.info(s"Reconciled greeting ${Id.of(greeting)} to status: ${pp.pretty(status)}")
    val ids = peopleIds(Id.of(greeting), status)
    val findPeople: IO[List[Person]] = {
      peopleMirror.active.flatMap { all =>
        ids.traverse(id => IO.fromTry(findPerson(all, id)))
      }
    }

    val checkFinalizers = (people: List[Person]) => {
      val hasFinalizer = (person: Person) =>
        person.metadata.finalizers.exists(list => list.contains_(finalizerName))

      people.filterNot(hasFinalizer) match {
        case Nil => IO.unit
        case nonEmpty => IO.raiseError(new RuntimeException(s"Some people don't have a finalizer installed yet: ${nonEmpty}"))
      }
    }

    for {
      // Only proceed if referenced people are still found
      people <- findPeople
      _ <- checkFinalizers(people)
    } yield status
  }}

  private def greetingController(
    greetingsMirror: ResourceMirror[IO, Greeting],
    peopleMirror: ResourceMirror[IO, Person]
  ): IO[Unit] = {
    val input = greetingsMirror.withResourceTrigger(peopleMirror) {
      case ResourceState.SoftDeleted(person) => {
        greetingsMirror.activeValues.map(greetings => greetings.filter(references(person, _)).map(Id.of[Greeting]))
      }

      case ResourceState.Active(person) => {
        // Given a Person's latest state, figure out what greetings need an update
        val needsUpdate = { greeting: Greeting =>
          val shouldAdd = matches(person, greeting) && !references(person, greeting)
          val shouldRemove = !matches(person, greeting) && references(person, greeting)
          if (shouldAdd || shouldRemove) {
            val desc = if (shouldAdd) { "add" } else { "remove" }
            logger.debug(s"Greeting needs to ${desc} this person: ${prettyPrintCustomResource[GreetingSpec,GreetingStatus].pretty(greeting)}")
            true
          } else {
            false
          }
        }
        logger.debug(s"Checking for necessary Greeting updates after change to ${prettyPrintCustomResource[PersonSpec,Unit].pretty(person)}")
        greetingsMirror.activeValues.map(_.filter(needsUpdate).map(Id.of[Greeting]))
      }
    }

    val reconciler = Reconciler.builder[IO, C, Greeting].status(greetingUpdater(peopleMirror) { greeting =>
      greeting.spec.surname match {

        // if there's no surname, just do what SimpleOperator does
        case None => IO.pure(SimpleOperator.expectedStatus(greeting))

        case Some(surname) => {
          peopleMirror.active.map { activePeople =>
            val people = activePeople.values.filter { person =>
              person.spec.surname == surname
            }.toList.sortBy(_.spec.firstName)

            val familyMembers = people match {
              case Nil => s"[empty]"
              case people => people.map(_.spec.firstName).mkString(", ")
            }
            GreetingStatus(
              message = s"Hello to the ${surname} family: ${familyMembers}",
              people = people.map(_.metadata.name)
            )
          }
        }
      }
    })

    client[Greeting].runReconcilerWithInput(input, reconciler, reconcileOpts)
  }

  private def personController(
    greetingsMirror: ResourceMirror[IO, Greeting],
    peopleMirror: ResourceMirror[IO, Person]
  ): IO[Unit] = {
    def finalize(person: Person) = {
      // The Greetings operator will remove a person from its status soon as the person is soft-deleted,
      // but that might not be immediate. This operator just fails (and backs off) while
      // a dangling reference remains.
      greetingsMirror.active.flatMap { active =>
        if (active.values.exists(references(person, _))) {
          IO.raiseError(new RuntimeException("Waiting for greeting references to be removed"))
        } else IO.unit
      }
    }

    val reconciler = Reconciler.builder[IO, C, Person].empty.withFinalizer(finalizerName, finalize)
    client[Person].runReconcilerWithInput(peopleMirror, reconciler, reconcileOpts)
  }
}
