package com.github.sstone.amqp.samples

import scala.concurrent.{Future, ExecutionContext}
import akka.actor.ActorSystem
import com.github.sstone.amqp.RabbitMQConnection
import com.github.sstone.amqp.Amqp._
import com.github.sstone.amqp.RpcServer.IProcessor
import com.github.sstone.amqp.RpcServer.ProcessResult
import com.github.sstone.amqp.Amqp.ChannelParameters
import com.github.sstone.amqp.Amqp.QueueParameters
import com.github.sstone.amqp.Amqp.Delivery

/**
 * start with mvn exec:java -Dexec.mainClass=com.github.sstone.amqp.samples.BasicRpcServer -Dexec.classpathScope="compile"
 */
object BasicRpcServer extends App {
  import ExecutionContext.Implicits.global

  implicit val system = ActorSystem("mySystem")

  // create an AMQP connection
  val conn = new RabbitMQConnection(host = "localhost", name = "Connection")

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

  conn.createRpcServer(StandardExchanges.amqDirect, queueParams, "my_key", processor, Some(ChannelParameters(qos = 1)))
}
