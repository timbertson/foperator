package net.gfxmonk.foperator.internal

import monix.catnap.Semaphore
import monix.eval.Task

trait PermitScope {
  def withPermit[A](task: Task[A]): Task[A]
}

object PermitScope {
  def semaphore(sem: Semaphore[Task]): PermitScope = new PermitScope {
    override def withPermit[A](task: Task[A]): Task[A] = sem.withPermit(task)
  }
}
