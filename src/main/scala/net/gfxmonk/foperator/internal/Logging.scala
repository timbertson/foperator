package net.gfxmonk.foperator.internal

import org.slf4j.LoggerFactory

trait Logging {
  val logger = LoggerFactory.getLogger(this.getClass.getCanonicalName.stripSuffix("$"))
}
