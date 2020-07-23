package net.gfxmonk.foperator.sample

import cats.data.Validated
import minitest.laws.Checkers
import monix.eval.Task
import monix.execution.schedulers.{CanBlock, TestScheduler, TrampolineScheduler}
import monix.execution.{ExecutionModel, Scheduler}
import net.gfxmonk.foperator.internal.Logging
import net.gfxmonk.foperator.sample.Implicits._
import net.gfxmonk.foperator.sample.Models._
import net.gfxmonk.foperator.testkit.FoperatorDriver
import org.scalacheck.Shrink
import org.scalatest.FunSpec

import scala.util.Random
import scala.concurrent.duration._


// TODO PropSpec or something more direct?
class AdvancedTest extends FunSpec with Checkers with Logging {

  def assertValid(validator: StateValidator) = {
    validator.validate match {
      case Validated.Valid(_) => Task.unit
      case Validated.Invalid(errors) => {
        validator.dumpState >> Task.raiseError(
          new AssertionError("Inconsistencies found:\n" + errors.toList.mkString("\n"))
        )
      }
    }
  }

  it("Reaches a consistent state after every mutation") {
    implicit val disableShrink: Shrink[Int] = Shrink(_ => Stream.empty)
    check1 { (seed: Int) =>
      val testScheduler = TestScheduler(ExecutionModel.SynchronousExecution)
      val realScheduler = TrampolineScheduler(Scheduler.global, ExecutionModel.SynchronousExecution)
      val driver = new FoperatorDriver()(realScheduler)
      implicit val client = driver.client

      val greetings = driver.mirror[Greeting]()
      val people = driver.mirror[Person]()
      val rand = new Random(seed)
      val main = new AdvancedOperator(testScheduler, client).runWith(greetings, people).runToFuture(testScheduler)
      val mutator = new Mutator(client, greetings, people)

      def loop(limit: Int): Task[Boolean] = {
        if (limit == 0) {
          Task.pure(true)
        } else {
          (for {
            action <- mutator.nextAction(rand, _ => true)
            _ <- Task(logger.info(s"Running step #${limit} (seed: $seed) ${action}"))
            _ <- action.run
            _ <- Task(logger.info(s"Ticking..."))
            _ = testScheduler.tick(10.seconds)
            _ <- Task(logger.info(s"Checking consistency..."))
            validator <- mutator.stateValidator
            _ <- assertValid(validator)
          } yield ()).flatMap(_ => loop(limit-1))
        }
      }

      loop(20).flatMap { result =>
        if (main.value.isDefined) {
          Task.raiseError(new RuntimeException("Operator thread died prematurely"))
        } else {
          main.cancel()
          Task.pure(result)
        }
      }.runSyncUnsafe()(realScheduler, implicitly[CanBlock])
    }
  }
}
