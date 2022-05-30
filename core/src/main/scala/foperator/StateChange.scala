package foperator


sealed trait StateChange[T]

sealed trait ResourceChange[T] extends StateChange[T] {
  def raw: T
}

object StateChange {

  // Resource added / modified. This includes soft-deletions
  case class Updated[T](raw: T) extends ResourceChange[T]

  // Resource hard-deleted
  case class Deleted[T](raw: T) extends ResourceChange[T]

  // Reset the state (initial population or reset after EOF / error)
  case class ResetState[T](all: List[T]) extends StateChange[T]

  def desc[T](event: ResourceChange[T]) = event match {
    case Updated(_) => "Updated"
    case Deleted(_) => "Deleted"
  }
}