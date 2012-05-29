package com.aphelia

import collection.JavaConversions._
import com.rabbitmq.client.{Envelope, Channel}
import com.rabbitmq.client.AMQP.BasicProperties

package object amqp {
  /**
   * queue parameters
   * @param name queue name. if empty, the broker will generate a random name, see Queue.DeclareOk
   * @param passive if true, just check that que queue exists
   * @param durable if true, the queue will be durable i.e. will survive a broker restart
   * @param exclusive if true, the queue can be used by one connection only
   * @param autodelete if true, the queue will be destroyed when it is no longer used
   * @param args additional parameters, such as TTL, ...
   */
  case class QueueParameters(name: String, passive: Boolean, durable: Boolean = false, exclusive: Boolean = false, autodelete: Boolean = true, args: Map[String, AnyRef] = Map.empty)

  /**
   * declare a queue
   * @param channel valid AMQP channel
   * @param q queue parameters
   * @return a com.rabbitmq.client.AMQP.Queue.DeclareOk object
   */
  def declareQueue(channel: Channel, q: QueueParameters) = {
    if (q.passive)
      channel.queueDeclarePassive(q.name)
    else
      channel.queueDeclare(q.name, q.durable, q.exclusive, q.autodelete, q.args)
  }

  /**
   * exchange parameters
   * @param name exchange name
   * @param passive if true, just check that the exchange exists
   * @param exchangeType exchange type: "direct", "fanout", "topic", "headers"
   * @param durable if true, the exchange will  be durable i.e. will survive a broker restart
   * @param autodelete if true, the exchange will be destroyed when it is no longer used
   * @param args additional arguments
   */
  case class ExchangeParameters(name: String, passive: Boolean, exchangeType: String, durable: Boolean = false, autodelete: Boolean = false, args: Map[String, AnyRef] = Map.empty)

  /**
   * declare an exchange
   * @param channel valid AMQP channel
   * @param e exchange parameters
   * @return a com.rabbitmq.client.AMQP.Exchange.DeclareOk object
   */
  def declareExchange(channel: Channel, e: ExchangeParameters) = {
    if (e.passive)
      channel.exchangeDeclarePassive(e.name)
    else
      channel.exchangeDeclare(e.name, e.exchangeType, e.durable, e.autodelete, e.args)
  }

  /**
   * Channel parameters
   * @param qos "quality of service", or prefetch count. The number of non-acknowledged messages a channel can receive. If set
   * to one then the consumer using this channel will not receive another message until it has acknowledged or rejected
   * its current message. This feature is commonly used as a load-balancing strategy using multiple consumers and
   * a shared queue.
   */
  case class ChannelParameters(qos : Int)

  case class DeclareQueue(queue : QueueParameters)

  case class DeclareExchange(exchange : ExchangeParameters)

  case class QueueBind(queue : String, exchange : String, routing_key : String, args: Map[String, AnyRef] = Map.empty)

  case class Binding(exchange : ExchangeParameters, queue : QueueParameters, routingKey : String, autoack : Boolean)
  
  case class Delivery(consumerTag: String, envelope: Envelope, properties: BasicProperties, body: Array[Byte])

  case class Publish(exchange: String, key: String, buffer: Array[Byte], mandatory : Boolean = true, immediate : Boolean = false)
  
  case class Ack(deliveryTag : Long)
  
  case class Reject(deliveryTag : Long, requeue : Boolean = true)

  case class Transaction(publish : List[Publish])
}
