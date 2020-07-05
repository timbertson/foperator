package net.gfxmonk.foperator.internal

class ResourceLoopTest extends org.scalatest.funspec.AnyFunSpec {
  it("reconciles") {}
  it("is cancelable") {}
  describe("on success") {
    it("schedules a new reconcile periodically") {}
    it("releases its permit") {}
  }

  describe("on failure") {
    it("backs off until it reaches the periodic reconcile interval") {}
  }

  describe("updating") {
    it("reconciles immediately after a current reconcile") {}
    it("reconciles immediately if waiting") {}
  }
}
