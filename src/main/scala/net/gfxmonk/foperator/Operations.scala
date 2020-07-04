package net.gfxmonk.foperator

import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Sink
import monix.eval.Task
import monix.reactive.Observable
import play.api.libs.json.Format
import skuber.api.client.{EventType, KubernetesClient, LoggingContext}
import skuber.json.format._
import skuber.{ObjectResource, _}

import scala.util.{Failure, Random, Success}

sealed trait Update[+St]
object Update {
  case class Status[St](status: St) extends Update[St]
  case class Metadata(metadata: ObjectMeta) extends Update[Nothing]
}

object Operations {
  def listAndWatch[T<: ObjectResource](listOptions: ListOptions)(
    implicit fmt: Format[T], rd: ResourceDefinition[T], lc: LoggingContext, materializer: ActorMaterializer,
    client: KubernetesClient
  ): Observable[Input[T]] = {
    implicit val lrf = ListResourceFormat[T]
    val initial: Observable[ListResource[T]] = {
      Observable.fromFuture(client.listWithOptions[ListResource[T]](listOptions))
    }
    // TODO: check that watch ignores status updates to avoid loops (does it relate to status subresource?)
    initial.concatMap { listResource =>
      val source =
        client.watchWithOptions[T](listOptions.copy(
          resourceVersion = Some(listResource.resourceVersion),
          timeoutSeconds = Some(30) // TODO
        ))

      // shuffle so that if there's a poison pill we'll eventually service other records
      val initials = Observable.fromIterable(Random.shuffle(listResource.toList))
        .map(Input.Updated.apply)

      val deltas = Observable.fromReactivePublisher(source.runWith(Sink.asPublisher(fanout=false)))
        .map { event =>
          event._type match {
            case EventType.ERROR => ???
            case EventType.DELETED => Input.HardDeleted(event._object)
            case EventType.ADDED | EventType.MODIFIED => Input.Updated(event._object)
          }
        }

      initials ++ deltas
    }
  }

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


