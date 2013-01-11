package com.aphelia.amqp.samples

import akka.pattern.ask
import akka.actor.{Actor, Props, ActorSystem}
import com.aphelia.amqp.{RpcClient, Amqp, RabbitMQConnection}
import com.aphelia.amqp.Amqp._
import com.aphelia.amqp.RpcServer.{ProcessResult, IProcessor}
import com.aphelia.amqp.Amqp.QueueParameters
import com.aphelia.amqp.Amqp.Delivery
import com.aphelia.amqp.RpcClient.Request
import akka.util.duration._
import akka.util.Timeout

object OneToManyRpc extends App {
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
        ProcessResult(Some(response.getBytes))
      }
      def onFailure(delivery: Delivery, e: Exception) = ProcessResult(None) // we don't return anything
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
}
