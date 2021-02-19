package services

import akka.actor.typed.ActorSystem
import akka.event.slf4j.SLF4JLogging
import com.typesafe.config.Config

import scala.concurrent.Future

/**
 * services
 *
 * @author colin
 * @version 1.0, 2021/1/29
 * @since 0.4.1
 */
class UserService(config: Config)(implicit system: ActorSystem[_]) extends SLF4JLogging {
  private implicit val ec = this.system.executionContext
  private val userServiceHost = this.config.getString("services.user-service")
  private val authUrl = s"http://${this.userServiceHost}/user/auth"

  def verifyToken(token: String): Future[Option[Long]] = {
//    val request = HttpRequest(
//      method = HttpMethods.GET,
//      uri = this.authUrl,
//      headers = List(headers.Authorization(headers.OAuth2BearerToken(token)))
//    )
//    Http().singleRequest(request)
//      .map { response =>
//        if (response.status.isFailure()) {
//          Option.empty[Long]
//        }
//        else {
//          val optionalUserId: Optional[Long] = response.getHeader(DefinedHeaders.xForwardedUser)
//            .map(_.value())
//            .map(_.toLong)
//          if (optionalUserId.isPresent) {
//            Some(optionalUserId.get())
//          } else {
//            Option.empty[Long]
//          }
//        }
//      }
    Future(Some(token.toLong))
  }

}
