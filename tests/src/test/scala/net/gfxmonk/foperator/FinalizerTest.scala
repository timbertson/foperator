package net.gfxmonk.foperator

import monix.eval.Task
import monix.execution.Scheduler
import net.gfxmonk.auditspec._
import net.gfxmonk.foperator.ResourceState.{Active, SoftDeleted}
import net.gfxmonk.foperator.fixture._
import net.gfxmonk.foperator.testkit.TestClient
import org.scalatest.funspec.AsyncFunSpec
import org.scalatest.matchers.should.Matchers

object FinalizerTest {
  sealed trait Interaction
  case class SetFinalizers(finalizers: List[String]) extends Interaction
  case class ReconcileFailed(message: String) extends Interaction
  case object Reconcile extends Interaction
  case object Finalize extends Interaction
  val NAME = "finalizerName"
}

class FinalizerTest extends AsyncFunSpec with Matchers {
  import FinalizerTest._

  def reconcile(
    desc: String,
    resource: ResourceState[Resource],
    actions: List[Interaction],
    finalizeResult: Task[Unit] = Task.unit,
    wrapReconciler: (Audit[Interaction], Reconciler[Task, TestClient[Task], Resource]) => Reconciler[Task, TestClient[Task], Resource] = (_, r) => r
  ) = it(desc) {
    Audit.resource[Interaction].use { audit =>
      val reconciler = wrapReconciler(audit, TestClient.Reconciler[Resource]
        .run(_ => audit.record(Reconcile))
        .withFinalizer(NAME, _ => audit.record(Finalize) >> finalizeResult))
      for {
        client <- TestClient[Task].map(_.withAudit[Resource]{
          case Event.Updated(r) => audit.record(SetFinalizers(r.meta.finalizers))
          case Event.Deleted(_) => Task.unit
        })
        _ <- reconciler.reconcile(client, resource).onErrorHandleWith { error =>
          audit.record(ReconcileFailed(error.getMessage))
        }
        log <- audit.get
      } yield log should be(actions)
    }.runToFuture(Scheduler.global)
  }

  describe("active resources") {
    reconcile("adds the finalizer if empty",
      resource = Active(Resource.fixture),
      actions = List(SetFinalizers(List(NAME))))

    reconcile("adds the finalizer if missing",
      resource = Active(ResourceState.addFinalizer(Resource.fixture, "someone.else").get),
      actions = List(SetFinalizers(List(NAME, "someone.else"))))

    reconcile("reconciles if the finalizer is present",
      resource = Active(ResourceState.addFinalizer(Resource.fixture, NAME).get),
      actions = List(Reconcile)
    )
  }

  describe("soft-deleted resources") {
    reconcile("calls finalize then removes the finalizer",
      resource = SoftDeleted(ResourceState.addFinalizer(Resource.fixture, NAME).get),
      actions = List(Finalize, SetFinalizers(Nil))
    )

    reconcile("does not remove finalizer if finalize fails",
      resource = SoftDeleted(ResourceState.addFinalizer(Resource.fixture, NAME).get),
      finalizeResult = Task.raiseError(new RuntimeException("couldn't finalize")),
      actions = List(Finalize, ReconcileFailed("couldn't finalize"))
    )

    reconcile("does nothing when the finalizer is not present",
      resource = SoftDeleted(Resource.fixture),
      actions = Nil
    )

    reconcile("supports multiple finalizers",
      resource = SoftDeleted(ResourceState.addFinalizers(Resource.fixture, List(NAME + "1", NAME + "2")).get),
      wrapReconciler = (audit, r) => r.withFinalizer(NAME + "1", _ => audit.record(Finalize)),
      actions = List(Finalize, SetFinalizers(List(NAME + "2")))
    )
  }
}
