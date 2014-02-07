package com.github.sstone.amqp

import org.scalatest.junit.JUnitRunner
import org.junit.runner.RunWith
import concurrent.{Await, Future}
import concurrent.duration._
import concurrent.ExecutionContext.Implicits.global
import akka.actor.Props
import akka.pattern.ask
import com.rabbitmq.client.AMQP.BasicProperties
import com.github.sstone.amqp.RpcServer._
import com.github.sstone.amqp.Amqp._
import com.github.sstone.amqp.RpcServer.ProcessResult
import com.github.sstone.amqp.Amqp.Publish
import com.github.sstone.amqp.RpcClient.Response
import com.github.sstone.amqp.RpcClient.Undelivered
import com.github.sstone.amqp.Amqp.ExchangeParameters
import com.github.sstone.amqp.RpcClient.Request
import com.github.sstone.amqp.Amqp.QueueParameters
import com.github.sstone.amqp.Amqp.Delivery
import java.util.concurrent.TimeUnit

@RunWith(classOf[JUnitRunner])
class RpcSpec extends ChannelSpec {
  "RPC Servers" should {
    "reply to clients" in {
      val exchange = ExchangeParameters(name = "amq.direct", exchangeType = "", passive = true)
      val queue = randomQueue
      val routingKey = randomKey
      val proc = new RpcServer.IProcessor() {
        def process(delivery: Delivery) = Future {
          println("processing")
          val s = new String(delivery.body)
          if (s == "5") throw new Exception("I dont do 5s")
          ProcessResult(Some(delivery.body))
        }

        def onFailure(delivery: Delivery, e: Throwable) = ProcessResult(Some(e.toString.getBytes))
      }
      val server = ConnectionOwner.createChildActor(conn, RpcServer.props(queue, exchange, routingKey, proc))
      val client1 = ConnectionOwner.createChildActor(conn, RpcClient.props())
      val client2 = ConnectionOwner.createChildActor(conn, RpcClient.props())

      waitForConnection(system, conn, server, client1, client2).await()

      val f1 = Future {
        for (i <- 0 to 10) {
          try {
            val future = client1 ? Request(Publish("amq.direct", routingKey, i.toString.getBytes) :: Nil, 1)
            val result = Await.result(future, 1000.millis).asInstanceOf[Response]
            println("result1 " + new String(result.deliveries.head.body))
            Thread.sleep(300)
          }
          catch {
            case e: Exception => println(e.toString)
          }
        }
      }
      val f2 = Future {
        for (i <- 0 to 10) {
          try {
            val future = client2 ? Request(Publish("amq.direct", routingKey, i.toString.getBytes) :: Nil, 1)
            val result = Await.result(future, 1000.millis).asInstanceOf[Response]
            println("result2 " + new String(result.deliveries.head.body))
            Thread.sleep(300)
          }
          catch {
            case e: Exception => println(e.toString)
          }
        }
      }
      Await.result(f1, 1.minute)
      Await.result(f2, 1.minute)
    }

    "manage custom AMQP properties" in {
      val exchange = ExchangeParameters(name = "amq.direct", exchangeType = "", passive = true)
      val queue = randomQueue
      val routingKey = randomKey
      val proc = new RpcServer.IProcessor() {
        def process(delivery: Delivery) = Future {
          // return the same body with the same properties
          ProcessResult(Some(delivery.body), Some(delivery.properties))
        }

        def onFailure(delivery: Delivery, e: Throwable) = ProcessResult(Some(e.toString.getBytes), Some(delivery.properties))
      }
      val server = ConnectionOwner.createChildActor(conn, RpcServer.props(queue, exchange, routingKey, proc), timeout = 2000.millis)
      val client = ConnectionOwner.createChildActor(conn, RpcClient.props(), timeout = 2000.millis)
      waitForConnection(system, conn, server, client).await(10, TimeUnit.SECONDS)

      val myprops = new BasicProperties.Builder().contentType("my content").contentEncoding("my encoding").build()
      val future = client ? Request(Publish("amq.direct", routingKey, "yo!!".getBytes, Some(myprops)) :: Nil, 1)
      val result = Await.result(future, 1000.millis).asInstanceOf[Response]
      val delivery = result.deliveries.head
      assert(delivery.properties.getContentType === "my content")
      assert(delivery.properties.getContentEncoding === "my encoding")
    }
  }

  "RPC Clients" should {
    "correctly handle returned message" in {
      val client = ConnectionOwner.createChildActor(conn, RpcClient.props(), timeout = 2000.millis)
      waitForConnection(system, conn, client).await(2, TimeUnit.SECONDS)

      val future = client ? Request(Publish("", "mykey", "yo!".getBytes) :: Nil, 1)
      val result = Await.result(future, 1000.millis)
      assert(result.isInstanceOf[Undelivered])
    }
  }

  "RPC Clients and Servers" should {
    "implement 1 request/several responses patterns" in {
      val exchange = ExchangeParameters(name = "amq.direct", exchangeType = "", passive = true)
      // empty means that a random name will be generated when the queue is declared
      val queue = QueueParameters(name = "", passive = false, exclusive = true)
      // create 2 servers, each using a broker generated private queue and their own processor
      val proc1 = new IProcessor {
        def process(delivery: Delivery) = Future(ProcessResult(Some("proc1".getBytes)))

        def onFailure(delivery: Delivery, e: Throwable) = ProcessResult(None)
      }
      val routingKey = randomKey
      val server1 = ConnectionOwner.createChildActor(conn, RpcServer.props(queue, exchange, routingKey, proc1), timeout = 2000.millis)
      val proc2 = new IProcessor {
        def process(delivery: Delivery) = Future(ProcessResult(Some("proc2".getBytes)))

        def onFailure(delivery: Delivery, e: Throwable) = ProcessResult(None)
      }
      val server2 = ConnectionOwner.createChildActor(conn, RpcServer.props(queue, exchange, routingKey, proc2), timeout = 2000.millis)

      val client = ConnectionOwner.createChildActor(conn, RpcClient.props(), timeout = 2000.millis)
      waitForConnection(system, conn, server1, server2, client).await(2, TimeUnit.SECONDS)

      val future = client ? Request(Publish(exchange.name, routingKey, "yo!".getBytes) :: Nil, 2)
      val result = Await.result(future, 2000.millis).asInstanceOf[Response]
      assert(result.deliveries.length === 2)
      // we're supposed to have received to answers, "proc1" and "proc2"
      val strings = result.deliveries.map(d => new String(d.body))
      assert(strings.sorted === List("proc1", "proc2"))
    }
  }
}
