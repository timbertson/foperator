package net.gfxmonk.foperator

import skuber.ObjectResource

sealed trait Input[+T]
object Input {
  // Resource added / modified. This includes soft-deletions
  case class Updated[T<: ObjectResource](state: T) extends Input[T]

  // Resource hard-deleted
  case class HardDeleted[T<: ObjectResource](state: T) extends Input[T]
}
