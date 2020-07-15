package net.gfxmonk.foperator

import monix.reactive.Observable

class ControllerInput[T] private (mirror: ResourceMirror[T], ids: Observable[Input[Id[T]]]) {
  def withActiveResourceTrigger[R](mirror: ResourceMirror[R])(extractIds: R => Iterable[Id[T]]): ControllerInput[T] = {
    withResourceTrigger(mirror) {
      case ResourceState.Active(value) => extractIds(value)
      case ResourceState.SoftDeleted(_) => Nil
    }
  }

  def withResourceTrigger[R](mirror: ResourceMirror[R])(extractIds: ResourceState[R] => Iterable[Id[T]]): ControllerInput[T] = {
    withIdTrigger[R](mirror) {
      case Input.HardDeleted(_) => Nil
      case Input.Updated(id) => {
        mirror.get(id).map(extractIds).getOrElse(Nil)
      }
    }
  }

  def withIdTrigger[R](watcher: ResourceUpdates[R])(extractIds: Input[Id[R]] => Iterable[Id[T]]): ControllerInput[T] = {
    val ids = watcher.ids.concatMap(update => Observable.from(extractIds(update)))
    withExternalTrigger(ids)
  }

  def withExternalTrigger[R](triggers: Observable[Id[T]]): ControllerInput[T] = {
    new ControllerInput(mirror, Observable(ids, triggers.map(Input.Updated.apply)).merge)
  }

  // used by Controller, not typically client code
  def inputs: Observable[Input[Id[T]]] = Observable(mirror.ids, ids).merge

  def get(id: Id[T]): Option[ResourceState[T]] = mirror.get(id)
}

object ControllerInput {
  def apply[T](mirror: ResourceMirror[T]) = new ControllerInput[T](mirror, Observable.empty[Input[Id[T]]])
}

