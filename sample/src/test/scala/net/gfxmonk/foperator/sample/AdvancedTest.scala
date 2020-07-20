package net.gfxmonk.foperator.sample

import cats.effect.ExitCode
import monix.eval.{Task, TaskApp}
import monix.execution.Scheduler
import net.gfxmonk.foperator.testkit.{FoperatorClient, FoperatorDriver}

object AdvancedTest extends TaskApp {
  import Models._

  override def run(args: List[String]): Task[ExitCode] = {
    implicit val s: Scheduler = scheduler
    val driver = new FoperatorDriver()
    implicit val client = driver.client

    val greetings = driver.mirror[Greeting]()
    val people = driver.mirror[Person]()
    Task.parZip2(
      (new AdvancedOperator).runWith(greetings, people),
      (new MutatorMain).runMutator(greetings, people)
    ).map(_ => ExitCode.Success)
  }
}
