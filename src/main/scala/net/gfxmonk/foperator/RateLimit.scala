package net.gfxmonk.foperator

import scala.concurrent.duration.FiniteDuration

case class RateLimitConfig(capacity: Long, interval: FiniteDuration)
