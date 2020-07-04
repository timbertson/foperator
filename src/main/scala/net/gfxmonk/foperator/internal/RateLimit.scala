package net.gfxmonk.foperator.internal

import net.gfxmonk.foperator.RateLimitConfig
import monix.eval.Task
import monix.execution.atomic.Atomic
import monix.execution.{Cancelable, Scheduler}
import monix.reactive.Observable

import scala.collection.immutable.Queue
import scala.concurrent.Promise

// implements a "token bucket" rate limiter
object RateLimit {
  case class State(capacity: Long, waiting: Queue[Promise[Unit]]) {
    def take: (Task[Unit], State) = {
      if (capacity > 0) {
        (Task.unit, State(capacity-1, waiting))
      } else {
        val p = Promise[Unit]()
        (Task.fromFuture(p.future), State(capacity, waiting.enqueue(p)))
      }
    }

    def tryRelease(maxCapacity: Long): (Option[Promise[Unit]], State) = {
      if (capacity == maxCapacity) {
        (None, this)
      } else {
        waiting.dequeueOption match {
          case None => (None, State(capacity+1, waiting))
          case Some((head, tail)) => (Some(head), State(capacity, tail))
        }
      }
    }
  }

  def initialState(capacity: Long) = State(capacity, Queue.empty)
}

class RateLimit(config: RateLimitConfig)(implicit scheduler: Scheduler) extends Cancelable {
  private val state = Atomic(RateLimit.initialState(config.capacity))
  private val thread = Observable.intervalWithFixedDelay(config.interval)
    .mapEval(_ => release)
    .completedL
    .runToFuture

  def take: Task[Unit] = Task {
    val task = state.transformAndExtract(_.take)
    if(!task.eq(Task.unit)) {
      println("rateLimit: block")
    }
  }

  override def cancel(): Unit = thread.cancel()

  private def release: Task[Unit] = Task {
    state.transformAndExtract(_.tryRelease(config.capacity)).foreach { promise =>
      println("rateLimit: release")
      promise.success(())
    }
  }
}
