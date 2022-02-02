package foperator.internal

import cats.Eq
import cats.effect.concurrent.{Deferred, MVar, MVar2}
import cats.effect.{ExitCase, Fiber}
import cats.implicits._
import foperator.fixture.{Resource, ResourceSpec, ResourceStatus, resource}
import foperator.internal.Dispatcher.StateMap
import foperator.testkit.{TestClient, TestSchedulerUtil}
import foperator._
import fs2.Stream
import monix.eval.Task
import monix.execution.schedulers.TestScheduler
import monix.execution.{ExecutionModel, Scheduler}
import net.gfxmonk.auditspec.Audit

import scala.concurrent.duration._
import scala.util.Failure

object DispatcherTest extends SimpleTimedTaskSuite with Logging {

  val r1 = resource("id1")
  val r2 = resource("id2")
  val r3 = resource("id3")
  val r4 = resource("id4")

  val reconcileLoop = List(ReconcileStart(r1.name), ReconcileEnd(r1.name))
  val failedReconcileLoop = List(ReconcileStart(r1.name), ReconcileFailed(r1.name))

  timedTest("reconciles existing objects on startup") {
    for {
      ctx <- spawn(doTick = false)
      // this write can occur off the test scheduler, since
      // we haven't ticked (i.e. the client hasn't started yet)
      _ <- ctx.client.apply[Resource].write(r1)
      _ <- ctx.expectAudit(log => expect(log === List(Updated(r1.name))))
      _ <- ctx.tick()
      log <- ctx.audit.get
    } yield {
      expect(log === reconcileLoop)
    }
  }

  timedTest("runs the reconciler for new objects") {
    for {
      ctx <- spawn()
      // make sure we didn't reconcile anything yet
      _ <- ctx.expectAudit(log => expect(log === Nil))
      _ <- ctx.write(r1)
      _ <- ctx.tick()
      log <- ctx.audit.get
    } yield {
      expect(log === List(Updated(r1.name)) ++ reconcileLoop)
    }
  }

  timedTest("immediately reconciles upon update") {
    for {
      ctx <- spawn(opts = ReconcileOptions(refreshInterval = Some(10.minutes)))
      _ <- ctx.write(r1)
      _ <- ctx.tick(1.second)
      _ <- ctx.write(r1.copy(status = Some(ResourceStatus(1))))
      _ <- ctx.tick(1.second)
      log <- ctx.audit.get
    } yield {
      expect(log === List(
        Updated(r1.name),
        ReconcileStart(r1.name),
        ReconcileEnd(r1.name),
        // reconcile is 10m, so this must be due to immediate update
        Updated(r1.name),
        ReconcileStart(r1.name),
        ReconcileEnd(r1.name),
      ))
    }
  }

  timedTest("schedules a periodic update") {
    for {
      ctx <- spawn(opts = ReconcileOptions(refreshInterval = Some(10.minutes)))
      _ <- ctx.write(r1)

      // immediate reconcile
      _ <- ctx.tick()
      _ <- ctx.expectAudit(l => expect(l === List(Updated(r1.name)) ++ reconcileLoop))

      // nothing for next 9 minutes
      _ <- ctx.tick(9.minutes)
      _ <- ctx.expectAudit(l => expect(l === Nil))

      // reconcile again at 10 minutes
      _ <- ctx.tick(2.minutes)
      _ <- ctx.expectAudit(l => expect(l === reconcileLoop))

      // ...and again
      _ <- ctx.tick(10.minutes)
      _ <- ctx.expectAudit(l => expect(l === reconcileLoop))
    } yield success
  }

  timedTest("reschedules (with backoff) on error") {
    for {
      ctx <- spawn(
        opts = ReconcileOptions(refreshInterval = None, errorDelay = { n => (n * n).seconds }),
        reconcile = _ => Task.raiseError(new RuntimeException("reconcile failed!"))
      )
      _ <- ctx.write(r1)

      // immediate reconcile
      _ <- ctx.tick()
      _ <- ctx.expectAudit(l => expect(l === List(Updated(r1.name)) ++ failedReconcileLoop))

      // retry in 1s
      _ <- ctx.tick(1.second)
      _ <- ctx.expectAudit(l => expect(l === failedReconcileLoop))

      // second failure, in 4s
      _ <- ctx.tick(4.second)
      _ <- ctx.expectAudit(l => expect(l === failedReconcileLoop))

      // 3rd in 9
      _ <- ctx.tick(9.second)
      _ <- ctx.expectAudit(l => expect(l === failedReconcileLoop))

      // 3rd in 16
      _ <- ctx.tick(16.second)
      _ <- ctx.expectAudit(l => expect(l === failedReconcileLoop))
    } yield success
  }

  timedTest("reschedules based on explicit retry indication") {
    for {
      ctx <- spawn(
        opts = ReconcileOptions(refreshInterval = None),
        reconcile = _ => Task.pure(ReconcileResult.RetryAfter(1.second))
      )
      _ <- ctx.write(r1)

      // immediate reconcile
      _ <- ctx.tick()
      _ <- ctx.expectAudit(l => expect(l === List(Updated(r1.name)) ++ reconcileLoop))

      // retry in 1s
      _ <- ctx.tick(1.second)
      _ <- ctx.expectAudit(l => expect(l === reconcileLoop))
    } yield success
  }

  timedTest("schedules a follow-on reconcile if an update arrives during a reconcile") {
    for {
      ctx <- spawn(
        reconcile = _ => Task.sleep(10.seconds).as(ReconcileResult.Ok)
      )
      _ <- ctx.write(r1)

      // immediate reconcile
      _ <- ctx.tick()
      _ <- ctx.expectAudit(l => expect(l === List(Updated(r1.name), ReconcileStart(r1.name))))

      // update occurs after 5s
      _ <- ctx.writeAfter(5.seconds, r1.copy(spec = ResourceSpec("updated")))
      _ <- ctx.tick(5.seconds)
      _ <- ctx.expectAudit(l => expect(l === List(Updated(r1.name))))

      // after another 5s, the first reconcile is done and we immediately start a new one
      _ <- ctx.tick(5.seconds)
      _ <- ctx.expectAudit(l => expect(l === List(ReconcileEnd(r1.name), ReconcileStart(r1.name))))
    } yield success
  }

  timedTest("skips intermediate updates while busy") {
    // takes 10s to reconcile, updates at 0, 2, 4, 6, 8s. two reconciles of states 0, n
    var logResource: Resource => Task[Unit] = _ => Task.unit
    for {
      ctx <- spawn(doTick = false, reconcile = { res =>
        logResource(res) >> Task.sleep(10.seconds).as(ReconcileResult.Ok)
      })
      _ <- Task { logResource = (r: Resource) => ctx.audit.record(Spec(r.spec)) }
      _ <- ctx.tick()
      _ <- ctx.write(r1)

      // immediate reconcile
      _ <- ctx.tick()
      _ <- ctx.expectAudit(l => expect(l === List(Updated(r1.name), ReconcileStart(r1.name), Spec(r1.spec))))

      // updates occur at 2, 4, 6 seconds
      _ <- ctx.writeAfter(2.seconds, r1.copy(spec = ResourceSpec("update1")))
      _ <- ctx.writeAfter(4.seconds, r1.copy(spec = ResourceSpec("update2")))
      _ <- ctx.writeAfter(6.seconds, r1.copy(spec = ResourceSpec("update3")))
      _ <- ctx.tick(10.seconds)
      _ <- ctx.expectAudit(l => expect(l === List(
        Updated(r1.name),
        Updated(r1.name),
        Updated(r1.name),
        ReconcileEnd(r1.name),
        ReconcileStart(r1.name),
        Spec(ResourceSpec("update3"))
      )))
    } yield success
  }

  timedTest("limits the number of active reconciles") {
    // 10s to reconcile, 2 limit, update 4 and check sequencing
    def countTypes(log: List[Interaction]) =
      log.groupBy(_.getClass).view.mapValues(_.size).toMap

    for {
      ctx <- spawn(
        reconcile = { _ => Task.sleep(10.seconds).as(ReconcileResult.Ok) },
        opts = ReconcileOptions(concurrency = 2)
      )
      _ <- List(r1, r2, r3, r4).traverse_(ctx.write)

      // immediate reconcile picks up two
      _ <- ctx.tick()
      _ <- ctx.expectAudit { l =>
        expect(countTypes(l) === Map(
          classOf[Updated] -> 4,
          classOf[ReconcileStart] -> 2,
        ))
      }

      _ <- ctx.tick(10.seconds)
      _ <- ctx.expectAudit { l =>
        expect(countTypes(l) === Map(
          classOf[ReconcileEnd] -> 2,
          classOf[ReconcileStart] -> 2,
        ))
      }

      // no further reconciles started
      _ <- ctx.tick(10.seconds)
      _ <- ctx.expectAudit { l =>
        expect(countTypes(l) === Map(
          classOf[ReconcileEnd] -> 2,
        ))
      }
    } yield success
  }

  timedTest("aborts the primary task when any reconcile loop fails") {
    val input = Stream.repeatEval(Task.unit)
    val error = new RuntimeException("internal error in reconcile loop")
    val loop = new ReconcileLoop[Task, Unit] {
      override def markDirty: Dispatcher.State[Task] => Task[Dispatcher.State[Task]] = Task.pure
      override def run(k: Unit): Task[Unit] = Task.raiseError(error)
    }

    for {
      state <- MVar[Task].of(Map.empty: StateMap[Task, Unit])
      errorDeferred <- Deferred[Task, Throwable]
      result <- Dispatcher.main(state, errorDeferred, loop, input).materialize
    } yield {
      expect(result == Failure(error))
    }
  }

  timedTest("removes the running loop if the resource is deleted") {
    for {
      state <- MVar[Task].of(Map.empty: StateMap[Task, Id[Resource]])
      ctx <- spawn(
        reconcile = _ => Task.sleep(1.second).as(ReconcileResult.Ok),
        state = Some(state)
      )
      _ <- ctx.write(r1)
      _ <- ctx.tick()
      _ <- ctx.expectAudit { l =>
        expect(l === List(Updated(r1.name), ReconcileStart(r1.name)))
      }
      _ <- state.read.flatMap(s => expect(s.size === 1).failFast)

      _ <- ctx.delete(r1.id)
      _ <- ctx.tick(1.second)
      _ <- ctx.expectAudit { l =>
        expect(l === List(Deleted(r1.name), ReconcileEnd(r1.name)))
      }
      _ <- state.read.flatMap(s => expect(s.size === 0).failFast)
    } yield success
  }


  // test helpers

  sealed trait Interaction
  case class ReconcileStart(name: String) extends Interaction
  case class Updated(name: String) extends Interaction
  case class Deleted(name: String) extends Interaction
  case class ReconcileEnd(name: String) extends Interaction
  case class ReconcileCanceled(name: String) extends Interaction
  case class ReconcileFailed(name: String) extends Interaction
  case class Spec(value: ResourceSpec) extends Interaction

  implicit val eq: Eq[Interaction] = Eq.fromUniversalEquals[Interaction]
  implicit val ord: Ordering[Interaction] = new Ordering[Interaction] {
    val s = implicitly[Ordering[String]]
    override def compare(a: Interaction, b: Interaction): Int = (a, b) match {
      case (Updated(a), Updated(b)) => s.compare(a, b)
      case (Deleted(a), Deleted(b)) => s.compare(a, b)
      case (ReconcileEnd(a), ReconcileEnd(b)) => s.compare(a, b)
      case (ReconcileCanceled(a), ReconcileCanceled(b)) => s.compare(a, b)
      case (ReconcileFailed(a), ReconcileFailed(b)) => s.compare(a, b)
      case (Spec(a), Spec(b)) => s.compare(a.title, b.title)
      case _ => s.compare(a.getClass.getName, b.getClass.getName)
    }
  }

  class Ctx(
    val testScheduler: TestScheduler,
    val client: TestClient[Task],
    val audit: Audit[Interaction],
    val fiber: Fiber[Task, Unit])
  {
    // The use of schedulers in these tests is somewhat fragile.
    // The tests themselves are async, but we want all foperator interactions
    // to occur on the test scheduler. This includes writes initiated from
    // the test body.

    private val ops = client.apply[Resource]

    def write(r: Resource) = spawn(ops.forceWrite(r))

    def delete(r: Id[Resource]) = spawn(ops.delete(r))

    def writeAfter(d: FiniteDuration, r: Resource) = spawn(Task.sleep(d) >> ops.forceWrite(r))

    def tick(time: FiniteDuration = Duration.Zero) = for {
      _ <- Task(logger.debug("> ticking {} ({})", time, testScheduler.state.tasks.size))
      _ <- TestSchedulerUtil.tick(testScheduler, time)
      _ <- Task(logger.debug("< tick ({})", testScheduler.state.tasks.size))
    } yield ()

    def expectAudit(fn: List[Interaction] => weaver.Expectations): Task[Unit] = {
      audit.reset.map(fn).flatMap(_.failFast)
    }

    private def spawn[T](task: Task[T]): Task[Unit] = Task {
      task.executeOn(testScheduler).onErrorHandle { err =>
        logger.error("Spawned task failed", err)
      }.runAsyncAndForget(Scheduler.global)
    }
  }

  def defaultReconcile(@annotation.unused _res: Resource): Task[ReconcileResult] = {
    Task.pure(ReconcileResult.Ok)
  }

  def spawn(
    reconcile: Resource => Task[ReconcileResult] = defaultReconcile,
    opts: ReconcileOptions = ReconcileOptions(),
    state: Option[MVar2[Task, Dispatcher.StateMap[Task, Id[Resource]]]] = None,
    doTick: Boolean = true,
  ): Task[Ctx] = {
    def wrapReconciler(audit: Audit[Interaction], fn: Resource => Task[ReconcileResult])(@annotation.unused _client: TestClient[Task], res: ResourceState[Resource]) = res match {
      case ResourceState.Active(v) => {
        audit.record(ReconcileStart(v.name)) >> fn(v).guaranteeCase {
          case ExitCase.Canceled => audit.record(ReconcileCanceled(v.name))
          case ExitCase.Completed => audit.record(ReconcileEnd(v.name))
          case ExitCase.Error(_) => audit.record(ReconcileFailed(v.name))
        }
      }
      case ResourceState.SoftDeleted(_) => Task.pure(ReconcileResult.Ok)
    }

    val testScheduler = TestScheduler(ExecutionModel.AlwaysAsyncExecution)
    for {
      audit <- Audit[Interaction]
      client <- TestClient[Task].map(_.withAudit[Resource] {
        case Event.Updated(r) => audit.record(Updated(r.name))
        case Event.Deleted(r) => audit.record(Deleted(r.name))
      })
      fiber <- client.apply[Resource].mirror { mirror =>
        Dispatcher
          .resource(client, mirror, wrapReconciler(audit, reconcile), opts, stateOverride = state)
          .use(identity)
      }.executeOn(testScheduler).start
      ctx = new Ctx(testScheduler, client, audit, fiber)
      _ <- if (doTick) ctx.tick() else Task.unit
    } yield ctx
  }
}
