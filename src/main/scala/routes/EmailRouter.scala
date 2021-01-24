package routes

import actors.EmergencyEmailSendPersistentBehavior
import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.AskPattern.Askable
import akka.event.slf4j.SLF4JLogging
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.util.Timeout
import services.EmailService
import spray.json.{DefaultJsonProtocol, JsNumber, JsObject, JsString}

import java.time.LocalDateTime
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import scala.util.{Failure, Success}

/**
 * routes
 *
 * @author colin
 * @version 1.0, 2021/1/24
 * @since 0.4.1
 */
final case class SendEmailRequest(receiver: String, subject: String, content: String)

trait JsonSupport extends SprayJsonSupport with DefaultJsonProtocol {
  implicit val f1 = jsonFormat3(SendEmailRequest)
}

class EmailRouter(emailService: EmailService)(implicit system: ActorSystem[_]) extends SLF4JLogging with JsonSupport {
  implicit val timeout: Timeout = 3.seconds
  implicit val ec = system.executionContext
  implicit val scheduler = akka.actor.typed.scaladsl.AskPattern.schedulerFromActorSystem(system)

  private def sendEmergencyEmail = (post & path("messages" / "admin" / "email" / "emergency" / "send")) {
    val now = LocalDateTime.now()
    entity(as[SendEmailRequest]) { case SendEmailRequest(receiver, subject, content) =>
      val sendResultF =
        if (this.emailService.isInvalidEmail(receiver)) {
          Future(complete(status = StatusCodes.BadRequest, JsObject("code" -> JsNumber(1), "msg" -> JsString(s"receiver: ${receiver} is an invalid email format"))))
        }
        else if (subject.trim.isEmpty) {
          Future(complete(status = StatusCodes.BadRequest, JsObject("code" -> JsNumber(1), "msg" -> JsString("email subject can't be empty"))))
        }
        else {
          val emailSendActor = EmergencyEmailSendPersistentBehavior.initSingleton(this.system)
          val emailData = EmergencyEmailSendPersistentBehavior.EmailData(receiver, subject, content, now)
          emailSendActor.ask(replyTo => EmergencyEmailSendPersistentBehavior.ApplySendEmail(emailData, replyTo))
            .map(_ => complete(JsObject(
              "code" -> JsNumber(0),
              "msg" -> JsString("success")
            )))
        }

      onComplete(sendResultF) {
        case Success(result) => result
        case Failure(ex) =>
          val errorMsg = s"ask send email timedout, msg: ${ex.getMessage}, stack: ${ex.fillInStackTrace()}"
          log.warn(errorMsg)
          complete(status = StatusCodes.InternalServerError, JsObject("code" -> JsNumber(1), "msg" -> JsString(s"$errorMsg")))
      }
    }
  }

  val routes: Route = concat(
    sendEmergencyEmail
  )
}
