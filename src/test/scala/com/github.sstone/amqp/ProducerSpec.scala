package com.github.sstone.amqp

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import akka.testkit.TestProbe
import akka.actor.Props
import concurrent.duration._
import com.rabbitmq.client.AMQP.BasicProperties
import com.github.sstone.amqp.Amqp._
import com.github.sstone.amqp.Amqp.Publish
import com.github.sstone.amqp.Amqp.ExchangeParameters
import com.github.sstone.amqp.Amqp.Binding
import com.github.sstone.amqp.Amqp.QueueParameters
import com.github.sstone.amqp.Amqp.Delivery

@RunWith(classOf[JUnitRunner])
class ProducerSpec extends ChannelSpec {
  "Producers" should {
    "be able to specify custom message properties" in {
      val exchange = StandardExchanges.amqDirect
      val queue = QueueParameters(name = "queue", passive = false, exclusive = false)
      val probe = TestProbe()
      val consumer = ConnectionOwner.createChildActor(conn, Consumer.props(listener = Some(probe.ref)), timeout = 5000 millis)
      val producer = ConnectionOwner.createChildActor(conn, ChannelOwner.props())
      waitForConnection(system, conn, consumer, producer).await()

      // create a queue, bind it to "my_key" and consume from it
      consumer ! AddBinding(Binding(exchange, queue, "my_key"))

      fishForMessage(1 second) {
        case Amqp.Ok(AddBinding(Binding(`exchange`, `queue`, "my_key")), _) => true
        case _ => false
      }

      val message = "yo!".getBytes
      producer ! Publish(exchange.name, "my_key", message, Some(new BasicProperties.Builder().contentType("my content").build()))


      val delivery = probe.receiveOne(1.second).asInstanceOf[Delivery]
      assert(delivery.properties.getContentType === "my content")
    }
    "publish messages within an AMQP transaction" in  {
      val exchange = StandardExchanges.amqDirect
      val queue = QueueParameters(name = "queue", passive = false, exclusive = false)
      val probe = TestProbe()
      val consumer = ConnectionOwner.createChildActor(conn, Consumer.props(listener = Some(probe.ref)), timeout = 5000 millis)
      val producer = ConnectionOwner.createChildActor(conn, ChannelOwner.props())
      waitForConnection(system, conn, consumer, producer).await()

      // create a queue, bind it to "my_key" and consume from it
      consumer ! AddBinding(Binding(exchange, queue, "my_key"))

      fishForMessage(1 second) {
        case Amqp.Ok(AddBinding(Binding(`exchange`, `queue`, "my_key")), _) => true
        case _ => false
      }

      val message = "yo!".getBytes
      producer ! Transaction(List(Publish(exchange.name, "my_key", message), Publish(exchange.name, "my_key", message), Publish(exchange.name, "my_key", message)))

      var received = List[Delivery]()
      probe.receiveWhile(2.seconds) {
        case message: Delivery => received = message :: received
      }
      assert(received.length === 3)
    }
  }
}
