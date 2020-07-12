package net.gfxmonk.foperator.sample

trait PrettyPrint[T] {
  def pretty(value: T): String
}

object PrettyPrint {
  def fromString[T]: PrettyPrint[T] = new PrettyPrint[T] {
    override def pretty(value: T): String = value.toString
  }
}

