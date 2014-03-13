package com.github.sstone.amqp.samples

import akka.actor._
import com.rabbitmq.client.ConnectionFactory
import com.github.sstone.amqp.{Amqp, ChannelOwner, Consumer, ConnectionOwner}
import com.github.sstone.amqp.ConnectionOwner.Create
import com.github.sstone.amqp.Amqp._
import com.github.sstone.amqp.ConnectionOwner.Create
import java.util.concurrent.TimeUnit
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global
import com.github.sstone.amqp.Amqp.Ack
import com.github.sstone.amqp.Amqp.CancelConsumer
import com.github.sstone.amqp.Amqp.Publish
import com.github.sstone.amqp.Amqp.DeclareQueue
import com.github.sstone.amqp.ConnectionOwner.Create
import scala.Some
import com.github.sstone.amqp.Amqp.AddQueue
import com.github.sstone.amqp.Amqp.QueueParameters
import com.github.sstone.amqp.Amqp.Delivery

/**
 * AMQP consumer that switches between multiple queues
 */
object Consumer4 extends App {
  implicit val system = ActorSystem("mySystem")

  // create an AMQP connection
  val connFactory = new ConnectionFactory()
  connFactory.setUri("amqp://guest:guest@localhost/%2F")
  val conn = system.actorOf(ConnectionOwner.props(connFactory, 1 second))
  val producer = ConnectionOwner.createChildActor(conn, ChannelOwner.props())

  // send a message to queue1 and queue2 every second
  waitForConnection(system, conn, producer).await(5, TimeUnit.SECONDS)
  producer ! DeclareQueue(QueueParameters(name = "queue1", passive = false, durable = false,  autodelete = false))
  producer ! DeclareQueue(QueueParameters(name = "queue2", passive = false, durable = false,  autodelete = false))
  system.scheduler.schedule(1 second, 1 second, producer, Publish(exchange = "", key = "queue1", body = "test1".getBytes("UTF-8")))
  system.scheduler.schedule(1 second, 1 second, producer, Publish(exchange = "", key = "queue2", body = "test2".getBytes("UTF-8")))

  class Boot extends Actor with ActorLogging {
    conn ! Create(Consumer.props(listener = self, autoack = false, channelParams = None), name = Some("consumer"))

    context.system.scheduler.schedule(1 second, 10 seconds, self, ('switch, "queue1"))
    context.system.scheduler.schedule(10 seconds, 10 seconds, self, ('switch, "queue2"))

    override def unhandled(message: Any): Unit = message match {
      case Delivery(consumerTag, envelope, properties, body) =>
        log.info(s"received message ${new String(body)}")
        sender ! Ack(envelope.getDeliveryTag)
      case Amqp.Ok(_, _) => ()
    }

    def receive = waitingForConsumer

    def waitingForConsumer: Receive = {
      case consumer: ActorRef => context.become(usingConsumer(consumer))
    }

    def usingConsumer(consumer: ActorRef): Receive = {
      case ('switch, queue: String) =>
        log.info(s"switch to queue $queue")
        consumer ! AddQueue(QueueParameters(name = queue, passive = true))
      case Amqp.Ok(AddQueue(_), Some(consumerTag: String)) =>
        log.info(s"using consumer tag $consumerTag")
        context.become(usingConsumer(consumer, consumerTag))
    }

    def usingConsumer(consumer: ActorRef, consumerTag: String): Receive = {
      case ('switch, queue: String) =>
        log.info(s"switch to queue $queue")
        consumer ! AddQueue(QueueParameters(name = queue, passive = true))
      case Amqp.Ok(AddQueue(_), Some(newConsumerTag: String)) =>
        log.info(s"cancelling $consumerTag, using $newConsumerTag")
        consumer ! CancelConsumer(consumerTag)
        context.become(usingConsumer(consumer, newConsumerTag))
    }
  }

  val boot = system.actorOf(Props[Boot])
}
