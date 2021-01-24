import actors.UserWebsocketsEntity
import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.Behaviors
import akka.cluster.sharding.typed.scaladsl.ClusterSharding
import akka.http.scaladsl.Http
import com.github.swagger.akka.SwaggerSite
import projections.{EmergencyEmailSendProjection, InstantEmailSendProjection, TimeUnlimitedEmailSendProjection}
import routes.EmailRouter
import services.EmailService

object App extends SwaggerSite {

  def main(args: Array[String]): Unit = {
    ActorSystem(Behaviors.setup[String] { context =>
      implicit val system = context.system
      val config = context.system.settings.config

      val sharding = ClusterSharding(system)
      UserWebsocketsEntity.shardRegion(sharding)

      val emailService = new EmailService(config)
      val emailRouter = new EmailRouter(emailService)
      val routes = concat(emailRouter.routes)
      val host = "0.0.0.0"
      val port = config.getInt("server.port")
      Http().newServerAt(host, port).bind(routes)

      EmergencyEmailSendProjection.init(system)
      InstantEmailSendProjection.init(system)
      TimeUnlimitedEmailSendProjection.init(system)
      context.log.info(s"server started at ${host}:${port}")
      Behaviors.same
    }, "messages-service")
  }
}

