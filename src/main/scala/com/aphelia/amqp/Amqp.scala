package com.aphelia.amqp

import collection.JavaConversions._
import com.rabbitmq.client.AMQP.BasicProperties
import com.rabbitmq.client.{Channel, Envelope}
import akka.actor.{Actor, Props, ActorRef, ActorSystem}
import akka.actor.FSM.{SubscribeTransitionCallBack, CurrentState, Transition}
import java.util.concurrent.CountDownLatch

object Amqp {

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
   *            to one then the consumer using this channel will not receive another message until it has acknowledged or rejected
   *            its current message. This feature is commonly used as a load-balancing strategy using multiple consumers and
   *            a shared queue.
   */
  case class ChannelParameters(qos: Int)

  case class DeclareQueue(queue: QueueParameters)

  case class DeleteQueue(name : String, ifUnused : Boolean = false, ifEmpty : Boolean = false)

  case class PurgeQueue(name : String)

  case class DeclareExchange(exchange: ExchangeParameters)

  case class DeleteExchange(name : String, ifUnused : Boolean = false)

  case class QueueBind(queue: String, exchange: String, routing_key: String, args: Map[String, AnyRef] = Map.empty)

  case class QueueUnbind(queue: String, exchange: String, routing_key: String, args: Map[String, AnyRef] = Map.empty)

  case class Binding(exchange: ExchangeParameters, queue: QueueParameters, routingKey: String, autoack: Boolean)

  case class Delivery(consumerTag: String, envelope: Envelope, properties: BasicProperties, body: Array[Byte])

  case class Publish(exchange: String, key: String, buffer: Array[Byte], mandatory: Boolean = true, immediate: Boolean = false)

  case class Ack(deliveryTag: Long)

  case class Reject(deliveryTag: Long, requeue: Boolean = true)

  case class Transaction(publish: List[Publish])

  case class Error(e: Throwable)

  /** executes a callback when a connection or channel actors is "connected" i.e. usable
   * <ul>
   *   <li>for a connection actor, connected means that it is connected to the AMQP broker</li>
   *   <li>for a channel actor, connected means that it is has a valid channel (sent by its connection parent)</li>
   * </ul>
   * this is a simple wrapper around the FSM state monitoring tools provided by Akka, since ConnectionOwner and ChannelOwner
   * are state machines with 2 states (Disconnected and Connected)
   * @param system actor system (will be used to create a temporary watcher)
   * @param channelOrConnectionActor reference to a ConnectionOwner or ChannelOwner actor
   * @param onConnected connection callback
   */
  def onConnection(system: ActorSystem, channelOrConnectionActor: ActorRef, onConnected: () => Unit) = {
    val m = system.actorOf(Props(new Actor {
      def receive = {
        case Transition(_, ChannelOwner.Disconnected, ChannelOwner.Connected)
             | Transition(_, ConnectionOwner.Disconnected, ConnectionOwner.Connected)
             | CurrentState(_, ConnectionOwner.Connected)
             | CurrentState(_, ChannelOwner.Connected) => {
          onConnected()
          context.stop(self)
        }
      }
    }))
    channelOrConnectionActor ! SubscribeTransitionCallBack(m)
  }

  /** wait until a number of connection or channel actors are connected
   *
   * @param system actor system (will be used to create temporary watchers)
   * @param actors set of reference to ConnectionOwner or ChannelOwner actors
   * @return a CountDownLatch object you can wait on; its count will reach 0 when all actors are connected
   */
  def waitForConnection(system: ActorSystem, actors: ActorRef*): CountDownLatch = {
    val latch = new CountDownLatch(actors.size)
    actors.foreach(onConnection(system, _, () => latch.countDown()))
    latch
  }
}
