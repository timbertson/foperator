package net.gfxmonk.foperator

import skuber.CustomResource

package object fixture {
  type Resource = CustomResource[ResourceSpec, ResourceStatus]
}
