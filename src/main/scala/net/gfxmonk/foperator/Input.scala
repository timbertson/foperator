package net.gfxmonk.foperator

sealed trait Input[+T]
object Input {
  // Resource added / modified. This includes soft-deletions
  case class Updated[T](state: T) extends Input[T]

  // Resource hard-deleted
  case class HardDeleted[T](state: T) extends Input[T]
}
