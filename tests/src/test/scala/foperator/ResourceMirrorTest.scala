package foperator

import cats.effect.concurrent.Deferred
import cats.implicits._
import foperator.fixture.Resource
import foperator.testkit.{TestClient, TestClientEngineImpl}
import fs2.Stream
import monix.eval.Task
import monix.execution.ExecutionModel
import monix.execution.schedulers.TestScheduler
import org.scalatest.funspec.AnyFunSpec

import scala.concurrent.Future
import scala.util.Success

class FallibleEngine extends TestClientEngineImpl[Task, Resource] {
  val error = Deferred.unsafe[Task, Throwable]

  override def listAndWatch(c: TestClient[Task], opts: ListOptions): Task[(List[Resource], fs2.Stream[Task, Event[Resource]])] = {
    super.listAndWatch(c, opts).map {
      case (initial, updates) => (initial, updates.merge(Stream.eval(error.get.flatMap(Task.raiseError))))
    }
  }
}

class ResourceMirrorTest extends AnyFunSpec {

  val testScheduler = TestScheduler(ExecutionModel.AlwaysAsyncExecution)
  val f1 = Resource.fixture.copy(name = "f1")
  val f2 = Resource.fixture.copy(name = "f2")
  val f3 = Resource.fixture.copy(name = "f3")

  private def tick[T](t: Task[T]): Future[T] = {
    val f = t.runToFuture(testScheduler)
    testScheduler.tick()
    f
  }

  it("aborts the `use` block on error") {
    val engine = new FallibleEngine
    val error = new RuntimeException("injected error from test")
    var cancelled = false
    val client = TestClient.unsafe()

    val f = tick(ResourceMirror.apply[Task, TestClient[Task], Resource, Unit](client, ListOptions.all) { _ =>
        Task.never.doOnCancel(Task {
          cancelled = true
        }).void
      }(implicitly, implicitly, engine))

    // Then trigger an error, and our future should fail:
    tick(engine.error.complete(error))
    assert(f.value.flatMap(_.failed.toOption).map(_.getCause) == Some(error))
  }

  it("is populated with the initial resource set") {
    val ops = TestClient.unsafe().apply[Resource]
    tick(ops.write(Resource.fixture))

    val f = tick(ops.mirror { mirror =>
      mirror.active.map(_.values.toList)
    })

    assert(f.value.get.get.map(_.spec) == List(Resource.fixture.spec))
  }

  it("emits IDs for updates") {
    val ops = TestClient.unsafe().apply[Resource]
    tick(ops.write(f1))

    val f = tick(ops.mirror { mirror =>
      mirror.ids.take(4).compile.toList
    })

    tick(
      ops.write(f2) >>
      ops.write(f3) >>
      ops.delete(Id.of(f3))
    )

    def id(name: String) = Id.apply("default", name)
    assert(f.value.get.get == List(
      id("f1"),
      id("f2"),
      id("f3"),
      id("f3"),
    ))
  }

  it("updates internal state before emitting an ID") {
    val ops = TestClient.unsafe().apply[Resource]
    val f = tick(ops.mirror { mirror =>
      mirror.ids.take(1).compile.toList.flatMap {
        case List(id) => mirror.getActive(id).map(res => (id, res.map(_.spec)))
        case _ => ???
      }
    })
    tick(ops.write(Resource.fixture))
    assert(f.value.get.get == ((Id.of(Resource.fixture), Some(Resource.fixture.spec))))
  }

  it("supports multiple concurrent ID consumers") {
    val ops = TestClient.unsafe().apply[Resource]
    val f = tick(ops.mirror { mirror =>
      Task.parZip2(
        mirror.ids.take(2).compile.toList,
        mirror.ids.take(2).compile.toList
      )
    })

    tick(ops.write(f1) >> ops.write(f2))

    val expectedList = List(Id.of(f1), Id.of(f2))
    assert(f.value.get.get == ((expectedList, expectedList)))
  }

  it("cannot skip updates during concurrent subscription") {
    // if we subscribe concurrently to an item's creation, then either:
    // we observe the creation as part of the initial set, or
    // emitting of the item is delayed until our subscriber is installed,
    // so we see it as an update.
    val ops = TestClient.unsafe().apply[Resource]
    val subscribe = ops.mirror { mirror =>
      mirror.ids.take(2).compile.toList
    }

    val f = tick(Task.parZip3(
      subscribe,
      ops.write(f1),
      ops.write(f2)
    ))

    assert(f.value.map(_.map(_._1.sorted)) == Some(Success(List(Id.of(f1)))))
  }
}
