# Simple Scala AMQP client

Simple [AMQP](http://www.amqp.org/) client in Scala/Akka based on the [RabbitMQ](http://www.rabbitmq.com/) java client.

## Overview

This client provides a simple API for

* publishing and consuming messages over AMQP
* setting up RPC clients and servers
* automatic reconnection

It is based on the [Akka](http://akka.io/) 2.0 framework.

## Limitations and compatiblity issues

* This client is compatible with AMQP 0.9.1, not AMQP 1.0.
* This client is most probably not easily usable from Java


## Configuring maven/sbt

```xml
<repositories>
  <repository>
    <id>sstone snapshots</id>
    <url>https://github.com/sstone/sstone-mvn-repo/raw/master/snapshots</url>
  </repository>
</repositories>

<dependencies>
  <dependency>
    <groupId>com.aphelia</groupId>
    <artifactId>amqp-client_2.9.2</artifactId>
    <version>1.0-SNAPSHOT</version>
  </dependency>
</dependencies>
```

## Library design

I guess that if you ended up here you already know a bit about AMQP 0.9.1. There are very nice tutorial on the
[RabbitMQ](http://www.rabbitmq.com/) website, and also [there](http://www.zeromq.org/whitepapers:amqp-analysis), and
probably many other...

### Connection and channel management

* AMQP connections are equivalent to "physical" connections. They are managed by ConnectionOwner objects. Each ConnectionOwner
 object manages a single connection and will try and reconnect when the connection is lost.
* AMQP channels are multiplexed over AMQP connections. You use channels to publish and consume messages. Channels are managed
by ChannelOwner objects.

ConnectionOwner and ChannelOwner are implemened as Akka actors, using Akka supervision:
* channel owners are created by connection owners
* when a connection is lost, the connection owner will create a new connection and provide each of its children with a
new channel

YMMV, but using few connections (one per JVM) and many channels per connection is a common practice.

### Basic usage

To create a connection, and a channel owner that you can use to publish message:

``` scala

val connFactory = new ConnectionFactory()
connFactory.setHost("rabbit")
val conn = system.actorOf(Props(new ConnectionOwner(connFactory)), name = "conn")
val producer = ConnectionOwner.createActor(conn, Props(new ChannelOwner()))
waitForConnection(system, consumer, producer).await()
producer ! Publish("amq.direct", "my_key", "yo!".getBytes)


```

To consume messages:

``` scala
val connFactory = new ConnectionFactory()
connFactory.setHost("localhost")
val conn = system.actorOf(Props(new ConnectionOwner(connFactory)), name = "conn")
// use the standard direct exchange
val exchange = ExchangeParameters(name = "amq.direct", exchangeType = "", passive = true)
// and a shared queue
val queue = QueueParameters(name = "my_queue", passive = false, exclusive = true, autodelete = true)

val foo = system.actorOf(Props(new Actor {
  def receive = {
    case Delivery(tag, envelope, properties, body) => {
      println("got a message")
      println(properties)
      sender ! Ack(envelope.getDeliveryTag)
    }
  }
}))

// create a consumer that will pass all messages to the foo Actor; the consumer will declare the bindings
val consumer = ConnectionOwner.createActor(conn, Props(new Consumer(List(Binding(exchange, queue, "my_key", autoack = false)), foo)), 5000 millis)
val producer = ConnectionOwner.createActor(conn, Props(new ChannelOwner()))
waitForConnection(system, consumer, producer).await()
producer ! Publish("amq.direct", "my_key", "yo!".getBytes, Some(new BasicProperties.Builder().contentType("my content").build()))

```


## RPC patterns

Typical RPC with AMQP follows this pattern:

1.  client sets up a private, exclusive response queue
2.  client sends message and set their 'replyTo' property to the name of this response queue
3.  server processes the message and replies to its 'replyTo' queue by publishing the response to the default exchange using the queue name as routing key (all queues are bound to their name
on the default exchange)

Usually, you would set up several RPC servers which all use the same shared queue. The broker will load-balance messsages
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

Please check ChannelOwnerSpec.scala in [src/test/scala/com/aphelia/amqp/ChannelOwnerSpec.scala](http://github.com/sstone/amqp-client/blob/master/src/test/scala/com/aphelia/amqp/ChannelOwnerSpec.scala) for
more comprehensive samples

``` scala

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
      val consumer = ConnectionOwner.createActor(conn, Props(new Consumer(List(Binding(exchange, queue, "my_key", autoack = false)), foo)), 5000 millis)
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
      for(i <- 0 to 10) producer ! Transaction(Publish("amq.direct", "my_key", "yo".getBytes, true, false) :: Nil)
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
        def process(delivery : Delivery) = {
          println("processing")
          Some(delivery.body)
        }
        def onFailure(delivery : Delivery, e: Exception) = Some(e.toString.getBytes)
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
      waitForConnection(system, server1, server2, client).await()
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
      system.shutdown()
    }
````



