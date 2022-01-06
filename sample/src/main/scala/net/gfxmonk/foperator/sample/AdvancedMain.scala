package net.gfxmonk.foperator.sample

import cats.effect.ExitCode
import cats.implicits._
import monix.eval.{Task, TaskApp}
import net.gfxmonk.foperator._
import net.gfxmonk.foperator.internal.Logging
import net.gfxmonk.foperator.sample.PrettyPrint.Implicits._
import net.gfxmonk.foperator.sample.Models._
import net.gfxmonk.foperator.skuberengine.Skuber
import net.gfxmonk.foperator.skuberengine.implicits._
import net.gfxmonk.foperator.types.Engine
import skuber.apiextensions.CustomResourceDefinition

import scala.util.Try

object AdvancedMain {
  def main(args: Array[String]): Unit = {
    new AdvancedOperator(Skuber()).main(args)
  }
}

object AdvancedOperator {
  val finalizerName = s"AdvancedMain.${Models.greetingSpec.apiGroup}"
}

class AdvancedOperator[C](client: C)
  (implicit
    // note: this individual engine listing is only needed when you're
    // writing a client-agnostic operator (which we do use for tests)
    engineP: Engine[Task, C, Person],
    engineG: Engine[Task, C, Greeting],
    engineD: Engine[Task, C, CustomResourceDefinition],
  )
  extends TaskApp with Logging {
  import AdvancedOperator._
  import Models._

  val personOps: Operations[Task, C, Person] = Operations(client).apply[Person]
  val greetingOps: Operations[Task, C, Greeting] = Operations(client).apply[Greeting]
  val reconcileOpts = ReconcileOptions(refreshInterval = None, concurrency = 5)

  override def run(args: List[String]): Task[ExitCode] = {
    install >>
    greetingOps.mirror { greetings =>
      personOps.mirror { people =>
        runWith(greetings, people).map(_ => ExitCode.Success)
      }
    }
  }

  def runWith(greetings: ResourceMirror[Task, Greeting], people: ResourceMirror[Task, Person]): Task[Unit] = {
    Task.parZip2(
      greetingController(greetings, people),
      personController(greetings, people)
    ).void
  }

  def install = {
    val ops = Operations(client).apply[CustomResourceDefinition]
    ops.forceWrite(greetingCrd).void >>
    ops.forceWrite(personCrd).void
  }

  // should this greeting match this person?
  private def matches(person: Person, greeting: Greeting) = greeting.spec.surname === Some(person.spec.surname)

  // does this greeting's status currently reference this person?
  private def references(person: Person, greeting: Greeting) = greeting.status.map(_.people).getOrElse(Nil).contains_(person.metadata.name)

  private def findPerson(all: Map[Id[Person], Person], id: Id[Person]): Try[Person] = {
    all.get(id).toRight(new RuntimeException(s"Person not active: $id")).toTry
  }

  // Given a greeting update, apply it to the cluster.
  // This differs from the default Operations.applyUpdate because
  // it also installs finalizers on referenced people.
  private def greetingUpdater(peopleMirror: ResourceMirror[Task, Person])
    (fn: Greeting => Task[GreetingStatus])
    (implicit pp: PrettyPrint[GreetingStatus]): Greeting => Task[GreetingStatus] = { greeting =>
  fn(greeting).flatMap { status =>
    logger.info(s"Reconciled greeting ${Id.of(greeting)} to status: ${pp.pretty(status)}")
    val ids = GreetingStatus.peopleIds(Id.of(greeting), status)
    val findPeople: Task[List[Person]] = {
      peopleMirror.active.flatMap { all =>
        ids.traverse(id => Task.fromTry(findPerson(all, id)))
      }
    }

    val checkFinalizers = (people: List[Person]) => {
      val hasFinalizer = (person: Person) =>
        person.metadata.finalizers.exists(list => list.contains_(finalizerName))

      people.filterNot(hasFinalizer) match {
        case Nil => Task.unit
        case nonEmpty => Task.raiseError(new RuntimeException(s"Some people don't have a finalizer installed yet: ${nonEmpty}"))
      }
    }

    for {
      // Only proceed if referenced people are still found
      people <- findPeople
      _ <- checkFinalizers(people)
    } yield status
  }}

  private def greetingController(
    greetingsMirror: ResourceMirror[Task, Greeting],
    peopleMirror: ResourceMirror[Task, Person]
  ): Task[Unit] = {
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

    val reconciler = Reconciler.builder[Task, C, Greeting].status(greetingUpdater(peopleMirror) { greeting =>
      greeting.spec.surname match {

        // if there's no surname, just do what SimpleOperator does
        case None => Task.pure(SimpleOperator.expectedStatus(greeting))

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

    greetingOps.runReconcilerWithInput(input, reconciler, reconcileOpts)
  }

  private def personController(
    greetingsMirror: ResourceMirror[Task, Greeting],
    peopleMirror: ResourceMirror[Task, Person]
  ): Task[Unit] = {
    def finalize(person: Person) = {
      // The Greetings operator will remove a person from its status soon as the person is soft-deleted,
      // but that might not be immediate. This operator just fails (and backs off) while
      // a dangling reference remains.
      greetingsMirror.active.flatMap { active =>
        if (active.values.exists(references(person, _))) {
          Task.raiseError(new RuntimeException("Waiting for greeting references to be removed"))
        } else Task.unit
      }
    }

    val reconciler = Reconciler.builder[Task, C, Person].empty.withFinalizer(finalizerName, finalize)
    personOps.runReconcilerWithInput(peopleMirror, reconciler, reconcileOpts)
  }
}
