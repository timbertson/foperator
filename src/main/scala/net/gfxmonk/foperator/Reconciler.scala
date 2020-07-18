package net.gfxmonk.foperator

import monix.eval.Task
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

object Reconciler {
  def make[T](fn: T => Task[ReconcileResult]): Reconciler[T] = {
    (resource: T) => fn(resource)
  }

  def customResourceUpdater[Sp,St](fn: CustomResource[Sp,St] => Task[CRUpdate[Sp,St]])
                                  (implicit fmt: Format[CustomResource[Sp,St]],
                                   rd: ResourceDefinition[CustomResource[Sp,St]],
                                   st: HasStatusSubresource[CustomResource[Sp,St]],
                                   client: KubernetesClient
                                  ): UpdateReconciler[CustomResource[Sp,St], CRUpdate[Sp,St]] = {
    def apply(update: CRUpdate[Sp,St]): Task[ReconcileResult] = {
      Operations.apply(update).map(_ => ReconcileResult.Ok)
    }
    new UpdateReconciler(fn, apply)
  }

  def empty: Reconciler[Any] = EmptyReconciler

  def updater[T,U](updateFn: (U) => Task[ReconcileResult])(reconcileFn: T => Task[U]): UpdateReconciler[T,U] = {
    new UpdateReconciler[T,U](reconcileFn, updateFn)
  }
}
