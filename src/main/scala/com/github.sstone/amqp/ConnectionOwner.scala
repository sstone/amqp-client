package com.github.sstone.amqp

import scala.util.{Failure, Success, Try}
import concurrent.Await
import concurrent.duration._
import java.io.IOException
import akka.util.Timeout
import akka.actor._
import akka.pattern.ask
import com.rabbitmq.client.{Connection, ShutdownSignalException, ShutdownListener, ConnectionFactory, Address => RMQAddress}
import Amqp._
import java.util.concurrent.ExecutorService


object ConnectionOwner {

  sealed trait State

  case object Disconnected extends State

  case object Connected extends State

  case class Create(props: Props, name: Option[String] = None)

  def props(connFactory: ConnectionFactory, reconnectionDelay: FiniteDuration = 10000.millis,
             executor: Option[ExecutorService] = None, addresses: Option[Array[RMQAddress]] = None) : Props =
    Props(classOf[ConnectionOwner], connFactory, reconnectionDelay, executor, addresses)

  private[amqp] sealed trait Data

  private[amqp] case object Uninitialized extends Data

  private[amqp] case class Connected(conn: Connection) extends Data

  private[amqp] case class CreateChannel()

  private[amqp] case class Shutdown(cause: ShutdownSignalException)

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
 * Helper class that encapsulates a connection owner so that it is easier to manipulate
 * @param host RabbitMQ Host. Default value from com.rabbitmq.ConnectionFactory is used if undefined
 * @param port RabbitMQ Port. Default value from com.rabbitmq.ConnectionFactory is used if undefined
 * @param vhost RabbitMQ Virtual Host.  Default value from com.rabbitmq.ConnectionFactory is used if undefined
 * @param user RabbitMQ User name.  Default value from com.rabbitmq.ConnectionFactory is used if undefined
 * @param password RabbitMQ User password.  Default value from com.rabbitmq.ConnectionFactory is used if undefined
 * @param name name of a ConnectionOwner actor
 * @param reconnectionDelay Period to reconnect
 * @param executor alternative ThreadPool for consumer threads (http://www.rabbitmq.com/api-guide.html#consumer-thread-pool)
 * @param addresses List of alternative addresses for a HA RabbitMQ cluster (http://www.rabbitmq.com/api-guide.html#address-array)
 * @param actorRefFactory Actor factory: System or Context
 */
class RabbitMQConnection(host: String = ConnectionFactory.DEFAULT_HOST, port: Int = ConnectionFactory.DEFAULT_AMQP_PORT,
                         vhost: String = ConnectionFactory.DEFAULT_VHOST, user: String = ConnectionFactory.DEFAULT_USER,
                         password: String = ConnectionFactory.DEFAULT_PASS, name: String,
                         reconnectionDelay: FiniteDuration = 10000.millis, executor: Option[ExecutorService] = None,
addresses: Option[Array[RMQAddress]] = None)(implicit actorRefFactory: ActorRefFactory) {

  import ConnectionOwner._

  lazy val owner = actorRefFactory.actorOf(
    Props(classOf[ConnectionOwner], buildConnFactory(host = host, port = port, vhost = vhost, user = user, password = password),
    reconnectionDelay, executor, addresses), name = name)

  def waitForConnection = Amqp.waitForConnection(actorRefFactory, owner)

  def stop() = actorRefFactory.stop(owner)

  def createChild(props: Props, name: Option[String] = None, timeout: Timeout = 5000.millis): ActorRef = {
    val future = owner.ask(Create(props, name))(timeout).mapTo[ActorRef]
    Await.result(future, timeout.duration)
  }

  def createChannelOwner(channelParams: Option[ChannelParameters] = None) = createChild(Props(classOf[ChannelOwner], channelParams))

  def createConsumer(bindings: List[Binding], listener: ActorRef, channelParams: Option[ChannelParameters], autoack: Boolean) = {
    createChild(Consumer.props(Some(listener), autoack, bindings.map(b => AddBinding(b)), channelParams))
  }

  def createConsumer(exchange: ExchangeParameters, queue: QueueParameters, routingKey: String, listener: ActorRef, channelParams: Option[ChannelParameters] = None, autoack: Boolean = false) = {
    createChild(Consumer.props(listener, exchange, queue, routingKey, channelParams, autoack))
  }

  def createRpcServer(bindings: List[Binding], processor: RpcServer.IProcessor, channelParams: Option[ChannelParameters]) = {
    createChild(Props(classOf[RpcServer], processor, bindings.map(b => AddBinding(b)), channelParams), None)
  }

  def createRpcServer(exchange: ExchangeParameters, queue: QueueParameters, routingKey: String, processor: RpcServer.IProcessor, channelParams: Option[ChannelParameters]) = {
    createChild(Props(classOf[RpcServer], processor, List(AddBinding(Binding(exchange, queue, routingKey))), channelParams), None)
  }

  def createRpcClient() = {
    createChild(Props[RpcClient])
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
class ConnectionOwner(connFactory: ConnectionFactory, reconnectionDelay: FiniteDuration = 10000.millis,
                      executor: Option[ExecutorService] = None, addresses: Option[Array[RMQAddress]] = None)
  extends Actor with FSM[ConnectionOwner.State, ConnectionOwner.Data] {

  import ConnectionOwner._

  override def preStart() {
    self ! 'connect
  }

  startWith(Disconnected, Uninitialized)

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

  when(Disconnected) {
    case Event('connect, _) => {
      try {
        val conn = (executor, addresses) match {
          case (None, None) => connFactory.newConnection()
          case (Some(ex), None) => connFactory.newConnection(ex)
          case (None, Some(addr)) => connFactory.newConnection(addr)
          case (Some(ex), Some(addr)) => connFactory.newConnection(ex, addr)
        }
        conn.addShutdownListener(new ShutdownListener {
          def shutdownCompleted(cause: ShutdownSignalException) {
            self ! Shutdown(cause)
          }
        })
        cancelTimer("reconnect")
        goto(Connected) using Connected(conn)
      }
      catch {
        case e: IOException => {
          log.error(e, "cannot connect to {}, retrying in {}", connFactory, reconnectionDelay)
          setTimer("reconnect", 'connect, reconnectionDelay, repeat = true)
          stay()
        }
      }
    }
    /*
     * create a "channel aware" actor that will request channels from this connection actor
     */
    case Event(Create(props, name), _) => {
      val child = createChild(props, name)
      log.debug("creating child {} while in disconnected state", child)
      stay replying child
    }
    /*
     * when disconnected, ignore channel request. Another option would to send back something like None...
     */
    case Event(CreateChannel, _) => stay()
  }

  when(Connected) {
    /*
     * channel request. send back a channel
     */
    case Event(CreateChannel, Connected(conn)) => Try(conn.createChannel()) match {
      case Success(channel) => stay replying channel
      case Failure(cause) => goto(Disconnected) using Uninitialized
    }
    /*
     * create a "channel aware" actor that will request channels from this connection actor
     */
    case Event(Create(props, name), Connected(conn)) => {
      val child = createChild(props, name)
      stay replying child
    }
    /*
     * shutdown event sent by the connection's shutdown listener
     */
    case Event(Shutdown(cause), _) => {
      if (!cause.isInitiatedByApplication) {
        log.error(cause.toString)
        context.children.foreach(_ ! Shutdown(cause))
      }
      goto(Disconnected) using Uninitialized
    }
  }

  onTransition {
    case Disconnected -> Connected => {
      log.info("connected to " + toUri(connFactory))
      nextStateData match {
        case Connected(conn) => context.children.foreach(_ ! conn.createChannel())
        case _ => {}
      }
    }
    case Connected -> Disconnected => {
      log.warning("lost connection to " + toUri(connFactory))
      self ! 'connect
    }
  }

  onTermination {
    case StopEvent(_, Connected, Connected(conn)) => {
      log.info("closing connection to " + toUri(connFactory))
      conn.close()
    }
  }

  initialize()
}

