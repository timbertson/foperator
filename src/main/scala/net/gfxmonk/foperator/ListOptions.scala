package net.gfxmonk.foperator

// TODO less stringly typed...
case class ListOptions(
  labelSelector: List[String] = Nil,
  fieldSelector: List[String] = Nil,
)

object ListOptions {
  val all = ListOptions()
}