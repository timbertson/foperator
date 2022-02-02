package foperator

import cats.{Eq, Order}
import foperator.types.ObjectResource

import scala.util.{Failure, Success, Try}

// Not a case class because we don't want people to create it outside the explicit API
class Id[T] private(val namespace: String, val name: String) {
  private [foperator] def product = (namespace, name)

  override def toString(): String = s"$namespace/$name"

  override def hashCode(): Int = name.hashCode()

  override def equals(obj: Any): Boolean = {
    obj != null && obj.isInstanceOf[Id[T]] && {
      val instance = obj.asInstanceOf[Id[T]]
      instance.name == name && instance.namespace == namespace
    }
  }

  def sibling(name: String): Id[T] = Id[T](namespace, name)
}

object Id {
  implicit def eq[T]: Eq[Id[T]] = Eq.fromUniversalEquals[Id[T]]
  implicit def ord[T]: Order[Id[T]] = Order.by(_.product)

  def of[T](resource: T)(implicit res: ObjectResource[T]): Id[T] = res.id(resource)

  // unsafe methods rely on the generic type being given correctly, since we can't
  // guarantee that without an actual instance
  def parse[T](value: String): Try[Id[T]] = value.split('/').toList match {
    case List(name) => Success(apply[T]("default", name))
    case List(namespace, name) => Success(apply[T](namespace, name))
    case _ => Failure(new IllegalArgumentException(s"Not a valid object ID: $value"))
  }

  def apply[T](namespace: String, name: String): Id[T] = {
    new Id[T](namespace, name)
  }

  def cast[R](id: Id[_]): Id[R] = apply[R](id.namespace, id.name)
}
