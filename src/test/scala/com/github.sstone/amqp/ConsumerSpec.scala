package com.github.sstone.amqp

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import akka.testkit.TestProbe
import akka.actor.Props
import concurrent.duration._
import com.github.sstone.amqp.Amqp._
import com.github.sstone.amqp.Amqp.Publish
import com.github.sstone.amqp.Amqp.ExchangeParameters
import com.github.sstone.amqp.Amqp.Binding
import com.github.sstone.amqp.Amqp.QueueParameters
import com.rabbitmq.client.AMQP.Queue

@RunWith(classOf[JUnitRunner])
class ConsumerSpec extends ChannelSpec {
  "Consumers" should {
    "receive messages sent by producers" in {
      val exchange = ExchangeParameters(name = "amq.direct", exchangeType = "", passive = true)
      val queue = QueueParameters(name = "", passive = false, exclusive = true)
      ignoreMsg {
        case Amqp.Ok(p:Publish, _) => true
      }
      val probe = TestProbe()
      val consumer = ConnectionOwner.createChildActor(conn, Consumer.props(listener = Some(probe.ref)), timeout = 5000 millis)
      val producer = ConnectionOwner.createChildActor(conn, ChannelOwner.props())
      consumer ! AddStatusListener(probe.ref)
      producer ! AddStatusListener(probe.ref)
      probe.expectMsg(1 second, ChannelOwner.Connected)
      probe.expectMsg(1 second, ChannelOwner.Connected)
      consumer ! AddBinding(Binding(exchange, queue, "my_key"))
      val check = receiveOne(1 second)
      println(check)
      val message = "yo!".getBytes
      producer ! Publish(exchange.name, "my_key", message)
      probe.expectMsgClass(1.second, classOf[Delivery])
    }
    "be able to set their channel's prefetch size" in {
      val queue = randomQueue
      val probe = TestProbe()
      val consumer = ConnectionOwner.createChildActor(conn, Consumer.props(listener = probe.ref, autoack = false, channelParams = Some(ChannelParameters(qos = 3))), timeout = 5000 millis)
      consumer ! AddStatusListener(probe.ref)
      probe.expectMsg(1 second, ChannelOwner.Connected)

      consumer ! AddQueue(queue)
      val Amqp.Ok(AddQueue(_), _) = receiveOne(1 second)

      consumer ! Publish("", queue.name, "test".getBytes("UTF-8"))
      val delivery1 = probe.expectMsgClass(200 milliseconds, classOf[Delivery])
      consumer ! Publish("", queue.name, "test".getBytes("UTF-8"))
      val delivery2 = probe.expectMsgClass(200 milliseconds, classOf[Delivery])
      consumer ! Publish("", queue.name, "test".getBytes("UTF-8"))
      val delivery3 = probe.expectMsgClass(200 milliseconds, classOf[Delivery])

      // we have 3 pending messages, this one should not be received
      consumer ! Publish("", queue.name, "test".getBytes("UTF-8"))
      probe.expectNoMsg(500 milliseconds)

      // but if we ack one our our messages we shoule get the 4th delivery
      consumer ! Ack(deliveryTag = delivery1.envelope.getDeliveryTag)
      val Amqp.Ok(Ack(_), _) = receiveOne(1 second)
      val delivery4 = probe.expectMsgClass(200 milliseconds, classOf[Delivery])
    }
    "be restarted if their channel crashes" in {
      val exchange = ExchangeParameters(name = "amq.direct", exchangeType = "", passive = true)
      val queue = randomQueue
      val probe = TestProbe()
      ignoreMsg {
        case Amqp.Ok(p:Publish, _) => true
      }
      val consumer = ConnectionOwner.createChildActor(conn, Consumer.props(listener = Some(probe.ref)), timeout = 5000 millis)
      val producer = ConnectionOwner.createChildActor(conn, ChannelOwner.props())
      consumer ! AddStatusListener(probe.ref)
      producer ! AddStatusListener(probe.ref)
      probe.expectMsg(1 second, ChannelOwner.Connected)
      probe.expectMsg(1 second, ChannelOwner.Connected)
      consumer ! Record(AddBinding(Binding(exchange, queue, "my_key")))
      val Amqp.Ok(AddBinding(_), _) = receiveOne(1 second)

      val message = "yo!".getBytes
      producer ! Publish(exchange.name, "my_key", message)
      probe.expectMsgClass(1.second, classOf[Delivery])

      // crash the consumer's channel
      consumer ! DeclareExchange(ExchangeParameters(name = "foo", passive = true, exchangeType =""))
      receiveOne(1 second)
      probe.expectMsgAllOf(1 second, ChannelOwner.Disconnected, ChannelOwner.Connected)
      Thread.sleep(100)

      producer ! Publish(exchange.name, "my_key", message)
      probe.expectMsgClass(1.second, classOf[Delivery])
    }
    "declare queues and bindings" in {
      // passive = false means that the consumer will create the exchange
      val exchange = ExchangeParameters(name = randomExchangeName, exchangeType = "direct", passive = false, durable = false, autodelete = true)
      val queue = randomQueue
      val probe = TestProbe()
      ignoreMsg {
        case Amqp.Ok(p:Publish, _) => true
      }
      val consumer = ConnectionOwner.createChildActor(conn, Consumer.props(listener = Some(probe.ref)), timeout = 5000 millis)
      val producer = ConnectionOwner.createChildActor(conn, ChannelOwner.props())
      consumer ! AddStatusListener(probe.ref)
      producer ! AddStatusListener(probe.ref)
      probe.expectMsg(1 second, ChannelOwner.Connected)
      probe.expectMsg(1 second, ChannelOwner.Connected)
      consumer ! AddBinding(Binding(exchange, queue, "test_key"))
      val Amqp.Ok(AddBinding(_), _) = receiveOne(1 second)

      // check that our exchange was created
      val exchange1 = exchange.copy(passive = true)
      consumer ! DeclareExchange(exchange1)
      val Amqp.Ok(DeclareExchange(_), _) = receiveOne(1 second)

      // check that publishing works
      producer ! Publish(exchange.name, "test_key", "test message".getBytes("UTF-8"))
      probe.expectMsgClass(1 second, classOf[Delivery])
    }

    "not receive message if their queue is cancelled" in {

      val queue = randomQueue.copy(autodelete = false)
      val probe = TestProbe()
      ignoreMsg {
        case Amqp.Ok(p:Publish, _) => true
      }
      val consumer = ConnectionOwner.createChildActor(conn, Consumer.props(listener = Some(probe.ref)), timeout = 5000 millis)
      val producer = ConnectionOwner.createChildActor(conn, ChannelOwner.props())

      consumer ! AddStatusListener(probe.ref)
      producer ! AddStatusListener(probe.ref)
      probe.expectMsg(1 second, ChannelOwner.Connected)
      probe.expectMsg(1 second, ChannelOwner.Connected)
      producer ! DeclareQueue(queue)
      val Amqp.Ok(DeclareQueue(_), _) = receiveOne(1 second)
      consumer ! Record(AddQueue(queue))
      val Amqp.Ok(AddQueue(_), _) = receiveOne(1 second)

      val message = "yo!".getBytes
      producer ! Publish("", queue.name, message)
      val Delivery(_, env, _, _) = probe.receiveOne(1.second)

      // cancel the queue
      consumer ! CancelQueue(queue.name)

      // Acknowledge the message after cancel
      probe.reply(Ack(env.getDeliveryTag))
      probe.expectMsgClass(1.second, classOf[Ok])

      val Amqp.Ok(CancelQueue(_), _) = receiveOne(1 second)
      // The published message is not expected before re-adding the queue
      producer ! Publish("", queue.name, message)

      probe.expectNoMsg(1.second)

      consumer ! Record(AddQueue(queue))
      val Amqp.Ok(AddQueue(_), _) = receiveOne(1 second)
      probe.expectMsgClass(1.second, classOf[Delivery])

      producer ! DeleteQueue(queue.name)
    }

  }
}
