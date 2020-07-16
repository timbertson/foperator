package net.gfxmonk

package object foperator {
  /**
   * User-built components are typically UniformReconciler, where the returned type
   * matches the input type
   */
  type UniformReconciler[T] = Reconciler[T,T]

  /**
   * FullReconciler is what a Controller needs, i.e. taking care of finalizer logic
   * as well as resource reconciliation
   */
  type FullReconciler[T] = Reconciler[ResourceState[T],T]
}
