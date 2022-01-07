package foperator

import foperator.testkit.TestResource

package object fixture {
  type Resource = TestResource[ResourceSpec, ResourceStatus]
  def resource(
    name: String="res1",
    spec: ResourceSpec = ResourceSpec(title="fixture"),
    status: Option[ResourceStatus] = None
  ): Resource = TestResource[ResourceSpec, ResourceStatus](
    name,
    spec,
    status
  )
}
