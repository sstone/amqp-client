package com.aphelia.amqp

import collection.JavaConversions._
import com.rabbitmq.client.AMQP.BasicProperties
import com.rabbitmq.client._
import com.aphelia.amqp.ChannelOwner.{Data, State}
import akka.actor.{ActorRef, Actor, FSM}
import com.aphelia.amqp.ConnectionOwner.Shutdown
import com.aphelia.amqp.Amqp._
import java.io.IOException
import com.aphelia.amqp.RpcServer.ProcessResult

object ChannelOwner {

  sealed trait State

  case object Disconnected extends State

  case object Connected extends State

  private[amqp] sealed trait Data

  private[amqp] case object Uninitialized extends Data

  private[amqp] case class Connected(channel: com.rabbitmq.client.Channel) extends Data

  def withChannel[T](channel: Channel)(f: Channel => T) = {
    try {
      f(channel)
    }
    catch {
      case e: IOException => Amqp.Error(e)
    }
  }

  def publishMessage(channel: Channel, publish: Publish) {
    import publish._
    val props = properties match {
      case Some(p) => p
      case None => new AMQP.BasicProperties.Builder().build()
    }
    channel.basicPublish(exchange, key, mandatory, immediate, props, body)
  }
}

/**
 * Channel owners are created by connection owners and hold an AMQP channel which is used to do
 * basically everything: create queues and bindings, publish messages, consume messages...
 * @param channelParams
 */
class ChannelOwner(channelParams: Option[ChannelParameters] = None) extends Actor with FSM[State, Data] {

  import ChannelOwner._

  startWith(Disconnected, Uninitialized)


  override def preRestart(reason: Throwable, message: Option[Any]) {
    log.warning("preRestart {} {}", reason, message)
    super.preRestart(reason, message)
  }

  override def postRestart(reason: Throwable) {
    log.warning("preRestart {} {}", reason)
    super.postRestart(reason)
  }

  /**
   * additional setup step that gets called each when this actor receives a channel
   * from its ConnectionOwner parent.
   * override this to implement custom steps
   * @param channel AMQP channel sent by the actor's parent
   */
  def onChannel(channel: Channel) {}

  def setup(channel: Channel) {
    channelParams.foreach(p => channel.basicQos(p.qos))
    channel.addReturnListener(new ReturnListener() {
      def handleReturn(replyCode: Int, replyText: String, exchange: String, routingKey: String, properties: BasicProperties, body: Array[Byte]) {
        log.warning("returned message code=%d text=%s exchange=%s routing_key=%s".format(replyCode, replyText, exchange, routingKey))
        self ! ReturnedMessage(replyCode, replyText, exchange, routingKey, properties, body)
      }
    })
    channel.addShutdownListener(new ShutdownListener {
      def shutdownCompleted(cause: ShutdownSignalException) {
        if (!cause.isInitiatedByApplication) {
          log.error(cause, "channel was shut down")
          self ! Shutdown(cause)
        }
      }
    })
    onChannel(channel)
  }

  when(Disconnected) {
    case Event(channel: Channel, _) => {
      setup(channel)
      goto(Connected) using Connected(channel)
    }
  }

  when(Connected) {
    case Event(channel: Channel, _) => {
      // we already have a channel, close this one to prevent resource leaks
      log.warning("closing unexpected channel {}", channel)
      channel.close()
      stay
    }
    /*
     * sent by the actor's parent when the AMQP connection is lost
     */
    case Event(Shutdown(cause), _) => goto(Disconnected)
    case Event(Publish(exchange, routingKey, body, properties, mandatory, immediate), Connected(channel)) => {
      val props = properties getOrElse  new AMQP.BasicProperties.Builder().build()
      channel.basicPublish(exchange, routingKey, mandatory, immediate, props, body)
      stay
    }
    case Event(Transaction(publish), Connected(channel)) => {
      channel.txSelect()
      publish.foreach(p => channel.basicPublish(p.exchange, p.key, p.mandatory, p.immediate, new AMQP.BasicProperties.Builder().build(), p.body))
      channel.txCommit()
      stay
    }
    case Event(Ack(deliveryTag), Connected(channel)) => {
      log.debug("acking %d on %s".format(deliveryTag, channel))
      channel.basicAck(deliveryTag, false)
      stay
    }
    case Event(Reject(deliveryTag, requeue), Connected(channel)) => {
      log.debug("rejecting %d on %s".format(deliveryTag, channel))
      channel.basicReject(deliveryTag, requeue)
      stay
    }
    case Event(DeclareExchange(exchange), Connected(channel)) => {
      stay replying withChannel(channel)(c => declareExchange(c, exchange))
    }
    case Event(DeleteExchange(exchange, ifUnused), Connected(channel)) => {
      stay replying withChannel(channel)(c => c.exchangeDelete(exchange, ifUnused))
    }
    case Event(DeclareQueue(queue), Connected(channel)) => {
      stay replying withChannel(channel)(c => declareQueue(c, queue))
    }
    case Event(PurgeQueue(queue), Connected(channel)) => {
      stay replying withChannel(channel)(c => c.queuePurge(queue))
    }
    case Event(DeleteQueue(queue, ifUnused, ifEmpty), Connected(channel)) => {
      stay replying withChannel(channel)(c => c.queueDelete(queue, ifUnused, ifEmpty))
    }
    case Event(QueueBind(queue, exchange, routing_key, args), Connected(channel)) => {
      stay replying withChannel(channel)(c => c.queueBind(queue, exchange, routing_key, args))
    }
    case Event(QueueUnbind(queue, exchange, routing_key, args), Connected(channel)) => {
      stay replying withChannel(channel)(c => c.queueUnbind(queue, exchange, routing_key, args))
    }
  }

  onTransition {
    case Disconnected -> Connected => {
      log.info("connected")
    }
    case Connected -> Disconnected => {
      log.warning("disconnect")
    }
  }

  onTermination {
    case StopEvent(_, Connected, Connected(channel)) => {
      try {
        log.info("closing channel")
        channel.close()
      }
      catch {
        case e: Exception => log.warning(e.toString)
      }
    }
  }
}
