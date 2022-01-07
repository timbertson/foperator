package foperator.testkit

import monix.eval.Task
import monix.execution.schedulers.TestScheduler

import java.util.concurrent._
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}

// A heavy-handed workaround for https://github.com/monix/monix/issues/1527
object TestSchedulerUtil {
  private val _isTickThread = new ThreadLocal[Boolean]

  private lazy val ec: ExecutionContext = {
    val exec = Executors.newSingleThreadExecutor(new ThreadFactory() {
      override def newThread(r: Runnable): Thread = {
        val t = Executors.defaultThreadFactory.newThread(r)
        t.setName("SchedulerTickThread")
        t.setDaemon(true)
        t
      }
    })
    val ec = ExecutionContext.fromExecutor(exec)
    // don't return ec until we've set the threadlocal bool to true
    Await.result(Future { _isTickThread.set(true) }(ec), 1.second)
    ec
  }

  /**
   * drive a task to completion by repeatedly invoking tick() in parallel.
   * The task should be some reference to work executing on the test scheduler,
   * either `fiber.join` or `deferred.get`.
   */
  def await[T](testScheduler: TestScheduler, get: Task[T], time: FiniteDuration = Duration.Zero): Task[T] =
    Task.race(get, tick(testScheduler, time).restartUntil(_ => false)).flatMap {
      case Left(result) => Task.pure(result)
      case Right(_) => Task.raiseError(new RuntimeException("tick() branch completed; this is impossible"))
    }

  def run[T](testScheduler: TestScheduler, t: Task[T]): Task[T] = {
    t.executeOn(testScheduler).start.flatMap { fiber =>
      await(testScheduler, fiber.join)
    }
  }

  def tick(scheduler: TestScheduler, time: FiniteDuration = Duration.Zero): Task[Unit] = {
    Task.deferFuture {
      if (_isTickThread.get()) {
        Future.failed(new RuntimeException("tick() invoked from within the tick thread"))
      } else {
        Future(scheduler.tick(time))(ec)
      }
    }
  }
}
