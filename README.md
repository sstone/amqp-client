# Simple Scala AMQP client

Simple [AMQP](http://www.amqp.org/) client in Scala/Akka based on the [RabbitMQ](http://www.rabbitmq.com/) java client.

[![Build Status](https://travis-ci.org/sstone/amqp-client.png?branch=scala2.10)](https://travis-ci.org/sstone/amqp-client)

## Overview

This client provides a simple API for

* publishing and consuming messages over AMQP
* setting up RPC clients and servers
* automatic reconnection

It is based on the [Akka](http://akka.io/) 2.0 framework.

## Limitations and compatibility issues

* This client is compatible with AMQP 0.9.1, not AMQP 1.0.
* This client is most probably not easily usable from Java

## "Production" status

This very simple library is being used in production in a few projects now, either directly or through the
[Akka AMQP Proxies pattern](https://github.com/sstone/akka-amqp-proxies), and so far so good....
So it kind of works and will be maintained for some time :-)

## Configuring maven/sbt

* releases and milestones are pushed to maven central
* snapshots are pushed to the sonatype snapshot repository

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
    <version>1.5</version>
  </dependency>
  <dependency>
    <groupId>com.typesafe.akka</groupId>
    <artifactId>akka-actor</artifactId> <!-- for Akka 2.0.X -->
    <artifactId>akka-actor_SCALA-VERSION</artifactId> <!-- from Akka 2.1.X on -->
    <version>AKKA-VERSION</version>
  </dependency>
</dependencies>
```

Please note that the Akka dependency is now in the "provided" scope which means that you'll have to define it explicitly in your
maven/sbt projects. 

The latest snapshot (development) version is 1.6-SNAPSHOT, the latest released version is 1.5

## Compatibily table

|  amqp-client |    Scala   |  Akka  |
| -------------| ---------- | ------ |
| 1.0          | 2.9.2      | 2.0.3  |
| 1.1          | 2.9.2      | 2.0.5  |
| 1.1          | 2.10.0     | 2.1.0  |
| 1.2          | 2.10       | 2.1    |
| 1.3          | 2.10       | 2.2    |
| 1.4          | 2.10, 2.11 | 2.3.2  |
| 1.5          | 2.10, 2.11 | 2.3.11 |
| 1.6-SNAPSHOT | 2.10, 2.11 | 2.4.3  |
 

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

ConnectionOwner and ChannelOwner are implemened as Akka actors:
* channel owners are created by connection owners
* when a connection is lost, the connection owner will create a new connection and provide each of its children with a
new channel
* connection owners and channel owners are implemented as Finite State Machines, with 2 possible states: Connected and Disconnected
* For a connection owner, "connected" means that it owns a valid connection to the AMQP broker
* For a channel owner, "connected" means that it owns a valid AMQP channel

YMMV, but using few connections (one per JVM) and many channels per connection (one per thread) is a common practice.

### Wrapping the RabbitMQ client

As explained above, this is an actor-based wrapper around the RabbitMQ client, with 2 main classes: ConnectionOwner and
ChannelOwner. Instead of calling the RabbitMQ [Channel](http://www.rabbitmq.com/releases/rabbitmq-java-client/v3.1.1/rabbitmq-java-client-javadoc-3.1.1/com/rabbitmq/client/Channel.html)
interface, you send a message to a ChannelOwner actor, which replies with whatever the java client returned wrapped in an Amqp.Ok()
message if the call was successful, or an Amqp.Error if it failed.

For example, to declare a queue you could write:

``` scala

  val connFactory = new ConnectionFactory()
  connFactory.setUri("amqp://guest:guest@localhost/%2F")
  val conn = system.actorOf(ConnectionOwner.props(connFactory, 1 second))
  val channel = ConnectionOwner.createChildActor(conn, ChannelOwner.props())

  channel ! DeclareQueue(QueueParameters("my_queue", passive = false, durable = false, exclusive = false, autodelete = true))

```

Or, if you want to check the number of messages in a queue:

``` scala

  val connFactory = new ConnectionFactory()
  connFactory.setUri("amqp://guest:guest@localhost/%2F")
  val conn = system.actorOf(ConnectionOwner.props(connFactory, 1 second))
  val channel = ConnectionOwner.createChildActor(conn, ChannelOwner.props())

  val Amqp.Ok(_, Some(result: Queue.DeclareOk)) = Await.result(
    (channel ? DeclareQueue(QueueParameters(name = "my_queue", passive = true))).mapTo[Amqp.Ok],
    5 seconds
  )
  println("there are %d messages in the queue named %s".format(result.getMessageCount, result.getQueue))

```

### Initialization and failure handling

If the connection to the broker is lost, ConnectionOwner actors will try and reconnect, and once they are connected
again they will send a new AMQP channel to each of their ChannelOwner children.

Likewise, if the channel owned by a ChannelOwner is shut down because of an error it will request a new one from its parent.

In this case you might want to "replay" some of the messages that were sent to the ChannelOwner actor before it lost
its channel, like queue declarations and bindings.

For this, you have 2 options:
* initialize the ChannelOwner with a list of requests
* wrap requests inside a Record message

Here, queues and bindings will be gone if the connection is lost and restored:

``` scala

  val connFactory = new ConnectionFactory()
  connFactory.setUri("amqp://guest:guest@localhost/%2F")
  val conn = system.actorOf(ConnectionOwner.props(connFactory, 1 second))

  // create an actor that will receive AMQP deliveries
  val listener = system.actorOf(Props(new Actor {
    def receive = {
      case Delivery(consumerTag, envelope, properties, body) => {
        println("got a message: " + new String(body))
        sender ! Ack(envelope.getDeliveryTag)
      }
    }
  }))

  // create a consumer that will route incoming AMQP messages to our listener
  // it starts with an empty list of queues to consume from
  val consumer = ConnectionOwner.createChildActor(conn, Consumer.props(listener, channelParams = None, autoack = false))

  // wait till everyone is actually connected to the broker
  Amqp.waitForConnection(system, consumer).await()

  // create a queue, bind it to a routing key and consume from it
  // here we don't wrap our requests inside a Record message, so they won't replayed when if the connection to
  // the broker is lost: queue and binding will be gone

  // create a queue
  val queueParams = QueueParameters("my_queue", passive = false, durable = false, exclusive = false, autodelete = true)
  consumer ! DeclareQueue(queueParams)
  // bind it
  consumer ! QueueBind(queue = "my_queue", exchange = "amq.direct", routing_key = "my_key")
  // tell our consumer to consume from it
  consumer ! AddQueue(QueueParameters(name = "my_queue", passive = false))

```

We can initialize our consumer with a list of messages that will be replayed each time its receives a new channel:

``` scala

 val consumer = ConnectionOwner.createChildActor(conn, Consumer.props(
    listener = Some(listener),
    init = List(AddBinding(Binding(StandardExchanges.amqDirect, queueParams, "my_key")))
  ), name = Some("consumer"))

```

Or can can wrap our initialization messages with Record to make sure they will be replayed each time its receives a new channel:

``` scala

  consumer ! Record(AddBinding(Binding(StandardExchanges.amqDirect, QueueParameters("my_queue", passive = false, durable = false, exclusive = false, autodelete = true), "my_key")))

```

If you have a reason to add a heartbeat (for instance, to keep your load balancer from dropping the connection), you can easily do so:

``` scala
  val connFactory = new ConnectionFactory()
  connFactory.setRequestedHeartbeat(5) // seconds
```

## RPC patterns

Typical RPC with AMQP follows this pattern:

1.  client sets up a private, exclusive response queue
2.  client sends message and set their 'replyTo' property to the name of this response queue
3.  server processes the message and replies to its 'replyTo' queue by publishing the response to the default exchange using the queue name as routing key (all queues are bound to their name
on the default exchange)

### Distributed Worker Pattern

This is one of the simplest but most useful pattern: using a shared queue to distributed work among consumers.
The broker will load-balance messages between these consumers using round-robin distribution, which can be combined with 'prefetch' channel settings.
Setting 'prefetch' to 1 is very useful if you need resource-based (CPU, ...) load-balancing.
You will typically use explicit acknowledgments and ack messages once they have been processed and the response has been sent. This
way, if your consumer fails to process the request or is disconnected, the broker will re-send the same request to another consumer.

``` scala
  // typical "work queue" pattern, where a job can be picked up by any running node
  implicit val system = ActorSystem("mySystem")

  // create an AMQP connection
  val connFactory = new ConnectionFactory()
  connFactory.setUri("amqp://guest:guest@localhost/%2F")
  val conn = system.actorOf(ConnectionOwner.props(connFactory, 1 second))

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
    ConnectionOwner.createChildActor(conn, RpcServer.props(queueParams, StandardExchanges.amqDirect,  "my_key", processor, ChannelParameters(qos = 1)))
  }

  val rpcClient = ConnectionOwner.createChildActor(conn, RpcClient.props())

  // wait till everyone is actually connected to the broker
  Amqp.waitForConnection(system, rpcServers: _*).await()
  Amqp.waitForConnection(system, rpcClient).await()

  implicit val timeout: Timeout = 2 seconds

  for (i <- 0 to 5) {
    val request = ("request " + i).getBytes
    val f = (rpcClient ? Request(List(Publish("amq.direct", "my_key", request)))).mapTo[RpcClient.Response]
    f.onComplete {
      case Success(response) => println(new String(response.deliveries.head.body))
      case Failure(error) => println(error)
    }
  }
  // wait 10 seconds and shut down
  Thread.sleep(10000)
  system.shutdown()

```

### One request/several responses

If your process is "sharded" and one request should result in several responses (one per shard for example) you can
use private exclusive queues which are all bound to the same key. In this case, each server will receive the same request and
will send back a response.

This is very useful if you want to break a single operation into multiple, parallel steps.

``` scala

  // one request/several responses pattern
  implicit val system = ActorSystem("mySystem")

  // create an AMQP connection
  val connFactory = new ConnectionFactory()
  connFactory.setUri("amqp://guest:guest@localhost/%2F")
  val conn = system.actorOf(ConnectionOwner.props(connFactory, 1 second))

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
    ConnectionOwner.createChildActor(conn, RpcServer.props(privateReplyQueue, StandardExchanges.amqDirect,  "my_key", processor, ChannelParameters(qos = 1)))
  }

  val rpcClient = ConnectionOwner.createChildActor(conn, RpcClient.props())

  // wait till everyone is actually connected to the broker
  Amqp.waitForConnection(system, rpcServers: _*).await()
  Amqp.waitForConnection(system, rpcClient).await()

  implicit val timeout: Timeout = 2 seconds

  for (i <- 0 to 5) {
    val request = ("request " + i).getBytes
    val f = (rpcClient ? Request(List(Publish("amq.direct", "my_key", request)), 3)).mapTo[RpcClient.Response]
    f.onComplete {
      case Success(response) => {
        response.deliveries.foreach(delivery => println(new String(delivery.body)))
      }
      case Failure(error) => println(error)
    }
  }
  // wait 10 seconds and shut down
  Thread.sleep(10000)
  system.shutdown()

```

### Workflow Pattern

This could be further extended with a simple 'workflow' pattern where each server publishes its results
to the shared queue used by the next step.
For example, if you want to chain steps A, B and C, set up a shared queue for each step, have 'A' processors
publish to queue 'B', 'B' processors publish to queue 'C' ....


## Samples

You can check either samples [src/main/scala/com/github/sstone/amqp/samples](https://github.com/sstone/amqp-client/tree/scala2.11/src/main/scala/com/github/sstone/amqp/samples) or spec tests [src/test/scala/com/github/sstone/amqp](https://github.com/sstone/amqp-client/tree/scala2.11/src/test/scala/com/github/sstone/amqp) for examples of how to use the library.





