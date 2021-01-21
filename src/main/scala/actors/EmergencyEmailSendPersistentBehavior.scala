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
object EmergencyEmailSendPersistentBehavior {

  // command
  sealed trait Command extends JacksonCborSerializable

  case class EmailData(receiver: String, subject: String, content: String, sendTime: LocalDateTime)

  final case class ApplySendEmail(emailData: EmailData, replyTo: ActorRef[Reply]) extends Command

  // reply
  sealed trait Reply extends JacksonCborSerializable

  final case object ApplySendEmailSuccess extends Reply

  // event
  sealed trait Event extends JacksonJsonSerializable

  final case class Email(emailData: EmailData) extends Event

  // state
  final case class State() extends JacksonCborSerializable {

    def applyCommand(command: Command): Effect[Event, State] = {
      command match {
        case ApplySendEmail(emailData, replyTo) =>
          val email = Email(emailData)
          Effect.persist(email).thenReply(replyTo)(_ => ApplySendEmailSuccess)
      }
    }

  }

  def persistenceId() = PersistenceId.ofUniqueId(tag)

  def tag = "emergency-email-send"

  def apply(): Behavior[Command] = Behaviors.setup { context =>
    context.log.info("starting emergency-email-send persistent actor")
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
      SingletonActor(Behaviors.supervise(EmergencyEmailSendPersistentBehavior()).onFailure[Exception](SupervisorStrategy.restart), "emergencyEmailSend")
    }
  }

}
