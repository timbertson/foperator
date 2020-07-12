package net.gfxmonk.foperator.internal

import cats.effect.ExitCase
import monix.eval.Task
import monix.execution.schedulers.TestScheduler
import net.gfxmonk.foperator.internal.Dispatcher.PermitScope
import net.gfxmonk.foperator.internal.ResourceLoop.ErrorCount
import net.gfxmonk.foperator.{ReconcileResult, Reconciler}

import scala.collection.mutable.ListBuffer
import scala.concurrent.duration._

class ResourceLoopTest extends org.scalatest.funspec.AnyFunSpec {
  implicit val scheduler = TestScheduler()

  class Context(initial: String, delegate: (Int, String) => Task[ReconcileResult[String]]) {
    val reconcileDuration = 1.second
    val refreshInterval = 5.seconds

    private val logBuffer = ListBuffer[String]()
    def log = logBuffer.toList
    def append(message: String) = logBuffer.append(message)

    private var iteration = 0

    val reconciler = new Reconciler[String] {
      override def reconcile(resource: String): Task[ReconcileResult[String]] = {
        for {
          _ <- Task(append("reconcile start"))
          _ <- Task.sleep(reconcileDuration)
          _ <- Task(iteration += 1)
          result <- delegate(iteration, resource)
        } yield {
          append("reconcile end")
          result
        }
      }.guaranteeCase {
        case ExitCase.Canceled => Task(append("cancel"))
        case ExitCase.Error(e) => Task(append(s"error(${e.getMessage})"))
        case ExitCase.Completed => Task.unit
      }
    }

    val permitScope = new PermitScope {
      override def withPermit[A](task: Task[A]): Task[A] = {
        for {
          _ <- Task(logBuffer.append("acquire"))
          result <- task
          _ <- Task(logBuffer.append("release"))
        } yield result
      }
    }

    val backoffTime = 2.seconds
    def calculateBackoff(errorCount: ErrorCount) = {
      logBuffer.append(s"backoffTime(${errorCount.value})")
      backoffTime
    }

    val loop = new ResourceLoop(Task.pure(Some(initial)), reconciler, refreshInterval, permitScope, calculateBackoff)
  }

  def defaultReconciler[T](iteration: Int, item: T): Task[ReconcileResult[T]] = Task.pure(ReconcileResult.Continue)

  def withContext(initial: String, delegate: (Int, String) => Task[ReconcileResult[String]] = defaultReconciler[String] _)(body: Context => Unit): Unit = {
    val ctx = new Context(initial, delegate)
    try {
      body(ctx)
    } finally {
      ctx.loop.cancel()
    }
  }

  val reconcileStart = List("acquire", "reconcile start")
  val reconcileEnd = List("reconcile end", "release")
  val reconcileFull = reconcileStart ++ reconcileEnd

  it("reconciles periodically") {
    withContext("initial") { ctx =>
      scheduler.tick(ctx.reconcileDuration)
      assert(ctx.log == reconcileFull)
      scheduler.tick(ctx.refreshInterval + (ctx.reconcileDuration/2))
      assert(ctx.log == reconcileFull ++ reconcileStart)
    }
  }

  it("skips reconcile when the item has diasappeared") {
    // TODO set to None
  }

  it("is cancelable") {
    withContext("initial") { ctx =>
      scheduler.tick(ctx.reconcileDuration/2)
      ctx.loop.cancel()
      assert(ctx.log == reconcileStart ++ List("cancel"))
      scheduler.tick(ctx.refreshInterval * 10)
      // nothing further should happen
      assert(ctx.log == reconcileStart ++ List("cancel"))
    }
  }

  it("backs off on failure") {
    def reconcile(iteration: Int, value: String) = iteration match {
      case 1|2 => Task.raiseError(new RuntimeException(s"failed ${iteration}"))
      case 3 => Task.pure(ReconcileResult.Continue)
      case 4 => Task.raiseError(new RuntimeException(s"failed ${iteration}"))
    }
    withContext("initial", delegate = reconcile) { ctx =>
      scheduler.tick((ctx.reconcileDuration * 4) + (ctx.backoffTime * 2) + (ctx.refreshInterval))
      assert(ctx.log ==
        reconcileStart ++ List("error(failed 1)", "release", "backoffTime(1)") ++
        reconcileStart ++ List("error(failed 2)", "release", "backoffTime(2)") ++
        reconcileFull ++
        reconcileStart ++ List("error(failed 4)", "release", "backoffTime(1)")
      )
    }
  }

  describe("updating") {
    it("reconciles immediately after a current reconcile") {}
    it("reconciles immediately if waiting") {}
  }
}
