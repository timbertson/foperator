package net.gfxmonk.foperator

import cats.implicits._
import monix.eval.Task
import monix.execution.Scheduler
import net.gfxmonk.auditspec._
import net.gfxmonk.foperator.ResourceState.{Active, SoftDeleted}
import net.gfxmonk.foperator.fixture._
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
    finalizeResult: Task[Unit] = Task.unit
  ) = it(desc) {
    Audit.resource[Interaction].use { audit =>
      val finalizer = new Finalizer[Resource](NAME,
        update = (res, meta) => audit.record(SetFinalizers(meta.finalizers.getOrElse(Nil))).as(res),
        finalizeFn = _ => audit.record(Finalize) >> finalizeResult
      )
      val userReconcile = Reconciler.make[Resource](_ => audit.record(Reconcile).as(ReconcileResult.Ok))
      val reconciler = Finalizer.merge(finalizer, userReconcile)

      reconciler.reconcile(resource).onErrorHandleWith { error =>
        audit.record(ReconcileFailed(error.getMessage))
      } >> audit.get.map(_ should be(actions))
    }.runToFuture(Scheduler.global)
  }

  describe("active resources") {
    reconcile("adds the finalizer if empty",
      resource = Active(Resource.fixture),
      actions = List(SetFinalizers(List(NAME))))

    reconcile("adds the finalizer if missing",
      resource = Active(Resource.fixture.withFinalizers("someone.else")),
      actions = List(SetFinalizers(List(NAME, "someone.else"))))

    reconcile("reconciles if the finalizer is present",
      resource = Active(Resource.fixture.withFinalizers(NAME)),
      actions = List(Reconcile)
    )
  }

  describe("soft-deleted resources") {
    reconcile("calls finalize then removes the finalizer",
      resource = SoftDeleted(Resource.fixture.withFinalizers(NAME)),
      actions = List(Finalize, SetFinalizers(Nil))
    )

    reconcile("does not remove finalizer if finalize fails",
      resource = SoftDeleted(Resource.fixture.withFinalizers(NAME)),
      finalizeResult = Task.raiseError(new RuntimeException("couldn't finalize")),
      actions = List(Finalize, ReconcileFailed("couldn't finalize"))
    )

    reconcile("does nothing when the finalizer is not present",
      resource = SoftDeleted(Resource.fixture),
      actions = Nil
    )
  }
}
