package foperator.internal

import cats.Eq
import cats.effect.kernel.Outcome
import cats.effect.testkit.TestControl
import cats.effect.{Deferred, IO}
import cats.implicits._
import foperator._
import foperator.fixture.{Resource, ResourceSpec, ResourceStatus, resource}
import foperator.internal.Dispatcher.StateMap
import foperator.testkit.TestClient
import fs2.Stream
import net.gfxmonk.auditspec.Audit
import weaver.Expectations

import scala.concurrent.duration._

object DispatcherTest extends SimpleTimedIOSuite with Logging {

  val r1 = resource("id1")
  val r2 = resource("id2")
  val r3 = resource("id3")
  val r4 = resource("id4")

  val reconcileLoop = List(ReconcileStart(r1.name), ReconcileEnd(r1.name))
  val failedReconcileLoop = List(ReconcileStart(r1.name), ReconcileFailed(r1.name))

  test("reconciles existing objects on startup") {
    execute() { ctx =>
      for {
        _ <- ctx.write(r1)
        _ <- ctx.expectAudit(log => expect(log === List(Updated(r1.name))))
        _ <- ctx.tick
        log <- ctx.audit.get
      } yield {
        expect(log === reconcileLoop)
      }
    }
  }

  test("runs the reconciler for new objects") {
    execute() { ctx =>
      for {
        // make sure we didn't reconcile anything yet
        _ <- ctx.expectAudit(log => expect(log === Nil))
        _ <- ctx.write(r1)
        _ <- ctx.tick
        log <- ctx.audit.get
      } yield {
        expect(log === List(Updated(r1.name)) ++ reconcileLoop)
      }
    }
  }

  test("immediately reconciles upon update") {
    execute(opts = ReconcileOptions(refreshInterval = Some(10.minutes))) { ctx =>
      for {
        _ <- ctx.write(r1)
        _ <- ctx.tick
        _ <- ctx.write(r1.copy(status = Some(ResourceStatus(1))))
        _ <- ctx.tick
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
  }

  test("schedules a periodic update") {
    execute(opts = ReconcileOptions(refreshInterval = Some(10.minutes))) { ctx =>
      for {
        _ <- ctx.write(r1)

        // immediate reconcile
        _ <- ctx.tick
        _ <- ctx.expectAudit(l => expect(l === List(Updated(r1.name)) ++ reconcileLoop))

        // nothing for next 9 minutes
        _ <- IO.sleep(9.minutes)
        _ <- ctx.expectAudit(l => expect(l === Nil))

        // reconcile again at 10 minutes
        _ <- IO.sleep(2.minutes)
        _ <- ctx.expectAudit(l => expect(l === reconcileLoop))

        // ...and again
        _ <- IO.sleep(10.minutes)
        _ <- ctx.expectAudit(l => expect(l === reconcileLoop))
      } yield success
    }
  }

  test("reschedules (with backoff) on error") {
    execute(
      opts = ReconcileOptions(refreshInterval = None, errorDelay = { n => (n * n).seconds }),
      reconcile = _ => IO.raiseError(new RuntimeException("reconcile failed!"))
    ) { ctx =>
      for {
        _ <- ctx.write(r1)

        // immediate reconcile
        _ <- ctx.tick
        _ <- ctx.expectAudit(l => expect(l === List(Updated(r1.name)) ++ failedReconcileLoop))

        // retry in 1s
        _ <- IO.sleep(1.second)
        _ <- ctx.expectAudit(l => expect(l === failedReconcileLoop))

        // second failure, in 4s
        _ <- IO.sleep(4.second)
        _ <- ctx.expectAudit(l => expect(l === failedReconcileLoop))

        // 3rd in 9
        _ <- IO.sleep(9.second)
        _ <- ctx.expectAudit(l => expect(l === failedReconcileLoop))

        // 3rd in 16
        _ <- IO.sleep(16.second)
        _ <- ctx.expectAudit(l => expect(l === failedReconcileLoop))
      } yield success
    }
  }

  timedTest("reschedules based on explicit retry indication") {
    execute(
      opts = ReconcileOptions(refreshInterval = None),
      reconcile = _ => IO.pure(ReconcileResult.RetryAfter(1.second))
    ) { ctx =>
      for {
        _ <- ctx.write(r1)

        // immediate reconcile
        _ <- ctx.tick
        _ <- ctx.expectAudit(l => expect(l === List(Updated(r1.name)) ++ reconcileLoop))

        // retry in 1s
        _ <- IO.sleep(1.second)
        _ <- ctx.expectAudit(l => expect(l === reconcileLoop))
      } yield success
    }
  }

  timedTest("schedules a follow-on reconcile if an update arrives during a reconcile") {
    execute(
      reconcile = _ => IO.sleep(10.seconds).as(ReconcileResult.Ok)
    ) { ctx =>
        for {
        _ <- ctx.write(r1)

        // immediate reconcile
        _ <- ctx.tick
        _ <- ctx.expectAudit(l => expect(l === List(Updated(r1.name), ReconcileStart(r1.name))))

        // update occurs after 5s
        _ <- IO.sleep(5.seconds)
        _ <- ctx.write(r1.copy(spec = ResourceSpec("updated")))
        _ <- ctx.expectAudit(l => expect(l === List(Updated(r1.name))))

        // after another 5s, the first reconcile is done and we immediately start a new one
        _ <- IO.sleep(5.seconds)
        _ <- ctx.expectAudit(l => expect(l === List(ReconcileEnd(r1.name), ReconcileStart(r1.name))))
      } yield success
    }
  }

  timedTest("skips intermediate updates while busy") {
    // takes 10s to reconcile, updates at 0, 2, 4, 6, 8s. two reconciles of states 0, n
    var logResource: Resource => IO[Unit] = _ => IO.unit
    execute(reconcile = { res =>
      logResource(res) >> IO.sleep(10.seconds).as(ReconcileResult.Ok)
    }) { ctx =>
      for {
        _ <- IO { logResource = (r: Resource) => ctx.audit.record(Spec(r.spec)) }
        _ <- ctx.write(r1)

        // immediate reconcile
        _ <- ctx.tick
        _ <- ctx.expectAudit(l => expect(l === List(Updated(r1.name), ReconcileStart(r1.name), Spec(r1.spec))))

        // updates occur at 2, 4, 6 seconds
        _ <- List(
          ctx.write(r1.copy(spec = ResourceSpec("update1"))).delayBy(2.seconds),
          ctx.write(r1.copy(spec = ResourceSpec("update2"))).delayBy(4.seconds),
          ctx.write(r1.copy(spec = ResourceSpec("update3"))).delayBy(6.seconds),
        ).parSequence_
        _ <- IO.sleep(10.seconds)
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
  }

  timedTest("limits the number of active reconciles") {
    // 10s to reconcile, 2 limit, update 4 and check sequencing
    def countTypes(log: List[Interaction]) =
      log.groupBy(_.getClass).view.mapValues(_.size).toMap

    execute(
      reconcile = { _ => IO.sleep(10.seconds).as(ReconcileResult.Ok) },
      opts = ReconcileOptions(concurrency = 2)
    ) { ctx =>
      for {
        _ <- List(r1, r2, r3, r4).traverse_(ctx.write)

        // immediate reconcile picks up two
        _ <- ctx.tick
        _ <- ctx.expectAudit { l =>
          expect(countTypes(l) === Map(
            classOf[Updated] -> 4,
            classOf[ReconcileStart] -> 2,
          ))
        }

        _ <- IO.sleep(10.seconds)
        _ <- ctx.expectAudit { l =>
          expect(countTypes(l) === Map(
            classOf[ReconcileEnd] -> 2,
            classOf[ReconcileStart] -> 2,
          ))
        }

        // no further reconciles started
        _ <- IO.sleep(10.seconds)
        _ <- ctx.expectAudit { l =>
          expect(countTypes(l) === Map(
            classOf[ReconcileEnd] -> 2,
          ))
        }
      } yield success
    }
  }

  timedTest("aborts the primary task when any reconcile loop fails") {
    val input = Stream.repeatEval(IO.unit)
    val error = new RuntimeException("internal error in reconcile loop")
    val loop = new ReconcileLoop[IO, Unit] {
      override def markDirty: Dispatcher.State[IO] => IO[Dispatcher.State[IO]] = IO.pure
      override def run(k: Unit): IO[Unit] = IO.raiseError(error)
    }

    for {
      state <- IORef[IO].of(Map.empty: StateMap[IO, Unit])
      errorDeferred <- Deferred[IO, Throwable]
      result <- Dispatcher.main(state, errorDeferred, loop, input).attempt
    } yield {
      expect(result == Left(error))
    }
  }

  timedTest("removes the running loop if the resource is deleted") {
    IORef[IO].of(Map.empty: StateMap[IO, Id[Resource]]).flatMap { state =>
      execute(
        reconcile = _ => IO.sleep(1.second).as(ReconcileResult.Ok),
        state = Some(state)
      ) { ctx =>
        for {
          _ <- ctx.write(r1)
          _ <- ctx.tick
          _ <- ctx.expectAudit { l =>
            expect(l === List(Updated(r1.name), ReconcileStart(r1.name)))
          }
          _ <- state.readLast.flatMap(s => expect(s.size === 1).failFast)

          _ <- ctx.delete(r1.id)
          _ <- IO.sleep(1.second)
          _ <- ctx.expectAudit { l =>
            expect(l === List(Deleted(r1.name), ReconcileEnd(r1.name)))
          }
          _ <- state.readLast.flatMap(s => expect(s.size === 0).failFast)
        } yield success
      }
    }
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
    val client: TestClient[IO],
    val audit: Audit[IO, Interaction])
  {
    private val ops = client.apply[Resource]

    def write(r: Resource) = ops.forceWrite(r)

    def delete(r: Id[Resource]) = ops.delete(r)

    def expectAudit(fn: List[Interaction] => weaver.Expectations): IO[Unit] = {
      audit.reset.map(fn).flatMap(_.failFast)
    }

    def tick[T]: IO[Unit] = IO.sleep(1.millis)
  }

  def defaultReconcile(@annotation.unused _res: Resource): IO[ReconcileResult] = {
    IO.pure(ReconcileResult.Ok)
  }

  def execute(
    reconcile: Resource => IO[ReconcileResult] = defaultReconcile,
    opts: ReconcileOptions = ReconcileOptions(),
    state: Option[IORef[IO, Dispatcher.StateMap[IO, Id[Resource]]]] = None,
    delayStartup: Option[FiniteDuration] = None
  )(block: Ctx => IO[Expectations]): IO[Expectations] = {
    def wrapReconciler(audit: Audit[IO, Interaction], fn: Resource => IO[ReconcileResult])
      (@annotation.unused _client: TestClient[IO], res: ResourceState[Resource]) = res match {
      case ResourceState.Active(v) => {
        audit.record(ReconcileStart(v.name)) >> fn(v).guaranteeCase {
          case Outcome.Canceled() => audit.record(ReconcileCanceled(v.name))
          case Outcome.Succeeded(_) => audit.record(ReconcileEnd(v.name))
          case Outcome.Errored(_) => audit.record(ReconcileFailed(v.name))
        }
      }
      case ResourceState.SoftDeleted(_) => IO.pure(ReconcileResult.Ok)
    }

    val program = for {
      audit <- Audit[IO, Interaction]
      client <- TestClient[IO].client.map(_.withAudit[Resource] {
        case Event.Updated(r) => audit.record(Updated(r.name))
        case Event.Deleted(r) => audit.record(Deleted(r.name))
      })
      ctx = new Ctx(client, audit)
      dispatcherLoop = client.apply[Resource].mirror { mirror =>
        Dispatcher
          .resource(client, mirror, wrapReconciler(audit, reconcile), opts, stateOverride = state)
          .use(identity)
      }
      runDispatcher = delayStartup.fold(dispatcherLoop)(dispatcherLoop.delayBy)
      result <- IO.race(runDispatcher, ctx.tick >> block(ctx)).flatMap {
        case Left(_) => IO.raiseError(new AssertionError("dispacherLoop terminated"))
        case Right(r) => IO.pure(r)
      }
    } yield result
    TestControl.executeEmbed(program)
  }
}
