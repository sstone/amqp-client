package com.aphelia.amqp

import scala.language.postfixOps

import akka.pattern.ask
import com.rabbitmq.client.ConnectionFactory
import com.aphelia.amqp.RpcClient.{Response, Request}
import akka.actor._
import akka.actor.FSM.{Transition, SubscribeTransitionCallBack}
import com.aphelia.amqp.Amqp._
import java.util.concurrent.CountDownLatch
import akka.util.Timeout
import com.rabbitmq.client.AMQP.{BasicProperties, Queue}
import akka.actor.Status.Failure
import com.aphelia.amqp.RpcServer.ProcessResult
import scala.concurrent.Await
import scala.concurrent.duration.Duration
import scala.concurrent.duration._

object App {

  def foo() {
    val system = ActorSystem("MySystem")
    val connFactory = new ConnectionFactory()
    connFactory.setHost("localhost")
    // create a "connection owner" actor, which will try and reconnect automatically if the connection ins lost
    val conn = system.actorOf(Props(new ConnectionOwner(connFactory)), name = "conn")
    waitForConnection(system, conn).await()
    val c = ConnectionOwner.createActor(conn, Props(new ChannelOwner()))
    waitForConnection(system, c).await()
    implicit val timeout = Timeout(2.seconds)
    val check = Await.result(c.ask(DeclareQueue(QueueParameters("no_such_queue", passive = true))), timeout.duration)
    check match {
      case Amqp.Error(_, cause) => throw cause
      case uh => println(uh)
    }
    system.stop(conn)
  }

  def testProperties() {
    val system = ActorSystem("MySystem")
    val connFactory = new ConnectionFactory()
    connFactory.setHost("localhost")
    // create a "connection owner" actor, which will try and reconnect automatically if the connection ins lost
    val conn = system.actorOf(Props(new ConnectionOwner(connFactory)), name = "conn")
    // use the standard direct exchange
    val exchange = ExchangeParameters(name = "amq.direct", exchangeType = "", passive = true)
    // and an exclusive, private queue (name = "" means that the broker will generate a random name)
    val queue = QueueParameters(name = "my_queue", passive = false, exclusive = true, autodelete = true)

    val foo = system.actorOf(Props(new Actor {
      def receive = {
        case d@Delivery(tag, envelope, properties, body) => {
          println("got a message")
          println(properties)
          sender ! Ack(envelope.getDeliveryTag)
        }
      }
    }))
    // create a consumer that will pass all messages to the foo Actor; the consumer will declare the bindings
    val consumer = ConnectionOwner.createActor(conn, Props(new Consumer(List(Binding(exchange, queue, "my_key", autoack = false)), foo)), 5000.millis)
    val producer = ConnectionOwner.createActor(conn, Props(new ChannelOwner()))
    waitForConnection(system, consumer, producer).await()
    producer ! Publish("amq.direct", "my_key", "yo!".getBytes, Some(new BasicProperties.Builder().contentType("my content").build()))
    Thread.sleep(1000)
    consumer ! PoisonPill
    producer ! PoisonPill
    system.shutdown()
  }

  /**
   * basic consumer/producer test
   */
  def testConsumer() {
    val system = ActorSystem("MySystem")
    val connFactory = new ConnectionFactory()
    connFactory.setHost("localhost")
    // create a "connection owner" actor, which will try and reconnect automatically if the connection ins lost
    val conn = system.actorOf(Props(new ConnectionOwner(connFactory)), name = "conn")
    // use the standard direct exchange
    val exchange = ExchangeParameters(name = "amq.direct", exchangeType = "", passive = true)
    // and an exclusive, private queue (name = "" means that the broker will generate a random name)
    val queue = QueueParameters(name = "", passive = false, exclusive = true)

    val foo = system.actorOf(Props(new Actor {
      def receive = {
        case Delivery(tag, envelope, properties, body) => {
          println("got a message")
          sender ! Ack(envelope.getDeliveryTag)
        }
      }
    }))
    // create a consumer that will pass all messages to the foo Actor; the consumer will declare the bindings
    val consumer = ConnectionOwner.createActor(conn, Props(new Consumer(List(Binding(exchange, queue, "my_key", autoack = false)), foo)), 5000.millis)
    val producer = ConnectionOwner.createActor(conn, Props(new ChannelOwner()))
    waitForConnection(system, consumer, producer).await()
    producer ! Publish("amq.direct", "my_key", "yo!".getBytes)
    consumer ! PoisonPill
    producer ! PoisonPill
    system.shutdown()
  }

  /**
   * basic transaction test
   */
  def testTransactions() {
    val system = ActorSystem("MySystem")
    val connFactory = new ConnectionFactory()
    connFactory.setHost("localhost")
    // create a "connection owner" actor, which will try and reconnect automatically if the connection ins lost
    val conn = system.actorOf(Props(new ConnectionOwner(connFactory)), name = "conn")
    val exchange = ExchangeParameters(name = "amq.direct", exchangeType = "", passive = true)
    val queue = QueueParameters(name = "queue", passive = false, exclusive = false)
    val foo = system.actorOf(Props(new Actor {
      def receive = {
        case Delivery(tag, envelope, properties, body) => println("got a message")
      }
    }))
    val producer = ConnectionOwner.createActor(conn, Props(new ChannelOwner()))
    val consumer = ConnectionOwner.createActor(conn, Props(new Consumer(Binding(exchange, queue, "my_key", true) :: Nil, foo)))
    waitForConnection(system, producer, consumer).await()
    for (i <- 0 to 10) producer ! Transaction(Publish("amq.direct", "my_key", "yo".getBytes, mandatory = true, immediate = false) :: Nil)
    consumer ! PoisonPill
    producer ! PoisonPill
    foo ! PoisonPill
    system.shutdown()
  }

  /**
   * RPC sample where each request is picked up by 2 different server and results in 2 responses
   */
  def testMultipleResponses() {
    val system = ActorSystem("MySystem")
    val connFactory = new ConnectionFactory()
    connFactory.setHost("localhost")
    // create a "connection owner" actor, which will try and reconnect automatically if the connection ins lost
    val conn = system.actorOf(Props(new ConnectionOwner(connFactory)), name = "conn")

    // basic processor
    val proc = new RpcServer.IProcessor() {
      def process(delivery: Delivery) = {
        println("processing")
        ProcessResult(Some(delivery.body))
      }

      def onFailure(delivery: Delivery, e: Exception) = ProcessResult(Some(e.toString.getBytes))
    }
    // amq.direct is one of the standard AMQP exchanges
    val exchange = ExchangeParameters(name = "amq.direct", exchangeType = "", passive = true)
    // this is how you define an exclusive, private response queue. The name is empty
    // which means that the broker will generate a unique, random name when the queue is declared
    val queue = QueueParameters(name = "", passive = false, exclusive = true)
    // create 2 servers, each with its own private queue bound to the same key
    val server1 = ConnectionOwner.createActor(conn, Props(new RpcServer(queue, exchange, "my_key", proc)), 2000.millis)
    val server2 = ConnectionOwner.createActor(conn, Props(new RpcServer(queue, exchange, "my_key", proc)), 2000.millis)
    val client = ConnectionOwner.createActor(conn, Props(new RpcClient()), 2000.millis)
    waitForConnection(system, server1, server2, client).await()
    for (i <- 0 to 10) {
      try {
        // send one request and wait for 2 responses
        val future = client.ask(Request(Publish("amq.direct", "my_key", "client1".getBytes) :: Nil, 2))(1000.millis)
        val result = Await.result(future, 1000.millis).asInstanceOf[Response]
        println("result : " + result)
        Thread.sleep(100)
      }
      catch {
        case e: Exception => println(e.toString)
      }
    }
    client ! PoisonPill
    server1 ! PoisonPill
    server2 ! PoisonPill
    system.shutdown()
  }

  def main(args: Array[String]) {
    testProperties()
    var serverMode = false
    var host = "localhost"
    var exchange = "amq.direct"
    var queue = "my_queue"
    var key = "my_key"
    var numberOfResponse = 1
    var message = "yo!"

    def parseOptions(arguments: List[String]) {
      arguments match {
        case "-h" :: value :: tail => host = value; parseOptions(tail)
        case "-q" :: value :: tail => queue = value; parseOptions(tail)
        case "-k" :: value :: tail => key = value; parseOptions(tail)
        case "-n" :: value :: tail => numberOfResponse = Integer.parseInt(value); parseOptions(tail)
        case "-s" :: tail => serverMode = true
        case value :: Nil => message = value
        case Nil =>
      }
    }
    parseOptions(args.toList)

    val system = ActorSystem("MySystem")
    val connFactory = new ConnectionFactory()
    connFactory.setHost(host)
    val conn = system.actorOf(Props(new ConnectionOwner(connFactory)), name = "conn")

    if (serverMode) {
      val proc = new RpcServer.IProcessor() {
        def process(delivery: Delivery) = {
          println("processing" + delivery)
          ProcessResult(Some(delivery.body))
        }

        // just return the input
        def onFailure(delivery: Delivery, e: Exception) = ProcessResult(None)
      }
      val server = ConnectionOwner.createActor(conn,
        Props(new RpcServer(
          QueueParameters(queue, passive = false),
          ExchangeParameters(exchange, passive = true, exchangeType = "direct"),
          key,
          proc)
        ), 2000.millis
      )
      while (true) {
        Thread.sleep(100)
      }
    }
    else {
      val client = ConnectionOwner.createActor(conn, Props(new RpcClient()), 2000.millis)
      waitForConnection(system, client)
      val future = client.ask(Request(Publish(exchange, key, message.getBytes) :: Nil, numberOfResponse))(1000.millis)
      val result = Await.result(future, 1000.millis).asInstanceOf[Response]
      println("result " + result)
    }
    system.shutdown()

    //testMultipleResponses
    //sys.exit()
    //testMultiThread
    //testTransactions
    //testMultiThread(conn)
    //testMultipleResponses(conn)
  }
}
