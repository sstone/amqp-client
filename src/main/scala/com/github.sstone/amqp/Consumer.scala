package com.github.sstone.amqp

import Amqp._
import akka.actor.ActorRef
import com.rabbitmq.client.{Envelope, Channel, DefaultConsumer}
import com.rabbitmq.client.AMQP.BasicProperties

/**
 * Create an AMQP consumer, which takes a list of AMQP bindings, a listener to forward messages to, and optional channel parameters.
 * @param listener optional listener actor; if not set, self will be used instead
 * @param channelParams optional channel parameters
 */
class Consumer(listener: Option[ActorRef], autoack: Boolean = false, init: Seq[Request] = Seq.empty[Request], channelParams: Option[ChannelParameters] = None) extends ChannelOwner(init, channelParams) {
  import ChannelOwner._

  var consumer: Option[DefaultConsumer] = None

  override def onChannel(channel: Channel) {
    val destination = listener getOrElse self
    consumer = Some(new DefaultConsumer(channel) {
      override def handleDelivery(consumerTag: String, envelope: Envelope, properties: BasicProperties, body: Array[Byte]) {
        destination ! Delivery(consumerTag, envelope, properties, body)
      }
    })
  }

  when(Connected) {
    /**
     * add a queue to our consumer
     */
    case Event(request@AddQueue(queueName), Connected(channel)) => {
      stay replying withChannel(channel, request)(c => c.basicConsume(queueName, autoack, consumer.get))
    }

    /**
     * add a binding to our consumer: declare the queue, bind it, and consume from it
     */
    case Event(request@AddBinding(binding), Connected(channel)) => {
      stay replying withChannel(channel, request)(c => {
        val queueName = declareQueue(c, binding.queue).getQueue
        c.queueBind(queueName, binding.exchange.name, binding.routingKey)
        c.basicConsume(queueName, autoack, consumer.get)
      })
    }
  }
}
