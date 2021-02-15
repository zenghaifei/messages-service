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
object UserWsChatEntity {

  // command
  sealed trait Command extends JacksonCborSerializable

  final case class SendMessage(msg: String, replyTo: ActorRef[SendResult]) extends Command

  final case class RegisterWsActor(wsActor: ActorRef[String], replyTo: ActorRef[RegisterWsActorResult]) extends Command

  // reply
  sealed trait Reply extends JacksonCborSerializable

  sealed trait SendResult extends Reply

  final case object SendSuccess extends SendResult

  sealed trait RegisterWsActorResult extends Reply

  final case object RegisterWsActorSuccess extends RegisterWsActorResult

  // event
  sealed trait Event extends JacksonJsonSerializable

  // state
  final case class State(userId: Long, var wsActorOpt: Option[ActorRef[String]] = None) extends JacksonCborSerializable {

    def applyCommand(command: Command): Effect[Event, State] = {
      command match {
        case RegisterWsActor(wsActor, replyTo) =>
          this.wsActorOpt = Some(wsActor)
          Effect.none.thenReply(replyTo)(_ => RegisterWsActorSuccess)
        case SendMessage(msg, replyTo) =>
          this.wsActorOpt.foreach(_.tell("hello, " + msg))
          Effect.none.thenReply(replyTo)(_ => SendSuccess)
      }
    }

    def applyEvent(event: Event): State = {
      // todo: to be implemented
      this
    }
  }

  val TypeKey = EntityTypeKey[Command]("user-websockets")

  def shardRegion(sharding: ClusterSharding): ActorRef[ShardingEnvelope[Command]] =
    sharding.init {
      Entity(TypeKey) { entityContext =>
        UserWsChatEntity(entityContext.entityId, PersistenceId(entityContext.entityTypeKey.name, entityContext.entityId))
      }
    }

  def selectEntity(userId: Long, sharding: ClusterSharding): EntityRef[Command] = sharding.entityRefFor(TypeKey, userId.toString())

  def apply(entityId: String, persistenceId: PersistenceId): Behavior[Command] = Behaviors.setup { context =>
    context.log.info("starting userWebsocketsEntity, userId: {}", entityId)
    val userId = entityId.toLong

    EventSourcedBehavior[Command, Event, State](
      persistenceId = persistenceId,
      emptyState = State(userId = userId),
      commandHandler = (state, command) => state.applyCommand(command),
      eventHandler = (state, event) => state.applyEvent(event)
    )
      .withRetention(RetentionCriteria.snapshotEvery(20, 1))
      .onPersistFailure(SupervisorStrategy.restartWithBackoff(200.millis, 5.seconds, 0.1))
  }
}
