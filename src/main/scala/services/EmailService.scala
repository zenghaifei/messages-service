package services

import com.typesafe.config.Config
import courier.Defaults._
import courier._
import org.xbill.DNS.{Lookup, Type}
import utils.StringUtils

import scala.concurrent.Future

/**
 * 邮件服务
 *
 * @author colin
 * @version 1.0, 2021/1/20
 * @since 0.4.1
 */
class EmailService(config: Config) {

  val emailServer = this.config.getString("email.server")
  val emailServicePort = this.config.getInt("email.port")
  val emailSender = this.config.getString("email.sender")
  val emailPassword = this.config.getString("email.password")

  val mailer = Mailer(emailServer, emailServicePort)
    .auth(true)
    .as(this.emailSender, this.emailPassword)
    .ssl(true)()

  def sendEmail(receiver: String, subject: String, content: String): Future[Unit] = {
    mailer {
      Envelope.from(this.emailSender.addr)
        .to(receiver.addr)
        .subject(subject)
        .content(Multipart().html(content))
    }
  }

  def isValidEmail(email: String): Boolean = {
    if (!StringUtils.isValidEmailFormat(email)) {
      return false
    }
    val domainPart = email.split("@")(1)
    val lookup = new Lookup(domainPart, Type.MX)
    lookup.run() != null
  }

  def isInvalidEmail(email: String): Boolean = !isValidEmail(email)

}
