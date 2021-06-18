package net.gfxmonk.foperator

import cats.implicits._
import skuber.{ObjectMeta, ObjectResource}

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

  def of[T<:ObjectResource](value: T): ResourceState[T] = {
    if(value.metadata.deletionTimestamp.isDefined) {
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

  def withoutFinalizer(finalizer: String, metadata: ObjectMeta): ObjectMeta = {
    val newFinalizers = metadata.finalizers.flatMap {
      case Nil => None
      case list => list.filterNot(_ == finalizer) match {
        case Nil => None
        case list => Some(list)
      }
    }
    metadata.copy(finalizers = newFinalizers)
  }

  def withFinalizer(finalizer: String, metadata: ObjectMeta): ObjectMeta = {
    val finalizers = metadata.finalizers.getOrElse(Nil)
    if (finalizers.contains_(finalizer)) {
      metadata
    } else {
      metadata.copy(finalizers = Some(finalizer :: finalizers))
    }
  }
}

