package actors

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, Behavior, SupervisorStrategy}
import akka.cluster.sharding.typed.ShardingEnvelope
import akka.cluster.sharding.typed.scaladsl.{ClusterSharding, Entity, EntityRef, EntityTypeKey}
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.pattern.StatusReply
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.{Effect, EventSourcedBehavior, RetentionCriteria}
import spray.json.DefaultJsonProtocol

import scala.collection.mutable.ListBuffer
import scala.concurrent.duration.DurationInt

/**
 * actors
 *
 * @author colin
 * @version 1.0, 2021/2/18
 * @since 0.4.1
 */
object GroupWsChatEntity extends SprayJsonSupport with DefaultJsonProtocol{
  // command
  sealed trait Command extends JacksonCborSerializable

  final case class JoinGroup(userId: Long, replyTo: ActorRef[StatusReply[String]]) extends Command

  final case class LeaveGroup(userId: Long, replyTo: ActorRef[StatusReply[String]]) extends Command

  final case class BroadcastMsg(sender: Long, msg: String, replyTo: ActorRef[StatusReply[String]]) extends Command

  // event
  sealed trait Event extends JacksonJsonSerializable

  final case class UserJoined(userId: Long) extends Event

  final case class UserLeaved(userId: Long) extends Event

  final case class MsgReceived(sender: Long, msg: String) extends Event

  // state
  final case class State(members: ListBuffer[Long] = ListBuffer.empty) extends JacksonCborSerializable

  val TypeKey = EntityTypeKey[Command]("group-websockets")

  def shardRegion(sharding: ClusterSharding): ActorRef[ShardingEnvelope[Command]] =
    sharding.init {
      Entity(TypeKey) { entityContext =>
        GroupWsChatEntity(entityContext.entityId, PersistenceId(entityContext.entityTypeKey.name, entityContext.entityId))
      }
    }

  def selectEntity(groupId: Long, sharding: ClusterSharding): EntityRef[Command] = sharding.entityRefFor(TypeKey, groupId.toString())

  def apply(entityId: String, persistenceId: PersistenceId): Behavior[Command] = Behaviors.setup { context =>
    context.log.info("starting userWebsocketsEntity, userId: {}", entityId)
    val groupId = entityId.toLong
    val clusterSharding = ClusterSharding(context.system)
    implicit val ec = context.executionContext

    EventSourcedBehavior[Command, Event, State](
      persistenceId = persistenceId,
      emptyState = State(),
      commandHandler = (state, command) => {
        command match {
          case JoinGroup(userId, replyTo) =>
            if (!state.members.contains(userId)) {
              Effect.persist(UserJoined(userId)).thenReply(replyTo)(_ => StatusReply.Success(""))
            }
            else {
              Effect.none.thenReply(replyTo)(_ => StatusReply.Success(""))
            }
          case LeaveGroup(userId, replyTo) =>
            if (state.members.contains(userId)) {
              Effect.persist(UserLeaved(userId)).thenReply(replyTo)(_ => StatusReply.Success(""))
            }
            else {
              Effect.none.thenReply(replyTo)(_ => StatusReply.Success(""))
            }
          case BroadcastMsg(sender, msg, replyTo) =>
            if (state.members.contains(sender)) {
              state.members.foreach { member =>
                val memberWsEntity = UserWsChatEntity.selectEntity(member, clusterSharding)
                memberWsEntity ! UserWsChatEntity.SendInG2pMsg(groupId, sender, msg)
              }
              Effect.persist(MsgReceived(sender, msg)).thenReply(replyTo)(_ => StatusReply.Success(""))
            }
            else {
              Effect.none.thenReply(replyTo)(_ => StatusReply.Success(""))
            }
        }
      },
      eventHandler = (state, event) => {
        event match {
          case UserJoined(userId) =>
            state.members.addOne(userId)
            state
          case UserLeaved(userId) =>
            state.members.subtractOne(userId)
            state
          case MsgReceived(sender, msg) =>
            state
        }
      }
    )
      .withRetention(RetentionCriteria.snapshotEvery(20, 1))
      .onPersistFailure(SupervisorStrategy.restartWithBackoff(200.millis, 5.seconds, 0.1))
  }


}
