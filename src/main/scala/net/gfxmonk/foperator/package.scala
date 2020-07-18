package net.gfxmonk

import skuber.CustomResource

package object foperator {
  type CRUpdate[Sp,St] = Update[CustomResource[Sp,St],Sp,St]
}
