package com.github.sstone.amqp.samples

import akka.actor.{Actor, Props, ActorSystem}
import com.github.sstone.amqp.{ConnectionOwner, Amqp, Consumer}
import com.github.sstone.amqp.Amqp._
import com.rabbitmq.client.ConnectionFactory
import scala.concurrent.duration._

object Consumer3 extends App {
  implicit val system = ActorSystem("mySystem")

  // create an AMQP connection
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
  val queueParams = QueueParameters("my_queue", passive = false, durable = false, exclusive = false, autodelete = true)
  val consumer = ConnectionOwner.createChildActor(conn, Consumer.props(Some(listener)))

  // wait till everyone is actually connected to the broker
  Amqp.waitForConnection(system, consumer).await()

  // create a queue, bind it to a routing key and consume from it
  // here we wrap our requests inside a Record message, so will be replayed if the connection to
  // the broker is lost and restored
  consumer ! Record(AddBinding(Binding(StandardExchanges.amqDirect, queueParams, "my_key")))

  // run the Producer sample now and see what happens
  println("press enter...")
  System.in.read()
  system.shutdown()
}
