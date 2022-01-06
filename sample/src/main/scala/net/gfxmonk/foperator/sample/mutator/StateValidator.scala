package net.gfxmonk.foperator.sample.mutator

import cats.data.{NonEmptyList, Validated, ValidatedNel}
import cats.implicits._
import monix.eval.Task
import net.gfxmonk.foperator.internal.Logging
import net.gfxmonk.foperator.sample.Models._
import net.gfxmonk.foperator.sample.PrettyPrint.Implicits._
import net.gfxmonk.foperator.sample.{AdvancedOperator, PrettyPrint, SimpleOperator}
import net.gfxmonk.foperator.skuberengine.implicits._
import net.gfxmonk.foperator.{Id, ResourceState}

class StateValidator(people: Map[String, ResourceState[Person]], greetings: Map[String, ResourceState[Greeting]]) extends Logging {
  case class ValidationError(context: List[String], errors: List[String])

  def dumpState(implicit ppGreeting: PrettyPrint[ResourceState[Greeting]], ppPerson: PrettyPrint[ResourceState[Person]]) = Task {
    people.values.toList.sortBy(_.raw.name).foreach { person =>
      logger.info(ppPerson.pretty(person))
    }

    greetings.values.toList.sortBy(_.raw.name).foreach { greeting =>
      logger.info(ppGreeting.pretty(greeting))
      ResourceState.active(greeting).foreach { greeting =>
        val names = greeting.status.map(_.people).getOrElse(Nil)
        NonEmptyList.fromList(names.sorted) match {
          case None => logger.info(" - (no people)")
          case Some(names) => names.toList.foreach { name =>
            val pp = implicitly[PrettyPrint[Option[ResourceState[Person]]]]
            logger.info(s" - person: ${pp.pretty(people.get(name))}")
          }
        }
      }
    }
    logger.info(s"State has ${greetings.size} greetings and ${people.size} people")
  }

  private def validatePerson(person: Person) = {
    val activeGreetings = greetings.values.toList.mapFilter(ResourceState.active)
    val needsFinalizer = activeGreetings.exists(_.status.exists(_.people.contains_(person.name)))
    val hasFinalizer = person.metadata.finalizers.getOrElse(Nil).contains_(AdvancedOperator.finalizerName)
    // Having an unnecessary finalizer is OK, it may have been due to a (hard-deleted) greeting.
    // Cleaning up people finalizers on greeting deletion would mean adding finalizers to greetings,
    // which doesn't seem worth it.
    if (needsFinalizer && !hasFinalizer) {
      Validated.invalidNel(s"Person needs finalizer but none is set: ${person}")
    } else {
      Validated.validNel(())
    }
  }

  private def validateGreeting(greeting: Greeting) = {
    def require(test: Boolean, msg: => String): ValidatedNel[String, Unit] = Validated.condNel(test, (), msg)
    val activePeople = people.values.toList.mapFilter(ResourceState.active)

    greeting.spec.surname match {
      case None => {
        val expected = SimpleOperator.expectedStatus(greeting)
        require(
          greeting.status.exists(_ === expected),
          s"Greeting ${Id.of(greeting)} inconsistent! Expected Some(${expected}), but was ${greeting.status}"
        )
      }
      case Some(surname) => {
        val expectedPeople = activePeople.filter(_.spec.surname === surname)
        val actualNames = greeting.status.map(_.people).getOrElse(Nil).toSet
        val expectedNames = expectedPeople.map(_.name).toSet
        require(expectedNames === actualNames,
          s"Greeting ${Id.of(greeting)} inconsistent! Expected ${expectedNames.toList.sorted}, got ${actualNames.toList.sorted}")
      }
    }
  }

  private def validateResourceState[T,R](obj: ResourceState[T], ifActive: T => ValidatedNel[String,R])(implicit pp: PrettyPrint[T]): ValidatedNel[String,R] = {
    obj match {
      case ResourceState.Active(value) => ifActive(value)
      case ResourceState.SoftDeleted(value) => Validated.invalidNel(s"Resource is awaiting deletion: ${pp.pretty(value)}")
    }
  }

  def validate: ValidatedNel[String, Unit] = {
    (greetings.values.toList.map { greeting =>
      validateResourceState(greeting, validateGreeting)
    } ++ people.values.toList.map { person =>
      validateResourceState(person, validatePerson)
    }).sequence_
  }

  def report(verbose: Boolean) = {
    validate match {
      case Validated.Valid(_) => {
        if (verbose) dumpState else Task.unit
      }
      case Validated.Invalid(errors) => {
        dumpState >> Task {
          errors.toList.foreach(logger.error)
        }
      }
    }
  }
}
