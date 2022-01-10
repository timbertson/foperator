package foperator

import cats.effect.concurrent.{Deferred, Ref}
import cats.implicits._
import foperator.fixture.Resource
import foperator.internal.Logging
import foperator.testkit.{TestClient, TestClientEngineImpl, TestSchedulerUtil}
import fs2.Stream
import monix.eval.Task
import monix.execution.ExecutionModel
import monix.execution.schedulers.TestScheduler

class FallibleEngine extends TestClientEngineImpl[Task, Resource] {
  val error = Deferred.unsafe[Task, Throwable]

  override def listAndWatch(c: TestClient[Task], opts: ListOptions): Task[(List[Resource], fs2.Stream[Task, Event[Resource]])] = {
    super.listAndWatch(c, opts).map {
      case (initial, updates) => (initial, updates.merge(Stream.eval(error.get.flatMap(Task.raiseError))))
    }
  }
}

object ResourceMirrorTest extends SimpleTimedTaskSuite with Logging {
  val f1 = Resource.fixture.copy(name = "f1")
  val f2 = Resource.fixture.copy(name = "f2")
  val f3 = Resource.fixture.copy(name = "f3")
  val f4 = Resource.fixture.copy(name = "f4")

  def takeUnique(expectedSize: Int)(stream: Stream[Task, Id[Resource]]) = {
    stream.scan(Set.empty[Id[Resource]]){ (set, item) =>
      logger.debug(s"takeUnique: saw element ${item}")
      set.incl(item)
    }.find(_.size == expectedSize).compile.toList.map(_.flatten.sorted)
  }

  timedTest("aborts the `use` block on error") {
    val engine = new FallibleEngine
    val error = new RuntimeException("injected error from test")
    for {
      client <- TestClient[Task]
      cancelled <- Ref.of(false)
      fiber <- ResourceMirror.apply[Task, TestClient[Task], Resource, Unit](client, ListOptions.all) { _ =>
        Task.never.doOnCancel(cancelled.set(true))
      }(implicitly, implicitly, engine).start

      // Then trigger an error, and our fiber should fail:
      _ <- engine.error.complete(error)
      result <- fiber.join.materialize
    } yield {
      // getCause because operational failures are wrapped in a descriptive exception
      expect(result.failed.toOption.map(_.getCause) == Some(error))
    }
  }

  timedTest("is populated with the initial resource set") {
    for {
      ops <- TestClient[Task].map(_.apply[Resource])
      _ <- ops.write(f1)
      listed <- ops.mirror { mirror =>
        mirror.active.map(_.values.toList)
      }
    } yield {
      expect(listed.map(_.spec) === List(f1.spec))
    }
  }

  timedTest("emits IDs for updates") {
    val testScheduler = TestScheduler()
    for {
      ops <- TestClient[Task].map(_.apply[Resource])
      fiber <- TestSchedulerUtil.start(testScheduler, ops.mirror { mirror =>
        mirror.ids.take(4).compile.toList
      })
      // make sure we're subscribed
      _ <- TestSchedulerUtil.tick(testScheduler)
      _ <- (ops.write(f1) >> ops.write(f2) >> ops.write(f3) >> ops.delete(Id.of(f3)))
        .executeOn(testScheduler).start
      ids <- TestSchedulerUtil.await(testScheduler, fiber.join)
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

  timedTest("updates internal state before emitting an ID") {
    for {
      ops <- TestClient[Task].map(_.apply[Resource])
      fiber <- ops.mirror { mirror =>
        mirror.ids.evalMap(id => mirror.getActive(id).map(res => (id, res.map(_.spec)))).take(1).compile.toList
      }.start
      _ <- ops.write(f1)
      ids <- fiber.join
    } yield {
      expect(ids === List((Id.of(f1), Some(f1.spec))))
    }
  }

  timedTest("supports multiple concurrent ID consumers") {
    for {
      ops <- TestClient[Task].map(_.apply[Resource])
      fiber <- ops.mirror { mirror =>
        Task.parZip2(
          takeUnique(2)(mirror.ids),
          takeUnique(2)(mirror.ids)
        )
      }.start
      _ <- ops.write(f1) >> ops.write(f2)
      ids <- fiber.join
    } yield {
      val expectedList = List(Id.of(f1), Id.of(f2))
      expect(ids == ((expectedList, expectedList)))
    }
  }

  timedTest("cannot skip updates during concurrent subscription") {
    // if we subscribe concurrently to an item's creation, then either:
    // we observe the creation as part of the initial updates emitted, or
    // emitting of the item is delayed until our subscriber is installed,
    // so we see it as an update.

    val test = for {
      client <- TestClient[Task]
      ops = client[Resource]
      subscribe = ops.mirror { mirror => takeUnique(2)(mirror.ids) }
      events <- Task.parMap3(
        ops.write(f1),
        subscribe,
        ops.write(f2),
      )((_, lst, _) => lst)
    } yield {
      expect(events === List(Id.of(f1), Id.of(f2)))
    }

    // run on test scheduler, to inject some intentional reordering of operations
    val testScheduler = TestScheduler(ExecutionModel.AlwaysAsyncExecution)
    TestSchedulerUtil.run(testScheduler, test)
  }
}
