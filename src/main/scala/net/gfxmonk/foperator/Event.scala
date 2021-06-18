package net.gfxmonk.foperator

sealed trait Event[+T]
object Event {
  // Resource added / modified. This includes soft-deletions
  case class Updated[T](state: T) extends Event[T]

  // Resource hard-deleted
  case class HardDeleted[T](state: T) extends Event[T]
}
