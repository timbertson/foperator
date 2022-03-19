package foperator.sample

import cats.effect.IO
import foperator.internal.Logging
import foperator.sample.mutator.{MutationTestCase, MutatorTest}
import weaver.SimpleIOSuite

object AdvancedTest extends SimpleIOSuite with Logging {
  def run(seed: Long): IO[Unit] = MutatorTest.testSynthetic(MutationTestCase.withSeed(seed))

  test("Reaches a consistent state after every mutation") {
    val initalSeed = System.currentTimeMillis().toInt.toLong // toInt truncates to prevent overflow
    fs2.Stream.range(initalSeed, initalSeed + 100).evalMap { seed =>
      run(seed) >> IO(println(s"done: ${seed}"))
    }.compile.drain.as(success)
  }

//  test("specific seed") { // used for debugging a specific failure
//    run(765000891L)
//  }
}
