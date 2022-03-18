package foperator

import cats.effect.IO
import weaver.{Expectations, SimpleIOSuite, TestName}

import scala.concurrent.duration._

trait SimpleTimedIOSuite extends SimpleIOSuite {
  def timedTest(name: TestName)(t: IO[Expectations]) = test(name)(t.timeout(5.seconds))
}
