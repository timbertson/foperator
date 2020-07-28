package net.gfxmonk.foperator.fixture

import monix.eval.Task
import net.gfxmonk.foperator.internal.PermitScope

object PermitScopeFixture {
  def empty = new PermitScope {
    override def withPermit[A](task: Task[A]): Task[A] = task
  }
}
