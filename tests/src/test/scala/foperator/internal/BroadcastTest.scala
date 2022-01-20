package foperator.internal

import scala.concurrent.duration._
import cats.effect.concurrent.Semaphore
import cats.implicits._
import foperator.SimpleTimedTaskSuite
import foperator.testkit.TestSchedulerUtil
import fs2.concurrent.Topic
import monix.eval.Task
import monix.execution.Scheduler
import monix.execution.schedulers.TestScheduler
import monix.reactive.Observable

import scala.concurrent.Await

//object BroadcastTest extends SimpleTimedTaskSuite {
//  timedTest("subscribeAwait enforces ordering") {
//    for {
//      b <- Broadcast[Task, Int]
//      result <- b.subscribeAwait(10).use { sub =>
//        Task.parMap3(
//          b.publish1(1),
//          sub.take(2).compile.toList,
//          b.publish1(2)
//        )(( _, l, _ ) => l.sorted)
//      }
//    } yield {
//      expect(result === List(1,2))
//    }
//  }
//
//  timedTest("multiple subscribers") {
//    for {
//      b <- Broadcast[Task, Int]
//      result <-
//        b.subscribeAwait(10).use { s1 =>
//          // NOTE: need to do next subscription in parallel with consuming the first,
//          // as a subscription isn't released until you start consuming its stream.
//          Task.parMap2(
//            s1.take(2).compile.toList,
//            b.subscribeAwait(10).use { s2 =>
//              Task.parMap3(
//                b.publish1(1),
//                s2.take(2).compile.toList,
//                b.publish1(2)
//              )((_, l, _) => l)
//            }
//          )((a, b) => (a.sorted, b.sorted))
//        }
//    } yield {
//      expect(result === (List(1,2), List(1,2)))
//    }
//  }
//}
