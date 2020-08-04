package net.gfxmonk.foperator

import monix.eval.Task
import monix.execution.ExecutionModel
import monix.execution.schedulers.TestScheduler
import net.gfxmonk.foperator.fixture.Resource
import net.gfxmonk.foperator.testkit.FoperatorDriver
import org.scalatest.funspec.AnyFunSpec

import scala.util.Failure

class ResourceMirrorTest extends AnyFunSpec {
  import fixture.Implicits._
  import testkit.implicits._

  val testScheduler = TestScheduler(ExecutionModel.AlwaysAsyncExecution)
  val f1 = Resource.fixture.withName("f1")
  val f2 = Resource.fixture.withName("f2")

  it("aborts the `use` block on error") {
    implicit val driver = FoperatorDriver(testScheduler)
    val error = new RuntimeException("injected error from test")

    var cancelled = false
    val f = ResourceMirror.all[Resource].use { mirror =>
      Task.never.doOnCancel(Task {
        cancelled = true
      })
    }.runToFuture(testScheduler)

    // Ensure we're subscribed
    testScheduler.tick()

    // Then trigger an error, and our future should fail:
    driver.subject[Resource].onError(error)
    testScheduler.tick()
    assert(f.value == Some(Failure(error)))
  }

  it("is populated with the initial resource set") {
    implicit val driver = FoperatorDriver(testScheduler)
    driver.client.create(Resource.fixture)
    testScheduler.tick()

    val f = ResourceMirror.all[Resource].use { mirror =>
      mirror.active.map(_.values.toList)
    }.runToFuture(testScheduler)

    testScheduler.tick()
    assert(f.value.get.get.map(_.spec) == List(Resource.fixture.spec))
  }

  it("emits IDs for updates") {
    implicit val driver = FoperatorDriver(testScheduler)
    driver.client.create(Resource.fixture.withName("name1"))
    testScheduler.tick()

    val f = ResourceMirror.all[Resource].use { mirror =>
      testScheduler.tick()
      mirror.ids.take(4).toListL
    }.runToFuture(testScheduler)

    testScheduler.tick()
    driver.client.create(Resource.fixture.withName("name2"))
    testScheduler.tick()
    driver.client.create(Resource.fixture.withName("name3"))
    testScheduler.tick()
    driver.client.delete("name3")
    testScheduler.tick()

    def id(name: String) = Id.createUnsafe("default", name)
    assert(f.value.get.get == List(
      Event.Updated(id("name1")),
      Event.Updated(id("name2")),
      Event.Updated(id("name3")),
      Event.HardDeleted(id("name3")),
    ))
  }

  it("updates internal state before emitting an ID") {
    implicit val driver = FoperatorDriver(testScheduler)
    val f = ResourceMirror.all[Resource].use { mirror =>
      mirror.ids.headL.flatMap {
        case Event.Updated(id) =>
          mirror.getActive(id).map(res => (id, res.map(_.spec)))
        case _ => ???
      }
    }.runToFuture(testScheduler)


    driver.client.create(Resource.fixture)
    testScheduler.tick()
    assert(f.value.get.get == (Id.of(Resource.fixture), Some(Resource.fixture.spec)))
  }

  it("supports multiple concurrent ID consumers") {
    implicit val driver = FoperatorDriver(testScheduler)
    val f = ResourceMirror.all[Resource].use { mirror =>
      Task.parZip2(
        mirror.ids.take(2).toListL,
        mirror.ids.take(2).toListL
      )
    }.runToFuture(testScheduler)
    testScheduler.tick()

    driver.client.create(f1)
    testScheduler.tick()
    driver.client.create(f2)
    testScheduler.tick()

    val expectedList = List(Event.Updated(Id.of(f1)), Event.Updated(Id.of(f2)))
    assert(f.value.get.get == (expectedList, expectedList))
  }

  it("cannot skip updates during concurrent subscription") {
    // if we subscribe concurrently to an item's creation, then either:
    // we observe the creation as part of the initial set, or
    // emitting of the item is delayed until our subscriber is installed,
    // so we see it as an update.
    implicit val driver = FoperatorDriver(testScheduler)
    val subscribe = Task.deferFuture(ResourceMirror.all[Resource].use { mirror =>
      mirror.ids.headL
    }.runToFuture(testScheduler)).asyncBoundary(testScheduler)

    val emit = Task.deferFuture(driver.client.create(f1)).asyncBoundary(testScheduler)

    val f = Task.parZip2(subscribe, emit).runToFuture

    testScheduler.tick()

    assert(f.value.get.get._1 == Event.Updated(Id.of(f1)))
  }
}
