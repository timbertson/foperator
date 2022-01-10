package foperator

import monix.eval.Task
import weaver.{Expectations, TestName}
import weaver.monixcompat.SimpleTaskSuite
import scala.concurrent.duration._

trait SimpleTimedTaskSuite extends SimpleTaskSuite {
  def timedTest(name: TestName)(t: Task[Expectations]) = test(name)(t.timeout(5.seconds))
}
