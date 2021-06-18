package net.gfxmonk.foperator.sample

import cats.effect.ExitCode
import cats.implicits._
import monix.eval.{Task, TaskApp}
import net.gfxmonk.foperator._
import net.gfxmonk.foperator.implicits._
import net.gfxmonk.foperator.internal.Logging
import net.gfxmonk.foperator.sample.PrettyPrint.Implicits._
import skuber.apiextensions.CustomResourceDefinition

import scala.util.Try

object AdvancedMain {
  def main(args: Array[String]): Unit = {
    new AdvancedOperator(FoperatorContext.global).main(args)
  }
}

object AdvancedOperator {
  val finalizerName = s"AdvancedMain.${Models.greetingSpec.apiGroup}"
}

class AdvancedOperator(ctx: FoperatorContext) extends TaskApp with Logging {
  import AdvancedOperator._
  import Models._
  implicit val _ctxImplicit: FoperatorContext = ctx

  override def run(args: List[String]): Task[ExitCode] = {
    install() >> ResourceMirror.all[Greeting].use { greetings =>
      ResourceMirror.all[Person].use { people =>
        runWith(greetings, people).map(_ => ExitCode.Success)
      }
    }
  }

  def runWith(greetings: ResourceMirror[Greeting], people: ResourceMirror[Person]): Task[Unit] = {
    Task.parZip2(
      greetingController(greetings, people).run,
      personController(greetings, people).run
    ).void
  }

  def install() = {
    (new SimpleOperator(ctx)).install() >>
      Operations.forceWrite[CustomResourceDefinition](personCrd).void
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
  private def greetingUpdater(peopleMirror: ResourceMirror[Person])
    (update: Update.Status[Greeting, GreetingStatus])
    (implicit pp: PrettyPrint[CustomResourceUpdate[GreetingSpec, GreetingStatus]]): Task[ReconcileResult] = {
    val updateCast: Update[Greeting, GreetingSpec, GreetingStatus] = update
    if (Update.change(updateCast).isRight) { // don't bother logging a no-op update
      logger.info(s"Reconciled greeting, applying update: ${pp.pretty(update)}")
    }
    val ids = GreetingStatus.peopleIds(update)
    val findPeople: Task[List[Person]] = {
      peopleMirror.active.flatMap { all =>
        ids.traverse(id => Task.fromTry(findPerson(all, id)))
      }
    }

    val updateGreeting = Operations.apply(updateCast).map(_ => ReconcileResult.Ok)

    def addFinalizers(people: List[Person]) = Task.parSequenceUnordered(people.map { person =>
      val meta = ResourceState.withFinalizer(finalizerName)(person.metadata)
      Operations.apply[Person, PersonSpec, Unit](person.metadataUpdate(meta))
    }).void

    for {
      // Only proceed if referenced people are still found
      people <- findPeople
      // update the greeting first
      result <- updateGreeting
      // Add finalizers last. If any of these fail due to concurrent modifications (e.g. deletion),
      // the reconcile will fail and this greeting will be reconciled again shortly.
      _ <- addFinalizers(people)
    } yield result
  }

  private def greetingController(
    greetingsMirror: ResourceMirror[Greeting],
    peopleMirror: ResourceMirror[Person]
  ): Controller[Greeting] = {
    val operator = Operator[Greeting](
      refreshInterval = None,
      reconciler = Reconciler.updaterWith(greetingUpdater(peopleMirror)) { greeting =>
        val newStatus = greeting.spec.surname match {

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
        newStatus.map(greeting.statusUpdate)
      }
    )

    val controllerInput = ControllerInput(greetingsMirror).withResourceTrigger(peopleMirror) {
      case ResourceState.SoftDeleted(person) => {
        greetingsMirror.activeValues.filter(references(person, _)).map(Id.of)
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
        greetingsMirror.activeValues.filter(needsUpdate).map(Id.of)
      }
    }

    new Controller[Greeting](operator, controllerInput)
  }

  private def personController(
    greetingsMirror: ResourceMirror[Greeting],
    peopleMirror: ResourceMirror[Person]
  ): Controller[Person] = {
    def finalize(person: Person) = {
      // The Greetings operator will stop referencing a person as soon as it's been deleted,
      // but that might not be immediate. This operator just fails (and backs off) while
      // a dangling reference remains.
      greetingsMirror.active.flatMap { active =>
        if (active.values.exists(references(person, _))) {
          Task.raiseError(new RuntimeException("Waiting for greeting references to be removed"))
        } else Task.unit
      }
    }

    val operator = Operator[Person](finalizer = Some(Finalizer(finalizerName)(finalize)), refreshInterval = None)

    new Controller[Person](operator, ControllerInput(peopleMirror))
  }
}
