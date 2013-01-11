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

## "Production" status

This very simple library is being used in production in a few projects now, either directly or through the
[Akka AMQP Proxies pattern](https://github.com/sstone/akka-amqp-proxies), and so far so good....
So it kind of works and will be maintained for some time :-)

## Configuring maven/sbt

```xml
<repositories>
  <repository>
    <id>sstone snapshots</id>
    <url>http://sstone.github.com/sstone-mvn-repo/snapshots</url>
  </repository>
  <repository>
    <id>sstone releases</id>
    <url>http://sstone.github.com/sstone-mvn-repo/releases</url>
  </repository>
</repositories>

<dependencies>
  <dependency>
    <groupId>com.aphelia</groupId>
<<<<<<< HEAD
    <artifactId>amqp-client_SCALA-VERSION</artifactId>
    <version>1.0-SNAPSHOT</version>
=======
    <artifactId>amqp-client_2.9.2</artifactId>
    <version>1.1-SNAPSHOT</version>
  </dependency>
  <dependency>
    <groupId>com.typesafe.akka</groupId>
    <artifactId>akka-actor</artifactId>
    <version>2.0.3</version>
>>>>>>> 2730fef5765d29ef14cab7b7a8a4133292fbe766
  </dependency>
  <dependency>
    <groupId>com.typesafe.akka</groupId>
    <artifactId>akka-actor_SCALA-VERSION</artifactId>
    <version>YOUR-AKKA-VERSION</version>
  </dependency>
</dependencies>
```

<<<<<<< HEAD
Please note: The scope of the Akka dependency is now "provided", which means that you need to add it explicitely in your maven/sbt project.

The latest snapshot (development) version is 1.1-SNAPSHOT, the latest release version is 1.0

* amqp-client 1.0 is compatible with Scala 2.9.2 and Akka 2.0.3
* amqp-client 1.1-SNAPSHOT is compatible with Scala 2.9.2 and Akka 2.0.3
* amqp-client 1.1-SNAPSHOT is compatible with Scala 2.10.0-RC2 and Akka 2.1.0-RC2

=======
Please note that the Akka dependency is now in the "provided" scope which means that you'll have to define it explicitely in your
maven/sbt projects. 

The latest snapshot (development) version is 1.1-SNAPSHOT, the latest released version is 1.0
>>>>>>> 2730fef5765d29ef14cab7b7a8a4133292fbe766

## Library design

This is a thin wrapper over the RabbitMQ java client, which tries to take advantage of the nice actor model provided
by the Akka library. There is no effort to "hide/encapsulate" the RabbitMQ library (and I don't really see the point anyway
since AMQP is a binary protocol spec, not an API spec).
So to make the most of this library you should first check the documentation for the RabbitMQ client, and learn a bit
about AMQP 0.9.1. There are very nice tutorial on the [RabbitMQ](http://www.rabbitmq.com/) website, and
also [there](http://www.zeromq.org/whitepapers:amqp-analysis), and probably many other...

### Connection and channel management

* AMQP connections are equivalent to "physical" connections. They are managed by ConnectionOwner objects. Each ConnectionOwner
 object manages a single connection and will try and reconnect when the connection is lost.
* AMQP channels are multiplexed over AMQP connections. You use channels to publish and consume messages. Channels are managed
by ChannelOwner objects.

ConnectionOwner and ChannelOwner are implemened as Akka actors, using Akka supervision and the very useful Akka FSM:
* channel owners are created by connection owners
* when a connection is lost, the connection owner will create a new connection and provide each of its children with a
new channel
* connection owners and channel owners are implemented as Finite State Machines, with 2 possible states: Connected and Disconnected
* For a connection owner, "connected" means that it owns a valid connection to the AMQP broker
* For a channel owner, "connected" means that it owns a valid AMQP channel

YMMV, but using few connections (one per JVM) and many channels per connection (one per thread) is a common practice.

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




