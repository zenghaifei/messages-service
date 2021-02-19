package actors

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed._
import akka.cluster.typed.{ClusterSingleton, SingletonActor}

/**
 * actors
 *
 * @author colin
 * @version 1.0, 2021/1/21
 * @since 0.4.1
 */
object EmailSendDispatcherBehavior {

  def apply(): Behavior[EmailSenderBehavior.Command] = Behaviors.setup { context =>
    context.log.info("starting emailSendDispatcher actor")
    val props = MailboxSelector.fromConfig("akka.actor.custorm.mailbox.email-sender-bounded-stable-priority")
    val actorRef: ActorRef[EmailSenderBehavior.Command] = context.spawn(EmailSenderBehavior.apply(), "email-sender", props)

    Behaviors.receiveMessage[EmailSenderBehavior.Command] {
      case msg: EmailSenderBehavior.Command =>
        actorRef ! msg
        Behaviors.same
    }
  }

  def initSingleton(system: ActorSystem[_]): ActorRef[EmailSenderBehavior.Command] = {
    val singletonManager = ClusterSingleton(system)
    singletonManager.init {
      SingletonActor(Behaviors.supervise(EmailSenderBehavior()).onFailure[Exception](SupervisorStrategy.restart), "emailSendDispatcherActor")
    }
  }

}
