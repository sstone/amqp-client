package com.github.sstone.amqp

import collection.JavaConversions._
import com.rabbitmq.client.AMQP.BasicProperties
import com.rabbitmq.client._
import akka.actor.{Actor, FSM}
import com.github.sstone.amqp.Amqp._
import java.io.IOException
import com.github.sstone.amqp.ConnectionOwner.LeaveChannel

object ChannelOwner {
  sealed trait State
  case object Disconnected extends State
  case object Connected extends State

  private[amqp] sealed trait Data
  private[amqp] case object Uninitialized extends Data
  private[amqp] case class Connected(channel: Channel) extends Data
}


/**
 * Channel owners are created by connection owners and hold an AMQP channel which is used to do
 * basically everything: create queues and bindings, publish messages, consume messages...
 * @param channelParams
 */
class ChannelOwner(channelParams: Option[ChannelParameters] = None)
  extends Actor
  with FSM[ChannelOwner.State, ChannelOwner.Data] {

  import ChannelOwner._

  startWith(Disconnected, Uninitialized)

  when(Disconnected) {
    case Event(channel: Channel, _) =>
      setup(channel)
      goto(Connected) using Connected(channel)
  }

  when(Connected) {
    case Event(channel: Channel, _) =>
      // we already have a channel, close this one to prevent resource leaks
      log.warning("closing unexpected channel {}", channel)
      channel.close()
      stay

    // sent by the actor's parent when the AMQP connection is lost
    case Event(LeaveChannel(cause), _) => goto(Disconnected)

    case Event(request: Request, Connected(channel)) =>
      stay replying (try process(channel, request) catch {
        case e: IOException => Amqp.Error(request, e)
      })
  }

  onTransition {
    case Disconnected -> Connected => log.info("connected")
    case Connected -> Disconnected => log.warning("disconnected")
  }

  onTermination {
    case StopEvent(_, Connected, Connected(channel)) =>
      try {
        log.info("closing channel")
        channel.close()
      } catch {
        case e: Exception => log.warning(e.toString)
      }
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
          self ! LeaveChannel(cause)
        }
      }
    })
    onChannel(channel)
  }

  override def preRestart(reason: Throwable, message: Option[Any]) {
    log.warning("preRestart {} {}", reason, message)
    super.preRestart(reason, message)
  }

  override def postRestart(reason: Throwable) {
    log.warning("preRestart {} {}", reason)
    super.postRestart(reason)
  }

  def process(channel: Channel, request: Request): Ok = {
    implicit def unitToOk(x: Unit) = Ok(request)
    implicit def methodToOk(m: Method) = Ok(request, Some(m))

    request match {
      case Publish(exchange, routingKey, body, properties, mandatory, immediate) =>
        val props = properties getOrElse new AMQP.BasicProperties.Builder().build()
        channel.basicPublish(exchange, routingKey, mandatory, immediate, props, body)

      case Transaction(publish) =>
        channel.txSelect()
        publish.foreach(p => channel.basicPublish(
          p.exchange,
          p.key,
          p.mandatory,
          p.immediate,
          new AMQP.BasicProperties.Builder().build(),
          p.body))
        channel.txCommit()

      case Ack(deliveryTag) =>
        log.debug("acking %d on %s".format(deliveryTag, channel))
        channel.basicAck(deliveryTag, false)

      case Reject(deliveryTag, requeue) =>
        log.debug("rejecting %d on %s".format(deliveryTag, channel))
        channel.basicReject(deliveryTag, requeue)

      case DeclareExchange(exchange) =>
        log.debug("declaring exchange {}", exchange)
        declareExchange(channel, exchange)

      case DeleteExchange(exchange, ifUnused) =>
        log.debug("deleting exchange {} ifUnused {}", exchange, ifUnused)
        channel.exchangeDelete(exchange, ifUnused)

      case DeclareQueue(queue) =>
        log.debug("declaring queue {}", queue)
        declareQueue(channel, queue)

      case PurgeQueue(queue) =>
        log.debug("purging queue {}", queue)
        channel.queuePurge(queue)

      case DeleteQueue(queue, ifUnused, ifEmpty) =>
        log.debug("deleting queue {} ifUnused {} ifEmpty {}", queue, ifUnused, ifEmpty)
        channel.queueDelete(queue, ifUnused, ifEmpty)

      case QueueBind(queue, exchange, routingKey, args) =>
        log.debug("binding queue {} to key {} on exchange {}", queue, routingKey, exchange)
        channel.queueBind(queue, exchange, routingKey, args)

      case QueueUnbind(queue, exchange, routingKey, args) =>
        log.debug("unbinding queue {} to key {} on exchange {}", queue, routingKey, exchange)
        channel.queueUnbind(queue, exchange, routingKey, args)
    }
  }
}
