package net.gfxmonk.foperator

import cats.implicits._
import monix.eval.Task
import net.gfxmonk.foperator.Reconciler.logger
import play.api.libs.json.Format
import skuber.api.client.KubernetesClient
import skuber.{ObjectEditor, ObjectMeta, ObjectResource, ResourceDefinition}

class Finalizer[T](
  val name: String,
  val update: (T, ObjectMeta) => Task[T],
  val finalizeFn: T => Task[Unit])

object Finalizer {
  private[foperator] def merge[O<:ObjectResource](finalizer: Finalizer[O], reconciler: Reconciler[O]): Reconciler[ResourceState[O]] =
    new Reconciler[ResourceState[O]] {
      override def reconcile(resource: ResourceState[O]): Task[ReconcileResult] = {
        import implicits.metadataEq
        resource match {
          case ResourceState.Active(resource) => {
            val expectedMeta = ResourceState.withFinalizer(finalizer.name, resource.metadata)
            if (resource.metadata === expectedMeta) {
              // already installed, regular reconcile
              reconciler.reconcile(resource)
            } else {
              // install finalizer before doing any user actions.
              // Note that we don't even invoke the user reconciler in this case,
              // we rely on a re-reconcile being triggered by the metadata addition
              logger.info(s"Adding finalizer ${finalizer.name} to ${Id.of(resource)}")
              finalizer.update(resource, expectedMeta).as(ReconcileResult.Ok)
            }
          }

          case ResourceState.SoftDeleted(resource) => {
            val expectedMeta = ResourceState.withoutFinalizer(finalizer.name, resource.metadata)
            if (expectedMeta === resource.metadata) {
              Task.pure(ReconcileResult.Ok)
            } else {
              logger.info(s"Finalizing ${Id.of(resource)} [${finalizer.name}]")
              finalizer.finalizeFn(resource).flatMap { (_: Unit) =>
                finalizer.update(resource, expectedMeta)
              }.as(ReconcileResult.Ok)
            }
          }
        }
      }
    }

  // lift a regular reconciler into the finalizer contract (when we don't have any actual finalizer logic)
  private[foperator] def lift[T](reconciler: Reconciler[T]): Reconciler[ResourceState[T]] = new Reconciler[ResourceState[T]] {
    override def reconcile(resource: ResourceState[T]): Task[ReconcileResult] = {
      resource match {
        case ResourceState.Active(resource) => reconciler.reconcile(resource)
        case ResourceState.SoftDeleted(_) => Task.pure(ReconcileResult.Ok)
      }
    }
  }

  def apply[O<:ObjectResource, Sp,St](name: String)(finalizeFn: O => Task[Unit])(
    implicit fmt: Format[O],
    rd: ResourceDefinition[O],
    client: KubernetesClient,
    editor: ObjectEditor[O]
  ): Finalizer[O] = {
    def update(resource: O, meta: ObjectMeta) = Task.deferFuture(client.update(editor.updateMetadata(resource, meta)))
    new Finalizer(name, update, finalizeFn)
  }
}

