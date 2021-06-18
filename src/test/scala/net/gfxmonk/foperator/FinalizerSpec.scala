package net.gfxmonk.foperator

import org.scalatest.funspec.AnyFunSpec

class FinalizerTest extends AnyFunSpec {
  describe("active resources") {
    it("adds the finalizer if missing") { pending }
    it("reconciles if the finalizer is present") { pending }
  }

  describe("soft-deleted resources") {
    it("calls finalize then removes the finalizer") { pending }
    it("does not remove finalizer if finalize fails") { pending }
    it("does nothing when the finalizer is not present") { pending }
  }
}
