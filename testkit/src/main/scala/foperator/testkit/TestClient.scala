package foperator.testkit

import cats.Eq
import cats.effect.{Async, Clock, Concurrent}
import cats.implicits._
import foperator._
import foperator.internal.{IORef, Logging}
import foperator.types._
import fs2.BroadcastTopic

import java.time.Instant
import java.util.concurrent.TimeUnit

class TestClient[IO[_]](
  state: IORef[IO, TestClient.State],
  val topic: BroadcastTopic[IO, Event[TestClient.Entry]],
  val auditors: List[Event[TestClient.Entry] => IO[Unit]],
)(implicit io: Async[IO]) extends Client[IO, TestClient[IO]] with Logging {

  override def apply[T]
    (implicit e: Engine[IO, TestClient[IO], T], res: ObjectResource[T])
    : Operations[IO, TestClient[IO], T]
    = new Operations[IO, TestClient[IO], T](this)

  def readState = state.readLast

  def all[T](implicit res: ObjectResource[T]): IO[List[T]] = readState.map { state =>
    state.toList.mapFilter {
      case (k, v) => ResourceKey.cast(k, v)
    }
  }

  def modifyState[B](f: TestClient.State => IO[(TestClient.State, B)]): IO[B] = state.modify(f)

  def modifyState_(f: TestClient.State => IO[TestClient.State]): IO[Unit] = modifyState(s => f(s).map(r => (r, ())))

  private [foperator] def publish(update: Event[TestClient.Entry]) = {
    auditors.traverse_(audit => audit(update)) >>
      io.delay(logger.debug("publishing {}({})", Event.desc(update), update.raw._1.id)) >>
      topic.publish1(update)
  }

  def withAudit[T](audit: Event[T] => IO[Unit])(implicit res: ObjectResource[T]): TestClient[IO] = {
    val auditor: Event[TestClient.Entry] => IO[Unit] = { entry =>
      ResourceKey.castEvent(entry).fold(io.unit)(audit)
    }
    new TestClient(state, topic, auditor :: auditors)
  }
}

object TestClient {
  // Internal state is untyped for simplicity.
  // Correct usage requires that no two types have the same `kind` + `apiVersion`
  private [testkit] type State = Map[ResourceKey, Any]
  private [testkit] type Entry = (ResourceKey, Any)

  class Companion[IO[_]] extends Client.Companion[IO, TestClient[IO]] {
    def client(implicit io: Async[IO]): IO[TestClient[IO]] = {
      for {
        state <- IORef[IO].of(Map.empty: State)
        topic <- BroadcastTopic[IO, Event[(ResourceKey, Any)]]
      } yield new TestClient[IO](state, topic, Nil)
    }
  }

  def apply[IO[_]](implicit io: Async[IO]): Companion[IO] = new Companion[IO]

  implicit def implicitEngine[IO[_], T]
    (implicit io: Async[IO], clock: Clock[IO], res: ObjectResource[T], eq: Eq[T])
  : foperator.types.Engine[IO, TestClient[IO], T]
  = new TestClientEngineImpl[IO, T]

  implicit def implicitOps[IO[_], T](c: TestClient[IO])
    (implicit io: Async[IO], engine: Engine[IO, TestClient[IO], T], res: ObjectResource[T])
  : Operations[IO, TestClient[IO], T]
  = new Operations(c)
}

class TestClientError(val e: ClientError) extends RuntimeException(e.throwable)

case class ResourceKind(kind: String)
object ResourceKind {
  def forClass[T](implicit res: HasKind[T]) = new ResourceKind(res.kind)
  implicit val eq: Eq[ResourceKind] = Eq.fromUniversalEquals
}
case class ResourceKey(kind: ResourceKind, id: Id[Any])
object ResourceKey {
  def id[T](id: Id[T])(implicit res: HasKind[T]) = ResourceKey(ResourceKind.forClass[T], id.asInstanceOf[Id[Any]])

  def cast[T](key: ResourceKey, v: Any)(implicit res: HasKind[T]): Option[T] = {
    if (key.kind === ResourceKind.forClass[T]) {
      Some(v.asInstanceOf[T])
    } else {
      None
    }
  }

  def castEvent[T](event: Event[TestClient.Entry])(implicit res: HasKind[T]): Option[Event[T]] = {
    event match {
      case Event.Updated((k, v)) => cast[T](k, v).map(Event.Updated.apply)
      case Event.Deleted((k, v)) => cast[T](k, v).map(Event.Deleted.apply)
    }
  }
}

class TestClientEngineImpl[IO[_], T]
  (implicit io: Concurrent[IO], eq: Eq[T], clock: Clock[IO], res: ObjectResource[T])
  extends Engine[IO, TestClient[IO], T] with Logging
{

  private def _get(state: TestClient.State, id: Id[T]): Option[T] = {
    state.get(ResourceKey.id(id)).map(_.asInstanceOf[T])
  }

  override def read(c: TestClient[IO], id: Id[T]): IO[Option[T]] = c.readState.map(map => _get(map, id))

  private val _notFound = new TestClientError(ClientError.NotFound(new RuntimeException("not found")))

  private def _nextVersion(t: T): T = {
    res.withVersion(t, res.version(t) match {
      case None => "1"
      case Some(other) => s"${other.toInt + 1}"
    })
  }

  private def _update(c: TestClient[IO], t: T): IO[Unit] = {
    c.modifyState_ { stateMap =>
      val id = res.id(t)
      val key = ResourceKey.id(id)

      val update: IO[(TestClient.State, Option[Event[TestClient.Entry]])] = {
        val existing = _get(stateMap, id)
        (existing, res.version(t)) match {
          case (Some(_), None) => io.raiseError(new RuntimeException(s"Attempted to create an existing resource: $id"))
          case (None, Some(_)) => io.raiseError(new RuntimeException(s"Attempted to update a nonexistent resource: $id"))
          case (None, None) => {
            logger.debug("[{}] creating", res.id(t))
            val written = _nextVersion(t)
            io.pure((stateMap.updated(key, written), Some(Event.Updated((key, written)))))
          }
          case (Some(existing), Some(updatingVersion)) => { // update
            if (!res.version(existing).contains_(updatingVersion)) {
              io.raiseError(new TestClientError(ClientError.VersionConflict(
                new RuntimeException(s"version conflict (stored: ${res.version(existing)}, writing: ${res.version(t)})"))))
            } else if (res.isSoftDeleted(t) && res.finalizers(t).isEmpty) {
              logger.debug("[{}] soft-deleted resource has no remaining finalizers; deleting it", res.id(t))
              io.pure((stateMap.removed(key), Some(Event.Deleted((key, t)))))
            } else if (existing === t) {
              // we don't emit an event on a no-op change, otherwise we'd reconcile indefinitely
              logger.debug("[{}] no-op update", res.id(t))
              io.pure((stateMap, None))
            } else {
              val written = _nextVersion(t)
              logger.debug("[{}] updated (new version: {})", res.id(t), res.version(written))
              io.pure((stateMap.updated(key, written), Some(Event.Updated((key, written)))))
            }
          }
        }
      }

      update.flatMap {
        case (newState, event) => {
          event.traverse(c.publish).as(newState)
        }
      }
    }
  }

  override def update(c: TestClient[IO], t: T): IO[Unit] = {
    if (res.version(t).isEmpty) {
      io.raiseError(
        new RuntimeException(s"Can't update a resource without a version (try create?): ${res.id(t)}"))
    } else {
      _update(c, t)
    }
  }

  override def create(c: TestClient[IO], t: T): IO[Unit] = {
    if (res.version(t).isDefined) {
      io.raiseError(
        new RuntimeException(s"Can't create a resource with a version (try update?): ${res.id(t)}"))
    } else {
      _update(c, t)
    }
  }

  override def updateStatus[St](c: TestClient[IO], t: T, st: St)(implicit sub: HasStatus[T, St]): IO[Unit] = update(c, sub.withStatus(t, st))

  override def classifyError(e: Throwable): ClientError = e match {
    case ce: TestClientError => ce.e
    case other: Throwable => ClientError.Unknown(other)
  }

  override def delete(c: TestClient[IO], id: Id[T]): IO[Unit] = {
    c.modifyState_ { stateMap =>
      val key = ResourceKey.id(id)
      _get(stateMap, id) match {
        case None => io.raiseError(_notFound)
        case Some(existing) =>
          if (res.finalizers(existing).isEmpty) {
            val event: Event[TestClient.Entry] = Event.Deleted((key, existing))
            c.publish(event).as(stateMap.removed(key))
          } else {
            if (res.isSoftDeleted(existing)) {
              // no-op
              io.pure(stateMap)
            } else {
              clock.realTime.flatMap { time =>
                val updated = res.softDeletedAt(existing, Instant.ofEpochSecond(time.toSeconds))
                val event: Event[TestClient.Entry] = Event.Updated((key, updated))
                c.publish(event).as(stateMap.updated(key, updated))
              }
            }
          }
      }
    }
  }

  override def listAndWatch(c: TestClient[IO], opts: ListOptions): IO[(List[T], fs2.Stream[IO, Event[T]])] = {
    if (opts != ListOptions.all) {
      logger.warn(s"Ignoring $opts (not implemented)")
    }
    for {
      resource <- c.topic.subscribeAwait(64).allocated
      stateMap <- c.readState
    } yield {
      val initial = stateMap.flatMap {
        case (k, v) => ResourceKey.cast[T](k, v).toList
      }.toList
      logger.debug("listAndWatch returning {} initial items", initial.length)
      val (updates, release) = resource
      (initial, updates.mapFilter(ResourceKey.castEvent[T]).onFinalize(release))
    }
  }
}