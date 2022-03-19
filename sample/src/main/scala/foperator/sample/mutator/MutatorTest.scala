package foperator.sample.mutator

import cats.data.Validated
import cats.effect.{ExitCode, IO, IOApp}
import cats.implicits._
import foperator.backend.Skuber
import foperator.backend.skuber.implicits._
import foperator.internal.Logging
import foperator.sample.Models.Skuber._
import foperator.sample.PrettyPrint.Implicits._
import foperator.sample.mutator.Mutator.Action
import foperator.sample.{AdvancedOperator, PrettyPrint}
import foperator.testkit.TestClient
import foperator.{ResourceMirror, ResourceState}
import skuber.{ListResource, ObjectResource}

import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration._
import scala.util.Random

case class MutationTestCase(seed: Long, steps: Int, maxActionsPerStep: Int) {
  def withSeed(newSeed: Long) = copy(seed = newSeed)
}
object MutationTestCase {
  val base = MutationTestCase(seed=0, steps=20, maxActionsPerStep=3)
  def withSeed(seed: Long) = base.withSeed(seed)
}

object MutatorTestLive extends IOApp {
  override def run(args: List[String]): IO[ExitCode] = {
    Skuber[IO].default().use { client =>
      MutatorTest.mainFn(args, seed => {
        MutatorTest.testLive(MutationTestCase.withSeed(seed), client)
      })
    }.as(ExitCode.Success)
  }
}

object MutatorTest extends IOApp with Logging {
  override def run(args: List[String]): IO[ExitCode] = {
    mainFn(args, seed => testSynthetic(MutationTestCase.withSeed(seed)))
  }.as(ExitCode.Success)

  // given a runTest function, run whatever tests are implied bu commandline arguments
  def mainFn(args: Seq[String], runTest: Long => IO[Unit]): IO[Unit] = {
    if (args.isEmpty) {
      runTest(System.currentTimeMillis()).foreverM
    } else {
      args.map(_.toLong).traverse_(seed => runTest(seed))
    }
  }

  def assertValid(validator: StateValidator) = {
    validator.validate match {
      case Validated.Valid(_) => IO.unit
      case Validated.Invalid(errors) => {
        IO(logger.error("Invalid state detected:")) >> validator.dumpState >> IO.raiseError(
          new AssertionError("Inconsistencies found:\n" + errors.toList.mkString("\n"))
        )
      }
    }
  }

  // Runs a test case against a fake k8s client
  def testSynthetic(params: MutationTestCase): IO[Unit] = {
    TestClient[IO].client.flatMap { client =>

      def checkMirrorContents(greetings: ResourceMirror[IO, Greeting], people: ResourceMirror[IO, Person]): IO[Unit] = {
        // To detect issues with the mirror machinery, this compares the mirror's
        // view of the world matches the state pulled directly from FoperatorDriver.
        def check[O<:ObjectResource](fromMirror: Iterable[ResourceState[O]], fromClient: Iterable[ResourceState[O]]) = {
          val mirrorSorted = fromMirror.toList.sortBy(_.raw.name)
          val driverSorted = fromClient.toList.sortBy(_.raw.name)
          if (mirrorSorted != driverSorted) {
            IO.raiseError(new AssertionError(s"Mismatch:\nclient contains:\n  ${driverSorted.mkString("\n  ")}\n\nmirror contains:\n  ${mirrorSorted.mkString("\n  ")}"))
          } else IO.unit
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

      val tick = IO.sleep(1.milli)

      def tickAndValidate(mutator: Mutator[TestClient[IO]], greetings: ResourceMirror[IO, Greeting], people: ResourceMirror[IO, Person]): IO[Unit] = for {
        _ <- IO(logger.info(s"Ticking..."))
        _ <- tick
        _ <- IO(logger.info(s"Checking consistency..."))
        validator <- mutator.stateValidator
        _ <- checkMirrorContents(greetings, people)
        _ <- assertValid(validator)
      } yield ()

      client.apply[Greeting].mirror { greetings =>
        client.apply[Person].mirror { people =>
          for {
            fiber <- new AdvancedOperator(client).runWith(greetings, people).start
            mutator = new Mutator(client, greetings, people)
            _ <- runWithValidation(params, mutator,
              main = fiber.joinWithNever,
              tickAndValidate = tickAndValidate(mutator, greetings, people),
            )
            _ <- fiber.join
          } yield ()
        }
      }
    }
  }

  def testLive(params: MutationTestCase, client: Skuber[IO]): IO[Unit] = {
    // Runs a test case against a real k8s cluster
    val updateCount = new AtomicInteger(0)
    val lastUpdateCount = new AtomicInteger(0)

    def validate(mutator: Mutator[Skuber[IO]]) = {
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

      def tickLoop(remaining: Int): IO[Unit] = {
        IO.sleep(10.millis) >>
        IO.defer {
          if (lastUpdateCount.get() == updateCount.get()) {
            // no updates seen yet, short-circuit without decrementing `remaining`
            tickLoop(remaining)
          } else {
            IO.sleep(100.millis) >>
            IO(logger.info(s"Checking consistency... (remaining attempts: $remaining)")) >>
            mutator.stateValidator.flatMap { validator =>
              validator.validate match {
                case Validated.Valid(_) =>
                  // update lastUpdateCount for next loop
                  IO { lastUpdateCount.set(updateCount.get()) }
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

    val deleteAll = List(
      IO.fromFuture(IO(client.underlying.deleteAll[ListResource[Person]]())),
      IO.fromFuture(IO(client.underlying.deleteAll[ListResource[Greeting]]()))
    ).parSequence_

    (deleteAll >> Mutator.withResourceMirrors(client) { (greetings, people) =>
      val mutator = new Mutator(client, greetings, people)
      val main = new AdvancedOperator(client).runWith(greetings, people)

      // In parallel with main, we also trace when we see updates to resources.
      // This lets validate check that _some_ update has been seen since the last action,
      // even if we can't tell which update
      val increaseUpdateCount = IO(updateCount.incrementAndGet())
      // nested race is silly! https://github.com/typelevel/cats-effect/issues/512
      val mainWithMonitoring = IO.race(
        main,
        IO.race(
          greetings.ids.evalMap(_ => increaseUpdateCount).compile.drain,
          people.ids.evalMap(_ => increaseUpdateCount).compile.drain
        )
      ).void
      runWithValidation(params, mutator, main = mainWithMonitoring, tickAndValidate = validate(mutator))
    })
  }

  private def runWithValidation[C](params: MutationTestCase, mutator: Mutator[C], main: IO[Unit], tickAndValidate: IO[Unit]): IO[Unit] = {
    val actionpp = implicitly[PrettyPrint[Action]]
    val rand = new Random(params.seed)
    def loop(stepNo: Int): IO[Unit] = {
      if (stepNo >= params.steps) {
        IO(logger.info("Loop completed successfully"))
      } else {
        (for {
          numConcurrent <- IO(rand.between(1, params.maxActionsPerStep+1))
          actions <- mutator.nextActions(rand, numConcurrent, _ => true)
          _ <- IO(logger.info(s"Running step #${stepNo} (params: $params)\n - ${actions.map(actionpp.pretty).mkString("\n - ")}"))
          fiber <- (actions.map(_.run)).parSequence_.start
          _ <- List(tickAndValidate, fiber.join).parSequence_
        } yield ()).flatMap(_ => loop(stepNo + 1))
      }
    }
    IO.race(
      main.flatMap(_ => IO.raiseError(new AssertionError("main exited prematurely"))),
      loop(1)
    ).void
  }
}
