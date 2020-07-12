package net.gfxmonk

import skuber.CustomResource

package object foperator {
  type CustomResourceUpdate[Sp,St] = Update[CustomResource[Sp,St],Sp,St]
}
