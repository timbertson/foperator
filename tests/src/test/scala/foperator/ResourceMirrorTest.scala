package foperator

import cats.effect.kernel.Outcome
import cats.effect.testkit.{TestContext, TestControl}
import cats.effect.{Deferred, IO, Ref}
import cats.implicits._
import foperator.fixture.{Resource, ResourceSpec, ResourceStatus}
import foperator.internal.Logging
import foperator.testkit.{TestClient, TestClientEngineImpl, TestResource}
import fs2.Stream
import weaver.Expectations

import scala.concurrent.duration._

class FallibleEngine extends TestClientEngineImpl[IO, Resource] {
  val error = Deferred.unsafe[IO, Throwable]

  override def listAndWatch(c: TestClient[IO], opts: ListOptions): fs2.Stream[IO, StateChange[Resource]] = {
    super.listAndWatch(c, opts).merge(Stream.eval(error.get.flatMap(IO.raiseError)))
  }
}

object ResourceMirrorTest extends SimpleTimedIOSuite with Logging {
  val f1 = Resource.fixture.copy(name = "f1")
  val f2 = Resource.fixture.copy(name = "f2")
  val f3 = Resource.fixture.copy(name = "f3")
  val f4 = Resource.fixture.copy(name = "f4")

  def takeUnique(expectedSize: Int, desc: String = "")(stream: Stream[IO, Id[Resource]]) = {
    stream.scan(Set.empty[Id[Resource]]){ (set, item) =>
      val newSet = set.incl(item)
      logger.debug(s"takeUnique($expectedSize, $desc): saw element ${item} >> ${newSet}")
      newSet
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
      result <- fiber.join
    } yield {
      result match {
        // getCause because operational failures are wrapped in a descriptive exception
        case Outcome.Errored(t) => expect(t.getCause == error)
        case other => failure(s"unexpected outcome: $other")
      }
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

  timedTest("synthesizes updates when the state is reset") {
    ResourceMirror.forStateStream(Stream[IO, StateChange[TestResource[ResourceSpec, ResourceStatus]]](
      StateChange.ResetState(Nil),
      StateChange.Updated(f1),
      StateChange.Updated(f3),
      StateChange.ResetState(List(f1)),
      StateChange.ResetState(List(f1, f2)),
      StateChange.Updated(f4),
    ) ++ Stream.never) { mirror =>
      for {
        ids <- mirror.ids.take(5).compile.toList
        finalIds <- mirror.all.map(_.keySet)
      } yield {
        expect.all(
          ids === List(
            f1.id, f3.id, // update
            f3.id, // delete (missing from state)
            f2.id, // add (present in state)
            f4.id
          ),
          finalIds === Set(f1.id, f2.id, f4.id)
        )
      }
    }
  }

  timedTest("repeatedly invokes listAndWatch on EOF") {
    ResourceMirror.forStateStream(Stream[IO, StateChange[TestResource[ResourceSpec, ResourceStatus]]](
      StateChange.ResetState(List()),
      StateChange.Updated(f1),
    )) { mirror =>
      for {
        ids <- mirror.ids.take(10).compile.toList
      } yield expect(ids.length === 10)
    }
  }

  timedTest("supports multiple concurrent ID consumers") {
    for {
      ops <- TestClient[IO].client.map(_.apply[Resource])
      fiber <- ops.mirror { mirror =>
        (
          takeUnique(2, "a")(mirror.ids),
          takeUnique(2, "b")(mirror.ids)
        ).parTupled
      }.start
      _ <- ops.write(f1) >> ops.write(f2)
      ids <- fiber.joinWithNever
    } yield {
      val expectedList = List(Id.of(f1), Id.of(f2))
      expect(ids === (expectedList, expectedList))
    }
  }

  test("cannot skip updates during concurrent subscription") {
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

    def loop(remaining: Int): IO[Expectations] = {
      if (remaining > 0) {
        val seed = TestContext().seed
        IO.delay(logger.info(s"-- test start: $remaining (seed: ${seed})")) *>
          TestControl.executeEmbed(test, seed = Some(seed)).timeout(20.seconds) *>
          loop(remaining-1)
      } else IO.pure(succeed(()))
    }
    loop(50)
  }
}
