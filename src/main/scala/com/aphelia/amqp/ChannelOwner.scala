package com.aphelia.amqp

import collection.JavaConversions._
import akka.util.duration._
import com.rabbitmq.client.AMQP.BasicProperties
import com.rabbitmq.client._
import com.aphelia.amqp.ChannelOwner.{Data, State}
import akka.actor.{ActorRef, Actor, FSM}
import com.aphelia.amqp.ConnectionOwner.{CreateChannel, Shutdown}
import collection.mutable
import com.aphelia.amqp.Amqp._
import akka.actor.Status.Failure
import java.io.IOException

object ChannelOwner {

  sealed trait State

  case object Disconnected extends State

  case object Connected extends State

  private[amqp] sealed trait Data

  private[amqp] case object Uninitialized extends Data

  private[amqp] case class Connected(channel: com.rabbitmq.client.Channel) extends Data

  def withChannel[T](channel : Channel)(f : Channel => T) = {
    try {
      f(channel)
    }
    catch {
      case e : IOException => Amqp.Error(e)
    }
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
        self !('returned, replyCode, replyText, exchange, routingKey, properties, body)
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
    case Event(Publish(exchange, routingKey, body, mandatory, immediate), Connected(channel)) => {
      channel.basicPublish(exchange, routingKey, mandatory, immediate, new AMQP.BasicProperties.Builder().build(), body)
      stay
    }
    case Event(Transaction(publish), Connected(channel)) => {
      channel.txSelect()
      publish.foreach(p => channel.basicPublish(p.exchange, p.key, p.mandatory, p.immediate, new AMQP.BasicProperties.Builder().build(), p.buffer))
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

class Consumer(bindings: List[Binding], listener: Option[ActorRef], channelParams: Option[ChannelParameters] = None) extends ChannelOwner(channelParams) {
  def this(bindings: List[Binding], listener: ActorRef, channelParams: Option[ChannelParameters]) = this(bindings, Some(listener), channelParams)
  def this(bindings: List[Binding], listener: ActorRef) = this(bindings, Some(listener))

  var consumer: Option[DefaultConsumer] = None

  private def setupBinding(consumer: DefaultConsumer, binding: Binding) = {
    val channel = consumer.getChannel
    val queueName = declareQueue(channel, binding.queue).getQueue
    declareExchange(channel, binding.exchange)
    channel.queueBind(queueName, binding.exchange.name, binding.routingKey)
    channel.basicConsume(queueName, binding.autoack, consumer)
  }

  override def onChannel(channel: Channel) = {
    val destination = listener match {
      case None => self
      case Some(a) => a
    }
    consumer = Some(new DefaultConsumer(channel) {
      override def handleDelivery(consumerTag: String, envelope: Envelope, properties: BasicProperties, body: Array[Byte]) {
        destination ! Delivery(consumerTag, envelope, properties, body)
      }
    })
    bindings.foreach(b => setupBinding(consumer.get, b))
  }
}

object RpcServer {

  trait IProcessor {
    def process(delivery: Delivery): Option[Array[Byte]]

    def onFailure(delivery: Delivery, e: Exception): Option[Array[Byte]]
  }

}

class RpcServer(bindings: List[Binding], processor: RpcServer.IProcessor, channelParams: Option[ChannelParameters] = None) extends Consumer(bindings, None, channelParams) {
  def this(queue: QueueParameters, exchange: ExchangeParameters, routingKey: String, processor: RpcServer.IProcessor, channelParams: Option[ChannelParameters] = None)
  = this(List(Binding(exchange, queue, routingKey, false)), processor, channelParams)

  when(ChannelOwner.Connected) {
    case Event(delivery@Delivery(consumerTag: String, envelope: Envelope, properties: BasicProperties, body: Array[Byte]), ChannelOwner.Connected(channel)) => {
      log.debug("processing delivery")
      try {
        processor.process(delivery) match {
          // send a reply only if processor return something *and* replyTo is set
          case Some(data) if (properties.getReplyTo != null) => {
            val props = new BasicProperties.Builder().correlationId(properties.getCorrelationId).build()
            channel.basicPublish("", properties.getReplyTo, true, false, props, data)
          }
          case _ => {}
        }
        channel.basicAck(envelope.getDeliveryTag, false)
      }
      catch {
        case e: Exception => {
          // check re-delivered tag
          envelope.isRedeliver match {
            // first failure: reject the message
            case false => {
              log.error(e, "processing {} failed, rejecting message", delivery)
              channel.basicReject(envelope.getDeliveryTag, true)
            }
            // second failure: reply with an error message, ack the message
            case true => {
              log.error(e, "processing {} failed for the second time, acking message", delivery)
              processor.onFailure(delivery, e) match {
                case Some(data) if (properties.getReplyTo != null) => {
                  val props = new BasicProperties.Builder().correlationId(properties.getCorrelationId).build()
                  channel.basicPublish("", properties.getReplyTo, true, false, props, data)
                }
                case _ => {}
              }
              channel.basicAck(envelope.getDeliveryTag, false)
            }
          }
        }
      }
      stay
    }
  }
}

object RpcClient {

  private[amqp] case class RpcResult(destination: ActorRef, expected: Int, buffers: scala.collection.mutable.ListBuffer[Array[Byte]])

  private[amqp] case class Request(publish: List[Publish], numberOfResponses: Int)

  case class Response(buffers: List[Array[Byte]])

}

class RpcClient(channelParams: Option[ChannelParameters] = None) extends ChannelOwner(channelParams) {

  import RpcClient._

  var queue: String = ""
  var consumer: Option[DefaultConsumer] = None
  var counter: Int = 0
  var correlationMap = scala.collection.mutable.Map.empty[String, RpcResult]

  override def onChannel(channel: Channel) = {
    queue = declareQueue(channel, QueueParameters("", passive = false, exclusive = true)).getQueue
    consumer = Some(new DefaultConsumer(channel) {
      override def handleDelivery(consumerTag: String, envelope: Envelope, properties: BasicProperties, body: Array[Byte]) {
        self ! Delivery(consumerTag, envelope, properties, body)
      }
    })
    channel.basicConsume(queue, false, consumer.get)
    correlationMap.clear()
  }

  when(ChannelOwner.Connected) {
    case Event(Request(publish, numberOfResponses), ChannelOwner.Connected(channel)) => {
      counter = counter + 1
      val props = new BasicProperties.Builder().correlationId(counter.toString).replyTo(queue).build()
      publish.foreach(r => channel.basicPublish(r.exchange, r.key, r.mandatory, r.immediate, props, r.buffer))
      correlationMap += (counter.toString -> RpcResult(sender, numberOfResponses, collection.mutable.ListBuffer.empty[Array[Byte]]))
      stay
    }
    case Event(Delivery(consumerTag: String, envelope: Envelope, properties: BasicProperties, body: Array[Byte]), ChannelOwner.Connected(channel)) => {
      channel.basicAck(envelope.getDeliveryTag, false)
      if (correlationMap.contains(properties.getCorrelationId)) {
        val results = correlationMap.get(properties.getCorrelationId).get
        results.buffers += body
        if (results.buffers.length == results.expected) {
          results.destination ! Response(results.buffers.toList)
          correlationMap -= properties.getCorrelationId
        }
      }
      else {
        log.warning("unexpected message with correlation id " + properties.getCorrelationId)
      }
      stay
    }
  }
}
