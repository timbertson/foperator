package foperator

import cats.Eq
import cats.effect.IO
import cats.implicits._
import foperator.ResourceState.{Active, SoftDeleted}
import foperator.fixture._
import foperator.testkit.TestClient
import monix.eval.Task
import net.gfxmonk.auditspec._
import weaver.SimpleIOSuite

trait ReconcilerTest { self: SimpleIOSuite =>
  import ReconcilerTest._

  def reconcile(
    desc: String,
    resource: ResourceState[Resource],
    actions: List[Interaction],
    finalizeResult: Task[Unit] = Task.unit,
    wrapReconciler: (Audit[Interaction], Reconciler[Task, TestClient[Task], Resource]) => Reconciler[Task, TestClient[Task], Resource] = (_, r) => r
  ) = test(desc) {
    Audit.resource[Interaction].use { audit =>
      val reconciler = wrapReconciler(audit, TestClient[IO].Reconciler[Resource]
        .run(_ => audit.record(Reconcile))
        .withFinalizer(NAME, _ => audit.record(Finalize) >> finalizeResult))
      for {
        baseClient <- TestClient[Task]
        auditedClient = baseClient.withAudit[Resource] {
          case Event.Updated(r) => audit.record(SetFinalizers(r.meta.finalizers))
          case Event.Deleted(_) => Task.unit
        }
        // reconcile always runs on a persisted resource (i.e. it updates, never creates) so make sure
        // the resource is persisted
        writtenOpt <- baseClient[Resource].write(resource.raw) >> baseClient[Resource].get(Id.of(resource.raw))
        written = ResourceState.map(resource, (_: Resource) => writtenOpt.get)
        _ <- reconciler.reconcile(auditedClient, written).onErrorHandleWith { error =>
          audit.record(ReconcileFailed(error.getMessage))
        }
        log <- audit.get
      } yield expect(log === actions)
    }
  }
}


object ReconcilerTest {
  sealed trait Interaction
  case class SetFinalizers(finalizers: List[String]) extends Interaction
  case class ReconcileFailed(message: String) extends Interaction
  case object Reconcile extends Interaction
  case object Finalize extends Interaction
  val NAME = "finalizerName"
  implicit val eq: Eq[Interaction] = Eq.fromUniversalEquals

  object ActiveResources extends SimpleIOSuite with ReconcilerTest {
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

  object SoftDeletedResources extends SimpleIOSuite with ReconcilerTest {
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