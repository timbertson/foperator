package foperator.internal

import scala.concurrent.duration.FiniteDuration


private[foperator] case class ErrorCount(value: Int) extends AnyVal {
  def increment = ErrorCount(value + 1)

  def nonzero = value > 0
}

private[foperator] object ErrorCount {
  def zero = ErrorCount(0)

  type RetryDelay = ErrorCount => Option[FiniteDuration]
}
