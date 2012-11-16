package com.aphelia.amqp.samples

import akka.actor.ActorSystem
import com.aphelia.amqp.{Amqp, RabbitMQConnection}
import com.aphelia.amqp.Amqp.Publish

object Producer extends App {
  implicit val system = ActorSystem("mySystem")

  // create an AMQP connection
  val conn = new RabbitMQConnection(host = "localhost", name = "Connection")

  // create a "channel owner" on this connection
  val producer = conn.createChannelOwner()

  // wait till everyone is actually connected to the broker
  Amqp.waitForConnection(system, producer).await()

  // send a message
  producer ! Publish("amq.direct", "my_key", "yo!!".getBytes, properties = None, mandatory = true, immediate = false)

  // give it some time before shutting everything down
  Thread.sleep(500)
  system.shutdown()
}
