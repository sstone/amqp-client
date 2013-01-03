package com.github.sstone.amqp.samples

import akka.actor.{Props, Actor, ActorSystem}
import com.github.sstone.amqp.{Amqp, RabbitMQConnection}
import com.github.sstone.amqp.Amqp.{QueueParameters, Ack, Delivery, Publish}

object Consumer extends App {
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
  val consumer = conn.createConsumer(Amqp.StandardExchanges.amqDirect, queueParams, "my_key", listener, None)

  // wait till everyone is actually connected to the broker
  Amqp.waitForConnection(system, consumer).await()

  // wait 10 seconds and shut down
  // run the Producer sample now and see what happens
  Thread.sleep(10000)
  system.shutdown()
}
