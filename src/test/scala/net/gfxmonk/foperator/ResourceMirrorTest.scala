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
  import implicits._

  it("aborts the `use` block on error") {
    val testScheduler = TestScheduler(ExecutionModel.AlwaysAsyncExecution)
    val driver = FoperatorDriver(testScheduler)
    implicit val ctx = driver.context
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

  it("is populated with the initial resource set") {}
  it("emits IDs for updates") {}
  it("updates internal state before emitting an ID") {}
  it("supports multiple ID consumers") {}

  it("cannot skip updates during concurrent subscription") {
    // if we subscribe concurrently to an item's creation, then either:
    // we observe the creation as part of the initial set, or
    // emitting of the item is delayed until our subscriber is installed,
    // so we see it as an update.
  }
}
