package net.gfxmonk.foperator

import monix.eval.Task
import monix.reactive.Observable

class ControllerInput[T] private (mirror: ResourceMirror[T], ids: Observable[Event[Id[T]]]) {
  def withActiveResourceTrigger[R](mirror: ResourceMirror[R])(extractIds: R => Observable[Id[T]]): ControllerInput[T] = {
    withResourceTrigger(mirror) {
      case ResourceState.Active(value) => extractIds(value)
      case ResourceState.SoftDeleted(_) => Observable.empty
    }
  }

  def withResourceTrigger[R](mirror: ResourceMirror[R])(extractIds: ResourceState[R] => Observable[Id[T]]): ControllerInput[T] = {
    withIdTrigger[R](mirror) {
      case Event.HardDeleted(_) => Observable.empty
      case Event.Updated(id) => {
        Observable.fromTask(mirror.get(id)).concatMap {
          case Some(resource) => extractIds(resource)
          case None => Observable.empty
        }
      }
    }
  }

  def withIdTrigger[R](watcher: ResourceUpdates[R])(extractIds: Event[Id[R]] => Observable[Id[T]]): ControllerInput[T] = {
    withExternalTrigger(watcher.ids.concatMap(extractIds))
  }

  def withExternalTrigger[R](triggers: Observable[Id[T]]): ControllerInput[T] = {
    new ControllerInput(mirror, Observable(ids, triggers.map(Event.Updated.apply)).merge)
  }

  // used by Controller, not typically client code
  def events: Observable[Event[Id[T]]] = Observable(mirror.ids, ids).merge

  def get(id: Id[T]): Task[Option[ResourceState[T]]] = mirror.get(id)
}

object ControllerInput {
  def apply[T](mirror: ResourceMirror[T]) = new ControllerInput[T](mirror, Observable.empty[Event[Id[T]]])
}

