package com.github.sstone.amqp.samples

import akka.actor.{Actor, Props, ActorSystem}
import com.github.sstone.amqp.{Amqp, Consumer, RabbitMQConnection}
import com.github.sstone.amqp.Amqp._

object Consumer3 extends App {
  implicit val system = ActorSystem("mySystem")

  // create an AMQP connection
  val conn = new RabbitMQConnection(host = "localhost", name = "Connection")

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

  val queueParams = QueueParameters("my_queue", passive = false, durable = false, exclusive = false, autodelete = true)

  val consumer = conn.createChild(Props(new Consumer(listener = Some(listener))))
  // wait till everyone is actually connected to the broker
  Amqp.waitForConnection(system, consumer).await()
  consumer ! Record(AddBinding(Binding(StandardExchanges.amqDirect, queueParams, "my_key")))

  // run the Producer sample now and see what happens
  println("press enter...")
  System.in.read()
  system.shutdown()
}
