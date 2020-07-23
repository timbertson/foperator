package net.gfxmonk.foperator.sample

import cats.data.{NonEmptyList, Validated, ValidatedNel}
import cats.implicits._
import monix.eval.Task
import net.gfxmonk.foperator.internal.Logging
import Models._
import Implicits._
import net.gfxmonk.foperator.Id

class StateValidator(people: Map[String, Person], greetings: Map[String, Greeting]) extends Logging {
  case class ValidationError(context: List[String], errors: List[String])

  def dumpState(implicit ppGreeting: PrettyPrint[Greeting], ppPerson: PrettyPrint[Person]) = Task {
    people.values.toList.sortBy(_.name).foreach { person =>
      logger.info(s"Person: ${ppPerson.pretty(person)}")
    }

    greetings.values.toList.sortBy(_.name).foreach { greeting =>
      logger.info(s"Greeting: ${ppGreeting.pretty(greeting)}")
      val names = greeting.status.map(_.people).getOrElse(Nil)
      NonEmptyList.fromList(names.sorted) match {
        case None => logger.info(" - (no people)")
        case Some(names) => names.toList.foreach { name =>
          val pp = implicitly[PrettyPrint[Option[Person]]]
          logger.info(s" - person: ${pp.pretty(people.get(name))}")
        }
      }
    }
    logger.info(s"State has ${greetings.size} greetings and ${people.size} people")
  }

  private def validatePerson(person: Person) = {
    val needsFinalizer = greetings.values.exists(_.status.exists(_.people.contains_(person.name)))
    val hasFinalizer = person.metadata.finalizers.getOrElse(Nil).contains_(AdvancedOperator.finalizerName)
    if (needsFinalizer && !hasFinalizer) {
      Validated.invalidNel(s"Person needs finalizer but none is set: ${person}")
    } else if (!needsFinalizer && hasFinalizer) {
      Validated.invalidNel(s"Person has unnecessary finalizer: ${person}")
    } else {
      Validated.validNel(())
    }
  }

  private def validateGreeting(greeting: Greeting) = {
    def require(test: Boolean, msg: => String): ValidatedNel[String, Unit] = Validated.condNel(test, (), msg)

    greeting.spec.surname match {
      case None => {
        val expected = SimpleOperator.expectedStatus(greeting)
        require(
          greeting.status.exists(_ === expected),
          s"Greeting ${Id.of(greeting)} inconsistent! Expected Some(${expected}), but was ${greeting.status}"
        )
      }
      case Some(surname) => {
        val expectedPeople = people.values.filter(_.spec.surname === surname)
        val actualNames = greeting.status.map(_.people).getOrElse(Nil).toSet
        val expectedNames = expectedPeople.map(_.name).toSet
        require(expectedNames === actualNames,
          s"Greeting ${Id.of(greeting)} inconsistent! Expected ${expectedNames.toList.sorted}, got ${actualNames.toList.sorted}")
      }
    }
  }

  def validate = {
    (greetings.values.toList.map { greeting =>
      validateGreeting(greeting)
    } ++ people.values.toList.map { person =>
      validatePerson(person)
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
