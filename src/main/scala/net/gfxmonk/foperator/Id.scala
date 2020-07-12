package net.gfxmonk.foperator

import cats.Eq
import skuber.ObjectResource

import scala.util.{Failure, Success, Try}

case class Id[T](namespace: String, name: String)

object Id {
  object Implicits {
    implicit def IdEq[T]: Eq[Id[T]] = Eq.fromUniversalEquals[Id[T]]
  }

  def of[T<:ObjectResource](resource: T): Id[T] = Id(
    namespace = resource.namespace,
    name = resource.name)

  // unsafe methods take `cls` so that callsites must be explicit about the type they want
  // (since we can't actually confirm the ID refers to the given resource type)
  def unsafeParse[T<:ObjectResource](cls: Class[T], value: String): Try[Id[T]] = value.split('/').toList match {
    case List(name) => Success(unsafeCreate(cls, "default", name))
    case List(namespace, name) => Success(unsafeCreate(cls, namespace, name))
    case _ => Failure(new IllegalArgumentException(s"Not a valid object ID: $value"))
  }

  def unsafeCreate[T<:ObjectResource](_cls: Class[T], namespace: String, name: String): Id[T] = {
    _cls match { case _ => () } // ignore unused
    Id[T](namespace, name)
  }
}
