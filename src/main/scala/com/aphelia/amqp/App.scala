package com.aphelia.amqp

import akka.pattern.ask
import akka.util.duration._
import com.rabbitmq.client.{Channel, ConnectionFactory}
import akka.actor.Props.apply
import java.util.concurrent.{Executors, ExecutorService}
import akka.dispatch.{ExecutionContext, Future, Await}
import com.aphelia.amqp.RpcClient.{Response, Request}
import com.aphelia.amqp.ConnectionOwner.Create
import akka.util.Duration
import akka.actor._
import akka.actor.FSM.{Transition, SubscribeTransitionCallBack}


/**
 * @author ${user.name}
 */
object App {

  /**
   * Since ChannelOwner are FSM with "Connected" and "Disconnected" states, it is fairly
   * easy to monitor state transitions. In this case we just want to knwo when
   * a channel owner is "connected" i.e owns a valid channel
   * @param channelActor channel actor
   * @param onConnected connection callback, called when the actor transitions from "disconnected"
   * to "connected"
   */
  def setMonitor(system : ActorSystem, channelActor : ActorRef,  onConnected: () => Unit) = {
    val m = system.actorOf(Props(new Actor {
      def receive = {
        case Transition(_, ChannelOwner.Disconnected, ChannelOwner.Connected) => {
          onConnected()
          context.stop(self)
        }
      }
    }))
    channelActor ! SubscribeTransitionCallBack(m)
  }

  /**
   * basic consumer/producer test
   * @param conn ConnectionOwner actor
   */
  def testConsumer(conn : ActorRef) {
    val system = ActorSystem("MySystem")
    val exchange = ExchangeParameters(name = "amq.direct", exchangeType = "", passive = true)
    val queue = QueueParameters(name = "", passive = false, exclusive = true)
    val foo = system.actorOf(Props(new Actor {
      def receive = {
        case Delivery(tag, envelope, properties, body) => {
          println("got a message")
          sender ! Ack(envelope.getDeliveryTag)
        }
      }
    }))
    var consumerReady = false
    var producerReady = false
    val consumer = ConnectionOwner.createActor(conn, Props(new Consumer(List(Binding(exchange, queue, "my_key", autoack = false)), foo)), 5000 millis)
    setMonitor(system, consumer, () => consumerReady = true)
    val producer = ConnectionOwner.createActor(conn, Props(new ChannelOwner()))
    setMonitor(system, producer, () => producerReady = true)
    while(!producerReady && !consumerReady) Thread.sleep(200)
    producer ! Publish("amq.direct", "my_key", "yo!".getBytes)
    consumer ! PoisonPill
    producer ! PoisonPill
  }

  /**
   * basic transaction test
   * @param conn ConnectionOwner actor
   */
  def testTransactions(conn : ActorRef) {
    val system = ActorSystem("MySystem")
    val exchange = ExchangeParameters(name = "amq.direct", exchangeType = "", passive = true)
    val queue = QueueParameters(name = "queue", passive = false, exclusive = false)
    val foo = system.actorOf(Props(new Actor {
      def receive = {
        case Delivery(tag, envelope, properties, body) => println("got a message")
      }
    }))
    var producerReady = false
    var consumerReady = false
    val producer = ConnectionOwner.createActor(conn, Props(new ChannelOwner()))
    val consumer = ConnectionOwner.createActor(conn, Props(new Consumer(Binding(exchange, queue, "my_key", true) :: Nil, foo)))
    setMonitor(system, producer, () => producerReady = true)
    setMonitor(system, consumer, () => consumerReady = true)
    while(!producerReady && !consumerReady) Thread.sleep(200)
    for(i <- 0 to 10) producer ! Transaction(Publish("amq.direct", "my_key2", "yo".getBytes, true, false) :: Nil)
    consumer ! PoisonPill
    producer ! PoisonPill
    foo ! PoisonPill
  }

  /**
   * RPC sample where each request is picked up by 2 different server and results in 2 responses
   * @param conn ConnectionOwner actor
   */
  def testMultipleResponses(conn : ActorRef) {
    // basic processor
    val proc = new RpcServer.IProcessor() {
      def process(input: Array[Byte]) = {
        println("processing")
        input
      }

      def onFailure(e: Exception) = e.toString.getBytes
    }
    // amq.direct is one of the standard AMQP exchanges
    val exchange = ExchangeParameters(name = "amq.direct", exchangeType = "", passive = true)
    // this is how you define an exclusive, private response queue. The name is empty
    // which means that the broker will generate a unique, random name when the queue is declared
    val queue = QueueParameters(name = "", passive = false, exclusive = true)
    // create 2 servers, each with its own private queue bound to the same key
    val server1 = ConnectionOwner.createActor(conn, Props(new RpcServer(queue, exchange, "my_key", proc)), 2000 millis)
    val server2 = ConnectionOwner.createActor(conn, Props(new RpcServer(queue, exchange, "my_key", proc)), 2000 millis)
    val client = ConnectionOwner.createActor(conn, Props(new RpcClient()), 2000 millis)
    for (i <-0 to 10) {
      try {
        // send one request and wait for 2 responses
        val future = client.ask(Request(Publish("amq.direct", "my_key", "client1".getBytes) :: Nil, 2))(1000 millis)
        val result = Await.result(future, 1000 millis).asInstanceOf[Response]
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
  }

  def main(args: Array[String]) {
    var serverMode = false
    var host = "localhost"
    var exchange = "amq.direct"
    var queue = "my_queue"
    var key = "my_key"
    var numberOfResponse = 1
    var message = "yo!"

    def parseOptions(arguments : List[String]) {
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
    testConsumer(conn)
    system.shutdown()
    sys.exit()

    if (serverMode) {
      val proc = new RpcServer.IProcessor() {
        def process(input: Array[Byte]) = {
          println("processing" + new String(input))
          input
        } // just return the input
        def onFailure(e: Exception) = null
      }
      val server = ConnectionOwner.createActor(conn,
        Props(new RpcServer(
          QueueParameters(queue, passive = false),
          ExchangeParameters(exchange, passive = true, exchangeType = "direct"),
          key,
          proc)
        ), 2000 millis
      )
      while(true) {
        Thread.sleep(100)
      }
    }
    else {
      val client = ConnectionOwner.createActor(conn, Props(new RpcClient()), 2000 millis)
      Thread.sleep(100)
      val future = client.ask(Request(Publish(exchange, key, message.getBytes) :: Nil, numberOfResponse))(1000 millis)
      val result = Await.result(future, 1000 millis).asInstanceOf[Response]
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
