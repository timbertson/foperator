package net.gfxmonk.foperator.sample

import cats.data.Validated
import monix.eval.Task
import monix.execution.Scheduler
import monix.execution.atomic.AtomicBoolean
import monix.execution.schedulers.{CanBlock, TestScheduler, TrampolineExecutionContext}
import net.gfxmonk.foperator.internal.Logging
import net.gfxmonk.foperator.sample.Implicits._
import net.gfxmonk.foperator.sample.Models._
import net.gfxmonk.foperator.testkit.FoperatorDriver
import net.gfxmonk.foperator.{ResourceMirror, ResourceState}
import skuber.{ListResource, ObjectResource}

import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
import scala.util.Random

case class MutationTestCase(seed: Long, steps: Int, maxActionsPerStep: Int) {
  def withSeed(newSeed: Long) = copy(seed = newSeed)
}
object MutationTestCase {
  val base = MutationTestCase(seed=0, steps=20, maxActionsPerStep=3)
  def withSeed(seed: Long) = base.withSeed(seed)
}

object MutatorTestLive extends Logging {
  def main(args: Array[String]): Unit = {
    val implicits = SchedulerImplicits.global
    args match {
      case Array() => while(true) {
        MutatorTest.testLive(MutationTestCase.withSeed(System.currentTimeMillis()), implicits)
      }
      case args => args.map(_.toLong).foreach(seed => MutatorTest.testLive(MutationTestCase.withSeed(seed), implicits))
    }
  }
}

object MutatorTest extends Logging {
  val dedicatedThread = Scheduler.singleThread("mutatorTest", daemonic = true)

  def main(args: Array[String]): Unit = {
    args match {
      case Array() => while(true) {
        testSynthetic(MutationTestCase.withSeed(System.currentTimeMillis()))
      }
      case args => args.map(_.toLong).foreach(seed => testSynthetic(MutationTestCase.withSeed(seed)))
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
  def testSynthetic(params: MutationTestCase) = {
    // Scheduler juggling:
    //  - FoperatorClient operations are fully synchronous (despite retuning a Future).
    //    Whenever it schedules an update notification (for a watch), it does so
    //    by submitting to the user's scheduler (i.e. testScheduler), but it
    //    never waits on the result of such notifications.
    //
    // So, once we call an action it completes immediately, possibly
    // queueing update notifications. Any further work (including k8s calls make to this client
    // by the actual operators) _only_ requires the TestScheduler to tick.
    // The real scheduler is only used to drive the test, and executes things inline (i.e. on the main thread)
    val realScheduler = Scheduler(TrampolineExecutionContext.immediate)

    val testScheduler = TestScheduler()

    val driver = new FoperatorDriver(testScheduler)
    implicit val client = driver.client

    val implicits = {
      // This is extremely silly: akka's logger setup synchronously blocks for the logger to (asynchronously)
      // respond that it's ready, but it can't because the scheduler's paused. So... we run it in a background
      // thread until akka gets unblocked.
      val condition = AtomicBoolean(false)
      val runScheduler = Future {
        while(!condition.get()) {
          testScheduler.tick(1.second)
        }
      }(dedicatedThread)
      val result = SchedulerImplicits.full(testScheduler, client)
      condition.set(true)
      Await.result(runScheduler, Duration.Inf)
      result
    }

    def checkMirrorContents(greetings: ResourceMirror[Greeting], people: ResourceMirror[Person]): Task[Unit] = {
      // To detect issues with the mirror machinery, this compares the mirror's
      // view of the world matches the state pulled directly from FoperatorDriver.
      def check[O<:ObjectResource](fromMirror: Iterable[ResourceState[O]], fromDriver: Iterable[ResourceState[O]]) = {
        val mirrorSorted = fromMirror.toList.sortBy(_.raw.name)
        val driverSorted = fromDriver.toList.sortBy(_.raw.name)
        if (mirrorSorted != driverSorted) {
          Task.raiseError(new AssertionError(s"Mismatch:\nwanted: $driverSorted\ngot:    $mirrorSorted"))
        } else Task.unit
      }

      for {
        allGreetings <- greetings.all
        _ <- check(allGreetings.values, driver.list[Greeting])
        allPeople <- people.all
        _ <- check(allPeople.values, driver.list[Person])
      } yield ()
    }

    val tick = {
      def tickLoop: Task[Unit] = {
        // We repeatedly call tick in a loop rather than calling Tick(Duration.INF)
        // so that the timeout can have an effect
        Task(testScheduler.tick(1.second)).flatMap { _ =>
          if (testScheduler.state.tasks.nonEmpty) {
            tickLoop
          } else {
            Task.unit
          }
        }
      }

      // We tick on a dedicated thread, because otherwise the testScheduler.tick() call is happening on the
      // main thread, which is also servicing trampolined executions for the TestScheduler.
      // tl;dr if you do that, your `tick()` can return even though there are trampolined thunks
      // awaiting execution.
      // The sample operator has periodic refresh disabled, so we know that:
      //  - the only delayed schedules are due to error backoff, which we want to allow
      //  - if something is in a reschedule loop, it'll keep going until it times out the Await
      tickLoop.executeOn(dedicatedThread).timeout(1.second)
    }

    def tickAndValidate(mutator: Mutator, greetings: ResourceMirror[Greeting], people: ResourceMirror[Person]): Task[Unit] = for {
      _ <- Task(logger.info(s"Ticking..."))
      _ <- tick
      _ <- Task(logger.info(s"Checking consistency..."))
      validator <- mutator.stateValidator
      _ <- checkMirrorContents(greetings, people)
      _ <- assertValid(validator)
    } yield ()

    (for {
      greetings <- driver.mirror[Greeting]()
      people <- driver.mirror[Person]()
      mutator = new Mutator(client, greetings, people)

      // Start main eagerly, and make sure it lives on the testScheduler. Once started, tick() to ensure
      // it's set up all its wachers etc
      main = new AdvancedOperator(implicits).runWith(greetings, people).runToFuture(testScheduler)
      _ = testScheduler.tick()
      _ <- runWithValidation(params, mutator, main = Task.fromFuture(main),
        tickAndValidate = tickAndValidate(mutator, greetings, people))
    } yield ()).runSyncUnsafe()(realScheduler, implicitly[CanBlock])
  }

  def testLive(params: MutationTestCase, implicits: SchedulerImplicits) = {
    // Runs a test case against a real k8s cluster
    implicit val client = implicits.k8sClient
    implicit val mat = implicits.materializer

    def validate(mutator: Mutator) = {
      // When using a real scheduler, we can't know fur sure when things have settled.
      // So instead, we do a small tick, and then keep ticking more and more while
      // validation fails.
      // The main risks are if:
      // - The initial tick isn't enough for our test infrastructure to see the update
      //   we just made
      // - The state is initially consistent, _but_ would get inconsistent if we waited
      //   longer (this seems unlikely)
      // TODO: we could check the mirror state to see if the change we made has been reflected,
      //       which would protect against the first issue.

      val maxTicks = 100

      def tickLoop(remaining: Int): Task[Unit] = {
        Task.sleep(100.millis) >>
        Task(logger.info(s"Checking consistency... (remaining attempts: $remaining)")) >>
        mutator.stateValidator.flatMap { validator =>
          validator.validate match {
            case Validated.Valid(_) => Task.unit
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

      tickLoop(maxTicks)
    }

    val deleteAll = Task.parZip2(
      Task.deferFuture(client.deleteAll[ListResource[Person]]()),
      Task.deferFuture(client.deleteAll[ListResource[Greeting]]())
    ).void

    (deleteAll >> Mutator.withResourceMirrors(client) { (greetings, people) =>
      val mutator = new Mutator(client, greetings, people)
      val main = new AdvancedOperator(implicits).runWith(greetings, people)
      runWithValidation(params, mutator, main=main, tickAndValidate = validate(mutator))
    }).runSyncUnsafe()(Scheduler.global, implicitly[CanBlock])
  }

  private def runWithValidation(params: MutationTestCase, mutator: Mutator, main: Task[Unit], tickAndValidate: Task[Unit]): Task[Unit] = {
    val rand = new Random(params.seed)
    def loop(stepNo: Int): Task[Unit] = {
      if (stepNo >= params.steps) {
        Task(logger.info("Loop completed successfully"))
      } else {
        (for {
          numConcurrent <- Task(rand.between(1, params.maxActionsPerStep+1))
          actions <- mutator.nextActions(rand, numConcurrent, _ => true)
          _ <- Task(logger.info(s"Running step #${stepNo} ${actions} (params: $params)"))
          _ <- Task.gatherUnordered(actions.map(_.run)).void
          _ <- tickAndValidate
        } yield ()).flatMap(_ => loop(stepNo + 1))
      }
    }
    Task.race(
      main.flatMap(_ => Task.raiseError(new AssertionError("main exited prematurely"))),
      loop(1)
    ).void
  }
}
