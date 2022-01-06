package net.gfxmonk.foperator

import scala.concurrent.duration._

class ReconcileOptions(
  val refreshInterval: Option[FiniteDuration],
  val errorDelay: Int => FiniteDuration,
  val concurrency: Int,
) {
  def retryDelay(errorCount: Int): Option[FiniteDuration] = {
    if (errorCount > 0) {
      Some(errorDelay(errorCount))
    } else {
      refreshInterval
    }
  }
}

object ReconcileOptions {
  val defaultErrorBackoff: Int => FiniteDuration = { count =>
    Math.pow(1.2, count.toDouble).seconds
  }

  def apply(
    refreshInterval: Option[FiniteDuration] = Some(5.minutes),
    concurrency: Int = 1,
    errorDelay: Int => FiniteDuration = defaultErrorBackoff
  ) = new ReconcileOptions(refreshInterval, errorDelay, concurrency)
}
