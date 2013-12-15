package com.github.sstone.amqp.samples

import scala.concurrent.{Future, ExecutionContext}
import akka.actor.{Props, Actor, ActorSystem}
import com.github.sstone.amqp.{Amqp, RabbitMQConnection}
import com.github.sstone.amqp.Amqp._
import com.github.sstone.amqp.RpcServer.{ProcessResult, IProcessor}

object PublisherConfirms extends App {
  import ExecutionContext.Implicits.global

  implicit val system = ActorSystem("mySystem")

  // create an AMQP connection
  val conn = new RabbitMQConnection(host = "localhost", name = "Connection")

  val producer = conn.createChannelOwner()
  Amqp.waitForConnection(system, producer).await()

  class Foo extends Actor {

    producer ! ConfirmSelect
    producer ! AddReturnListener(self)
    producer ! AddConfirmListener(self)
    producer ! DeclareQueue(QueueParameters(name = "my_queue", passive = false, durable = false, autodelete = true))

    def receive = {
      case 'publishing_fails => {
        producer ! Publish("", "no_such_queue", "yo!".getBytes)
        producer ! Publish("", "no_such_queue", "yo!".getBytes)
        producer ! WaitForConfirms(None)
      }
      case 'publishing_works => {
        producer ! Publish("", "my_queue", "yo!".getBytes)
        producer ! Publish("", "my_queue", "yo!".getBytes)
        producer ! WaitForConfirms(None)
      }
      case msg => println(msg)
    }
  }

  val foo = system.actorOf(Props[Foo])
  foo ! 'publishing_fails
  Thread.sleep(1000)
  foo ! 'publishing_works
}
