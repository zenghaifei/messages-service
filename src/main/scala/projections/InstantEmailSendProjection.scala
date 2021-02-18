package projections

import actors.EmailSenderBehavior.EmailType
import actors.InstantEmailSendPersistentBehavior.{EmailData, InstantEmail}
import actors.{EmailSendDispatcherBehavior, EmailSenderBehavior, InstantEmailSendPersistentBehavior}
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

import java.time.LocalDateTime
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt

/**
 * projections
 *
 * @author colin
 * @version 1.0, 2021/1/21
 * @since 0.4.1
 */
object InstantEmailSendProjection {

  def init(system: ActorSystem[_]) = ShardedDaemonProcess(system).init[ProjectionBehavior.Command](
    name = "instant-email-send",
    numberOfInstances = 1,
    behaviorFactory = _ => ProjectionBehavior(build(system)),
    stopMessage = ProjectionBehavior.Stop
  )

  private def build(system: ActorSystem[_]) = CassandraProjection.atLeastOnce(
    projectionId = ProjectionId("instant-email-send", "p1"),
    EventSourcedProvider.eventsByTag[InstantEmailSendPersistentBehavior.Event](system, CassandraReadJournal.Identifier, InstantEmailSendPersistentBehavior.tag),
    handler = () => new InstantEmailSendHandler(system)
  )
    .withSaveOffset(afterEnvelopes = 50, afterDuration = 1.seconds)
    .withRecoveryStrategy(HandlerRecoveryStrategy.retryAndFail(5, delay = 1.seconds))
    .withRestartBackoff(minBackoff = 200.millis, maxBackoff = 20.seconds, randomFactor = 0.1)
}

class InstantEmailSendHandler(system: ActorSystem[_]) extends Handler[EventEnvelope[InstantEmailSendPersistentBehavior.Event]] with SLF4JLogging {
  implicit val timeout: Timeout = 3.seconds
  implicit val ec = system.executionContext
  implicit val scheduler = akka.actor.typed.scaladsl.AskPattern.schedulerFromActorSystem(system)


  override def process(envelope: EventEnvelope[InstantEmailSendPersistentBehavior.Event]): Future[Done] = {
    envelope.event match {
      case InstantEmail(emailData: EmailData, overdueTime: LocalDateTime) =>
        val EmailData(receiver, subject, content, sendTime) = emailData
        val emailSendDispatcherActor = EmailSendDispatcherBehavior.initSingleton(this.system)
        val now = LocalDateTime.now()
        if (overdueTime.isBefore(now)) {
          log.warn(s"instant email overdued, receiver: ${receiver}, subject: ${subject}")
          Future(Done)
        }
        else {
          emailSendDispatcherActor.askWithStatus(ref => EmailSenderBehavior.SendEmail(receiver, subject, content, EmailType.instant, ref))
            .map(_ => Done)
            .recover { case ex: Throwable =>
              log.warn(s"send email failed, receiver: ${receiver}, subject: ${subject}, msg: ${ex.getMessage}")
              Done
            }
        }
    }
  }
}
