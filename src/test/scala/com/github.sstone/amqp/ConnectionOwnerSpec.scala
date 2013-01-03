package com.github.sstone.amqp

import akka.testkit.TestProbe
import akka.actor.Props
import concurrent.duration._
import ConnectionOwner.CreateChannel
import com.rabbitmq.client.Channel
import Amqp._

class ConnectionOwnerSpec extends BasicAmqpTestSpec {

  "ConnectionOwner" should {
    "provide a working userfriendly constructor" in {
      val conn = new RabbitMQConnection(vhost = "/", name = "conn")
      conn.waitForConnection.await()
      val p = TestProbe()
      p.send(conn.owner, CreateChannel)
      p.expectMsgClass(2.second, classOf[Channel])
      conn.stop
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
        p.expectMsgClass(2.second, classOf[Channel])
      }
      system stop conn
    }
  }
}
