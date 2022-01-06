package net.gfxmonk.foperator.sample

import monix.execution.Scheduler
import net.gfxmonk.foperator.internal.Logging
import net.gfxmonk.foperator.sample.mutator.{MutationTestCase, MutatorTest}
import org.scalatest.funspec.AnyFunSpec

class AdvancedTest extends AnyFunSpec with Logging {
  def run(seed: Long) =
    MutatorTest.testSynthetic(MutationTestCase.withSeed(seed)).runSyncUnsafe()(Scheduler.global, implicitly)

  it("Reaches a consistent state after every mutation") {
    val initalSeed = System.currentTimeMillis().toInt.toLong // toInt truncates to prevent overflow
    Range.Long(initalSeed, initalSeed + 100, 1).foreach { seed =>
      run(seed)
      println(s"done: ${seed}")
    }
  }

//  it("specific seed") { // used for debugging a specific failure
//    run(765000891L)
//  }
}
