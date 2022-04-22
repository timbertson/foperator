package foperator.types

import cats.Eq
import foperator.{Event, Id, ListOptions}

import java.time.Instant

// Typeclasses used throughout foperator

trait HasVersion[T] {
  def version(t: T): Option[String]
  def withVersion(t: T, newVersion: String): T
}

trait HasSoftDelete[T] {
  def isSoftDeleted(t: T): Boolean
  def softDeletedAt(t: T, time: Instant): T
}

trait HasFinalizers[T] {
  def finalizers(t: T): List[String]
  def replaceFinalizers(t: T, f: List[String]): T
}

trait HasKind[T] {
  // short name used in logs etc, doesn't need to match k8s kind exactly
  def kindDescription: String
}

trait HasId[T] {
  def id(t: T): Id[T]
}

trait ObjectResource[T] extends HasKind[T] with HasId[T] with HasVersion[T] with HasSoftDelete [T] with HasFinalizers[T]

// Only used for `updateStatus`. NOTE: this currently implies status is a subresource,
// but that may not be appropriate in all cases.
trait HasStatus[T, Status] {
  val eqStatus: Eq[Status]
  def status(obj: T): Option[Status]
  def withStatus(obj: T, status: Status): T
}

// Not required by foperator itself, but provided by backends for convenience
trait HasSpec[T, Spec] {
  def spec(obj: T): Spec
  def withSpec(obj: T, spec: Spec): T
}

trait HasCustomResourceDefinition[T, D] {
  def customResourceDefinition: D
}

sealed trait ClientError {
  def throwable: Throwable
}
object ClientError {
  case class VersionConflict(throwable: Throwable) extends ClientError
  case class NotFound(throwable: Throwable) extends ClientError
  case class Unknown(throwable: Throwable) extends ClientError
}

// Implementations define how a given Backend can manipulate a given resource type.
// Not all backends can operate on all resource types.
trait Engine[IO[_], C, T] {
  def read(i: C, t: Id[T]): IO[Option[T]]

  def create(i: C, t: T): IO[Unit]
  def update(i: C, t: T): IO[Unit]
  def updateStatus[St](i: C, t: T, st: St)(implicit sub: HasStatus[T, St]): IO[Unit]

  def classifyError(e: Throwable): ClientError

  def delete(i: C, id: Id[T]): IO[Unit]
  def listAndWatch(i: C, opts: ListOptions): IO[(List[T], fs2.Stream[IO, Event[T]])]
}