package net.gfxmonk.foperator

// TODO rename to Event?
sealed trait Event[+T]
object Event {
  // Resource added / modified. This includes soft-deletions
  case class Updated[T](state: T) extends Event[T]

  // Resource hard-deleted
  case class HardDeleted[T](state: T) extends Event[T]
}
