package net.gfxmonk.foperator.internal

import java.util.concurrent.atomic.AtomicReference

import cats.effect.ExitCase
import monix.eval.Task
import monix.execution.ExecutionModel
import monix.execution.schedulers.TestScheduler
import net.gfxmonk.foperator.internal.ResourceLoop.ErrorCount
import net.gfxmonk.foperator.{ReconcileResult, Reconciler, ResourceState}

import scala.collection.mutable.ListBuffer
import scala.concurrent.duration._

class ResourceLoopTest extends org.scalatest.funspec.AnyFunSpec with Logging {
  implicit var scheduler = TestScheduler(ExecutionModel.AlwaysAsyncExecution)

  class Context(initial: String, delegate: (Int, ResourceState[String]) => Task[ReconcileResult]) {
    val jiffy = 0.1.second
    val halfReconcileDuration = 0.5.second
    val reconcileDuration = 1.second
    val refreshInterval = 5.seconds

    private val logBuffer = ListBuffer[String]()
    def log = logBuffer.toList
    def append(message: String) = logBuffer.append(message)

    private var iteration = 0

    val reconciler = new Reconciler[ResourceState[String]] {
      override def reconcile(resource: ResourceState[String]): Task[ReconcileResult] = {
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

    var resourceRef = new AtomicReference(Option(ResourceState.Active(initial)))

    val loop = new ResourceLoop(
      Task { resourceRef.get }.asyncBoundary,
      reconciler,
      Some(refreshInterval),
      permitScope,
      calculateBackoff,
      onError = t => Task(logger.error("loop failed", t))
    )
  }

  def defaultReconciler[T](iteration: Int, item: ResourceState[T]): Task[ReconcileResult] = Task.pure(ReconcileResult.Ok)

  def withContext(initial: String, delegate: (Int, ResourceState[String]) => Task[ReconcileResult] = defaultReconciler[String] _)(body: Context => Unit): Unit = {
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

  it("stops reconciling if item disappears") {
    withContext("initial") { ctx =>
      ctx.resourceRef.set(None)
      scheduler.tick((ctx.refreshInterval + ctx.reconcileDuration) * 10)
      assert(ctx.log == List("acquire", "release"))
    }
  }

  it("is cancelable") {
    withContext("initial") { ctx =>
      scheduler.tick(ctx.reconcileDuration/2)
      ctx.loop.cancel()
      scheduler.tick()
      assert(ctx.log == reconcileStart ++ List("cancel"))
      scheduler.tick(ctx.refreshInterval * 10)
      // nothing further should happen
      assert(ctx.log == reconcileStart ++ List("cancel"))
    }
  }

  it("backs off on failure") {
    def reconcile(iteration: Int, value: ResourceState[String]) = iteration match {
      case 1|2 => Task.raiseError(new RuntimeException(s"failed ${iteration}"))
      case 3 => Task.pure(ReconcileResult.Ok)
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
    it("causes a new reconcile to start after the current reconcile") {
      withContext("initial") { ctx =>
        scheduler.tick(ctx.halfReconcileDuration + ctx.jiffy)
        ctx.loop.update.runAsyncAndForget(scheduler)
        scheduler.tick()
        assert(ctx.log == reconcileStart)
        scheduler.tick(ctx.halfReconcileDuration + ctx.jiffy)
        assert(ctx.log == reconcileFull ++ reconcileStart)
      }
    }

    it("reconciles immediately if sleeping") {
      withContext("initial") { ctx =>
        scheduler.tick(ctx.reconcileDuration + ctx.jiffy)
        assert(ctx.log == reconcileFull)
        ctx.loop.update.runAsyncAndForget(scheduler)
        scheduler.tick()
        assert(ctx.log == reconcileFull ++ reconcileStart)
      }
    }
  }
}
