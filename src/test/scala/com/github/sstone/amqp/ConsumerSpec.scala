package com.github.sstone.amqp

import akka.actor.ActorSystem
import akka.pattern.{ask, gracefulStop}
import akka.testkit.TestProbe
import akka.util.Timeout
import com.github.sstone.amqp.Amqp._
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import scala.concurrent.Await
import scala.concurrent.duration._

@RunWith(classOf[JUnitRunner])
class ConsumerSpec extends ChannelSpecNoTestKit {
  override implicit val system: ActorSystem = ActorSystem("ConsumerSpec")
  "Consumers" should {
    "receive messages sent by producers" in {
      val exchange = ExchangeParameters(name = "amq.direct", exchangeType = "", passive = true)
      val queue = QueueParameters(name = "", passive = false, exclusive = true)
      val probe = TestProbe()
      implicit val sender = probe.ref
      probe.ignoreMsg {
        case Amqp.Ok(p:Publish, _) => true
      }
      val consumer = ConnectionOwner.createChildActor(conn, Consumer.props(listener = Some(probe.ref)), timeout = 5000 millis)
      val producer = ConnectionOwner.createChildActor(conn, ChannelOwner.props())
      consumer ! AddStatusListener(probe.ref)
      producer ! AddStatusListener(probe.ref)
      probe.expectMsg(1 second, ChannelOwner.Connected)
      probe.expectMsg(1 second, ChannelOwner.Connected)
      consumer ! AddBinding(Binding(exchange, queue, "my_key"))
      val check = probe.receiveOne(1 second)
      println(check)
      val message = "yo!".getBytes
      producer ! Publish(exchange.name, "my_key", message)
      probe.expectMsgClass(1.second, classOf[Delivery])
    }
    "be able to set their channel's prefetch size" in {
      val queue = randomQueue
      val probe = TestProbe()
      implicit val sender = probe.ref
      probe.ignoreMsg {
        case Amqp.Ok(p:Publish, _) => true
      }
      val consumer = ConnectionOwner.createChildActor(conn, Consumer.props(listener = probe.ref, autoack = false, channelParams = Some(ChannelParameters(qos = 3))), timeout = 5000 millis)
      consumer ! AddStatusListener(probe.ref)
      probe.expectMsg(1 second, ChannelOwner.Connected)

      consumer ! AddQueue(queue)
      val Amqp.Ok(AddQueue(_), _) = probe.receiveOne(1 second)

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
      val Amqp.Ok(Ack(_), _) = probe.receiveOne(1 second)
      val delivery4 = probe.expectMsgClass(200 milliseconds, classOf[Delivery])
    }
    "be restarted if their channel crashes" in {
      val exchange = ExchangeParameters(name = "amq.direct", exchangeType = "", passive = true)
      val queue = randomQueue
      val probe = TestProbe()
      implicit val sender = probe.ref
      probe.ignoreMsg {
        case Amqp.Ok(p:Publish, _) => true
      }
      val consumer = ConnectionOwner.createChildActor(conn, Consumer.props(listener = Some(probe.ref)), timeout = 5000 millis)
      val producer = ConnectionOwner.createChildActor(conn, ChannelOwner.props())
      consumer ! AddStatusListener(probe.ref)
      producer ! AddStatusListener(probe.ref)
      probe.expectMsg(1 second, ChannelOwner.Connected)
      probe.expectMsg(1 second, ChannelOwner.Connected)
      consumer ! Record(AddBinding(Binding(exchange, queue, "my_key")))
      val Amqp.Ok(AddBinding(_), _) = probe.receiveOne(1 second)

      val message = "yo!".getBytes
      producer ! Publish(exchange.name, "my_key", message)
      probe.expectMsgClass(1.second, classOf[Delivery])

      // crash the consumer's channel
      consumer ! DeclareExchange(ExchangeParameters(name = "foo", passive = true, exchangeType =""))
      val Amqp.Error(DeclareExchange(_), _) = probe.receiveOne(1 second)
      probe.expectMsgAllOf(1 second, ChannelOwner.Disconnected, ChannelOwner.Connected)
      val Ok(AddBinding(Binding(`exchange`, `queue`, "my_key")), Some(_)) = probe.receiveOne(1 second)
      Thread.sleep(100)

      producer ! Publish(exchange.name, "my_key", message)
      probe.expectMsgClass(1.second, classOf[Delivery])
    }
    "declare queues and bindings" in {
      // passive = false means that the consumer will create the exchange
      val exchange = ExchangeParameters(name = randomExchangeName, exchangeType = "direct", passive = false, durable = false, autodelete = true)
      val queue = randomQueue
      val probe = TestProbe()
      implicit val sender = probe.ref
      probe.ignoreMsg {
        case Amqp.Ok(p:Publish, _) => true
      }
      val consumer = ConnectionOwner.createChildActor(conn, Consumer.props(listener = Some(probe.ref)), timeout = 5000 millis)
      val producer = ConnectionOwner.createChildActor(conn, ChannelOwner.props())
      consumer ! AddStatusListener(probe.ref)
      producer ! AddStatusListener(probe.ref)
      probe.expectMsg(1 second, ChannelOwner.Connected)
      probe.expectMsg(1 second, ChannelOwner.Connected)
      consumer ! AddBinding(Binding(exchange, queue, "test_key"))
      val Amqp.Ok(AddBinding(_), _) = probe.receiveOne(1 second)

      // check that our exchange was created
      val exchange1 = exchange.copy(passive = true)
      consumer ! DeclareExchange(exchange1)
      val Amqp.Ok(DeclareExchange(_), _) = probe.receiveOne(1 second)

      // check that publishing works
      producer ! Publish(exchange.name, "test_key", "test message".getBytes("UTF-8"))
      probe.expectMsgClass(1 second, classOf[Delivery])
    }
    "cancel consumers" in {
      val queue1 = randomQueue
      val queue2 = randomQueue
      val probe = TestProbe()
      implicit val sender = probe.ref
      probe.ignoreMsg {
        case Amqp.Ok(p:Publish, _) => true
      }
      val consumer = ConnectionOwner.createChildActor(conn, Consumer.props(listener = Some(probe.ref), autoack = false), timeout = 5000 millis)
      val producer = ConnectionOwner.createChildActor(conn, ChannelOwner.props())
      consumer ! AddStatusListener(probe.ref)
      producer ! AddStatusListener(probe.ref)
      probe.expectMsg(1 second, ChannelOwner.Connected)
      probe.expectMsg(1 second, ChannelOwner.Connected)

      consumer ! AddQueue(queue1)
      val Amqp.Ok(AddQueue(_), Some(consumerTag1: String)) = probe.receiveOne(1 second)
      consumer ! AddQueue(queue2)
      val Amqp.Ok(AddQueue(_), Some(consumerTag2: String)) = probe.receiveOne(1 second)

      producer ! Publish("", queue1.name, "test1".getBytes("UTF-8"))
      val delivery1: Delivery = probe.expectMsgClass(classOf[Delivery])
      assert(delivery1.consumerTag === consumerTag1)

      producer ! Publish("", queue2.name, "test2".getBytes("UTF-8"))
      val delivery2: Delivery = probe.expectMsgClass(classOf[Delivery])
      assert(delivery2.consumerTag === consumerTag2)

      consumer ! CancelConsumer(consumerTag1)
      val Amqp.Ok(CancelConsumer(_), _) = probe.receiveOne(1 second)

      producer ! Publish("", queue1.name, "test1".getBytes("UTF-8"))
      probe.expectNoMsg()
    }
    "send consumer cancellation notifications" in {
      val probe = TestProbe()
      implicit val sender = probe.ref
      probe.ignoreMsg {
        case Amqp.Ok(p:Publish, _) => true
      }
      val queue = randomQueue
      val consumer = ConnectionOwner.createChildActor(conn, Consumer.props(listener = Some(probe.ref), autoack = false), timeout = 5000 millis)
      val producer = ConnectionOwner.createChildActor(conn, ChannelOwner.props())
      consumer ! AddStatusListener(probe.ref)
      producer ! AddStatusListener(probe.ref)
      probe.expectMsg(1 second, ChannelOwner.Connected)
      probe.expectMsg(1 second, ChannelOwner.Connected)

      consumer ! AddQueue(queue)
      val Amqp.Ok(AddQueue(_), Some(consumerTag: String)) = probe.receiveOne(1 second)

      producer ! Publish("", queue.name, "test".getBytes("UTF-8"))
      val delivery: Delivery = probe.expectMsgClass(classOf[Delivery])
      assert(delivery.consumerTag === consumerTag)

      producer ! DeleteQueue(queue.name)
      val Ok(DeleteQueue(_, _, _), result) = probe.receiveOne(1 second)
      probe.expectMsg(1 second, ConsumerCancelled(consumerTag))
    }
    "create exclusive consumers" in {
      val probe = TestProbe()
      implicit val sender = probe.ref
      probe.ignoreMsg {
        case Amqp.Ok(p:Publish, _) => true
      }
      val queue = randomQueue
      val consumer = ConnectionOwner.createChildActor(conn, Consumer.props(
        listener = Some(probe.ref), autoack = false, init = Seq.empty[Request], channelParams = None,
        consumerTag = "", noLocal = false, exclusive = true, arguments = Map.empty), timeout = 5000 millis)
      val producer = ConnectionOwner.createChildActor(conn, ChannelOwner.props())

      consumer ! AddStatusListener(probe.ref)
      producer ! AddStatusListener(probe.ref)

      probe.expectMsg(1 second, ChannelOwner.Connected)
      probe.expectMsg(1 second, ChannelOwner.Connected)

      consumer ! AddQueue(queue)
      val Amqp.Ok(AddQueue(_), Some(consumerTag: String)) = probe.receiveOne(1 second)

      producer ! Publish("", queue.name, "test".getBytes("UTF-8"))
      val delivery: Delivery = probe.expectMsgClass(classOf[Delivery])
      assert(delivery.consumerTag === consumerTag)

      val consumer1 = ConnectionOwner.createChildActor(conn, Consumer.props(
        listener = Some(probe.ref), autoack = false, init = Seq.empty[Request], channelParams = None,
        consumerTag = "", noLocal = false, exclusive = true, arguments = Map.empty), timeout = 5000 millis)

      consumer1 ! AddStatusListener(probe.ref)
      probe.expectMsg(1 second, ChannelOwner.Connected)

      // you cannot have more than 1 exclusive consumer on the same queue
      consumer1 ! AddQueue(queue)
      val Amqp.Error(_, reason) = probe.receiveOne(1 second)
    }
    "save sender for requests while disconnected" in {
      val declareExchange = DeclareExchange(ExchangeParameters(name = "amq.direct", passive = true, exchangeType = ""))
      val conn = system.actorOf(ConnectionOwner.props(connFactory, 1 second))
      try {
        val channelOwner = ConnectionOwner.createChildActor(conn, ChannelOwner.props())
        val Ok(`declareExchange`, Some(_)) = Await.result(channelOwner.ask(Record(declareExchange))(Timeout(1 second)), 1 second)
      } finally {
        Await.result(gracefulStop(conn, 5 seconds), 6 seconds)
      }
    }
  }
}
