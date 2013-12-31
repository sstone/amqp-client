package com.github.sstone.amqp

import Amqp._
import akka.actor.{Props, ActorRef}
import com.rabbitmq.client.{Envelope, Channel, DefaultConsumer}
import com.rabbitmq.client.AMQP.BasicProperties

object Consumer {
  def props(listener: Option[ActorRef], autoack: Boolean = false, init: Seq[Request] = Seq.empty[Request], channelParams: Option[ChannelParameters] = None): Props =
    Props(new Consumer(listener, autoack, init, channelParams))

  def props(listener: ActorRef, exchange: ExchangeParameters, queue: QueueParameters, routingKey: String, channelParams: Option[ChannelParameters], autoack: Boolean): Props =
    props(Some(listener), init = List(AddBinding(Binding(exchange, queue, routingKey))), channelParams = channelParams, autoack = autoack)
}

/**
 * Create an AMQP consumer, which takes a list of AMQP bindings, a listener to forward messages to, and optional channel parameters.
 * @param listener optional listener actor; if not set, self will be used instead
 * @param channelParams optional channel parameters
 */
class Consumer(listener: Option[ActorRef], autoack: Boolean = false, init: Seq[Request] = Seq.empty[Request], channelParams: Option[ChannelParameters] = None) extends ChannelOwner(init, channelParams) {
  import ChannelOwner._
  // consumer tag -> queue map
  val consumerTags = scala.collection.mutable.HashMap.empty[String, String]
  var consumer: Option[DefaultConsumer] = None
  var pending = Vector.empty[Request]

  override def onChannel(channel: Channel, forwarder: ActorRef) {
    val destination = listener getOrElse self
    forwarder ! CreateConsumer(destination)
    consumerTags.clear()
  }

  override def connected(channel: Channel, forwarder: ActorRef) : Receive =  ({
    case Ok(_, Some(consumer: DefaultConsumer)) => {
      log.info("consumer ready")
      pending.map(r => self ! r)
      pending = Vector.empty[Request]
      context.become(consumerReady(channel, forwarder, consumer))
    }
    /**
     * add a queue to our consumer
     */
    case request@AddQueue(queue) => {
      log.debug(s"buffering $request")
      pending = pending :+ request
    }

    /**
     * add a binding to our consumer: declare the queue, bind it, and consume from it
     */
    case request@AddBinding(binding) => {
      log.debug(s"buffering $request")
      pending = pending :+ request
    }
  } : Receive) orElse super.connected(channel, forwarder)

  def consumerReady(channel: Channel, forwarder: ActorRef, consumer: DefaultConsumer) : Receive = ({
    /**
     * add a queue to our consumer
     */
    case request@AddQueue(queue) => {
      log.debug("processing %s".format(request))
      sender !  withChannel(channel, request)(c => {
        val queueName = declareQueue(c, queue).getQueue
        val consumerTag = c.basicConsume(queueName, autoack, consumer)
        consumerTags.put(consumerTag, queueName)
        consumerTag
      })
    }

    /**
     * add a binding to our consumer: declare the queue, bind it, and consume from it
     */
    case request@AddBinding(binding) => {
      log.debug("processing %s".format(request))
      sender ! withChannel(channel, request)(c => {
        val queueName = declareQueue(c, binding.queue).getQueue
        c.queueBind(queueName, binding.exchange.name, binding.routingKey)
        val consumerTag = c.basicConsume(queueName, autoack, consumer)
        consumerTags.put(consumerTag, queueName)
        consumerTag
      })
    }
  } : Receive) orElse connected(channel, forwarder)
}
