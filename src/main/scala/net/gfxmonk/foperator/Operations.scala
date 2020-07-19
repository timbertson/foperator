package net.gfxmonk.foperator

import cats.{Applicative, Eq}
import cats.implicits._
import monix.eval.Task
import play.api.libs.json.Format
import skuber.api.client.{KubernetesClient, LoggingContext}
import skuber.{ObjectResource, _}
import cats.implicits._
import net.gfxmonk.foperator.internal.Logging
import net.gfxmonk.foperator.implicits._

import scala.concurrent.Future
import scala.util.{Failure, Success}

sealed trait Update[T<:ObjectResource, +Sp, +St] {
  def initial: T
  def id: Id[T] = Id.of(initial)
  override def toString: Finalizer = s"${this.getClass.getSimpleName}(${id}, ...)"
}

object Update {
  // A subset of update that excludes Unchanged
  sealed trait Change[T<:ObjectResource, +Sp, +St] extends Update[T,Sp,St]

  case class Spec[T<:ObjectResource, Sp](initial: T, spec: Sp) extends Change[T, Sp, Nothing]
  case class Status[T<:ObjectResource, St](initial: T, status: St) extends Change[T, Nothing, St]
  case class Metadata[T<:ObjectResource](initial: T, metadata: ObjectMeta) extends Change[T, Nothing, Nothing]
  case class Unchanged[T<:ObjectResource](initial: T) extends Update[T, Nothing, Nothing]

  // TODO can we use something more general than CustomResource
  def minimal[Sp,St](update: CustomResourceUpdate[Sp, St])(implicit eqSp: Eq[Sp], eqSt: Eq[St]): CustomResourceUpdate[Sp, St] = {
    update match {
      case Status(initial, status) => if(initial.status.exists(_ === status)) Unchanged(initial) else update
      case Spec(initial, spec) => if(initial.spec === spec) Unchanged(initial) else update
      case Metadata(initial, metadata) => if(initial.metadata === metadata) Unchanged(initial) else update
      case Unchanged(initial) => Unchanged(initial)
    }
  }

//  def fold[T<:ObjectResource,Sp,St,R](update: Update[T,Sp,St])(passthru: T => R)(apply: Change[T,Sp,St] => R): R = update match {
//    case Update.Unchanged(initial) => passthru(initial)
//    case other:Change[T,Sp,St] => apply(other)
//  }
//
  def change[Sp,St](update: CustomResourceUpdate[Sp,St])(implicit eqSp: Eq[Sp], eqSt: Eq[St]):
    Either[CustomResource[Sp,St],Change[CustomResource[Sp,St],Sp,St]] = minimal(update) match {
    case Update.Unchanged(initial) => Left(initial)
    case other:Change[CustomResource[Sp,St],Sp,St] => Right(other)
  }
//
//  def applyWith[F[_], T<:ObjectResource,Sp,St](update: Update[T,Sp,St])(apply: Change[T,Sp,St] => F[T])(implicit F: Applicative[F]): F[T] = {
//    fold(update)(F.pure)(apply)
//  }
}

object Operations extends Logging {
  def write[O<:ObjectResource](withMetadata: (O, ObjectMeta) => O)(resource: O)(implicit client: KubernetesClient, fmt: Format[O], rd: ResourceDefinition[O], lc: LoggingContext): Task[O] = {
    Task(logger.debug(s"Writing ${resource.name}")) >>
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
    }.map { result =>
      logger.debug(s"Wrote ${resource.name} v${resource.metadata.resourceVersion}")
      result
    }
  }

  def apply[Sp,St](update: Update[CustomResource[Sp,St], Sp, St])(
    implicit fmt: Format[CustomResource[Sp,St]],
    rd: ResourceDefinition[CustomResource[Sp,St]],
    st: HasStatusSubresource[CustomResource[Sp,St]],
    client: KubernetesClient,
    eqSp: Eq[Sp],
    eqSt: Eq[St],
  ): Task[CustomResource[Sp,St]] = {
    type Resource = CustomResource[Sp,St]
    implicit val hasStatus: HasStatusSubresource[Resource] = st // TODO why is this needed?
    Update.change(update).fold(Task.pure, { change =>
      logger.debug(s"Applying update ${change}")
      Task.fromFuture(change match {
        // TODO no updateMetadata? Is metadata not a subresource?
        case Update.Metadata(original, meta) => client.update(original.withMetadata(meta))
        case Update.Spec(original, sp) => client.update(original.copy(spec = sp))
        case Update.Status(original, st) => client.updateStatus(original.withStatus(st))
      })
    })
  }

  def applyMany[Sp,St](updates: List[Update[CustomResource[Sp,St], Sp, St]])(
    implicit fmt: Format[CustomResource[Sp,St]],
    rd: ResourceDefinition[CustomResource[Sp,St]],
    st: HasStatusSubresource[CustomResource[Sp,St]],
    client: KubernetesClient,
    eqSp: Eq[Sp],
    eqSt: Eq[St],
  ): Task[Unit] = {
    updates.traverse(update => Operations.apply(update)).void
  }
}
