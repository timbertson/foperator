package net.gfxmonk.foperator.sample

import monix.catnap.MVar
import monix.eval.Task
import monix.execution.Scheduler
import monix.execution.schedulers.{CanBlock, TestScheduler}
import net.gfxmonk.foperator.internal.Logging
import org.scalatest.funspec.AnyFunSpec

import scala.util.Success

class RefTest extends AnyFunSpec with Logging {
  it("works") {
    val realScheduler = Scheduler.global
    val ref = MVar[Task].of[Int](1).runSyncUnsafe()(realScheduler, implicitly[CanBlock])

    val testScheduler = TestScheduler()
    val main = {
      for {
        initial <- ref.take
        _ <- ref.put(initial+1)
        result <- ref.read
      } yield result
    }
    val f = main.runToFuture(testScheduler)
    testScheduler.tick()
    println(f.value)
    assert(f.value == Some(Success(1)))
  }
}
