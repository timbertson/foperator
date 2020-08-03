package net.gfxmonk.foperator

import monix.eval.Task
import monix.execution.ExecutionModel
import monix.execution.schedulers.TestScheduler
import net.gfxmonk.foperator.fixture.Resource
import net.gfxmonk.foperator.testkit.FoperatorDriver
import org.scalatest.funspec.AnyFunSpec

import scala.util.{Failure, Success}

class ResourceMirrorTest extends AnyFunSpec {
  import fixture.Implicits._
  import testkit.implicits._

  val testScheduler = TestScheduler(ExecutionModel.AlwaysAsyncExecution)

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
    pending
  }
  it("updates internal state before emitting an ID") {
    pending
  }
  it("supports multiple ID consumers") {
    pending
  }

  it("cannot skip updates during concurrent subscription") {
    // if we subscribe concurrently to an item's creation, then either:
    // we observe the creation as part of the initial set, or
    // emitting of the item is delayed until our subscriber is installed,
    // so we see it as an update.
    pending
  }
}
