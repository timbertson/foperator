package monix.execution.internal

import monix.execution.internal.collection.ChunkedArrayQueue

object TrampolineSpy {

  def look(any: Any): String = {
//    classOf[Trampoline].getDeclaredFields().foreach { f =>
//      println(s"Field: ${f}")
//      println(s" - ${f.getName}")
//    }
////    val immediateQueue
    // SO WILDLY UNSAFE...
    val immediateQueue = classOf[Trampoline].getDeclaredFields.head
    immediateQueue.setAccessible(true)
    val withinLoop = classOf[Trampoline].getDeclaredField("withinLoop")
    withinLoop.setAccessible(true)
    val q = immediateQueue.get(any).asInstanceOf[ChunkedArrayQueue[_]]
    s"TRAMPOLINE: withinLoop=${withinLoop.get(any)}, immediateQueue.isEmpty=${q.isEmpty}"
  }
}
