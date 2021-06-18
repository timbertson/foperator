package net.gfxmonk.foperator

import cats.Eq
import monix.eval.Task
import net.gfxmonk.foperator.implicits._
import net.gfxmonk.foperator.internal.Logging
import play.api.libs.json.Format
import skuber.api.client.KubernetesClient
import skuber.{CustomResource, HasStatusSubresource, ResourceDefinition}

import scala.concurrent.duration.FiniteDuration

sealed trait ReconcileResult
object ReconcileResult {
  case class RetryAfter(delay: FiniteDuration) extends ReconcileResult
  case object Ok extends ReconcileResult
}

trait Reconciler[-T] {
  def reconcile(resource: T): Task[ReconcileResult]
}

class UpdateReconciler[T,Op](val operation: T => Task[Op], val apply: Op => Task[ReconcileResult]) extends Reconciler[T] {
  override def reconcile(resource: T): Task[ReconcileResult] = operation(resource).flatMap(apply)
}

case object EmptyReconciler extends Reconciler[Any] {
  override def reconcile(resource: Any): Task[ReconcileResult] = Task.pure(ReconcileResult.Ok)
}

object Reconciler extends Logging {
  def make[T](fn: T => Task[ReconcileResult]): Reconciler[T] = {
    (resource: T) => fn(resource)
  }

  def updater[Sp,St](fn: CustomResource[Sp,St] => Task[CustomResourceUpdate[Sp,St]])
                                  (implicit fmt: Format[CustomResource[Sp,St]],
                                   rd: ResourceDefinition[CustomResource[Sp,St]],
                                   st: HasStatusSubresource[CustomResource[Sp,St]],
                                   client: KubernetesClient,
                                   eqSp: Eq[Sp],
                                   eqSt: Eq[St],
                                  ): UpdateReconciler[CustomResource[Sp,St], CustomResourceUpdate[Sp,St]] = {
    def apply(update: CustomResourceUpdate[Sp,St]): Task[ReconcileResult] = {
      Operations.apply(update).map(_ => ReconcileResult.Ok)
    }
    new UpdateReconciler(fn, apply)
  }

  def withFinalizer[Sp, St](name: String, reconciler: Reconciler[CustomResource[Sp, St]])
    ( implicit fmt: Format[CustomResource[Sp, St]],
      rd: ResourceDefinition[CustomResource[Sp, St]],
      st: HasStatusSubresource[CustomResource[Sp, St]],
      client: KubernetesClient,
      eqSp: Eq[Sp],
      eqSt: Eq[St],
    ): Reconciler[CustomResource[Sp, St]] = make[CustomResource[Sp, St]] { resource =>
    val withFinalizer = resource.metadataUpdate(ResourceState.withFinalizer(name)(resource.metadata))
    // TODO: this doesn't apply finalizer when reconciler fails. We should apply finalizer, and then
    // resubmit for reconciliation
    Update.change[CustomResource[Sp,St], Sp, St](withFinalizer).fold(reconciler.reconcile, { change =>
      logger.debug(s"Adding finalizer $name to ${Id.of(resource)}")
      Operations.apply(change).map(_ => ReconcileResult.Ok)
    })
  }

  def empty: Reconciler[Any] = EmptyReconciler

  def updaterWith[T,U](updateFn: (U) => Task[ReconcileResult])(reconcileFn: T => Task[U]): UpdateReconciler[T,U] = {
    new UpdateReconciler[T,U](reconcileFn, updateFn)
  }
}
