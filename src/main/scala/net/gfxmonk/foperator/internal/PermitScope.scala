package net.gfxmonk.foperator.internal

import monix.eval.Task

trait PermitScope {
  def withPermit[A](task: Task[A]): Task[A]
}
