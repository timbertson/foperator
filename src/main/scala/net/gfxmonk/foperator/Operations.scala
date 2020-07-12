package net.gfxmonk.foperator

import monix.eval.Task
import play.api.libs.json.Format
import skuber.api.client.{KubernetesClient, LoggingContext}
import skuber.{ObjectResource, _}

import scala.util.{Failure, Success}

sealed trait Update[+St]
object Update {
  case class Status[St](status: St) extends Update[St]
  case class Metadata(metadata: ObjectMeta) extends Update[Nothing]
}

object Operations {
  def isDeleted[T<:ObjectResource](resource: T): Boolean = resource.metadata.deletionTimestamp.isDefined

  def write[O<:ObjectResource](withMetadata: (O, ObjectMeta) => O)(resource: O)(implicit client: KubernetesClient, fmt: Format[O], rd: ResourceDefinition[O], lc: LoggingContext): Task[O] = {
    Task.deferFuture(client.create(resource)).materialize.flatMap {
      case Success(resource) => Task.pure(resource)
      case Failure(err: K8SException) if err.status.code.contains(409) => {
        // resource exists, update based on the current resource version
        Task.deferFuture(client.get[O](resource.name)).flatMap { existing =>
          val currentVersion = existing.metadata.resourceVersion
          val newMeta = resource.metadata.copy(resourceVersion = currentVersion)
          val updatedObj = withMetadata(resource, newMeta)
          Task.deferFuture(client.update(updatedObj))
        }
      }
      case Failure(err) => Task.raiseError(err)
    }
  }

  def applyUpdate[Sp,St](original: CustomResource[Sp,St], update: Update[St])(
    implicit fmt: Format[CustomResource[Sp,St]],
    rd: ResourceDefinition[CustomResource[Sp,St]],
    st: HasStatusSubresource[CustomResource[Sp,St]],
    client: KubernetesClient
  ): Task[CustomResource[Sp,St]] = {
    implicit val hasStatus: HasStatusSubresource[CustomResource[Sp,St]] = st // TODO why is this needed?
    Task.deferFuture(update match {
      // TODO no updateMetadata? Is metadata not a subresource?
      case Update.Metadata(meta) => client.update(original.withMetadata(meta))
      case Update.Status(st) => client.updateStatus(original.withStatus(st))
    })
  }
}


