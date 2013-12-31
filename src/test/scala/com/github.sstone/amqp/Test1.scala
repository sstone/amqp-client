package com.github.sstone.amqp

import akka.actor.{Props, Actor, ActorSystem}
import com.github.sstone.amqp.Amqp._
import com.rabbitmq.client.ConnectionFactory
import scala.concurrent.duration._

object Test1 extends App {
  implicit val system = ActorSystem("mySystem")

  // create an AMQP connection
  val conn = system.actorOf(ConnectionOwner.props(new ConnectionFactory(), reconnectionDelay = 5 seconds), "connection")

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

  // we initialize our consumer with an AddBinding request: the queue and the binding will be recreated if the connection
  // to the broker is lost and restored
  val consumer = ConnectionOwner.createChildActor(conn, Consumer.props(listener, StandardExchanges.amqDirect, queueParams, "my_key", channelParams = None, autoack = true))

  // wait till everyone is actually connected to the broker
  Amqp.waitForConnection(system, consumer).await()

  // run the Producer sample now and see what happens
  println("press enter...")
  System.in.read()
  system.shutdown()
}
