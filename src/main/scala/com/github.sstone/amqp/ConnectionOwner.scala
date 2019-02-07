package com.github.sstone.amqp

import Amqp._
import akka.actor._
import akka.event.LoggingReceive
import akka.pattern.ask
import akka.util.Timeout
import com.rabbitmq.client.{Connection, ShutdownSignalException, ShutdownListener, ConnectionFactory, Address => RMQAddress}
import scala.concurrent.Await
import concurrent.duration._
import java.util.concurrent.ExecutorService
import scala.util.{Failure, Success, Try}

object ConnectionOwner {

  sealed trait State

  case object Disconnected extends State

  case object Connected extends State

  case class Create(props: Props, name: Option[String] = None)

  case object CreateChannel

  def props(connFactory: ConnectionFactory, reconnectionDelay: FiniteDuration = 10000 millis,
            executor: Option[ExecutorService] = None, addresses: Option[Array[RMQAddress]] = None): Props = {
    connFactory.setAutomaticRecoveryEnabled(false) //Automatic recovery causing leaking connection in this library
    Props(new ConnectionOwner(connFactory, reconnectionDelay, executor, addresses))
  }

  def createChildActor(conn: ActorRef, channelOwner: Props, name: Option[String] = None, timeout: Timeout = 5000.millis): ActorRef = {
    val future = conn.ask(Create(channelOwner, name))(timeout).mapTo[ActorRef]
    Await.result(future, timeout.duration)
  }


  /**
   * creates an amqp uri from a ConnectionFactory. From the specs:
   * <ul>
   * <li>amqp_URI       = "amqp://" amqp_authority [ "/" vhost ]</li>
   * <li>amqp_authority = [ amqp_userinfo "@" ] host [ ":" port ]</li>
   * <li>amqp_userinfo  = username [ ":" password ]</li>
   * </ul>
   * @param cf connection factory
   * @return an amqp uri
   */
  def toUri(cf: ConnectionFactory): String = {
    "amqp://%s:%s@%s:%d/%s".format(cf.getUsername, cf.getPassword, cf.getHost, cf.getPort, cf.getVirtualHost)
  }

  def buildConnFactory(host: String = "localhost", port: Int = 5672, vhost: String = "/", user: String = "guest", password: String = "guest"): ConnectionFactory = {
    val connFactory = new ConnectionFactory()
    connFactory.setHost(host)
    connFactory.setPort(port)
    connFactory.setVirtualHost(vhost)
    connFactory.setUsername(user)
    connFactory.setPassword(password)
    connFactory
  }
}

/**
 * ConnectionOwner class, which holds an AMQP connection and handles re-connection
 * It is implemented as a state machine which 2 possible states
 * <ul>
 * <li>Disconnected, and it will try to connect to the broker at regular intervals</li>
 * <li>Connected; it is then holding a connection
 * </ul>
 * Connection owner is responsible for creating "channel aware" actor (channel are like virtual connections,
 * which are multiplexed on the underlying connection). The parent connection owner will automatically tell
 * its children when the connection is lost, and send them new channels when it comes back on.
 * YMMV, but it is a good practice to have few connections and several channels per connection
 * @param connFactory connection factory
 * @param reconnectionDelay delay between reconnection attempts
 */
class ConnectionOwner(connFactory: ConnectionFactory,
                      reconnectionDelay: FiniteDuration = 10000 millis,
                      executor: Option[ExecutorService] = None,
                      addresses: Option[Array[RMQAddress]] = None) extends Actor with ActorLogging {

  import ConnectionOwner._
  import context.dispatcher

  var connection: Option[Connection] = None
  val statusListeners = collection.mutable.HashSet.empty[ActorRef]

  val reconnectTimer = context.system.scheduler.schedule(10 milliseconds, reconnectionDelay, self, 'connect)

  override def postStop = connection.map{ c =>
    Try(if (c.isOpen) c.close())
      .recover{ case e => log.error(e, "Connection closing failed")}
  }

  override def unhandled(message: Any): Unit = message match {
    case Terminated(actor) if statusListeners.contains(actor) => {
      context.unwatch(actor)
      statusListeners.remove(actor)
    }
    case _ => super.unhandled(message)
  }

  /**
   * ask this connection owner to create a "channel aware" child
   * @param props actor creation properties
   * @param name optional actor name
   * @return a new actor
   */
  private def createChild(props: Props, name: Option[String]) = {
    // why isn't there an actorOf(props: Props, name: Option[String] = None) ?
    name match {
      case None => context.actorOf(props)
      case Some(actorName) => context.actorOf(props, actorName)
    }
  }

  def createConnection: Connection = {
    val conn = (executor, addresses) match {
      case (None, None) => connFactory.newConnection()
      case (Some(ex), None) => connFactory.newConnection(ex)
      case (None, Some(addr)) => connFactory.newConnection(addr)
      case (Some(ex), Some(addr)) => connFactory.newConnection(ex, addr)
    }
    conn.addShutdownListener(new ShutdownListener {
      def shutdownCompleted(cause: ShutdownSignalException) {
        self ! Shutdown(cause)
        statusListeners.map(a => a ! Disconnected)
       }
    })
    conn
  }

  // start in disconnected mode
  def receive = disconnected

  def disconnected: Receive = LoggingReceive {
    /**
     * connect to the broker
     */
    case 'connect => {
      log.debug(s"trying to connect ${toUri(connFactory)}")
      Try(createConnection) match {
        case Success(conn) => {
          log.info(s"connected to ${toUri(connFactory)}")
          statusListeners.map(a => a ! Connected)
          connection = Some(conn)
          context.children.foreach(_ ! conn.createChannel())
          context.become(connected(conn))
        }
        case Failure(cause) => {
          log.error(cause, "connection failed")
        }
      }
    }

    /**
     * add a status listener that will be sent Disconnected and Connected messages
     */
    case AddStatusListener(listener) => addStatusListener(listener)

    /**
     * create a "channel aware" child actor
     */
    case Create(props, name) => {
      val child = createChild(props, name)
      log.debug("creating child {} while in disconnected state", child)
      sender ! child
    }
  }

  def connected(conn: Connection): Receive = LoggingReceive {
    case 'connect => ()
    case Amqp.Ok(_, _) => ()
    case Abort(code, message) => {
      conn.abort(code, message)
      context.stop(self)
    }
    case Close(code, message, timeout) => {
      conn.close(code, message, timeout)
      context.stop(self)
    }
    case CreateChannel if !conn.isOpen =>
      statusListeners.foreach(_ ! Disconnected)
      context.become(disconnected)

    case CreateChannel => Try(conn.createChannel()) match {
      case Success(channel) => sender ! channel
      case Failure(cause) => {
        val namesOfListeners = statusListeners.map(_.path).mkString("[", ",", "]")
        log.error(cause, s"cannot create channel. Sending `Disconnected` to $namesOfListeners")
        statusListeners.foreach(_ ! Disconnected)
        context.become(disconnected)
      }
    }

    case AddStatusListener(listener) => {
      addStatusListener(listener)
      listener ! Connected
    }
    case Create(props, name) => {
      sender ! createChild(props, name)
    }
    case Shutdown(cause) => {
      log.error(cause, "connection lost")
      connection.foreach{ c =>
        Try(if (c.isOpen) c.close())
          .recover{ case e => log.error(e, "Connection closing failed")}
      }
      connection = None
      context.children.foreach(_ ! Shutdown(cause))
      self ! 'connect
      context.become(disconnected)
    }
  }

  private def addStatusListener(listener: ActorRef) {
    if (!statusListeners.contains(listener)) {
      context.watch(listener)
      statusListeners.add(listener)
      context.children.foreach(_ ! AddStatusListener(listener))
    }
  }
}

