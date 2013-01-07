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
        <id>sonatype snapshots</id>
        <url>https://oss.sonatype.org/content/repositories/snapshots/</url>
    </repository>
</repositories>

<dependencies>
  <dependency>
    <groupId>com.github.sstone</groupId>
    <artifactId>amqp-client_SCALA-VERSION</artifactId>
    <version>1.1-SNAPSHOT</version>
  </dependency>
  <dependency>
    <groupId>com.typesafe.akka</groupId>
    <artifactId>akka-actor</artifactId> <!-- for Akka 2.0.X -->
    <artifactId>akka-actor_SCALA-VERSION</artifactId> <!-- for Akka 2.1.X -->
    <version>AKKA-VERSION</version>
  </dependency>
</dependencies>
```

Please note that the Akka dependency is now in the "provided" scope which means that you'll have to define it explicitely in your
maven/sbt projects. 

The latest snapshot (development) version is 1.1-SNAPSHOT, the latest released version is 1.1-RC1. They are both compatible with
Akka 2.1.0/Scala 2.10 and Akka 2.0.5/Scala 2.9.2

Version 1.0 was package with groupdId set to com.aphelia and uploaded to my own mvn repository (http://sstone.github.com/sstone-mvn-repo/{snapshots/releases}).
From version 1.1 on, snapshots are uploaded to https://oss.sonatype.org/content/repositories/snapshots and releases are synced to maven central.

## Compatibility with Scala 2.10

Master is currently developped against 2.9.2, branch scala2.10 against 2.10.0


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

  implicit val system = ActorSystem("mySystem")

  // create an AMQP connection
  val conn = new RabbitMQConnection(host = "localhost", name = "Connection")

  // create a "channel owner" on this connection
  val producer = conn.createChannelOwner()

  // wait till everyone is actually connected to the broker
  Amqp.waitForConnection(system, producer).await()

  // send a message
  producer ! Publish("amq.direct", "my_key", "yo!!".getBytes, properties = None, mandatory = true, immediate = false)

```

To consume messages:

``` scala
  implicit val system = ActorSystem("mySystem")

  // create an AMQP connection
  val conn = new RabbitMQConnection(host = "localhost", name = "Connection")

  // create an actor that will receive AMQP deliveries
  val listener = system.actorOf(Props(new Actor {
    protected def receive = {
      case Delivery(consumerTag, envelope, properties, body) => {
        println("got a message: " + new String(body))
        sender ! Ack(envelope.getDeliveryTag)
      }
    }
  }))

  // create a consumer that will route incoming AMQP messages to our listener
  val queueParams = QueueParameters("my_queue", passive = false, durable = false, exclusive = false, autodelete = true)
  val consumer = conn.createConsumer(Amqp.StandardExchanges.amqDirect, queueParams, "my_key", listener, None)

  // wait till everyone is actually connected to the broker
  Amqp.waitForConnection(system, consumer).await()

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

``` scala
  // typical "work queue" pattern, where a job can be picked up by any running node
  implicit val system = ActorSystem("mySystem")

  // create an AMQP connection
  val conn = new RabbitMQConnection(host = "localhost", name = "Connection")

  val queueParams = QueueParameters("my_queue", passive = false, durable = false, exclusive = false, autodelete = true)

  // create 2 equivalent servers
  val rpcServers = for (i <- 1 to 2) yield {
    // create a "processor"
    // in real life you would use a serialization framework (json, protobuf, ....), define command messages, etc...
    // check the Akka AMQP proxies project for examples
    val processor = new IProcessor {
      def process(delivery: Delivery) = {
        // assume that the message body is a string
        val response = "response to " + new String(delivery.body)
        Future(ProcessResult(Some(response.getBytes)))
      }
      def onFailure(delivery: Delivery, e: Throwable) = ProcessResult(None) // we don't return anything
    }
    conn.createRpcServer(StandardExchanges.amqDirect, queueParams, "my_key", processor, Some(ChannelParameters(qos = 1)))
  }

  val rpcClient = conn.createRpcClient()

  // wait till everyone is actually connected to the broker
  Amqp.waitForConnection(system, rpcServers: _*).await()
  Amqp.waitForConnection(system, rpcClient).await()

  implicit val timeout: Timeout = 2 seconds

  for (i <- 0 to 5) {
    val request = ("request " + i).getBytes
    val f = (rpcClient ? Request(List(Publish("amq.direct", "my_key", request)))).mapTo[RpcClient.Response]
    f.onComplete {
      case Right(response) => println(new String(response.deliveries.head.body))
      case Left(error) => println(error)
    }
  }
  // wait 10 seconds and shut down
  // run the Producer sample now and see what happens
  Thread.sleep(10000)
  system.shutdown()

```

``` scala

  // one request/several responses pattern
  implicit val system = ActorSystem("mySystem")

  // create an AMQP connection
  val conn = new RabbitMQConnection(host = "localhost", name = "Connection")

  // typical "reply queue"; the name if left empty: the broker will generate a new random name
  val privateReplyQueue = QueueParameters("", passive = false, durable = false, exclusive = true, autodelete = true)

  // we have a problem that can be "sharded", we create one server per shard, and for each request we expect one
  // response from each shard

  // create one server per shard
  val rpcServers = for (i <- 0 to 2) yield {
    // create a "processor"
    // in real life you would use a serialization framework (json, protobuf, ....), define command messages, etc...
    // check the Akka AMQP proxies project for examples
    val processor = new IProcessor {
      def process(delivery: Delivery) = {
        // assume that the message body is a string
        val response = "response to " + new String(delivery.body) + " from shard " + i
        Future(ProcessResult(Some(response.getBytes)))
      }
      def onFailure(delivery: Delivery, e: Throwable) = ProcessResult(None) // we don't return anything
    }
    conn.createRpcServer(StandardExchanges.amqDirect, privateReplyQueue, "my_key", processor, Some(ChannelParameters(qos = 1)))
  }

  val rpcClient = conn.createRpcClient()

  // wait till everyone is actually connected to the broker
  Amqp.waitForConnection(system, rpcServers: _*).await()
  Amqp.waitForConnection(system, rpcClient).await()

  implicit val timeout: Timeout = 2 seconds

  for (i <- 0 to 5) {
    val request = ("request " + i).getBytes
    val f = (rpcClient ? Request(List(Publish("amq.direct", "my_key", request)), 3)).mapTo[RpcClient.Response]
    f.onComplete {
      case Right(response) => {
        response.deliveries.foreach(delivery => println(new String(delivery.body)))
      }
      case Left(error) => println(error)
    }
  }
  // wait 10 seconds and shut down
  // run the Producer sample now and see what happens
  Thread.sleep(10000)
  system.shutdown()

```


## Samples

Please check ChannelOwnerSpec.scala in [src/test/scala/com/aphelia/amqp/ChannelOwnerSpec.scala](http://github.com/sstone/amqp-client/blob/master/src/test/scala/com/aphelia/amqp/ChannelOwnerSpec.scala) for
more comprehensive samples




