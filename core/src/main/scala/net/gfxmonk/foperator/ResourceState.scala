package net.gfxmonk.foperator

import cats.implicits._
import cats.Monad
import net.gfxmonk.foperator.types.{HasFinalizers, HasSoftDelete}

sealed trait ResourceState[T] {
  def raw: T
}

object ResourceState {
  case class SoftDeleted[T](value: T) extends ResourceState[T] {
    override def raw: T = value
  }
  case class Active[T](value: T) extends ResourceState[T] {
    override def raw: T = value
  }

  def of[T](value: T)(implicit sd: HasSoftDelete[T]): ResourceState[T] = {
    if(sd.isSoftDeleted(value)) {
      SoftDeleted(value)
    } else {
      Active(value)
    }
  }

  def active[T](res: ResourceState[T]): Option[T] = res match {
    case SoftDeleted(_) => None
    case Active(value) => Some(value)
  }

  def softDeleted[T](res: ResourceState[T]): Option[T] = res match {
    case SoftDeleted(value) => Some(value)
    case Active(_) => None
  }

  def removeFinalizer[T](t: T, finalizer: String)(implicit f: HasFinalizers[T]): Option[T] = {
    val oldFinalizers = f.finalizers(t)
    val newFinalizers = oldFinalizers.filterNot(_ === finalizer)
    if (newFinalizers === oldFinalizers) None else Some(f.replaceFinalizers(t, newFinalizers))
  }

  def addFinalizer[T](t: T, finalizer: String)(implicit f: HasFinalizers[T]): Option[T] = addFinalizers(t, List(finalizer))

  def addFinalizers[T](t: T, finalizers: List[String])(implicit f: HasFinalizers[T]): Option[T] = {
    val oldFinalizers = f.finalizers(t)
    val newFinalizers = finalizers.filterNot(oldFinalizers.contains_)
    if (newFinalizers.isEmpty) {
      None
    } else {
      Some(f.replaceFinalizers(t, newFinalizers ++ oldFinalizers))
    }
  }

  def fold[IO[_], T, R](dfl: R, fn: T => IO[R])(t: ResourceState[T])(implicit io: Monad[IO]): IO[R] = t match {
    case SoftDeleted(_) => io.pure(dfl)
    case Active(t) => fn(t)
  }
}

