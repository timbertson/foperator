package net.gfxmonk.foperator

sealed trait Event[+T] {
  def raw: T
}
object Event {
  // Resource added / modified. This includes soft-deletions
  case class Updated[T](raw: T) extends Event[T]

  // Resource hard-deleted
  case class Deleted[T](raw: T) extends Event[T]

  def desc[T](event: Event[T]) = event match {
    case Updated(_) => "Updated"
    case Deleted(_) => "Deleted"
  }
}