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
  case object Continue extends ReconcileResult[Nothing] // try next reconciler
  case object Skip extends ReconcileResult[Nothing] // stop processing this update
  case class RetryAfter(delay: FiniteDuration) extends ReconcileResult[Nothing] // stop processing this update
}

trait Reconciler[T] {
  def reconcile(resource: T): Task[ReconcileResult[T]]
//
//  def activeOnly(implicit ev: T <:< ObjectResource): Reconciler[ResourceState[T]] = {
//    val self = this
//    new Reconciler[ResourceState[T]] {
//      override def reconcile(resource: ResourceState[T]): Task[ReconcileResult[ResourceState[T]]] = {
//        resource match {
//          case ResourceState.Active(value) => self.reconcile(value).map {
//            case ReconcileResult.Modified(value) => {
//              ReconcileResult.Modified(ResourceState.of[T](value))
//            }
//            case ReconcileResult.Continue => ReconcileResult.Continue
//            case ReconcileResult.Skip => ReconcileResult.Skip
//            case ReconcileResult.RetryAfter(delay) => ReconcileResult.RetryAfter(delay)
//          }
//        }
//      }
//    }
//  }
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
    case false => ReconcileResult.Skip
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
          case result @ (ReconcileResult.RetryAfter(_) | ReconcileResult.Skip) => Task.pure(result)
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
  def reconciler: Reconciler[ResourceState[T]]
}

class CustomResourceFinalizer[Sp,St](name: String, destroy: CustomResource[Sp,St] => Task[Unit])
                                    (
                                      implicit fmt: Format[CustomResource[Sp,St]],
                                      rd: ResourceDefinition[CustomResource[Sp,St]],
                                      st: HasStatusSubresource[CustomResource[Sp,St]],
                                      client: KubernetesClient
                                    ) extends Finalizer[CustomResource[Sp,St]](name, destroy) {

  override def reconciler: UpdateReconciler[ResourceState[CustomResource[Sp,St]], Update[St]] = {
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

    def applyUpdate(resource: ResourceState[CustomResource[Sp,St]], op: Update[St]): Task[ResourceState[CustomResource[Sp,St]]] = {
      resource match {
        case ResourceState.Active(active) => Operations.applyUpdate(active, op).map(ResourceState.of)
        case ResourceState.SoftDeleted(active) => Operations.applyUpdate(active, op).map(ResourceState.of)
      }
    }

    new UpdateReconciler[ResourceState[CustomResource[Sp,St]], Update[St]](reconcileFn, applyUpdate)
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
