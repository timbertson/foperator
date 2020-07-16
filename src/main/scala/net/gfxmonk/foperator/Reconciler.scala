package net.gfxmonk.foperator

import cats.data.NonEmptyList
import monix.eval.Task
import play.api.libs.json.Format
import skuber.{CustomResource, HasStatusSubresource, ObjectResource, ResourceDefinition}
import skuber.api.client.KubernetesClient

import scala.concurrent.duration.FiniteDuration

sealed trait ReconcileResult[+T]
object ReconcileResult {
  case class Modified[T](result: T) extends ReconcileResult[T]
  case class RetryAfter(delay: FiniteDuration) extends ReconcileResult[Nothing] // stop processing this update
  case object Continue extends ReconcileResult[Nothing] // try next reconciler
  case object Skip extends ReconcileResult[Nothing] // stop processing this update
}

trait Reconciler[A,B] {
  // Note: UniformReconciler[T] is a handy alias for the common case of Reconciler[T,T]
  def reconcile(resource: A): Task[ReconcileResult[B]]

  def andThen[C](next: Reconciler[B,C]): Reconciler[A, C] = {
    val self = this
    new Reconciler[A,C] {
      override def reconcile(resource: A): Task[ReconcileResult[C]] = {
        self.reconcile(resource).flatMap {
          case ReconcileResult.Modified(b) => next.reconcile(b)
          case ReconcileResult.RetryAfter(delay) => Task.pure(ReconcileResult.RetryAfter(delay))
          case ReconcileResult.Skip => Task.pure(ReconcileResult.Skip)
          case ReconcileResult.Continue => Task.pure(ReconcileResult.Continue)
        }
      }
    }
  }
}

class UpdateReconciler[T,R,Op](val operation: T => Task[Option[Op]], val apply: (T, Op) => Task[ReconcileResult[R]]) extends Reconciler[T,R] {
  override def reconcile(resource: T): Task[ReconcileResult[R]] = operation(resource).flatMap {
    case Some(update) => apply(resource, update)
    case None => Task.pure(ReconcileResult.Continue)
  }
}

class FilterReconciler[T](val predicate: T => Task[Boolean]) extends UniformReconciler[T] {
  override def reconcile(resource: T): Task[ReconcileResult[T]] = predicate(resource).map {
    case true => ReconcileResult.Continue
    case false => ReconcileResult.Skip
  }
}

object Reconciler {
  def make[T,R](fn: T => Task[ReconcileResult[R]]): Reconciler[T,R] = {
    (resource: T) => fn(resource)
  }

  def customResourceUpdater[Sp,St](fn: CustomResource[Sp,St] => Task[Option[Update[St]]])
                                  (implicit fmt: Format[CustomResource[Sp,St]],
                                   rd: ResourceDefinition[CustomResource[Sp,St]],
                                   st: HasStatusSubresource[CustomResource[Sp,St]],
                                   client: KubernetesClient
                                  ): UpdateReconciler[CustomResource[Sp,St], CustomResource[Sp,St], Update[St]] = {
    def apply(res: CustomResource[Sp,St], update: Update[St]): Task[ReconcileResult[CustomResource[Sp,St]]] = {
      Operations.applyUpdate(res, update).map(ReconcileResult.Modified.apply)
    }
    new UpdateReconciler[CustomResource[Sp,St],CustomResource[Sp,St],Update[St]](fn, apply)
  }

  def updater[T,U](updateFn: (T,U) => Task[ReconcileResult[T]])(reconcileFn: T => Task[Option[U]]): UpdateReconciler[T,T,U] = {
    new UpdateReconciler[T,T,U](reconcileFn, updateFn)
  }

  def fullUpdater[T,U](updateFn: (ResourceState[T],U) => Task[ReconcileResult[T]])(reconcileFn: ResourceState[T] => Task[Option[U]]): UpdateReconciler[ResourceState[T],T,U] = {
    new UpdateReconciler[ResourceState[T],T,U](reconcileFn, updateFn)
  }

  def filter[O<:ObjectResource](fn: O => Task[Boolean]): FilterReconciler[O] = new FilterReconciler[O](fn)
}

// Finalizer is just a reconciler, but we use a different type because it needs to be
// injected into the pipeline before deleted objects are filtered out.
abstract class Finalizer[T] {
  def reconciler: FullReconciler[T]
}

class CustomResourceFinalizer[Sp,St](name: String, destroy: CustomResource[Sp,St] => Task[Unit])
                                    (
                                      implicit fmt: Format[CustomResource[Sp,St]],
                                      rd: ResourceDefinition[CustomResource[Sp,St]],
                                      st: HasStatusSubresource[CustomResource[Sp,St]],
                                      client: KubernetesClient
                                    ) extends Finalizer[CustomResource[Sp,St]] {

  override def reconciler: UpdateReconciler[ResourceState[CustomResource[Sp,St]], CustomResource[Sp,St], Update[St]] = {
    def reconcileFn(resource: ResourceState[CustomResource[Sp,St]]): Task[Option[Update[St]]] = {
      def finalizers(resource:CustomResource[Sp,St]) = resource.metadata.finalizers.getOrElse(Nil)
      def hasMine(resource:CustomResource[Sp,St]) = finalizers(resource).contains(name)

      resource match {
        case ResourceState.SoftDeleted(resource) => {
          // Soft deleted, apply finalizer and remove
          if (hasMine(resource)) {
            // byee!
            destroy(resource).map { (_:Unit) =>
              val newFinalizers = NonEmptyList.fromList(finalizers(resource).filterNot(_ == name)).map(_.toList)
              // TODO ensure `None` actually deletes the finalizer
              Some(Update.Metadata(resource.metadata.copy(finalizers=newFinalizers)))
            }
          } else {
            // noop, finalizer already removed
            Task.pure(None)
          }
        }
        case ResourceState.Active(resource) =>
          // Not deleted, add if missing:
          Task.pure(if (hasMine(resource)) { None } else {
            Some(Update.Metadata(resource.metadata.copy(
              finalizers = Some(name :: finalizers(resource))
            )))
          })
      }
    }

    def applyUpdate(resource: ResourceState[CustomResource[Sp,St]], op: Update[St]): Task[ReconcileResult[CustomResource[Sp,St]]] = {
      resource match {
        case ResourceState.Active(active) => Operations.applyUpdate(active, op).map(ReconcileResult.Modified.apply)
        case ResourceState.SoftDeleted(deleted) => Operations.applyUpdate(deleted, op).map(_ => ReconcileResult.Skip)
      }
    }

    Reconciler.fullUpdater(applyUpdate)(reconcileFn)
  }
}

object Finalizer {
  def apply[Sp,St](name: String)(fn: CustomResource[Sp,St] => Task[Unit])(
    implicit fmt: Format[CustomResource[Sp,St]],
    rd: ResourceDefinition[CustomResource[Sp,St]],
    st: HasStatusSubresource[CustomResource[Sp,St]],
    client: KubernetesClient
  ): CustomResourceFinalizer[Sp,St] = new CustomResourceFinalizer(name, fn)

  def empty[T]: Finalizer[T] = new Finalizer[T] {
    override def reconciler: Reconciler[ResourceState[T],T] = Reconciler.make {
      case ResourceState.Active(active) => Task.pure(ReconcileResult.Modified(active))
      case ResourceState.SoftDeleted(_) => Task.pure(ReconcileResult.Skip)
    }
  }
}
