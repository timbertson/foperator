package net.gfxmonk.foperator.types

import cats.Eq
import net.gfxmonk.foperator.{Event, Id, ListOptions}

import java.time.ZonedDateTime

// Typeclasses used throughout foperator

trait HasVersion[T] {
  def version(t: T): Option[Int]
  def withVersion(t: T, newVersion: Int): T
}

trait HasSoftDelete[T] {
  def deletionTimestamp(t: T): Option[ZonedDateTime]
  def withDeleted(t: T, timestamp: ZonedDateTime): T
}

trait HasFinalizers[T] {
  def finalizers(t: T): List[String]
  def replaceFinalizers(t: T, f: List[String]): T
}

trait HasKind[T] {
  def kind: String
  def apiPrefix: String
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

sealed trait ClientError {
  def throwable: Throwable
}
object ClientError {
  case class VersionConflict(throwable: Throwable) extends ClientError
  case class NotFound(throwable: Throwable) extends ClientError
  case class Unknown(throwable: Throwable) extends ClientError
}

trait Engine[IO[_], Impl, T] {
  def read(i: Impl, t: Id[T]): IO[Option[T]]

  def write(i: Impl, t: T): IO[T]
  def writeStatus[St](i: Impl, t: T, st: St)(implicit sub: HasStatus[T, St]): IO[T]

  def classifyError(e: Throwable): ClientError

  def delete(i: Impl, id: Id[T]): IO[Unit]
  def listAndWatch(i: Impl, opts: ListOptions): IO[(List[T], fs2.Stream[IO, Event[T]])]
}