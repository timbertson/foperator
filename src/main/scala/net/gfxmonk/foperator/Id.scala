package net.gfxmonk.foperator

import java.util.Objects

import cats.Eq
import skuber.ObjectResource

import scala.util.{Failure, Success, Try}

// Not a case class because we don't want people to create it outside the explicit API
class Id[T] private(val namespace: String, val name: String) {
  override def hashCode(): Int = name.hashCode()

  override def equals(obj: Any): Boolean = {
    obj != null && obj.isInstanceOf[Id[T]] && {
      val instance = obj.asInstanceOf[Id[T]]
      instance.name == name && instance.namespace == namespace
    }
  }
}

object Id {
  object Implicits {
    implicit def IdEq[T]: Eq[Id[T]] = Eq.fromUniversalEquals[Id[T]]
  }

  def of[T<:ObjectResource](resource: T): Id[T] = new Id(
    namespace = resource.namespace,
    name = resource.name)

  // unsafe methods rely on the generic type being given correctly, since we can't
  // guarantee that without an actual instance
  def parseUnsafe[T<:ObjectResource](value: String): Try[Id[T]] = value.split('/').toList match {
    case List(name) => Success(createUnsafe[T]("default", name))
    case List(namespace, name) => Success(createUnsafe[T](namespace, name))
    case _ => Failure(new IllegalArgumentException(s"Not a valid object ID: $value"))
  }

  def createUnsafe[T<:ObjectResource](namespace: String, name: String): Id[T] = {
    new Id[T](namespace, name)
  }
}
