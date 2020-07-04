package net.gfxmonk.foperator

import cats.data.NonEmptyList
import monix.eval.Task
import play.api.libs.json.Format
import skuber.{CustomResource, HasStatusSubresource, ObjectResource, ResourceDefinition}
import skuber.api.client.KubernetesClient

sealed trait ReconcileResult[+T]
object ReconcileResult {
  case class Modified[T](result: T) extends ReconcileResult[T]
  case object Continue extends ReconcileResult[Nothing] // try next reconciler
  case object Ignore extends ReconcileResult[Nothing] // filtered, stop processing
}

trait Reconciler[T] {
  def reconcile(resource: T): Task[ReconcileResult[T]]
}

class UpdateReconciler[T,Op](val operation: T => Task[Option[Op]], val apply: (T, Op) => Task[T]) extends Reconciler[T] {
  override def reconcile(resource: T): Task[ReconcileResult[T]] = operation(resource).flatMap {
    case Some(update) => apply(resource, update).map(ReconcileResult.Modified.apply)
    case None => Task.pure(ReconcileResult.Continue)
  }
}

class FilterReconciler[T](val predicate: T => Task[Boolean]) extends Reconciler[T] {
  override def reconcile(resource: T): Task[ReconcileResult[T]] = predicate(resource).map {
    case true => ReconcileResult.Continue
    case false => ReconcileResult.Ignore
  }
}

object Reconciler {
  def sequence[T](reconcilers: List[Reconciler[T]]): Reconciler[T] = {
    def loop(resource: T, reconcilers: List[Reconciler[T]]): Task[ReconcileResult[T]] = {
      reconcilers match {
        case Nil => Task.pure(ReconcileResult.Continue)
        case head::tail => head.reconcile(resource).flatMap {
          case ReconcileResult.Continue => loop(resource, tail)
          case ReconcileResult.Modified(newResource) => loop(newResource, tail)
          case ReconcileResult.Ignore => Task.pure(ReconcileResult.Ignore)
        }
      }
    }
    resource => loop(resource, reconcilers)
  }

  def apply[T](fn: T => Task[ReconcileResult[T]]): Reconciler[T] = {
    (resource: T) => fn(resource)
  }

  def updater[Sp,St](fn: CustomResource[Sp,St] => Task[Option[Update[St]]])
                    (implicit fmt: Format[CustomResource[Sp,St]],
                     rd: ResourceDefinition[CustomResource[Sp,St]],
                     st: HasStatusSubresource[CustomResource[Sp,St]],
                     client: KubernetesClient
                    ): UpdateReconciler[CustomResource[Sp,St], Update[St]] = {
    new UpdateReconciler[CustomResource[Sp,St], Update[St]](fn, Operations.applyUpdate)
  }

  def filter[O<:ObjectResource](fn: O => Task[Boolean]): FilterReconciler[O] = new FilterReconciler[O](fn)

  def ignoreDeleted[O<:ObjectResource]: FilterReconciler[O] = {
    filter[O] { resource =>
      Task.pure(!Operations.isDeleted(resource))
    }
  }
}

// Finalizer is just a reconciler, but we use a different type because it needs to be
// injected into the pipeline before deleted objects are filtered out.
abstract class Finalizer[T](val name: String, val destroy: T => Task[Unit]) {
  def reconciler: Reconciler[T]
}

class CustomResourceFinalizer[Sp,St](name: String, destroy: CustomResource[Sp,St] => Task[Unit])
                                    (
                                      implicit fmt: Format[CustomResource[Sp,St]],
                                      rd: ResourceDefinition[CustomResource[Sp,St]],
                                      st: HasStatusSubresource[CustomResource[Sp,St]],
                                      client: KubernetesClient
                                    ) extends Finalizer[CustomResource[Sp,St]](name, destroy) {

  override def reconciler: UpdateReconciler[CustomResource[Sp,St], Update[St]] = {
    Reconciler.updater[Sp,St] { resource =>
      val finalizers = resource.metadata.finalizers.getOrElse(Nil)
      val hasMine = finalizers.contains(name)

      if (resource.metadata.deletionTimestamp.isDefined) {
        // Soft deleted, apply finalizer and remove
        if (hasMine) {
          // byee!
          destroy(resource).map { (_:Unit) =>
            val newFinalizers = NonEmptyList.fromList(finalizers.filterNot(_ == name)).map(_.toList)
            // TODO ensure `None` actually deletes the finalizer
            Some(Update.Metadata(resource.metadata.copy(finalizers=newFinalizers)))
          }
        } else {
          // noop, finalizer already removed
          Task.pure(None)
        }
      } else {
        // Not deleted, add if missing:
        Task.pure(if (hasMine) { None } else {
          Some(Update.Metadata(resource.metadata.copy(
            finalizers = Some(name :: finalizers)
          )))
        })
      }
    }
  }
}

object Finalizer {
  def apply[Sp,St](name: String)(fn: CustomResource[Sp,St] => Task[Unit])(
    implicit fmt: Format[CustomResource[Sp,St]],
    rd: ResourceDefinition[CustomResource[Sp,St]],
    st: HasStatusSubresource[CustomResource[Sp,St]],
    client: KubernetesClient
  ): CustomResourceFinalizer[Sp,St] = new CustomResourceFinalizer(name, fn)
}
