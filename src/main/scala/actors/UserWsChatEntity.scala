package actors

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, Behavior, SupervisorStrategy}
import akka.cluster.sharding.typed.ShardingEnvelope
import akka.cluster.sharding.typed.scaladsl.{ClusterSharding, Entity, EntityRef, EntityTypeKey}
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.pattern.StatusReply
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.{Effect, EventSourcedBehavior, RetentionCriteria}
import akka.util.Timeout
import spray.json.DefaultJsonProtocol

import scala.collection.mutable
import scala.concurrent.duration.DurationInt
import scala.util.{Failure, Success}

/**
 * actors
 *
 * @author colin
 * @version 1.0, 2021/1/7
 * @since 0.4.1
 */
object UserWsChatEntity extends SprayJsonSupport with DefaultJsonProtocol {

  // command
  sealed trait Command extends JacksonCborSerializable

  final case class RegisterWsActor(wsActor: ActorRef[String], replyTo: ActorRef[StatusReply[String]]) extends Command

  final case class UnRegisterWsActor(wsActor: ActorRef[String]) extends Command

  final case class SendOutMsg(msgType: String, receiver: Long, msg: String, replyTo: ActorRef[StatusReply[String]]) extends Command

  final case class SendInMsg(msgType: String, sender: Long, msg: String, replyTo: ActorRef[StatusReply[String]]) extends Command

  // event
  sealed trait Event extends JacksonJsonSerializable

  final case class MsgReceived(msgType: String, sender: Long, msg: String) extends Event

  final case object MsgsRead extends Event

  // state
  final case class State(msgs: mutable.ListBuffer[MsgReceived] = mutable.ListBuffer.empty) extends JacksonCborSerializable

  object MsgType {
    val P2P = "P2P"
    val P2G = "P2G"
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
    val clusterSharding = ClusterSharding(context.system)
    implicit val ec = context.executionContext
    implicit val timeout = Timeout(5.seconds)
    implicit val f1 = jsonFormat3(MsgReceived)
    val logger = context.log

    val wsActors = mutable.TreeSet.empty[ActorRef[String]]

    EventSourcedBehavior[Command, Event, State](
      persistenceId = persistenceId,
      emptyState = State(),
      commandHandler = (state, command) => command match {
        case RegisterWsActor(wsActor, replyTo) =>
          logger.info("registered wsActor, userId: {}", userId)
          wsActors.add(wsActor)
          context.watchWith(wsActor, UnRegisterWsActor(wsActor))
          if (wsActors.size == 1) {
            state.msgs.foreach(wsActor ! _.toJson.toString())
            Effect.persist(MsgsRead).thenReply(replyTo)(_ => StatusReply.Success(""))
          }
          else {
            Effect.none.thenReply(replyTo)(_ => StatusReply.Success(""))
          }
        case UnRegisterWsActor(wsActor) =>
          logger.info("unRegistered wsActor, userId: {}", userId)
          wsActors.remove(wsActor)
          Effect.none.thenNoReply()
        case SendOutMsg(msgType, receiver, msg, replyTo) =>
          msgType match {
            case MsgType.P2P =>
              val receiverChatEntity = UserWsChatEntity.selectEntity(receiver, clusterSharding)
              val sendResultF = receiverChatEntity.askWithStatus(actorRef => SendInMsg(msgType, userId, msg, actorRef))
              sendResultF.onComplete {
                case Success(_) => ()
                case Failure(ex) =>
                  logger.warn("ask receiver entity failed, sender: {}, receiver: {}, msg: {}, stack: {}", userId, receiver, ex.getMessage, ex.fillInStackTrace())
              }
              Effect.none.thenReply(replyTo)(_ => StatusReply.Success(""))
            case msgType: String =>
              logger.warn("unsupported msg type: {}", msgType)
              Effect.none.thenReply(replyTo)(_ => StatusReply.Success(""))
          }
        case SendInMsg(msgType, sender, msg, replyTo) =>
          val chatMessageReceived = MsgReceived(msgType, sender, msg)
          wsActors.foreach { wsActor =>
            wsActor ! chatMessageReceived.toJson.toString()
          }
          Effect.persist(chatMessageReceived).thenReply(replyTo)(_ => StatusReply.Success(""))
      },
      eventHandler = (state, event) => event match {
        case event: MsgReceived =>
          if (wsActors.isEmpty) {
            state.msgs.addOne(event)
          }
          state
        case MsgsRead =>
          state.msgs.clear()
          state
      }
    )
      .withRetention(RetentionCriteria.snapshotEvery(20, 1))
      .onPersistFailure(SupervisorStrategy.restartWithBackoff(200.millis, 5.seconds, 0.1))
  }
}
