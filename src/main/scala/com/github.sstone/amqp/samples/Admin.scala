package com.github.sstone.amqp.samples

import concurrent.duration._
import concurrent.ExecutionContext.Implicits.global
import akka.actor.{Props, ActorSystem}
import akka.pattern.ask
import akka.util.Timeout
import com.github.sstone.amqp.{Amqp, ChannelOwner, RabbitMQConnection}
import com.github.sstone.amqp.Amqp.{QueueParameters, DeclareQueue}
import com.rabbitmq.client.AMQP.Queue
import scala.concurrent.Await


object Admin extends App {
  implicit val system = ActorSystem("mySystem")
  implicit val timeout: Timeout = 5 seconds
  // create an AMQP connection
  val conn = new RabbitMQConnection(host = "localhost", name = "Connection")

  val channel = conn.createChild(Props(new ChannelOwner()), Some("Channel"))

  // wait till everyone is actually connected to the broker
  Amqp.waitForConnection(system, channel).await()

  // check the number of messages in the queue
  val Amqp.Ok(_, Some(result: Queue.DeclareOk)) = Await.result((channel ? DeclareQueue(QueueParameters(name = "my_queue", passive = true))).mapTo[Amqp.Ok], 5 seconds)
  println("there are %d messages in the queue named %s".format(result.getMessageCount, result.getQueue))

  for {
    Amqp.Ok(_, Some(result: Queue.DeclareOk)) <- (channel ? DeclareQueue(QueueParameters(name = "my_queue", passive = true))).mapTo[Amqp.Ok]
  } yield {
    println("there are %d messages in the queue named %s".format(result.getMessageCount, result.getQueue))
    system.shutdown()
  }
}
