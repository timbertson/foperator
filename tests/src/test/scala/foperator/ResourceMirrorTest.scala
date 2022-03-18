package foperator

import cats.effect.Ref
import cats.effect.testkit.TestControl
import cats.effect.{Deferred, IO}
import cats.implicits._
import cats.syntax._
import foperator.fixture.Resource
import foperator.internal.Logging
import foperator.testkit.{TestClient, TestClientEngineImpl}
import fs2.Stream
import weaver.Expectations

import scala.concurrent.duration.DurationInt

class FallibleEngine extends TestClientEngineImpl[IO, Resource] {
  val error = Deferred.unsafe[IO, Throwable]

  override def listAndWatch(c: TestClient[IO], opts: ListOptions): IO[(List[Resource], fs2.Stream[IO, Event[Resource]])] = {
    super.listAndWatch(c, opts).map {
      case (initial, updates) => (initial, updates.merge(Stream.eval(error.get.flatMap(IO.raiseError))))
    }
  }
}

object ResourceMirrorTest extends SimpleTimedIOSuite with Logging {
  val f1 = Resource.fixture.copy(name = "f1")
  val f2 = Resource.fixture.copy(name = "f2")
  val f3 = Resource.fixture.copy(name = "f3")
  val f4 = Resource.fixture.copy(name = "f4")

  def takeUnique(expectedSize: Int)(stream: Stream[IO, Id[Resource]]) = {
    stream.scan(Set.empty[Id[Resource]]){ (set, item) =>
      logger.debug(s"takeUnique: saw element ${item}")
      set.incl(item)
    }.find(_.size == expectedSize).compile.toList.map(_.flatten.sorted)
  }

  timedTest("aborts the `use` block on error") {
    val engine = new FallibleEngine
    val error = new RuntimeException("injected error from test")
    for {
      client <- TestClient[IO].client
      cancelled <- Ref.of(false)
      fiber <- ResourceMirror.apply[IO, TestClient[IO], Resource, Unit](client, ListOptions.all) { _ =>
        IO.never.onCancel(cancelled.set(true))
      }(implicitly, implicitly, engine).start

      // Then trigger an error, and our fiber should fail:
      _ <- engine.error.complete(error)
      result <- fiber.join.attempt
    } yield {
      // getCause because operational failures are wrapped in a descriptive exception
      expect(result.left.toOption.map(_.getCause) == Some(error))
    }
  }

  timedTest("is populated with the initial resource set") {
    for {
      ops <- TestClient[IO].client.map(_.apply[Resource])
      _ <- ops.write(f1)
      listed <- ops.mirror { mirror =>
        mirror.active.map(_.values.toList)
      }
    } yield {
      expect(listed.map(_.spec) === List(f1.spec))
    }
  }

  timedTest("emits IDs for updates") {
    TestControl.executeEmbed {
      for {
        ops <- TestClient[IO].client.map(_.apply[Resource])
        fiber <- ops.mirror { mirror =>
          mirror.ids.take(4).compile.toList
        }.start
        // make sure we're subscribed
        _ <- IO.sleep(1.second)
        _ <- (ops.write(f1) >> ops.write(f2) >> ops.write(f3) >> ops.delete(Id.of(f3)))
        ids <- fiber.joinWithNever
      } yield {
        def id(name: String) = Id.apply[Resource]("default", name)
        assert(ids.sorted == List(
          id("f1"),
          id("f2"),
          id("f3"),
          id("f3"),
        ))
      }
    }
  }

  timedTest("updates internal state before emitting an ID") {
    for {
      ops <- TestClient[IO].client.map(_.apply[Resource])
      fiber <- ops.mirror { mirror =>
        mirror.ids.evalMap(id => mirror.getActive(id).map(res => (id, res.map(_.spec)))).take(1).compile.toList
      }.start
      _ <- ops.write(f1)
      ids <- fiber.joinWithNever
    } yield {
      expect(ids === List((Id.of(f1), Some(f1.spec))))
    }
  }

  timedTest("supports multiple concurrent ID consumers") {
    for {
      ops <- TestClient[IO].client.map(_.apply[Resource])
      fiber <- ops.mirror { mirror =>
        (
          takeUnique(2)(mirror.ids),
          takeUnique(2)(mirror.ids)
        ).parTupled
      }.start
      _ <- ops.write(f1) >> ops.write(f2)
      ids <- fiber.joinWithNever
    } yield {
      val expectedList = List(Id.of(f1), Id.of(f2))
      expect(ids === (expectedList, expectedList))
    }
  }

  timedTest("cannot skip updates during concurrent subscription") {
    // if we subscribe concurrently to an item's creation, then either:
    // we observe the creation as part of the initial updates emitted, or
    // emitting of the item is delayed until our subscriber is installed,
    // so we see it as an update.
    val test = for {
      client <- TestClient[IO].client
      ops = client[Resource]
      subscribe = ops.mirror { mirror => takeUnique(2)(mirror.ids) }
      events <- (
        ops.write(f1),
        subscribe,
        ops.write(f2),
      ).parMapN { (_, lst, _) => lst }
      _ <- expect(events === List(Id.of(f1), Id.of(f2))).failFast
    } yield ()

    // run many times on test scheduler, to inject some intentional reordering of operations
    def loop(remaining: Int): IO[Expectations] = {
      if (remaining > 0) {
        test >> loop(remaining-1)
      } else IO.pure(succeed(()))
    }
    loop(100)
  }
}
