package com.github.sstone.amqp

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import akka.testkit.TestProbe
import akka.util.duration._
import akka.actor.Props
import java.util.concurrent.{TimeUnit, Executors}
import akka.dispatch.Future
import akka.dispatch.ExecutionContext
import com.github.sstone.amqp.RpcClient.{Undelivered, Request, Response}
import com.github.sstone.amqp.Amqp._
import com.github.sstone.amqp.RpcServer.{ProcessResult, IProcessor}
import com.rabbitmq.client.AMQP.Queue
import akka.dispatch.Await
import akka.util.Timeout._
import akka.pattern.ask
import akka.util.Timeout
import com.rabbitmq.client.AMQP.BasicProperties

@RunWith(classOf[JUnitRunner])
class ChannelOwnerSpec extends BasicAmqpTestSpec {

  "ChannelOwner" should {
    "transition from Disconnected to Connected when it receives a channel" in {
      val conn = system.actorOf(Props(new ConnectionOwner(connFactory)))
      val instance = ConnectionOwner.createActor(conn, Props(new ChannelOwner()), name = Some("instance"))
      val latch = waitForConnection(system, instance)
      latch.await(6000, TimeUnit.MILLISECONDS)
      latch.getCount should equal(0)
      system.stop(instance)
      system.stop(conn)
    }
    "allow users to create, bind, purge and delete queues" in {
      val conn = system.actorOf(Props(new ConnectionOwner(connFactory)))
      val instance = ConnectionOwner.createActor(conn, Props(new ChannelOwner()), name = Some("instance"))
      val latch = waitForConnection(system, conn, instance)
      latch.await(2, TimeUnit.SECONDS)
      val queue = "my_test_queue"
      implicit val timeout = Timeout(2 seconds)
      // declare a queue, bind it to "my_test_key" on "amq.direct" and publish a message
      instance ! DeclareQueue(QueueParameters(queue, passive = false))
      instance ! QueueBind(queue, "amq.direct", "my_test_key")
      instance ! Publish("amq.direct", "my_test_key", "yo!".getBytes)
      // check that there is 1 message in the queue
      val Amqp.Ok(_, Some(check1: Queue.DeclareOk)) = Await.result(
        instance.ask(DeclareQueue(QueueParameters(queue, passive = true))),
        1 second)
      assert(check1.getMessageCount === 1)

      // purge the queue
      instance ! PurgeQueue(queue)
      // check that there are no more messages in the queue
      val Amqp.Ok(_, Some(check2: Queue.DeclareOk)) = Await.result(
        instance.ask(DeclareQueue(QueueParameters(queue, passive = true))),
        1 second)
      assert(check2.getMessageCount === 0)
      // delete the queue
      val Amqp.Ok(_, Some(check3: Queue.DeleteOk)) = Await.result(
        instance.ask(DeleteQueue(queue)),
        1 second)
      system.stop(conn)
    }
    "implement basic error handling" in {
      val conn = system.actorOf(Props(new ConnectionOwner(connFactory)))
      waitForConnection(system, conn).await(2, TimeUnit.SECONDS)
      val instance = ConnectionOwner.createActor(conn, Props(new ChannelOwner()), name = Some("instance"))
      waitForConnection(system, instance).await(2, TimeUnit.SECONDS)
      implicit val timeout = Timeout(2 seconds)
      val check1 = Await.result(
        instance.ask(DeclareQueue(QueueParameters("no_such_queue", passive = true))),
        1 second)
      println(check1)
      assert(check1.getClass === classOf[Amqp.Error])
      system.stop(conn)
    }
  }

  "Multiple ChannelOwners" should {
    "each transition from Disconnected to Connected when they receive a channel" in {
      val conn = system.actorOf(Props(new ConnectionOwner(connFactory)))
      val concurrent = 10
      val actors = for (i <- 1 until concurrent) yield ConnectionOwner.createActor(conn, Props(new ChannelOwner()), name = Some(i + "-instance"))
      val latch = waitForConnection(system, actors: _*)
      latch.await(10000, TimeUnit.MILLISECONDS)
      latch.getCount should equal(0)
      system.stop(conn)
    }
  }

  "Consumers" should {
    "receive messages sent by producers" in {
      checkConnection
      val conn = system.actorOf(Props(new ConnectionOwner(connFactory)))
      val exchange = ExchangeParameters(name = "amq.direct", exchangeType = "", passive = true)
      val queue = QueueParameters(name = "", passive = false, exclusive = true)
      val probe = TestProbe()
      val consumer = ConnectionOwner.createActor(conn, Props(new Consumer(List(Binding(exchange, queue, "my_key")), Some(probe.ref), autoack = true)), 5000 millis)
      val producer = ConnectionOwner.createActor(conn, Props(new ChannelOwner()))
      waitForConnection(system, conn, consumer, producer).await()
      val message = "yo!".getBytes
      producer ! Publish(exchange.name, "my_key", message)
      probe.expectMsgClass(1 second, classOf[Delivery])
      system.stop(conn)
    }
  }

  "Producers" should {
    "be able to specify custom message properties" in {
      checkConnection
      val conn = system.actorOf(Props(new ConnectionOwner(connFactory)))
      val exchange = ExchangeParameters(name = "amq.direct", exchangeType = "", passive = true)
      val queue = QueueParameters(name = "queue", passive = false, exclusive = false)
      val probe = TestProbe()
      val consumer = ConnectionOwner.createActor(conn, Props(new Consumer(List(Binding(exchange, queue, "my_key")), probe.ref)), 5000 millis)
      val producer = ConnectionOwner.createActor(conn, Props(new ChannelOwner()))
      waitForConnection(system, conn, consumer, producer).await()
      val message = "yo!".getBytes
      producer ! Publish(exchange.name, "my_key", message, Some(new BasicProperties.Builder().contentType("my content").build()))
      val delivery = probe.receiveOne(1 second).asInstanceOf[Delivery]
      assert(delivery.properties.getContentType == "my content")
      system.stop(conn)
    }
    "publish messages within an AMQP transaction" in {
      checkConnection
      val conn = system.actorOf(Props(new ConnectionOwner(connFactory)))
      val exchange = ExchangeParameters(name = "amq.direct", exchangeType = "", passive = true)
      val queue = QueueParameters(name = "my_queue", passive = false)
      val probe = TestProbe()
      val consumer = ConnectionOwner.createActor(conn, Props(new Consumer(List(Binding(exchange, queue, "my_key")), Some(probe.ref), autoack = true)), 5000 millis)
      val producer = ConnectionOwner.createActor(conn, Props(new ChannelOwner()))
      waitForConnection(system, conn, consumer, producer).await()
      val message = "yo!".getBytes
      producer ! Transaction(List(Publish(exchange.name, "my_key", message), Publish(exchange.name, "my_key", message), Publish(exchange.name, "my_key", message)))
      var received = List[Delivery]()
      probe.receiveWhile(2 seconds) {
        case message: Delivery => received = message :: received
      }
      assert(received.length === 3)
      system.stop(conn)
    }
  }

  "RPC Servers" should {
    "reply to clients" in {
      checkConnection
      val conn = system.actorOf(Props(new ConnectionOwner(connFactory)))
      val exchange = ExchangeParameters(name = "amq.direct", exchangeType = "", passive = true)
      val queue = QueueParameters(name = "my_queue", passive = false)
      val proc = new RpcServer.IProcessor() {
        def process(delivery: Delivery) = {
          println("processing")
          val s = new String(delivery.body)
          if (s == "5") throw new Exception("I dont do 5s")
          Future(ProcessResult(Some(delivery.body)))
        }

        def onFailure(delivery: Delivery, e: Throwable) = ProcessResult(Some(e.toString.getBytes))
      }
      val server = ConnectionOwner.createActor(conn, Props(new RpcServer(queue, exchange, "my_key", proc)), 2000 millis)
      val client1 = ConnectionOwner.createActor(conn, Props(new RpcClient()), 2000 millis)
      val client2 = ConnectionOwner.createActor(conn, Props(new RpcClient()), 2000 millis)
      waitForConnection(system, conn, server, client1, client2).await()
      val exec = ExecutionContext.fromExecutorService(Executors.newCachedThreadPool())

      val f1 = Future {
        for (i <- 0 to 15) {
          try {
            val future = client1.ask(Request(Publish("amq.direct", "my_key", i.toString.getBytes) :: Nil, 1))(1000 millis)
            val result = Await.result(future, 1000 millis).asInstanceOf[Response]
            println("result1 " + new String(result.deliveries.head.body))
            Thread.sleep(300)
          }
          catch {
            case e: Exception => println(e.toString)
          }
        }
      }(exec)
      val f2 = Future {
        for (i <- 0 to 15) {
          try {
            val future = client2.ask(Request(Publish("amq.direct", "my_key", i.toString.getBytes) :: Nil, 1))(1000 millis)
            val result = Await.result(future, 1000 millis).asInstanceOf[Response]
            println("result2 " + new String(result.deliveries.head.body))
            Thread.sleep(300)
          }
          catch {
            case e: Exception => println(e.toString)
          }
        }
      }(exec)
      Await.result(f1, 1 minute)
      Await.result(f2, 1 minute)
      system.stop(conn)
    }

    "manage custom AMQP properties" in {
      checkConnection
      val conn = system.actorOf(Props(new ConnectionOwner(connFactory)))
      val exchange = ExchangeParameters(name = "amq.direct", exchangeType = "", passive = true)
      val queue = QueueParameters(name = "my_queue", passive = false)
      val proc = new RpcServer.IProcessor() {
        def process(delivery: Delivery) = {
          // return the same body with the same properties
          Future(ProcessResult(Some(delivery.body), Some(delivery.properties)))
        }

        def onFailure(delivery: Delivery, e: Throwable) = ProcessResult(Some(e.toString.getBytes), Some(delivery.properties))
      }
      val server = ConnectionOwner.createActor(conn, Props(new RpcServer(queue, exchange, "my_key", proc)), 2000 millis)
      val client = ConnectionOwner.createActor(conn, Props(new RpcClient()), 2000 millis)
      waitForConnection(system, conn, server, client).await()
      val myprops = new BasicProperties.Builder().contentType("my content").contentEncoding("my encoding").build()
      val future = client.ask(Request(Publish("amq.direct", "my_key", "yo!!".toString.getBytes, Some(myprops)) :: Nil, 1))(1000 millis)
      val result = Await.result(future, 1000 millis).asInstanceOf[Response]
      val delivery = result.deliveries.head
      assert(delivery.properties.getContentType == "my content")
      assert(delivery.properties.getContentEncoding == "my encoding")
    }
  }

  "RPC Clients" should {
    "correctly handle returned message" in {
      checkConnection
      val conn = system.actorOf(Props(new ConnectionOwner(connFactory)))
      val client = ConnectionOwner.createActor(conn, Props(new RpcClient()), 2000 millis)
      waitForConnection(system, conn, client)
      implicit val timeout = Timeout(10 seconds) // needed for `?` below

      val future = client.ask(Request(Publish("", "mykey", "yo!".getBytes) :: Nil, 1))(1000 millis)
      val result = Await.result(future, 1000 millis)
      assert(result.isInstanceOf[Undelivered])
    }
  }

  "RPC Clients and Servers" should {
    "implement 1 request/several responses patterns" in {
      checkConnection
      val conn = system.actorOf(Props(new ConnectionOwner(connFactory)))
      val exchange = ExchangeParameters(name = "amq.direct", exchangeType = "", passive = true)
      // empty means that a random name will be generated when the queue is declared
      val queue = QueueParameters(name = "", passive = false, exclusive = true)
      // create 2 servers, each using a broker generated private queue and their own processor
      val proc1 = new IProcessor {
        def process(delivery: Delivery) = Future(ProcessResult(Some("proc1".getBytes)))

        def onFailure(delivery: Delivery, e: Throwable) = ProcessResult(None)
      }
      val server1 = ConnectionOwner.createActor(conn, Props(new RpcServer(queue, exchange, "mykey", proc1)), 2000 millis)
      val proc2 = new IProcessor {
        def process(delivery: Delivery) = Future(ProcessResult(Some("proc2".getBytes)))

        def onFailure(delivery: Delivery, e: Throwable) = ProcessResult(None)
      }
      val server2 = ConnectionOwner.createActor(conn, Props(new RpcServer(queue, exchange, "mykey", proc2)), 2000 millis)

      val client = ConnectionOwner.createActor(conn, Props(new RpcClient()), 2000 millis)
      waitForConnection(system, conn, server1, server2, client)
      val future = client.ask(Request(Publish(exchange.name, "mykey", "yo!".getBytes) :: Nil, 2))(2000 millis)
      val result = Await.result(future, 2000 millis).asInstanceOf[Response]
      assert(result.deliveries.length == 2)
      // we're supposed to have received to answers, "proc1" and "proc2"
      val strings = result.deliveries.map(d => new String(d.body))
      assert(strings.sorted == List("proc1", "proc2"))
      system.stop(conn)
    }
  }
}