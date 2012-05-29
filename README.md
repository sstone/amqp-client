# Simple Scala AMQP client

Simple [AMQP](http://www.amqp.org/) client in Scala/Akka based on the [RabbitMQ](http://www.rabbitmq.com/) java client.

## Overview

This client provides a simple API for

* publishing and consuming messages over AMQP
* setting up RPC clients and servers
* automatic reconnection

It is based on the [Akka](http://akka.io/) 2.0 framework.

## RPC patterns

Typical RPC with AMQP follows this pattern:

1.  client sets up a private, exclusive response queue
2.  client sends message and set their 'replyTo' property to the name of this response queue
3.  server processes the message and replies to its 'replyTo' queue by publishing the response to the default exchange using the queue name as routing key (all queues are bound to their name
on the default exchange)

Usually, you would set up RPC server which all use the same shared queue. The broker will load-balance messsages
between these consumers using round-robin distribution, which can be combined with 'prefetch' channel settings.
Setting 'prefetch' to 1 is very useful if you need resource-based (CPU, ...) load-balancing.

But you can also extend this pattern by setting up RPC servers which all use private exclusive queues
bound to the same key. In this case, each server will receive the same request and will send back a response.
This is very useful if you want to break a single operation into multiple, parallel steps.
For example, if you want to decrypt things, you could divide the key space into N parts, set up one
RPC server for each part, publish a single RPC request and wait for N responses.

This could be further extended with a simple 'workflow' pattern where each server publishes its results
to the shared queue used by the next step.
For example, if you want to chain steps A, B and C, set up a shared queue for each step, have 'A' processors
publish to queue 'B', 'B' processors publish to queue 'C' ....


## Samples

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


