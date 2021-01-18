package actors

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, Behavior, SupervisorStrategy}
import akka.cluster.sharding.typed.ShardingEnvelope
import akka.cluster.sharding.typed.scaladsl.{ClusterSharding, Entity, EntityRef, EntityTypeKey}
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.{Effect, EventSourcedBehavior, RetentionCriteria}

import scala.concurrent.duration.DurationInt

/**
 * actors
 *
 * @author colin
 * @version 1.0, 2021/1/7
 * @since 0.4.1
 */
object UserWebsocketsEntity {

  // command
  sealed trait Command extends JacksonCborSerializable

  // reply
  sealed trait Reply extends JacksonCborSerializable

  // event
  sealed trait Event extends JacksonJsonSerializable

  // state
  final case class State() extends JacksonCborSerializable {

    def applyCommand(command: Command, userId: Long): Effect[Event, State] = ???

    def applyEvent(event: Event): State = ???
  }

  val TypeKey = EntityTypeKey[Command]("user-websockets")

  def shardRegion(sharding: ClusterSharding): ActorRef[ShardingEnvelope[Command]] =
    sharding.init {
      Entity(TypeKey) { entityContext =>
        UserWebsocketsEntity(entityContext.entityId, PersistenceId(entityContext.entityTypeKey.name, entityContext.entityId))
      }
    }

  def selectEntity(userId: Long, sharding: ClusterSharding): EntityRef[Command] = sharding.entityRefFor(TypeKey, userId.toString())

  def apply(entityId: String, persistenceId: PersistenceId): Behavior[Command] = Behaviors.setup { context =>
    context.log.info("starting userWebsocketsEntity, userId: {}", entityId)
    val userId = entityId.toLong

    EventSourcedBehavior[Command, Event, State](
      persistenceId = persistenceId,
      emptyState = State(),
      commandHandler = (state, command) => state.applyCommand(command, userId),
      eventHandler = (state, event) => state.applyEvent(event)
    )
      .withRetention(RetentionCriteria.snapshotEvery(20, 1))
      .onPersistFailure(SupervisorStrategy.restartWithBackoff(200.millis, 5.seconds, 0.1))
  }
}
