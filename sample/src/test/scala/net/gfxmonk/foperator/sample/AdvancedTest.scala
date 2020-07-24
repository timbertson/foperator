package net.gfxmonk.foperator.sample

import minitest.laws.Checkers
import net.gfxmonk.foperator.internal.Logging
import org.scalacheck.Shrink
import org.scalatest.funspec.AnyFunSpec

// TODO PropSpec or something more direct?
class AdvancedTest extends AnyFunSpec with Checkers with Logging {
  import MutatorTest._

  it("Reaches a consistent state after every mutation") {
    implicit val disableShrink: Shrink[Int] = Shrink(_ => Stream.empty)
    check1 { (seed: Int) => testWithSeed(seed); true }
  }
}
