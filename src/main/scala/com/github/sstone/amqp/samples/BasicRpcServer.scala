package com.github.sstone.amqp.samples

import akka.actor.ActorSystem
import com.github.sstone.amqp.{RpcServer, ConnectionOwner}
import com.github.sstone.amqp.Amqp._
import com.github.sstone.amqp.RpcServer.IProcessor
import com.github.sstone.amqp.RpcServer.ProcessResult
import com.rabbitmq.client.ConnectionFactory
import scala.concurrent.{Future, ExecutionContext}
import scala.concurrent.duration._
/**
 * start with mvn exec:java -Dexec.mainClass=com.github.sstone.amqp.samples.BasicRpcServer -Dexec.classpathScope="compile"
 */
object BasicRpcServer extends App {
  import ExecutionContext.Implicits.global

  implicit val system = ActorSystem("mySystem")

  // create an AMQP connection
  val connFactory = new ConnectionFactory()
  connFactory.setUri("amqp://guest:guest@localhost/%2F")
  val conn = system.actorOf(ConnectionOwner.props(connFactory, 1 second))

  val queueParams = QueueParameters("my_queue", passive = false, durable = false, exclusive = false, autodelete = true)

  // create a "processor"
  // in real life you would use a serialization framework (json, protobuf, ....), define command messages, etc...
  // check the Akka AMQP proxies project for examples
  val processor = new IProcessor {
    def process(delivery: Delivery) = Future {
      // assume that the message body is a string
      val input = new String(delivery.body)
      println("processing " + input)
      val output = "response to " + input
      ProcessResult(Some(output.getBytes("UTF-8")))
    }

    // likewise,  the same serialization framework would be used to return errors
    def onFailure(delivery: Delivery, e: Throwable) = ProcessResult(Some(("server error: " + e.getMessage).getBytes("UTF-8")))
  }

  ConnectionOwner.createChildActor(conn, RpcServer.props(queueParams, StandardExchanges.amqDirect,  "my_key", processor, ChannelParameters(qos = 1)))
}
