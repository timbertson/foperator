package net.gfxmonk.foperator

import java.time.{Clock, ZonedDateTime}

import skuber.{CustomResource, ObjectMeta, ObjectResource}

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

  def awaitingFinalizer[T<:ObjectResource](finalizer: String)(res: ResourceState[T]): Option[T] = {
    softDeleted(res).filter(_.metadata.finalizers.getOrElse(Nil).contains(finalizer))
  }

  def withoutFinalizer(finalizer: String)(metadata: ObjectMeta): ObjectMeta = {
    val newFinalizers = metadata.finalizers.flatMap {
      case Nil => None
      case list => list.filterNot(_ == finalizer) match {
        case Nil => None
        case list => Some(list)
      }
    }
    metadata.copy(finalizers = newFinalizers)
  }

  def softDelete(metadata: ObjectMeta): ObjectMeta = {
    val deletionTimestamp = metadata.deletionTimestamp.getOrElse(ZonedDateTime.now(Clock.systemUTC()))
    metadata.copy(deletionTimestamp = Some(deletionTimestamp))
  }
}

