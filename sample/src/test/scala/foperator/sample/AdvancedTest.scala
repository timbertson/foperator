package foperator.sample

import cats.implicits._
import foperator.internal.Logging
import foperator.sample.mutator.{MutationTestCase, MutatorTest}
import monix.eval.Task
import weaver.monixcompat.SimpleTaskSuite

object AdvancedTest extends SimpleTaskSuite with Logging {
  def run(seed: Long): Task[Unit] = MutatorTest.testSynthetic(MutationTestCase.withSeed(seed))

  test("Reaches a consistent state after every mutation") {
    val initalSeed = System.currentTimeMillis().toInt.toLong // toInt truncates to prevent overflow
    fs2.Stream.fromIterator(Range.Long(initalSeed, initalSeed + 100, 1).iterator).evalMap { seed =>
      run(seed) >> Task(println(s"done: ${seed}"))
    }.compile.drain.as(success)
  }

//  test("specific seed") { // used for debugging a specific failure
//    run(765000891L)
//  }
}
