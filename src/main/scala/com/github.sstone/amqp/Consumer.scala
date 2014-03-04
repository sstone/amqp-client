package com.github.sstone.amqp

import Amqp._
import akka.actor.{UnboundedStash, UnrestrictedStash, Props, ActorRef}
import com.rabbitmq.client.{Envelope, Channel, DefaultConsumer}
import com.rabbitmq.client.AMQP.BasicProperties
import akka.event.LoggingReceive

object Consumer {
  def props(listener: Option[ActorRef], autoack: Boolean = false, init: Seq[Request] = Seq.empty[Request], channelParams: Option[ChannelParameters] = None): Props =
    Props(new Consumer(listener, autoack, init, channelParams))

  def props(listener: ActorRef, exchange: ExchangeParameters, queue: QueueParameters, routingKey: String, channelParams: Option[ChannelParameters], autoack: Boolean): Props =
    props(Some(listener), init = List(AddBinding(Binding(exchange, queue, routingKey))), channelParams = channelParams, autoack = autoack)

  def props(listener: ActorRef, channelParams: Option[ChannelParameters], autoack: Boolean): Props = props(Some(listener), channelParams = channelParams, autoack = autoack)
}

/**
 * Create an AMQP consumer, which takes a list of AMQP bindings, a listener to forward messages to, and optional channel parameters.
 * @param listener optional listener actor; if not set, self will be used instead
 * @param channelParams optional channel parameters
 */
class Consumer(listener: Option[ActorRef], autoack: Boolean = false, init: Seq[Request] = Seq.empty[Request], channelParams: Option[ChannelParameters] = None) extends ChannelOwner(init, channelParams) with UnboundedStash {
  import ChannelOwner._
  // consumer tag -> queue map
  val consumerTags = scala.collection.mutable.HashMap.empty[String, String]
  var consumer: Option[DefaultConsumer] = None

  override def onChannel(channel: Channel, forwarder: ActorRef) {
    super.onChannel(channel, forwarder)
    val destination = listener getOrElse self
    consumer = Some(new DefaultConsumer(channel) {
      override def handleDelivery(consumerTag: String, envelope: Envelope, properties: BasicProperties, body: Array[Byte]) {
        destination.tell(Delivery(consumerTag, envelope, properties, body), sender = forwarder)
      }
    })
    consumerTags.clear()
  }

  override def connected(channel: Channel, forwarder: ActorRef) : Receive = LoggingReceive({
    /**
     * add a queue to our consumer
     */
    case request@AddQueue(queue) => {
      log.debug("processing %s".format(request))
      sender !  withChannel(channel, request)(c => {
        val queueName = declareQueue(c, queue).getQueue
        val consumerTag = c.basicConsume(queueName, autoack, consumer.get)
        consumerTags.put(consumerTag, queueName)
        log.debug(s"using consumer $consumerTag")
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
        declareExchange(c, binding.exchange)
        c.queueBind(queueName, binding.exchange.name, binding.routingKey)
        val consumerTag = c.basicConsume(queueName, autoack, consumer.get)
        log.debug(s"using consumer $consumerTag")
        consumerTags.put(consumerTag, queueName)
        consumerTag
      })
    }
    case request@CancelQueue(_) => {
      log.debug("processing %s".format(request))

      sender ! withChannel(channel, request)(c => {
        // keep only consumerTags of the queue
        val consumerTagForQueue = consumerTags.filter {
          case (key, value) => value == request.queueName
        }
        consumerTagForQueue.foreach {
          case (consumer, queue)  =>
            log.debug("cancelling consumer {} on queue {}", consumer, queue)
            channel.basicCancel(consumer)
        }
        consumerTags --= consumerTagForQueue.keySet
        // remove the Record
        requestLog = requestLog.filterNot {
          _ match {
            case AddQueue(queueName) => true
            case _ => false
          }
        } .toVector

      })
    }

  } : Receive) orElse super.connected(channel, forwarder)
}
