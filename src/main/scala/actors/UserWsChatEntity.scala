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

  final case class SendOutP2pMsg(receiver: Long, msg: String, replyTo: ActorRef[StatusReply[String]]) extends Command

  final case class SendOutP2pMsgSuccess(p2pMsgSent: P2pMsgSent, replyTo: ActorRef[StatusReply[String]]) extends Command

  final case class SendInP2pMsg(sender: Long, msg: String, replyTo: ActorRef[StatusReply[String]]) extends Command

  final case class SendOutP2gMsg(groupId: Long, msg: String, replyTo: ActorRef[StatusReply[String]]) extends Command

  final case class SendOutP2gMsgSuccess(p2gMsgSent: P2gMsgSent, replyTo: ActorRef[StatusReply[String]]) extends Command

  final case class SendInG2pMsg(groupId: Long, sender: Long, msg: String) extends Command

  final case class SendFail(msg: String, replyTo: ActorRef[StatusReply[String]]) extends Command

  // event
  sealed trait Event extends JacksonJsonSerializable

  final case class P2pMsgSent(receiver: Long, msg: String) extends Event

  final case class P2pMsgReceived(sender: Long, msg: String) extends Event

  final case class P2gMsgSent(groupId: Long, msg: String) extends Event

  final case class G2pMsgReceived(groupId: Long, sender: Long, msg: String) extends Event

  final case object MsgsRead extends Event

  // state
  final case class State(p2pMsgs: mutable.ListBuffer[P2pMsgReceived] = mutable.ListBuffer.empty,
                         g2pMsgs: mutable.ListBuffer[G2pMsgReceived] = mutable.ListBuffer.empty) extends JacksonCborSerializable

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
    implicit val f1 = jsonFormat2(P2pMsgReceived)
    implicit val f2 = jsonFormat3(G2pMsgReceived)
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
            state.p2pMsgs.foreach(wsActor ! _.toJson.toString())
            state.g2pMsgs.foreach(wsActor ! _.toJson.toString())
            Effect.persist(MsgsRead).thenReply(replyTo)(_ => StatusReply.Success(""))
          }
          else {
            Effect.none.thenReply(replyTo)(_ => StatusReply.Success(""))
          }
        case UnRegisterWsActor(wsActor) =>
          logger.info("unRegistered wsActor, userId: {}", userId)
          wsActors.remove(wsActor)
          Effect.none.thenNoReply()
        case SendOutP2pMsg(receiver, msg, replyTo) =>
          val receiverChatEntity = UserWsChatEntity.selectEntity(receiver, clusterSharding)
          context.askWithStatus(receiverChatEntity, actorRef => SendInP2pMsg(userId, msg, actorRef)) {
            case Success(_) =>
              val p2pMsgSent = P2pMsgSent(receiver, msg)
              SendOutP2pMsgSuccess(p2pMsgSent, replyTo)
            case Failure(ex) =>
              logger.warn("ask receiver entity failed, sender: {}, receiver: {}, msg: {}, stack: {}", userId, receiver, ex.getMessage, ex.fillInStackTrace())
              SendFail(ex.getMessage, replyTo)
          }
          Effect.none.thenNoReply()
        case SendOutP2pMsgSuccess(p2pMsgSent, replyTo) =>
          Effect.persist(p2pMsgSent).thenReply(replyTo)(_ => StatusReply.Success(""))
        case SendInP2pMsg(sender, msg, replyTo) =>
          val msgReceived = P2pMsgReceived(sender, msg)
          wsActors.foreach(_ ! msgReceived.toJson.toString())
          Effect.persist(msgReceived).thenReply(replyTo)(_ => StatusReply.Success(""))
        case SendOutP2gMsg(groupId, msg, replyTo) =>
          val groupEntity = GroupWsChatEntity.selectEntity(groupId, clusterSharding)
          context.askWithStatus(groupEntity, actorRef => GroupWsChatEntity.BroadcastMsg(userId, msg, actorRef)) {
            case Success(_) =>
              val p2gMsgSent = P2gMsgSent(groupId, msg)
              SendOutP2gMsgSuccess(p2gMsgSent, replyTo)
            case Failure(ex) =>
              logger.warn("ask receiver entity failed, sender: {}, groupId: {}, msg: {}, stack: {}", userId, groupId, ex.getMessage, ex.fillInStackTrace())
              SendFail(ex.getMessage, replyTo)
          }
          Effect.none.thenNoReply()
        case SendOutP2gMsgSuccess(p2gMsgSent, replyTo) =>
          Effect.persist(p2gMsgSent).thenReply(replyTo)(_ => StatusReply.Success(""))
        case SendInG2pMsg(groupId, sender, msg) =>
          val g2pMsgReceived = G2pMsgReceived(groupId, sender, msg)
          wsActors.foreach(_ ! g2pMsgReceived.toJson.toString())
          Effect.persist(g2pMsgReceived)
        case SendFail(msg, replyTo) =>
          Effect.none.thenReply(replyTo)(_ => StatusReply.Error(msg))
      },
      eventHandler = (state, event) => event match {
        case event: P2pMsgSent =>
          state
        case event: P2pMsgReceived =>
          if (wsActors.isEmpty) {
            state.p2pMsgs.addOne(event)
          }
          state
        case event: P2gMsgSent =>
          state
        case event: G2pMsgReceived =>
          if (wsActors.isEmpty) {
            state.g2pMsgs.addOne(event)
          }
          state
        case MsgsRead =>
          state.p2pMsgs.clear()
          state.g2pMsgs.clear()
          state
      }
    )
      .withRetention(RetentionCriteria.snapshotEvery(20, 1))
      .onPersistFailure(SupervisorStrategy.restartWithBackoff(200.millis, 5.seconds, 0.1))
  }
}
