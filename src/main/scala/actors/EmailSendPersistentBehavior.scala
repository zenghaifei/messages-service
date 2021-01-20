package actors

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, ActorSystem, Behavior, SupervisorStrategy}
import akka.cluster.typed.{ClusterSingleton, SingletonActor}
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.{Effect, EventSourcedBehavior, RetentionCriteria}

import java.time.LocalDateTime
import scala.concurrent.duration.DurationInt

/**
 * 发送邮件actor
 *
 * @author colin
 * @version 1.0, 2021/1/5
 * @since 0.4.1
 */
object EmailSendPersistentBehavior {

  // command
  sealed trait Command extends JacksonCborSerializable

  case class EmailData(receiver: String, subject: String, content: String, sendTime: LocalDateTime)

  final case class ApplySendEmergencyEmail(emailData: EmailData, replyTo: ActorRef[ApplySendEmailResult]) extends Command

  final case class ApplySendInstantEmail(emailData: EmailData, overdueTime: LocalDateTime, replyTo: ActorRef[ApplySendEmailResult]) extends Command

  final case class ApplySendTimeUnlimitedEmail(emailData: EmailData, replyTo: ActorRef[ApplySendEmailResult]) extends Command

  // reply
  sealed trait Reply extends JacksonCborSerializable

  sealed trait ApplySendEmailResult extends Reply

  final case object ApplySendEmailSuccess extends ApplySendEmailResult

  final case object ApplySendEmailOverdued extends ApplySendEmailResult

  // event
  sealed trait Event extends JacksonJsonSerializable

  final case class EmergencyEmail(emailData: EmailData) extends Event

  final case class InstantEmail(emailData: EmailData, overdueTime: LocalDateTime) extends Event

  final case class TimeUnlimitedEmail(emailData: EmailData) extends Event

  // state
  final case class State() extends JacksonCborSerializable {

    def applyCommand(command: Command): Effect[Event, State] = {
      command match {
        case ApplySendEmergencyEmail(emailData, replyTo) =>
          val email = EmergencyEmail(emailData)
          Effect.persist(email).thenReply(replyTo)(_ => ApplySendEmailSuccess)
        case ApplySendInstantEmail(emailData, overdueTime, replyTo) =>
          val now = LocalDateTime.now()
          if (now.isAfter(overdueTime)) {
            Effect.none.thenReply(replyTo)(_ => ApplySendEmailOverdued)
          }
          else {
            val instantEmail = InstantEmail(emailData, overdueTime)
            Effect.persist(instantEmail).thenReply(replyTo)(_ => ApplySendEmailSuccess)
          }
        case ApplySendTimeUnlimitedEmail(emailData, replyTo) =>
          val email = TimeUnlimitedEmail(emailData)
          Effect.persist(email).thenReply(replyTo)(_ => ApplySendEmailSuccess)
      }
    }

  }

  def persistenceId() = PersistenceId.ofUniqueId("email-send")

  def tag = "email-send"

  def apply(): Behavior[Command] = Behaviors.setup { context =>
    context.log.info("starting email-send persistent actor")
    EventSourcedBehavior[Command, Event, State](
      persistenceId = persistenceId(),
      emptyState = State(),
      commandHandler = (state, command) => state.applyCommand(command),
      eventHandler = (state, _) => state
    )
      .withTagger(_ => Set(tag))
      .withRetention(RetentionCriteria.snapshotEvery(50, 1))
      .onPersistFailure(SupervisorStrategy.restartWithBackoff(200.millis, 5.seconds, 0.1))
  }

  def initSingleton(system: ActorSystem[_]): ActorRef[Command] = {
    val singletonManager = ClusterSingleton(system)
    singletonManager.init {
      SingletonActor(Behaviors.supervise(EmailSendPersistentBehavior()).onFailure[Exception](SupervisorStrategy.restart), "emailSender")
    }
  }

}
