package com.aphelia.amqp

import akka.testkit.TestProbe
import akka.actor.Props
import akka.util.duration._
import com.aphelia.amqp.ConnectionOwner.CreateChannel
import com.rabbitmq.client.Channel
import com.aphelia.amqp.Amqp._

class ConnectionOwnerSpec extends BasicAmqpTestSpec {


  "ConnectionOwner" should {
    "provide a working userfriendly constructor" in {
      val conn = system.actorOf(Props(new ConnectionOwner(vhost = "/")), name = "connection")
      waitForConnection(system, conn).await()
      val p = TestProbe()
      p.send(conn, CreateChannel)
      p.expectMsgClass(2 second, classOf[Channel])
      system stop conn
    }
  }

  "ConnectionOwner" should {
    "provide channels for many child actors" in {
      val conn = system.actorOf(Props(new ConnectionOwner(connFactory)), name = "connection-owner")
      waitForConnection(system, conn).await()
      val actors = 1000
      for (i <- 0 until actors) {
        val p = TestProbe()
        p.send(conn, CreateChannel)
        p.expectMsgClass(2 second, classOf[Channel])
      }
      system stop conn
    }
  }
}
