package actors

import akka.actor.PoisonPill
import akka.actor.typed.scaladsl.{Behaviors, StashBuffer}
import akka.actor.typed.{ActorRef, Behavior}
import akka.dispatch.{BoundedStablePriorityMailbox, PriorityGenerator}
import services.EmailService

import scala.concurrent.duration.DurationInt
import scala.util.{Failure, Success}

/**
 * actors
 *
 * @author colin
 * @version 1.0, 2021/1/21
 * @since 0.4.1
 */
object EmailSenderBehavior {

  // command
  trait Command extends JacksonCborSerializable

  object EmailType {
    val emergency = "emergency"
    val instant = "instant"
    val timeUnlimited = "timeUnlimited"
  }

  final case class SendEmail(receiver: String, subject: String, content: String, emailType: String, replyTo: ActorRef[Reply]) extends Command

  final case class SendOutSuccess(replyTo: ActorRef[Reply]) extends Command

  final case class SendOutFailed(msg: String, replyTo: ActorRef[Reply]) extends Command

  // reply
  sealed trait Reply extends JacksonCborSerializable

  final case object SendEmailSuccess extends Reply

  final case class SendEmailFailed(msg: String) extends Reply

  // state
  sealed trait State extends JacksonCborSerializable

  final case object SendingState extends State

  final case object ReadyState extends State

  // mailbox
  class MessageQueue extends BoundedStablePriorityMailbox(
    PriorityGenerator {
      case SendOutSuccess | SendOutFailed => 0
      case email: SendEmail =>
        email.emailType match {
          case EmailType.emergency => 1
          case EmailType.instant => 2
          case EmailType.timeUnlimited => 4
        }
      case PoisonPill => 3
      case _ => 4
    }, 1000, 100.millis)

  def apply(): Behavior[Command] = Behaviors.setup { context =>
    context.log.info("starting email sender actor")
    val config = context.system.settings.config
    val emailService = new EmailService(config)

    def updated(state: State): Behavior[Command] = {
      Behaviors.withStash(100) { buffer: StashBuffer[Command] =>
        Behaviors.receiveMessage[Command] {
          case msg@SendEmail(receiver, subject, content, _, replyTo) =>
            state match {
              case ReadyState =>
                val sendF = emailService.sendEmail(receiver, subject, content)
                context.pipeToSelf(sendF) {
                  case Success(_) => SendOutSuccess(replyTo)
                  case Failure(ex) =>
                    val errorMsg = s"send email error, subject: ${subject}, receiver: ${receiver}, msg: ${ex.getMessage}, cause: ${ex.fillInStackTrace()}"
                    context.log.warn(errorMsg)
                    SendOutFailed(errorMsg, replyTo)
                }
                updated(SendingState)
              case SendingState =>
                buffer.stash(msg)
                Behaviors.same
            }
          case SendOutSuccess(replyTo) =>
            replyTo ! SendEmailSuccess
            buffer.unstash(updated(ReadyState), 1, command => command)
          case SendOutFailed(msg, replyTo) =>
            replyTo ! SendEmailFailed(msg)
            updated(ReadyState)
        }
      }
    }

    updated(ReadyState)
  }

}
