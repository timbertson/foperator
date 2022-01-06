package net.gfxmonk.foperator.fixture

import net.gfxmonk.foperator.testkit.TestResource

case class ResourceSpec(title: String)
case class ResourceStatus(count: Int)

object Resource {
  def fixture = TestResource[ResourceSpec, ResourceStatus](name = "fixture", spec = ResourceSpec(title="??"))
}