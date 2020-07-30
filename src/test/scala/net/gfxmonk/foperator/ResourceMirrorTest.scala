package net.gfxmonk.foperator

import org.scalatest.funspec.AnyFunSpec

class ResourceMirrorTest extends AnyFunSpec {
  it("TODO") {}
  it("aborts the `use` block on error") {}
  it("is populated with the initial resource set") {}
  it("emits IDs for updates") {}
  it("updates internal state before emitting an ID") {}
  it("supports multiple ID consumers") {}

  it("cannot skip updates during concurrent subscription") {
    // if we subscribe concurrently to an item's creation, then either:
    // we observe the creation as part of the initial set, or
    // emitting of the item is delayed until our subscriber is installed,
    // so we see it as an update.
  }
}
