package projections

import actors.EmailSenderBehavior.EmailType
import actors.EmergencyEmailSendPersistentBehavior.{EmailData, EmergencyEmail}
import actors.{EmailSendDispatcherBehavior, EmailSenderBehavior, EmergencyEmailSendPersistentBehavior}
import akka.Done
import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.AskPattern.Askable
import akka.cluster.sharding.typed.scaladsl.ShardedDaemonProcess
import akka.event.slf4j.SLF4JLogging
import akka.persistence.cassandra.query.scaladsl.CassandraReadJournal
import akka.projection.cassandra.scaladsl.CassandraProjection
import akka.projection.eventsourced.EventEnvelope
import akka.projection.eventsourced.scaladsl.EventSourcedProvider
import akka.projection.scaladsl.Handler
import akka.projection.{HandlerRecoveryStrategy, ProjectionBehavior, ProjectionId}
import akka.util.Timeout

import scala.concurrent.Future
import scala.concurrent.duration.DurationInt

/**
 * projections
 *
 * @author colin
 * @version 1.0, 2021/1/21
 * @since 0.4.1
 */
object EmergencyEmailSendProjection {

  def init(system: ActorSystem[_]) = ShardedDaemonProcess(system).init[ProjectionBehavior.Command](
    name = "emergency-email-send",
    numberOfInstances = 1,
    behaviorFactory = _ => ProjectionBehavior(build(system)),
    stopMessage = ProjectionBehavior.Stop
  )

  private def build(system: ActorSystem[_]) = CassandraProjection.atLeastOnce(
    projectionId = ProjectionId("emergency-email-send", "p1"),
    EventSourcedProvider.eventsByTag[EmergencyEmailSendPersistentBehavior.Event](system, CassandraReadJournal.Identifier, EmergencyEmailSendPersistentBehavior.tag),
    handler = () => new EmergencyEmailSendHandler(system)
  )
    .withSaveOffset(afterEnvelopes = 50, afterDuration = 1.seconds)
    .withRecoveryStrategy(HandlerRecoveryStrategy.retryAndFail(10, delay = 1.seconds))
    .withRestartBackoff(minBackoff = 200.millis, maxBackoff = 20.seconds, randomFactor = 0.1)
}

class EmergencyEmailSendHandler(system: ActorSystem[_]) extends Handler[EventEnvelope[EmergencyEmailSendPersistentBehavior.Event]] with SLF4JLogging {
  implicit val timeout: Timeout = 3.seconds
  implicit val ec = system.executionContext
  implicit val scheduler = akka.actor.typed.scaladsl.AskPattern.schedulerFromActorSystem(system)


  override def process(envelope: EventEnvelope[EmergencyEmailSendPersistentBehavior.Event]): Future[Done] = {
    envelope.event match {
      case EmergencyEmail(emailData: EmailData) =>
        val EmailData(receiver, subject, content, _) = emailData
        val emailSendDispatcherActor = EmailSendDispatcherBehavior.initSingleton(this.system)
        emailSendDispatcherActor.ask(ref => EmailSenderBehavior.SendEmail(receiver, subject, content, EmailType.emergency, ref))
        .map {
          case EmailSenderBehavior.SendEmailSuccess =>
            Done
          case EmailSenderBehavior.SendEmailFailed(msg) =>
            log.warn(s"send email failed, receiver: ${receiver}, subject: ${subject}, msg: ${msg}")
            Done
        }
    }
  }
}
