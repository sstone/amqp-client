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
      val exchange = ExchangeParameters(name = "amq.direct", exchangeType = "", passive = true)
      val queue = QueueParameters(name = "queue", passive = false, exclusive = false)
      val probe = TestProbe()
      val consumer = ConnectionOwner.createActor(conn, Props(new Consumer(List(Binding(exchange, queue, "my_key")), probe.ref)), 5000.millis)
      val producer = ConnectionOwner.createActor(conn, Props(new ChannelOwner()))
      waitForConnection(system, conn, consumer, producer).await()
      val message = "yo!".getBytes
      producer ! Publish(exchange.name, "my_key", message, Some(new BasicProperties.Builder().contentType("my content").build()))
      val delivery = probe.receiveOne(1.second).asInstanceOf[Delivery]
      assert(delivery.properties.getContentType === "my content")
    }
    "publish messages within an AMQP transaction" in  {
      val exchange = ExchangeParameters(name = "amq.direct", exchangeType = "", passive = true)
      val queue = QueueParameters(name = "my_queue", passive = false)
      val probe = TestProbe()
      val consumer = ConnectionOwner.createActor(conn, Props(new Consumer(List(Binding(exchange, queue, "my_key")), probe.ref)), 5000.millis)
      val producer = ConnectionOwner.createActor(conn, Props(new ChannelOwner()))
      waitForConnection(system, conn, consumer, producer).await()
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
