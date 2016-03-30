package com.github.sstone.amqp

import Amqp._
import akka.actor.{UnboundedStash, UnrestrictedStash, Props, ActorRef}
import com.rabbitmq.client.{Envelope, Channel, DefaultConsumer}
import com.rabbitmq.client.AMQP.BasicProperties
import akka.event.LoggingReceive

import scala.collection.JavaConversions._

object Consumer {
  def props(listener: Option[ActorRef], autoack: Boolean = false, init: Seq[Request] = Seq.empty[Request], channelParams: Option[ChannelParameters] = None,
            consumerTag: String = "", noLocal: Boolean = false, exclusive: Boolean = false, arguments: Map[String, AnyRef] = Map.empty): Props =
    Props(new Consumer(listener, autoack, init, channelParams, consumerTag, noLocal, exclusive, arguments))

  def props(listener: ActorRef, exchange: ExchangeParameters, queue: QueueParameters, routingKey: String, channelParams: Option[ChannelParameters], autoack: Boolean): Props =
    props(Some(listener), init = List(AddBinding(Binding(exchange, queue, routingKey))), channelParams = channelParams, autoack = autoack)

  def props(listener: ActorRef, channelParams: Option[ChannelParameters], autoack: Boolean): Props = props(Some(listener), channelParams = channelParams, autoack = autoack)
}

/**
 *  Create an AMQP consumer
 * @param listener optional listener actor; if not set, self will be used instead
 * @param autoack if true, messages  will be automatically acked (default = false)
 * @param init initial set of requests that will be executed when this consumer receives a valid channel
 * @param channelParams optional channel parameters
 * @param consumerTag user-specified consumer tag (default is "")
 * @param noLocal if true the server will not send messages to the connection that published them
 * @param exclusive if true, this consumer will declare itself as exclusive on all the queues it consumes messages from
 * @param arguments additional arguments
 */
class Consumer(listener: Option[ActorRef],
               autoack: Boolean = false,
               init: Seq[Request] = Seq.empty[Request],
               channelParams: Option[ChannelParameters] = None,
               consumerTag: String = "",
               noLocal: Boolean = false,
               exclusive: Boolean = false,
               arguments: Map[String, AnyRef] = Map.empty) extends ChannelOwner(init, channelParams) with UnboundedStash {

  import ChannelOwner._

  var consumer: Option[DefaultConsumer] = None

  override def onChannel(channel: Channel, forwarder: ActorRef): Unit = {
    super.onChannel(channel, forwarder)
    val destination = listener getOrElse self
    consumer = Some(new DefaultConsumer(channel) {
      override def handleDelivery(consumerTag: String, envelope: Envelope, properties: BasicProperties, body: Array[Byte]) {
        destination.tell(Delivery(consumerTag, envelope, properties, body), sender = forwarder)
      }

      override def handleCancel(consumerTag: String): Unit = {
        super.handleCancel(consumerTag)
        destination ! ConsumerCancelled(consumerTag)
      }
    })
  }

  override def connected(channel: Channel, forwarder: ActorRef): Receive = LoggingReceive({
    /**
     * add a queue to our consumer
     */
    case request@AddQueue(queue) => {
      log.debug("processing %s".format(request))
      sender ! withChannel(channel, request)(c => {
        val queueName = declareQueue(c, queue).getQueue
        val actualConsumerTag = c.basicConsume(queueName, autoack, consumerTag, noLocal, exclusive, arguments, consumer.get)
        log.debug(s"using consumer $actualConsumerTag")
        actualConsumerTag
      })
    }

    /**
     * add a binding to our consumer: declare the queue, bind it, and consume from it
     */
    case request@AddBinding(binding) => {
      log.debug("processing %s".format(request))
      sender ! withChannel(channel, request)(c => {
        declareExchange(c, binding.exchange)
        val queueName = declareQueue(c, binding.queue).getQueue
        binding.routingKeys.foreach(key => c.queueBind(queueName, binding.exchange.name, key))
        val actualConsumerTag = c.basicConsume(queueName, autoack, consumerTag, noLocal, exclusive, arguments, consumer.get)
        log.debug(s"using consumer $actualConsumerTag")
        actualConsumerTag
      })
    }

    case request@CancelConsumer(consumerTag) => {
      log.debug("processing %s".format(request))
      sender ! withChannel(channel, request)(c => c.basicCancel(consumerTag))
    }

  }: Receive) orElse super.connected(channel, forwarder)
}
