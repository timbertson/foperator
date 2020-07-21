package net.gfxmonk.foperator.sample

import minitest.laws.Checkers
import monix.eval.Task
import monix.execution.schedulers.{CanBlock, TestScheduler}
import monix.execution.{ExecutionModel, Scheduler}
import net.gfxmonk.foperator.internal.Logging
import net.gfxmonk.foperator.sample.Models._
import net.gfxmonk.foperator.testkit.FoperatorDriver
import org.scalatest.FunSpec

import scala.util.Random

class MyLawsTest extends FunSpec with Checkers with Logging {
  it("Reaches a consistent state after every mutation") {
    check1 { (seed: Int) =>
      val testScheduler = TestScheduler(ExecutionModel.SynchronousExecution)
      val realScheduler = Scheduler.global
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
            _ <- Task(logger.info(s"Running #${limit} ${action}"))
            _ <- action.run
            _ <- Task(logger.info(s"Ticking..."))
            _ = testScheduler.tick()
            _ <- Task(logger.info(s"Checking consistency..."))
            result <- mutator.checkConsistency(verbose = false)
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
