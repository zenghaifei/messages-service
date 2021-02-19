package routes

import actors.{GroupWsChatEntity, UserWsChatEntity}
import akka.Done
import akka.actor.ActorRef
import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.adapter._
import akka.cluster.sharding.typed.scaladsl.ClusterSharding
import akka.event.slf4j.SLF4JLogging
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.model.ws.{Message, TextMessage}
import akka.http.scaladsl.model.{HttpResponse, StatusCodes}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.stream.FlowMonitorState.Failed
import akka.stream.scaladsl.{Flow, Keep, Sink, Source}
import akka.stream.{CompletionStrategy, OverflowStrategy}
import akka.util.Timeout
import org.reactivestreams.Publisher
import routes.WebsocketRouter.{JoinChatGroupRequest, JsonSupport, MsgType, SendOutChatMessageRequest}
import services.UserService
import spray.json.{DefaultJsonProtocol, _}

import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, Future}
import scala.util.{Failure, Success}


/**
 * routes
 *
 * @author colin
 * @version 1.0, 2021/1/29
 * @since 0.4.1
 */
object WebsocketRouter {

  trait JsonSupport extends SprayJsonSupport with DefaultJsonProtocol {
    implicit val f1 = jsonFormat3(SendOutChatMessageRequest)
    implicit val f2 = jsonFormat2(JoinChatGroupRequest)
  }

  object MsgType {
    val P2P = "P2P"
    val P2G = "P2G"
  }

  final case class SendOutChatMessageRequest(msgType: String, receiver: Long, msg: String)

  final case class JoinChatGroupRequest(userId: Long, groupId: Long)

}

class WebsocketRouter(userService: UserService)(implicit val system: ActorSystem[_]) extends JsonSupport with SLF4JLogging {
  implicit val ec = system.executionContext
  implicit val timeout = Timeout(2.seconds)
  val clusterSharding = ClusterSharding(system)

  private def chatMessageHandleFlow(userId: Long): Flow[Message, Message, Any] = {
    val userChatEntity = UserWsChatEntity.selectEntity(userId, clusterSharding)
    val sink = Flow[Message].mapAsync(1) {
      case tm: TextMessage =>
        tm.toStrict(3.seconds).map(_.text)
          .flatMap { msg =>
            val SendOutChatMessageRequest(msgType, receiver, message) = msg.parseJson.convertTo[SendOutChatMessageRequest]
            msgType match {
              case MsgType.P2P =>
                userChatEntity.ask(replyTo => UserWsChatEntity.SendOutP2pMsg(receiver, message, replyTo))
              case MsgType.P2G =>
                userChatEntity.ask(replyTo => UserWsChatEntity.SendOutP2gMsg(receiver, message, replyTo))
            }
          }
      case _ =>
        Future.failed(new Exception("unsupported binaryMessage"))
    }.toMat(Sink.ignore)(Keep.left)

    val completionMatcher: PartialFunction[Any, CompletionStrategy] = {
      case Done =>
        CompletionStrategy.immediately
    }

    val failureMatcher: PartialFunction[Any, Throwable] = {
      case Failed =>
        new Exception("failured")
    }

    val (wsActor: ActorRef, publisher: Publisher[Message]) = Source.actorRef[String](
      completionMatcher = completionMatcher,
      failureMatcher = failureMatcher,
      bufferSize = 30,
      overflowStrategy = OverflowStrategy.fail
    )
      .map(TextMessage(_))
      .toMat(Sink.asPublisher[Message](fanout = false))(Keep.both)
      .run()

    val registerResultF = userChatEntity.ask(replyTo => UserWsChatEntity.RegisterWsActor(wsActor.toTyped[String], replyTo))
    Await.result(registerResultF, 2.seconds)

    val source = Source.fromPublisher(publisher)
    Flow.fromSinkAndSourceCoupled(sink, source)
  }

  private def chat: Route = (path("messages" / "public" / "websocket" / "chat") & parameter("token")) { token =>
    val userIdOptF = this.userService.verifyToken(token)
    onComplete(userIdOptF) {
      case Failure(ex) =>
        log.warn("verify token failure, msg: {}", ex.getMessage)
        complete(HttpResponse(StatusCodes.Unauthorized))
      case Success(userIdOpt) =>
        userIdOpt match {
          case None =>
            complete(HttpResponse(StatusCodes.Unauthorized))
          case Some(userId) =>
            handleWebSocketMessages(chatMessageHandleFlow(userId))
        }
    }
  }

  private def joinGroup: Route = (path("messages" / "websocket" / "chat_group" / "join")) {
    entity(as[JoinChatGroupRequest]) { case JoinChatGroupRequest(userId, groupId) =>
      val groupEntity = GroupWsChatEntity.selectEntity(groupId, clusterSharding)
      val joinResultF = groupEntity.askWithStatus(replyTo => GroupWsChatEntity.JoinGroup(userId, replyTo))
      onComplete(joinResultF) {
        case Success(_) =>
          complete(HttpResponse(StatusCodes.OK))
        case Failure(ex) =>
          complete(JsObject(
            "code" -> JsNumber(1),
            "msg" -> JsString(ex.getMessage)
          ))
      }
    }
  }

  val routes: Route = concat(
    chat,
    joinGroup
  )
}
