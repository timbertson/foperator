package foperator.fixture

import cats.Eq
import foperator.testkit.TestResource

case class ResourceSpec(title: String)
case class ResourceStatus(count: Int)

object Resource {
  def fixture = TestResource[ResourceSpec, ResourceStatus](name = "fixture", spec = ResourceSpec(title="??"))
}

object ResourceSpec {
  implicit val eq: Eq[ResourceSpec] = Eq.fromUniversalEquals[ResourceSpec]
}
object ResourceStatus {
  implicit val eq: Eq[ResourceStatus] = Eq.fromUniversalEquals[ResourceStatus]
}
