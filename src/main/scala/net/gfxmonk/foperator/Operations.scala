package net.gfxmonk.foperator

import monix.eval.Task
import play.api.libs.json.Format
import skuber.api.client.{KubernetesClient, LoggingContext}
import skuber.{ObjectResource, _}

import scala.concurrent.Future
import scala.util.{Failure, Success}

sealed trait Update[+T, +St]
object Update {
  case class Status[T, St](initial: T, status: St) extends Update[T, St]
  case class Metadata[T](initial: T, metadata: ObjectMeta) extends Update[T, Nothing]
  case class None[T](value: T) extends Update[T,Nothing]

  // TODO can we use something more general than CustomResource
  def minimal[Sp,St](update: Update[CustomResource[Sp,St], St]): Update[CustomResource[Sp,St], St] = {
    update match {
      case Status(initial, status) => if(initial.status == status) None(initial) else update
      case Metadata(initial, metadata) => if(initial.metadata == metadata) None(initial) else update
      case None(initial) => None(initial)
    }
  }

  object Implicits {
    implicit class UpdateExt[T<:ObjectResource](val resource: T) extends AnyVal {
      def statusUpdate[St](st: St): Update[T,St] = Update.Status(resource, st)
      def metadataUpdate(metadata: ObjectMeta): Update[T,Nothing] = Update.Metadata(resource, metadata)
      def unchanged: Update[T,Nothing] = Update.None(resource)
    }
  }
}

object Operations {
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

  def applyUpdate[Sp,St](update: Update[CustomResource[Sp,St], St])(
    implicit fmt: Format[CustomResource[Sp,St]],
    rd: ResourceDefinition[CustomResource[Sp,St]],
    st: HasStatusSubresource[CustomResource[Sp,St]],
    client: KubernetesClient
  ): Task[CustomResource[Sp,St]] = {
    type Resource = CustomResource[Sp,St]
    implicit val hasStatus: HasStatusSubresource[Resource] = st // TODO why is this needed?
    Task.fromFuture(Update.minimal(update) match {
      // TODO no updateMetadata? Is metadata not a subresource?
      case Update.Metadata(original, meta) => client.update(original.withMetadata(meta))
      case Update.Status(original, st) => client.updateStatus(original.withStatus(st))
      case Update.None(original) => Future.successful(original)
    })
  }
}


