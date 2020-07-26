package net.gfxmonk.foperator.sample

import net.gfxmonk.foperator.internal.Logging
import org.scalatest.funspec.AnyFunSpec

class AdvancedTest extends AnyFunSpec with Logging {
  it("Reaches a consistent state after every mutation") {
    val initalSeed = System.currentTimeMillis().toInt.toLong // toInt truncates to prevent overflow
    Range.Long(initalSeed, initalSeed + 100, 1).foreach { seed =>
      MutatorTest.testSynthetic(MutationTestCase.withSeed(seed))
    }
  }
}
