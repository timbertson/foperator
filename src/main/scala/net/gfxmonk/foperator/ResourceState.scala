package net.gfxmonk.foperator

import skuber.ObjectResource

sealed trait ResourceState[T]
object ResourceState {
  case class SoftDeleted[T](value: T) extends ResourceState[T]
  case class Active[T](value: T) extends ResourceState[T]

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

  def awaitingFinalize[T<:ObjectResource](finalizer: String)(res: ResourceState[T]): Option[T] = {
    softDeleted(res).filter(_.metadata.finalizers.getOrElse(Nil).contains(finalizer))
  }
}

