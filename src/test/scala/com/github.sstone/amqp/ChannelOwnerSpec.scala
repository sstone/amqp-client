package com.github.sstone.amqp

import org.junit.runner.RunWith
import org.specs2.runner.JUnitRunner
import org.specs2.mutable.{After, Specification}
import com.rabbitmq.client.ConnectionFactory
import akka.actor.{ActorSystem, Props}
import com.github.sstone.amqp.Amqp._
import java.util.concurrent.{Executors, TimeUnit}
import concurrent.{ExecutionContext, Future, Await}
import concurrent.duration._
import akka.testkit.{ImplicitSender, TestProbe, TestKit}
import akka.pattern.{ask, gracefulStop}
import org.specs2.time.NoTimeConversions
import akka.util.Timeout
import com.rabbitmq.client.AMQP.{BasicProperties, Queue}
import com.github.sstone.amqp.RpcServer.{IProcessor, ProcessResult}
import com.github.sstone.amqp.RpcClient.{Undelivered, Response, Request}
import ExecutionContext.Implicits.global

@RunWith(classOf[JUnitRunner])
class ChannelOwnerSpec extends TestKit(ActorSystem("TestSystem")) with Specification with NoTimeConversions with ImplicitSender {
  implicit val timeout = Timeout(5 seconds)
  sequential

  trait TestCtx extends After {
    val connFactory = new ConnectionFactory()
    val conn = system.actorOf(Props(new ConnectionOwner(connFactory)))
    val channelOwner = ConnectionOwner.createActor(conn, Props(new ChannelOwner()))
    waitForConnection(system, conn, channelOwner).await(5, TimeUnit.SECONDS)

    def after = {
      Await.result(gracefulStop(conn, 5 seconds)(system), 6 seconds)
    }
  }

  "ChannelOwner" should {
    "implement basic error handling" in new TestCtx {
      channelOwner ! DeclareQueue(QueueParameters("no_such_queue", passive = true))
      expectMsgClass(1 second, classOf[Amqp.Error])
    }
    "allow users to create, bind, purge and delete queues" in new TestCtx {
      val queue = "my_test_queue"

      // declare a queue, bind it to "my_test_key" on "amq.direct" and publish a message
      channelOwner ! DeclareQueue(QueueParameters(queue, passive = false, durable = false, autodelete = true))
      channelOwner ! QueueBind(queue, "amq.direct", "my_test_key")
      channelOwner ! Publish("amq.direct", "my_test_key", "yo!".getBytes)
      receiveN(3, 2 seconds)

      // check that there is 1 message in the queue
      channelOwner ! DeclareQueue(QueueParameters(queue, passive = true))
      val Amqp.Ok(_, Some(check1: Queue.DeclareOk)) = receiveOne(1 second)

      // purge the queue
      channelOwner ! PurgeQueue(queue)
      receiveOne(1 second)

      // check that there are no more messages in the queue
      channelOwner ! DeclareQueue(QueueParameters(queue, passive = true))
      val Amqp.Ok(_, Some(check2: Queue.DeclareOk)) = receiveOne(1 second)

      // delete the queue
      channelOwner ! DeleteQueue(queue)
      val Amqp.Ok(_, Some(check3: Queue.DeleteOk)) = receiveOne(1 second)

      assert(check1.getMessageCount === 1)
      assert(check2.getMessageCount === 0)
    }
  }

  "Multiple ChannelOwners" should {
    "each transition from Disconnected to Connected when they receive a channel" in new TestCtx {
      val concurrent = 10
      val actors = for (i <- 1 until concurrent) yield ConnectionOwner.createActor(conn, Props(new ChannelOwner()), name = Some(i + "-instance"))
      val latch = waitForConnection(system, actors: _*)
      latch.await(10000, TimeUnit.MILLISECONDS)
      latch.getCount mustEqual 0
    }
  }

  "Consumers" should {
    "receive messages sent by producers" in new TestCtx {
      val exchange = ExchangeParameters(name = "amq.direct", exchangeType = "", passive = true)
      val queue = QueueParameters(name = "", passive = false, exclusive = true)
      val probe = TestProbe()
      val consumer = ConnectionOwner.createActor(conn, Props(new Consumer(List(Binding(exchange, queue, "my_key")), probe.ref)), 5000.millis)
      val producer = ConnectionOwner.createActor(conn, Props(new ChannelOwner()))
      waitForConnection(system, consumer, producer).await()
      val message = "yo!".getBytes
      producer ! Publish(exchange.name, "my_key", message)
      probe.expectMsgClass(1.second, classOf[Delivery])
    }
  }

  "Producers" should {
    "be able to specify custom message properties" in new TestCtx {
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
    "publish messages within an AMQP transaction" in new TestCtx {
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

  "RPC Servers" should {
    "reply to clients" in new TestCtx {
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
      val server = ConnectionOwner.createActor(conn, Props(new RpcServer(queue, exchange, "my_key", proc)), 2000.millis)
      val client1 = ConnectionOwner.createActor(conn, Props(new RpcClient()), 2000.millis)
      val client2 = ConnectionOwner.createActor(conn, Props(new RpcClient()), 2000.millis)
      waitForConnection(system, conn, server, client1, client2).await()

      val f1 = Future {
        for (i <- 0 to 15) {
          try {
            val future = client1 ? Request(Publish("amq.direct", "my_key", i.toString.getBytes) :: Nil, 1)
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
        for (i <- 0 to 15) {
          try {
            val future = client2 ? Request(Publish("amq.direct", "my_key", i.toString.getBytes) :: Nil, 1)
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

    "manage custom AMQP properties" in new TestCtx {
      val exchange = ExchangeParameters(name = "amq.direct", exchangeType = "", passive = true)
      val queue = QueueParameters(name = "my_queue", passive = false)
      val proc = new RpcServer.IProcessor() {
        def process(delivery: Delivery) = {
          // return the same body with the same properties
          Future(ProcessResult(Some(delivery.body), Some(delivery.properties)))
        }

        def onFailure(delivery: Delivery, e: Throwable) = ProcessResult(Some(e.toString.getBytes), Some(delivery.properties))
      }
      val server = ConnectionOwner.createActor(conn, Props(new RpcServer(queue, exchange, "my_key", proc)), 2000.millis)
      val client = ConnectionOwner.createActor(conn, Props(new RpcClient()), 2000.millis)
      waitForConnection(system, conn, server, client).await()
      val myprops = new BasicProperties.Builder().contentType("my content").contentEncoding("my encoding").build()
      val future = client ? Request(Publish("amq.direct", "my_key", "yo!!".getBytes, Some(myprops)) :: Nil, 1)
      val result = Await.result(future, 1000.millis).asInstanceOf[Response]
      val delivery = result.deliveries.head
      assert(delivery.properties.getContentType === "my content")
      assert(delivery.properties.getContentEncoding === "my encoding")
    }
  }

  "RPC Clients" should {
    "correctly handle returned message" in new TestCtx {
      val client = ConnectionOwner.createActor(conn, Props(new RpcClient()), 2000.millis)
      waitForConnection(system, conn, client)

      val future = client ? Request(Publish("", "mykey", "yo!".getBytes) :: Nil, 1)
      val result = Await.result(future, 1000.millis)
      assert(result.isInstanceOf[Undelivered])
    }
  }

  "RPC Clients and Servers" should {
    "implement 1 request/several responses patterns" in new TestCtx {
      val exchange = ExchangeParameters(name = "amq.direct", exchangeType = "", passive = true)
      // empty means that a random name will be generated when the queue is declared
      val queue = QueueParameters(name = "", passive = false, exclusive = true)
      // create 2 servers, each using a broker generated private queue and their own processor
      val proc1 = new IProcessor {
        def process(delivery: Delivery) = Future(ProcessResult(Some("proc1".getBytes)))

        def onFailure(delivery: Delivery, e: Throwable) = ProcessResult(None)
      }
      val server1 = ConnectionOwner.createActor(conn, Props(new RpcServer(queue, exchange, "mykey", proc1)), 2000.millis)
      val proc2 = new IProcessor {
        def process(delivery: Delivery) = Future(ProcessResult(Some("proc2".getBytes)))

        def onFailure(delivery: Delivery, e: Throwable) = ProcessResult(None)
      }
      val server2 = ConnectionOwner.createActor(conn, Props(new RpcServer(queue, exchange, "mykey", proc2)), 2000.millis)

      val client = ConnectionOwner.createActor(conn, Props(new RpcClient()), 2000.millis)
      waitForConnection(system, conn, server1, server2, client)
      val future = client ? Request(Publish(exchange.name, "mykey", "yo!".getBytes) :: Nil, 2)
      val result = Await.result(future, 2000.millis).asInstanceOf[Response]
      assert(result.deliveries.length === 2)
      // we're supposed to have received to answers, "proc1" and "proc2"
      val strings = result.deliveries.map(d => new String(d.body))
      assert(strings.sorted === List("proc1", "proc2"))
    }
  }
}
