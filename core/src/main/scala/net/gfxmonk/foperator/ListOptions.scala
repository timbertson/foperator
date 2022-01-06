package net.gfxmonk.foperator

// TODO less stringly typed...
case class ListOptions(
  namespace: String = "default",
  labelSelector: List[String] = Nil,
  fieldSelector: List[String] = Nil,
)

object ListOptions {
  val all = ListOptions()
}