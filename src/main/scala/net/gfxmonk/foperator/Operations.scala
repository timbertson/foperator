package net.gfxmonk.foperator

import cats.Eq
import cats.implicits._
import monix.eval.Task
import net.gfxmonk.foperator.Update.Updateable
import net.gfxmonk.foperator.implicits._
import net.gfxmonk.foperator.internal.Logging
import play.api.libs.json.Format
import skuber.api.client.{KubernetesClient, LoggingContext}
import skuber.{ObjectResource, _}

import scala.util.{Failure, Success}

sealed trait Update[O<:ObjectResource, +Sp, +St] {
  def initial: O
  def id: Id[O] = Id.of(initial)
  override def toString: Finalizer = s"${this.getClass.getSimpleName}(${id}, ...)"
}

object Update {
  // typeclass for updateable objects.
  // mainly to act as a bridge between CustomResources and
  // builtin k8s types like Deployment etc
  trait Updateable[O<:ObjectResource,Sp,St] {
    def spec(obj: O): Option[Sp]
    def status(obj: O): Option[St]
    def withSpec(obj: O, spec: Sp): O
    def withStatus(obj: O, status: St): O
  }

  def makeUpdateable[O<: ObjectResource, Sp, St](
    specFn: O => Option[Sp],
    statusFn: O => Option[St],
    withSpecFn: (O, Sp) => O,
    withStatusFn: (O, St) => O,
  ) = new Updateable[O, Sp, St] {
    override def spec(obj: O): Option[Sp] = specFn(obj)

    override def status(obj: O): Option[St] = statusFn(obj)

    override def withSpec(obj: O, spec: Sp): O = withSpecFn(obj, spec)

    override def withStatus(obj: O, status: St): O = withStatusFn(obj, status)
  }

  // A subset of update that excludes Unchanged
  sealed trait Change[O<:ObjectResource, +Sp, +St] extends Update[O,Sp,St]

  case class Create[O<:ObjectResource](initial: O) extends Change[O, Nothing, Nothing]
  case class Spec[O<:ObjectResource, Sp](initial: O, spec: Sp) extends Change[O, Sp, Nothing]
  case class Status[O<:ObjectResource, St](initial: O, status: St) extends Change[O, Nothing, St]
  case class Metadata[O<:ObjectResource](initial: O, metadata: ObjectMeta) extends Change[O, Nothing, Nothing]
  case class Unchanged[O<:ObjectResource](initial: O) extends Update[O, Nothing, Nothing]

  def minimal[O<:ObjectResource, Sp,St](update: Update[O, Sp, St])(implicit eqSp: Eq[Sp], eqSt: Eq[St], impl: Updateable[O,Sp,St]): Update[O, Sp, St] = {
    update match {
      case Create(_) => update
      case Status(initial, status) => if(impl.status(initial).exists(_ === status)) Unchanged(initial) else update
      case Spec(initial, spec) => if(impl.spec(initial).exists(_ === spec)) Unchanged(initial) else update
      case Metadata(initial, metadata) => if(initial.metadata === metadata) Unchanged(initial) else update
      case Unchanged(initial) => Unchanged(initial)
    }
  }

  private[foperator] def change[O<:ObjectResource, Sp,St](update: Update[O, Sp,St])(implicit eqSp: Eq[Sp], eqSt: Eq[St], impl: Updateable[O, Sp, St]):
  Either[O,Change[O,Sp,St]] = minimal(update) match {
    case Update.Unchanged(initial) => Left(initial)
    case other:Change[O,Sp,St] => Right(other)
  }
}

object Operations extends Logging {
  def apply[O <: ObjectResource, Sp,St](update: Update[O, Sp, St])(
    implicit fmt: Format[O],
    rd: ResourceDefinition[O],
    st: HasStatusSubresource[O],
    client: KubernetesClient,
    eqSp: Eq[Sp],
    eqSt: Eq[St],
    editor: ObjectEditor[O],
    updateable: Updateable[O, Sp, St],
  ): Task[O] = {
    implicit val hasStatus: HasStatusSubresource[O] = st // TODO why is this needed?
    Update.change(update).fold(Task.pure, { change =>
      logger.info(s"Applying update ${change}")
      Task.fromFuture(change match {
        case Update.Create(obj) => client.create(obj)
        // TODO no updateMetadata? Is metadata not a subresource?
        case Update.Metadata(original, meta) => client.update(editor.updateMetadata(original, meta))
        case Update.Spec(original, sp) => client.update(updateable.withSpec(original, sp))
        case Update.Status(original, st) => client.updateStatus(updateable.withStatus(original, st))
      })
    })
  }

  def applyUnowned[O <: ObjectResource, Sp, St](update: Update[O, Sp, Nothing])(
    implicit fmt: Format[O],
    rd: ResourceDefinition[O],
    client: KubernetesClient,
    eqSp: Eq[Sp],
    editor: ObjectEditor[O],
    // St is Nothing in practice, but being generic allows the same instance to be used for apply and applyUnowned
    updateable: Updateable[O, Sp, St],
  ): Task[O] = {
    Update.change(update)(eqSp, Eq.fromUniversalEquals[St], updateable).fold(Task.pure, { change =>
      logger.info(s"Applying update ${change}")
      change match {
        case Update.Create(obj) => Task.deferFuture(client.create(obj))
        case Update.Metadata(original, meta) => Task.deferFuture(client.update(editor.updateMetadata(original, meta)))
        case Update.Spec(original, sp) => Task.deferFuture(client.update(updateable.withSpec(original, sp)))
        case Update.Status(_, _) => Task.raiseError(new RuntimeException("Impossible"))
      }
    })
  }

  def applyMany[O <: ObjectResource, Sp,St](updates: List[Update[O, Sp, St]])(
    implicit fmt: Format[O],
    rd: ResourceDefinition[O],
    st: HasStatusSubresource[O],
    client: KubernetesClient,
    eqSp: Eq[Sp],
    eqSt: Eq[St],
    editor: ObjectEditor[O],
    updateable: Updateable[O, Sp, St],
  ): Task[Unit] = {
    updates.traverse(update => Operations.apply(update)).void
  }

  def applyManyUnowned[O <: ObjectResource, Sp](updates: List[Update[O, Sp, Nothing]])(
    implicit fmt: Format[O],
    rd: ResourceDefinition[O],
    client: KubernetesClient,
    eqSp: Eq[Sp],
    editor: ObjectEditor[O],
    updateable: Updateable[O, Sp, Nothing],
  ): Task[Unit] = {
    updates.traverse(update => Operations.applyUnowned(update)).void
  }

  def delete[O<:ObjectResource](id: Id[O])(
    implicit rd: ResourceDefinition[O],
    client: KubernetesClient,
  ): Task[Unit] = {
    // TODO is there a namespace-aware delete method?
    logger.info(s"Deleting $id")
    Task.deferFuture(client.usingNamespace(id.namespace).delete[O](id.name))
  }

  def deleteIfPresent[O<:ObjectResource](id: Id[O])(
    implicit rd: ResourceDefinition[O],
    client: KubernetesClient,
  ): Task[Boolean] = {
    delete(id).as(true).onErrorRecover {
      case e: K8SException if e.status.code.contains(404) => false
    }
  }

  def get[O<:ObjectResource](id: Id[O])(
    implicit rd: ResourceDefinition[O],
    fmt: Format[O],
    client: KubernetesClient,
  ): Task[Option[O]] = {
    Task.deferFuture(client.usingNamespace(id.namespace).getOption(id.name))
  }

  def forceWrite[O<:ObjectResource](resource: O)(
    implicit client: KubernetesClient,
    fmt: Format[O],
    editor: ObjectEditor[O],
    rd: ResourceDefinition[O],
    lc: LoggingContext): Task[O] = {
    Task(logger.debug(s"Writing ${resource.name}")) >>
      Task.deferFuture(client.create(resource)).materialize.flatMap {
        case Success(resource) => Task.pure(resource)
        case Failure(err: K8SException) if err.status.code.contains(409) => {
          // resource exists, update based on the current resource version
          Task.deferFuture(client.getInNamespace[O](resource.name, resource.namespace)).flatMap { existing =>
            val currentVersion = existing.metadata.resourceVersion
            val newMeta = resource.metadata.copy(resourceVersion = currentVersion)
            val updatedObj = editor.updateMetadata(resource, newMeta)
            Task.deferFuture(client.update(updatedObj))
          }
        }
        case Failure(err) => Task.raiseError(err)
      }.map { result =>
        logger.info(s"Wrote ${resource.name} v${resource.metadata.resourceVersion}")
        result
      }
  }
}
