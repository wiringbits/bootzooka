package com.softwaremill.bootzooka.user

import cats.implicits._
import com.softwaremill.bootzooka._
import com.softwaremill.bootzooka.email.{EmailData, EmailScheduler, EmailTemplates}
import com.softwaremill.bootzooka.infrastructure.Doobie._
import com.softwaremill.bootzooka.security.{ApiKey, ApiKeyService}
import com.softwaremill.bootzooka.util._
import com.softwaremill.tagging.@@
import com.typesafe.scalalogging.StrictLogging
import monix.execution.Scheduler.Implicits.global
import tsec.common.Verified

import scala.concurrent.duration.Duration

class UserService(
                   userModel: UserModel,
                   emailScheduler: EmailScheduler,
                   emailTemplates: EmailTemplates,
                   apiKeyService: ApiKeyService,
                   idGenerator: IdGenerator,
                   clock: Clock,
                   config: UserConfig
) extends StrictLogging {

  private val LoginAlreadyUsed = "Login already in use!"
  private val EmailAlreadyUsed = "E-mail already in use!"
  private val IncorrectLoginOrPassword = "Incorrect login/email or password"

  def registerNewUser(login: String, email: String, password: String): ConnectionIO[ApiKey] = {
    def failIfDefined(op: ConnectionIO[Option[User]], msg: String): ConnectionIO[Unit] = {
      op.flatMap {
        case None    => ().pure[ConnectionIO]
        case Some(_) => Fail.IncorrectInput(msg).raiseError[ConnectionIO, Unit]
      }
    }

    def checkUserDoesNotExist(): ConnectionIO[Unit] = {
      failIfDefined(userModel.findByLogin(login.lowerCased), LoginAlreadyUsed) >>
        failIfDefined(userModel.findByEmail(email.lowerCased), EmailAlreadyUsed)
    }

    def doRegister(): ConnectionIO[ApiKey] = {
      for {
        id <- idGenerator.nextId[User]().to[ConnectionIO]
        now <- clock.now().to[ConnectionIO]
        user = User(id, login, login.lowerCased, email.lowerCased, User.hashPassword(password), now)
        confirmationEmail = emailTemplates.registrationConfirmation(login)
        _ = logger.debug(s"Registering new user: ${user.emailLowerCased}, with id: ${user.id}")
        _ <- userModel.insert(user)
        _ <- emailScheduler(EmailData(email, confirmationEmail))
        apiKey <- apiKeyService.create(user.id, config.defaultApiKeyValid)
      } yield apiKey
    }

    for {
      _ <- UserRegisterValidator
        .validate(login, email, password)
        .fold(msg => Fail.IncorrectInput(msg).raiseError[ConnectionIO, Unit], _ => ().pure[ConnectionIO])
      _ <- checkUserDoesNotExist()
      apiKey <- doRegister()
    } yield apiKey
  }

  def findById(id: Id @@ User): ConnectionIO[User] = userOrNotFound(userModel.findById(id))

  def login(loginOrEmail: String, password: String, apiKeyValid: Option[Duration]): ConnectionIO[ApiKey] =
    for {
      user <- userOrNotFound(userModel.findByLoginOrEmail(loginOrEmail.lowerCased))
      _ <- verifyPassword(user, password, validationErrorMsg = IncorrectLoginOrPassword)
      apiKey <- apiKeyService.create(user.id, apiKeyValid.getOrElse(config.defaultApiKeyValid))
    } yield apiKey

  def changeUser(userId: Id @@ User, newLogin: String, newEmail: String): ConnectionIO[Unit] = {
    def changeLogin(newLogin: String): ConnectionIO[Boolean] = {
      val newLoginLowerCased = newLogin.lowerCased
      userModel.findByLogin(newLoginLowerCased).flatMap {
        case Some(user) if user.id != userId => Fail.IncorrectInput(LoginAlreadyUsed).raiseError[ConnectionIO, Boolean]
        case Some(user) if user.login == newLogin => false.pure[ConnectionIO]
        case _ =>
          for {
            _ <- validateLogin(newLogin)
            _ = logger.debug(s"Changing login for user: $userId, to: $newLogin")
            _ <- userModel.updateLogin(userId, newLogin, newLoginLowerCased)
          } yield true
      }
    }

    def validateLogin(newLogin: String) =
      UserRegisterValidator
        .validateLogin(newLogin)
        .fold(msg => Fail.IncorrectInput(msg).raiseError[ConnectionIO, Unit], _ => ().pure[ConnectionIO])

    def changeEmail(newEmail: String): ConnectionIO[Boolean] = {
      val newEmailLowerCased = newEmail.lowerCased
      userModel.findByEmail(newEmailLowerCased).flatMap {
        case Some(user) if user.id != userId => Fail.IncorrectInput(EmailAlreadyUsed).raiseError[ConnectionIO, Boolean]
        case Some(user) if user.emailLowerCased == newEmailLowerCased => false.pure[ConnectionIO]
        case _ =>
          for {
            _ <- validateEmail(newEmailLowerCased)
            _ = logger.debug(s"Changing email for user: $userId, to: $newEmail")
            _ <- userModel.updateEmail(userId, newEmailLowerCased)
          } yield true
      }
    }

    def validateEmail(newEmailLowerCased: String) =
      UserRegisterValidator
        .validateEmail(newEmailLowerCased)
        .fold(msg => Fail.IncorrectInput(msg).raiseError[ConnectionIO, Unit], _ => ().pure[ConnectionIO])

    def doChange(newLogin: String, newEmail: String): ConnectionIO[Boolean] = {
      for {
        loginUpdated <- changeLogin(newLogin)
        emailUpdated <- changeEmail(newEmail)
      } yield loginUpdated || emailUpdated
    }

    def sendMail(user: User) = {
      val confirmationEmail = emailTemplates.profileDetailsChangeNotification(user.login)
      emailScheduler(EmailData(user.emailLowerCased, confirmationEmail))
    }

    doChange(newLogin, newEmail) flatMap { anyUpdate =>
      if (anyUpdate) {
        findById(userId).flatMap(user => sendMail(user))
      } else {
        ().pure[ConnectionIO]
      }
    }
  }

  def changePassword(userId: Id @@ User, currentPassword: String, newPassword: String): ConnectionIO[Unit] =
    for {
      user <- userOrNotFound(userModel.findById(userId))
      _ <- verifyPassword(user, currentPassword, validationErrorMsg = "Incorrect current password")
      _ <- validatePassword(newPassword)
      _ = logger.debug(s"Changing password for user: $userId")
      _ <- userModel.updatePassword(userId, User.hashPassword(newPassword))
      confirmationEmail = emailTemplates.passwordChangeNotification(user.login)
      _ <- emailScheduler(EmailData(user.emailLowerCased, confirmationEmail))
    } yield ()

  private def userOrNotFound(op: ConnectionIO[Option[User]]): ConnectionIO[User] = {
    op.flatMap {
      case Some(user) => user.pure[ConnectionIO]
      case None       => Fail.Unauthorized(IncorrectLoginOrPassword).raiseError[ConnectionIO, User]
    }
  }

  private def verifyPassword(user: User, password: String, validationErrorMsg: String): ConnectionIO[Unit] = {
    if (user.verifyPassword(password) == Verified) {
      ().pure[ConnectionIO]
    } else {
      Fail.Unauthorized(validationErrorMsg).raiseError[ConnectionIO, Unit]
    }
  }

  private def validatePassword(password: String) =
    UserRegisterValidator
      .validatePassword(password)
      .fold(msg => Fail.IncorrectInput(msg).raiseError[ConnectionIO, Unit], _ => ().pure[ConnectionIO])
}

object UserRegisterValidator {
  private val ValidationOk = Right(())
  val MinLoginLength = 3

  def validate(login: String, email: String, password: String): Either[String, Unit] =
    for {
      _ <- validateLogin(login.trim)
      _ <- validateEmail(email.trim)
      _ <- validatePassword(password.trim)
    } yield ()

  def validateLogin(login: String): Either[String, Unit] =
    if (login.length >= MinLoginLength) ValidationOk else Left("Login is too short!")

  private val emailRegex =
    """^[a-zA-Z0-9.!#$%&'*+/=?^_`{|}~-]+@[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?(?:\.[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?)*$""".r

  def validateEmail(email: String): Either[String, Unit] =
    if (emailRegex.findFirstMatchIn(email).isDefined) ValidationOk else Left("Invalid e-mail format!")

  def validatePassword(password: String): Either[String, Unit] =
    if (password.nonEmpty) ValidationOk else Left("Password cannot be empty!")
}
