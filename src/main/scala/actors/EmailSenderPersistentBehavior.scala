package actors

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, ActorSystem, Behavior, SupervisorStrategy}
import akka.cluster.typed.{ClusterSingleton, SingletonActor}
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.{EventSourcedBehavior, RetentionCriteria}

import scala.concurrent.duration.DurationInt

/**
 * 发送邮件actor
 *
 * @author colin
 * @version 1.0, 2021/1/5
 * @since 0.4.1
 */
object EmailSenderPersistentBehavior {

  // command
  sealed trait Command extends JacksonCborSerializable

  // reply
  sealed trait Reply extends JacksonCborSerializable

  // event
  sealed trait Event extends JacksonJsonSerializable

  // state
  final case class State() extends JacksonCborSerializable

  def persistenceId() = PersistenceId.ofUniqueId("email-sender")

  def tag = "email-sender"

  def apply(): Behavior[Command] = Behaviors.setup { context =>
    context.log.info("starting email-sender persistent actor")
    EventSourcedBehavior[Command, Event, State](
      persistenceId = persistenceId(),
      emptyState = State(),
      commandHandler = (state, command) => ???,
      eventHandler = (state, event) => ???
    )
      .withTagger(_ => Set(tag))
      .withRetention(RetentionCriteria.snapshotEvery(50, 1))
      .onPersistFailure(SupervisorStrategy.restartWithBackoff(200.millis, 5.seconds, 0.1))
  }

  def initSingleton(system: ActorSystem[_]): ActorRef[Command] = {
    val singletonManager = ClusterSingleton(system)
    singletonManager.init {
      SingletonActor(Behaviors.supervise(EmailSenderPersistentBehavior()).onFailure[Exception](SupervisorStrategy.restart), "emailSender")
    }
  }

}
