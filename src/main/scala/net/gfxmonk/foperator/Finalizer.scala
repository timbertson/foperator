package net.gfxmonk.foperator

import cats.data.NonEmptyList
import monix.eval.Task
import play.api.libs.json.Format
import skuber.{CustomResource, HasStatusSubresource, ResourceDefinition}
import skuber.api.client.KubernetesClient

// Finalizer is much like a reconciler, except it deals with ResourceState[T]
// and returns a new T to be fed to the downstream reconciler
trait Finalizer[T] {
  def reconcileState(resource: ResourceState[T]): Task[ResourceState[T]]
}

class CustomResourceFinalizer[Sp,St](name: String, destroy: CustomResource[Sp,St] => Task[Unit])
                                    (
                                      implicit fmt: Format[CustomResource[Sp,St]],
                                      rd: ResourceDefinition[CustomResource[Sp,St]],
                                      st: HasStatusSubresource[CustomResource[Sp,St]],
                                      client: KubernetesClient
                                    ) extends Finalizer[CustomResource[Sp,St]] {

  import net.gfxmonk.foperator.implicits._

  private def reconcileFn(resource: ResourceState[CustomResource[Sp,St]]): Task[CRUpdate[Sp,St]] = {
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
            resource.metadataUpdate(resource.metadata.copy(finalizers=newFinalizers))
          }
        } else {
          // noop, finalizer already removed
          Task.pure(resource.unchanged)
        }
      }
      case ResourceState.Active(resource) =>
        // Not deleted, add if missing:
        Task.pure(if (hasMine(resource)) { resource.unchanged } else {
          resource.metadataUpdate(resource.metadata.copy(
            finalizers = Some(name :: finalizers(resource))
          ))
        })
    }
  }

  override def reconcileState(resource: ResourceState[CustomResource[Sp,St]]): Task[ResourceState[CustomResource[Sp,St]]] = {
    // TODO shouldn't need all these explicits
    reconcileFn(resource).flatMap(Operations.apply(_)(fmt, rd, st, client)).map(ResourceState.of)
  }
}

object Finalizer {
  def reconciler[T](finalizer: Finalizer[T], reconciler: Reconciler[T]): Reconciler[ResourceState[T]] = new Reconciler[ResourceState[T]] {
    override def reconcile(resource: ResourceState[T]): Task[ReconcileResult] = {
      finalizer.reconcileState(resource).flatMap {
        case ResourceState.Active(resource) => reconciler.reconcile(resource)
        case ResourceState.SoftDeleted(_) => Task.pure(ReconcileResult.Ok)
      }
    }
  }

  def apply[Sp,St](name: String)(fn: CustomResource[Sp,St] => Task[Unit])(
    implicit fmt: Format[CustomResource[Sp,St]],
    rd: ResourceDefinition[CustomResource[Sp,St]],
    st: HasStatusSubresource[CustomResource[Sp,St]],
    client: KubernetesClient
  ): CustomResourceFinalizer[Sp,St] = new CustomResourceFinalizer(name, fn)

  def empty[T]: Finalizer[T] = new Finalizer[T] {
    override def reconcileState(resource: ResourceState[T]): Task[ResourceState[T]] = Task.pure(resource)
  }
}

