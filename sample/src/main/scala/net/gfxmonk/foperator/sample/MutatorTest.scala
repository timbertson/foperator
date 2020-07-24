package net.gfxmonk.foperator.sample

import cats.data.Validated
import monix.eval.Task
import monix.execution.schedulers.{CanBlock, TestScheduler, TrampolineScheduler}
import monix.execution.{ExecutionModel, Scheduler}
import net.gfxmonk.foperator.internal.Logging
import net.gfxmonk.foperator.sample.Implicits._
import net.gfxmonk.foperator.sample.Models._
import net.gfxmonk.foperator.testkit.FoperatorDriver

import scala.util.Random
import scala.concurrent.duration._

object MutatorTest extends Logging {
  def main(args: Array[String]): Unit = {
    val seed = args match {
      case Array(arg) => Integer.parseInt(arg)
      case Array() => Random.nextInt
      case other => throw new RuntimeException(s"Too many arguments: $other")
    }
    testWithSeed(seed)
  }

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

  def testWithSeed(seed: Int) = {
    val testScheduler = TestScheduler(ExecutionModel.SynchronousExecution)
    val realScheduler = TrampolineScheduler(Scheduler.global, ExecutionModel.SynchronousExecution)
    val driver = new FoperatorDriver(testScheduler)
    implicit val client = driver.client

    // Scheduler juggling:
    //  - FoperatorClient operations are fully synchronous (despite retuning a Future)
    //  - any update notifications are scheduled on the user's scheduler, but not waited on
    //
    // So, once we call an action it completes immediately, scheduling any
    // update notifications. Any further work (including invoking the client)
    // _only_ requires the TestScheduler to tick.
    // The real scheduler is only used to drive the test

    val greetings = driver.mirror[Greeting]()
    val people = driver.mirror[Person]()
    val rand = new Random(seed)
    val main = new AdvancedOperator(testScheduler, client).runWith(greetings, people).runToFuture(testScheduler)
    val mutator = new Mutator(client, greetings, people)

    def loop(limit: Int): Task[Unit] = {
      if (limit == 0) {
        Task.unit
      } else {
        (for {
          action <- mutator.nextAction(rand, _ => true)
          _ <- Task(logger.info(s"Running step #${limit} (seed: $seed) ${action}"))
          _ <- action.run
          _ <- Task(logger.info(s"Ticking..."))
          _ = testScheduler.tick(10.seconds)
          _ <- Task(logger.info(s"Checking consistency..."))
          validator <- mutator.stateValidator
//          _ <- validator.dumpState
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
