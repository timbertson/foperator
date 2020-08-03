package net.gfxmonk.foperator.testkit

import net.gfxmonk.foperator.implicits.DependencyImplicits

package object implicits extends DependencyImplicits {
  implicit def foperatorContextFromDriver(implicit driver: FoperatorDriver) = driver.context
}
