package com.github.sstone.amqp.samples

import akka.actor.ActorSystem
import com.github.sstone.amqp.{ChannelOwner, ConnectionOwner, Amqp}
import com.github.sstone.amqp.Amqp._
import com.rabbitmq.client.ConnectionFactory
import com.github.sstone.amqp.Amqp.Publish
import java.util.concurrent.TimeUnit
import scala.concurrent.duration._

object Producer extends App {
  implicit val system = ActorSystem("mySystem")

  // create an AMQP connection
  val connFactory = new ConnectionFactory()
  connFactory.setUri("amqp://guest:guest@localhost/%2F")
  val conn = system.actorOf(ConnectionOwner.props(connFactory, 1 second))
  val producer = ConnectionOwner.createChildActor(conn, ChannelOwner.props())

  // wait till everyone is actually connected to the broker
  waitForConnection(system, conn, producer).await(5, TimeUnit.SECONDS)

  // send a message
  producer ! Publish("amq.direct", "my_key", "yo!!".getBytes, properties = None, mandatory = true, immediate = false)

  // give it some time before shutting everything down
  Thread.sleep(500)
  system.shutdown()
}
