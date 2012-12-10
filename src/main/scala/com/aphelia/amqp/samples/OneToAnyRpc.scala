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
import akka.dispatch.Future

object OneToAnyRpc extends App {
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
}
