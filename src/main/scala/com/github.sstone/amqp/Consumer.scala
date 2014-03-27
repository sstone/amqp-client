package com.github.sstone.amqp

import Amqp._
import akka.actor.ActorRef
import com.rabbitmq.client.{Envelope, Channel, DefaultConsumer}
import com.rabbitmq.client.AMQP.BasicProperties

/**
 * Create an AMQP consumer, which takes a list of AMQP bindings, a listener to forward messages to, and optional channel parameters.
 * For each (Exchange, Queue, RoutingKey) biding, the consumer will:
 * <ul>
 *   <li>declare the exchange</li>
 *   <li>declare the queue</li>
 *   <li>bind the queue to the routing key on the exchange</li>
 *   <li>consume messages from the queue</li>
 *   <li>forward them to the listener actor, wrapped in a [[com.github.sstone.amqp.Amqp.Delivery]] instance</li>
 * </ul>
 * @param bindings list of bindings
 * @param listener optional listener actor; if not set, self will be used instead
 * @param channelParams optional channel parameters
 */
class Consumer(bindings: List[Binding], listener: Option[ActorRef], channelParams: Option[ChannelParameters] = None, autoack: Boolean = false) extends ChannelOwner(channelParams) {
  def this(bindings: List[Binding], listener: ActorRef, channelParams: Option[ChannelParameters]) = this(bindings, Some(listener), channelParams)

  def this(bindings: List[Binding], listener: ActorRef) = this(bindings, Some(listener))

  var consumer: Option[DefaultConsumer] = None

  private def setupBinding(consumer: DefaultConsumer, binding: Binding) = {
    val channel = consumer.getChannel
    val queueName = declareQueue(channel, binding.queue).getQueue
    log.info("binding to queue {}", queueName)
    if (!binding.consumeOnly) {
      declareExchange(channel, binding.exchange)
      channel.queueBind(queueName, binding.exchange.name, binding.routingKey)
    }
    channel.basicConsume(queueName, autoack, consumer)
  }

  override def onChannel(channel: Channel) {
    val destination = listener getOrElse self
    consumer = Some(new DefaultConsumer(channel) {
      override def handleDelivery(consumerTag: String, envelope: Envelope, properties: BasicProperties, body: Array[Byte]) {
        destination ! Delivery(consumerTag, envelope, properties, body)
      }
    })
    bindings.foreach(b => setupBinding(consumer.get, b))
  }
}
