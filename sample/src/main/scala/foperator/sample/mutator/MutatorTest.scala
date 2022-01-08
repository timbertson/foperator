package foperator.sample.mutator

import cats.data.Validated
import cats.effect.ExitCode
import cats.effect.concurrent.Deferred
import cats.implicits._
import foperator.backend.Skuber
import foperator.backend.skuber.implicits._
import foperator.internal.Logging
import foperator.sample.Models.Skuber._
import foperator.sample.PrettyPrint.Implicits._
import foperator.sample.mutator.Mutator.Action
import foperator.sample.{AdvancedOperator, PrettyPrint}
import foperator.testkit.{TestClient, TestSchedulerUtil}
import foperator.{ResourceMirror, ResourceState}
import monix.eval.{Task, TaskApp}
import monix.execution.Scheduler
import monix.execution.atomic.Atomic
import monix.execution.schedulers.TestScheduler
import skuber.{ListResource, ObjectResource}

import scala.concurrent.duration._
import scala.util.Random

case class MutationTestCase(seed: Long, steps: Int, maxActionsPerStep: Int) {
  def withSeed(newSeed: Long) = copy(seed = newSeed)
}
object MutationTestCase {
  val base = MutationTestCase(seed=0, steps=20, maxActionsPerStep=3)
  def withSeed(seed: Long) = base.withSeed(seed)
}

object MutatorTestLive extends TaskApp {
  override def run(args: List[String]): Task[ExitCode] = {
    Skuber().use { client =>
      MutatorTest.mainFn(args.toArray, seed => {
        MutatorTest.testLive(MutationTestCase.withSeed(seed), client)
      })
    }.as(ExitCode.Success)
  }
}

object MutatorTest extends Logging {
  def main(args: Array[String]): Unit = {
    mainFn(args, seed => testSynthetic(MutationTestCase.withSeed(seed)))
      .runSyncUnsafe()(Scheduler.global, implicitly)
  }

  // given a runTest function, run whatever tests are implied bu commandline arguments
  def mainFn(args: Array[String], runTest: Long => Task[Unit]): Task[Unit] = {
    args match {
      case Array() => runTest(System.currentTimeMillis()).restartUntil(_ => false)
      case args => args.toList.map(_.toLong).traverse_(seed => runTest(seed))
    }
  }

  def assertValid(validator: StateValidator) = {
    validator.validate match {
      case Validated.Valid(_) => Task.unit
      case Validated.Invalid(errors) => {
        Task(logger.error("Invalid state detected:")) >> validator.dumpState >> Task.raiseError(
          new AssertionError("Inconsistencies found:\n" + errors.toList.mkString("\n"))
        )
      }
    }
  }

  // Runs a test case against a fake k8s client
  def testSynthetic(params: MutationTestCase): Task[Unit] = {
    val testScheduler = TestScheduler()

    val client = TestClient.unsafe()

    def checkMirrorContents(greetings: ResourceMirror[Task, Greeting], people: ResourceMirror[Task, Person]): Task[Unit] = {
      // To detect issues with the mirror machinery, this compares the mirror's
      // view of the world matches the state pulled directly from FoperatorDriver.
      def check[O<:ObjectResource](fromMirror: Iterable[ResourceState[O]], fromClient: Iterable[ResourceState[O]]) = {
        val mirrorSorted = fromMirror.toList.sortBy(_.raw.name)
        val driverSorted = fromClient.toList.sortBy(_.raw.name)
        if (mirrorSorted != driverSorted) {
          Task.raiseError(new AssertionError(s"Mismatch:\nclient contains:\n  ${driverSorted.mkString("\n  ")}\n\nmirror contains:\n  ${mirrorSorted.mkString("\n  ")}"))
        } else Task.unit
      }

      for {
        mirrorGreetings <- greetings.all
        clientGreetings <- client.all[Greeting]
        _ <- check(mirrorGreetings.values, clientGreetings.map(ResourceState.of[Greeting]))

        mirrorPeople <- people.all
        clientPeople <- client.all[Person]
        _ <- check(mirrorPeople.values, clientPeople.map(ResourceState.of[Person]))
      } yield ()
    }

    val tick = {
      def tickLoop: Task[Unit] = {
        // We repeatedly call tick in a loop rather than calling Tick(Duration.INF)
        // so that the timeout can have an effect
        TestSchedulerUtil.tick(testScheduler, 1.second).flatMap { _ =>
          if (testScheduler.state.tasks.nonEmpty) {
            tickLoop
          } else {
            Task.unit
          }
        }
      }

      // The sample operator has periodic refresh disabled, so we know that:
      //  - the only delayed schedules are due to error backoff, which we want to allow
      //  - if something is in a reschedule loop, it'll keep going until it times out the Await
      tickLoop.timeout(1.second)
    }

    def tickAndValidate(mutator: Mutator[TestClient[Task]], greetings: ResourceMirror[Task, Greeting], people: ResourceMirror[Task, Person]): Task[Unit] = for {
      _ <- Task(logger.info(s"Ticking..."))
      _ <- tick
      _ <- Task(logger.info(s"Checking consistency..."))
      _ <- TestSchedulerUtil.run(testScheduler, for {
        validator <- mutator.stateValidator
        _ <- checkMirrorContents(greetings, people)
        _ <- assertValid(validator)
      } yield ())
    } yield ()

    val ready = Deferred.unsafe[Task, (ResourceMirror[Task, Greeting], ResourceMirror[Task, Person])]

    // We need all resource-related subscriptions to happen on the test scheduler.
    // So we run the overall block on the test scheduler, but pass state via
    // a deferred so that we can run the test logic outside the scheduler.
    val main = client.apply[Greeting].mirror { greetings =>
      client.apply[Person].mirror { people =>
        for {
          // start the operator first, then complete the `ready` deferred
          fiber <- new AdvancedOperator(client).runWith(greetings, people).start
          _ <- ready.complete((greetings, people))
          _ <- fiber.join
        } yield ()
      }
    }

    for {
      mainFiber <- main.executeOn(testScheduler).start
      startup <- TestSchedulerUtil.await(testScheduler, ready.get)
      (greetings, people) = startup
      mutator = new Mutator(client, greetings, people)
      _ <- runWithValidation(params, mutator,
        main = mainFiber.join,
        tickAndValidate = tickAndValidate(mutator, greetings, people),
        testScheduler = testScheduler)
    } yield ()
  }

  def testLive(params: MutationTestCase, client: Skuber): Task[Unit] = {
    // Runs a test case against a real k8s cluster
    val updateCount = Atomic(0)
    val lastUpdateCount = Atomic(0)

    def validate(mutator: Mutator[Skuber]) = {
      // When using a real scheduler, we can't know fur sure when things have settled.
      // So instead, we do a small tick, and then keep ticking more and more while
      // validation fails.
      // The main risks are if:
      // - The initial tick isn't enough for our test infrastructure to see the update
      //   we just made
      // - The state is initially consistent, _but_ would get inconsistent if we waited
      //   longer (this seems unlikely)
      // To mitigate the first, we always wait until at least one update has been seen
      // by the test infrastructure, then start ticking from there.

      val maxTicks = 100

      def tickLoop(remaining: Int): Task[Unit] = {
        Task.sleep(10.millis) >>
        Task.defer {
          if (lastUpdateCount.get == updateCount.get) {
            // no updates seen yet, short-circuit without decrementing `remaining`
            tickLoop(remaining)
          } else {
            Task.sleep(100.millis) >>
            Task(logger.info(s"Checking consistency... (remaining attempts: $remaining)")) >>
            mutator.stateValidator.flatMap { validator =>
              validator.validate match {
                case Validated.Valid(_) =>
                  // update lastUpdateCount for next loop
                  Task { lastUpdateCount.set(updateCount.get) }
                case Validated.Invalid(_) => {
                  if (remaining > 0) {
                    // not consistent yet, keep trying
                    tickLoop(remaining-1)
                  } else {
                    assertValid(validator)
                  }
                }
              }
            }
          }
        }
      }

      tickLoop(maxTicks)
    }

    val deleteAll = Task.parZip2(
      Task.deferFuture(client.underlying.deleteAll[ListResource[Person]]()),
      Task.deferFuture(client.underlying.deleteAll[ListResource[Greeting]]())
    ).void

    (deleteAll >> Mutator.withResourceMirrors(client) { (greetings, people) =>
      val mutator = new Mutator(client, greetings, people)
      val main = new AdvancedOperator(client).runWith(greetings, people)

      // In parallel with main, we also trace when we see updates to resources.
      // This lets validate check that _some_ update has been seen since the last action,
      // even if we can't tell which update
      val increaseUpdateCount = Task(updateCount.increment())
      val mainWithMonitoring = Task.raceMany(List(
        main,
        greetings.ids.evalMap(_ => increaseUpdateCount).compile.drain,
        people.ids.evalMap(_ => increaseUpdateCount).compile.drain
      ))
      Task.deferAction(sched =>
        runWithValidation(params, mutator, main = mainWithMonitoring, tickAndValidate = validate(mutator), testScheduler = sched)
      )
    })
  }

  private def runWithValidation[C](params: MutationTestCase, mutator: Mutator[C], main: Task[Unit], tickAndValidate: Task[Unit], testScheduler: Scheduler): Task[Unit] = {
    val actionpp = implicitly[PrettyPrint[Action]]
    val rand = new Random(params.seed)
    def loop(stepNo: Int): Task[Unit] = {
      if (stepNo >= params.steps) {
        Task(logger.info("Loop completed successfully"))
      } else {
        (for {
          numConcurrent <- Task(rand.between(1, params.maxActionsPerStep+1))
          actions <- mutator.nextActions(rand, numConcurrent, _ => true)
          _ <- Task(logger.info(s"Running step #${stepNo} (params: $params)\n - ${actions.map(actionpp.pretty).mkString("\n - ")}"))
          fiber <- Task.parSequenceUnordered(actions.map(_.run)).executeOn(testScheduler).start
          _ <- Task.parZip2(tickAndValidate, fiber.join)
        } yield ()).flatMap(_ => loop(stepNo + 1))
      }
    }
    Task.race(
      main.flatMap(_ => Task.raiseError(new AssertionError("main exited prematurely"))),
      loop(1)
    ).void
  }
}
