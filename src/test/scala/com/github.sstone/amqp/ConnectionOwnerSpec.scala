package com.github.sstone.amqp

import akka.testkit.{ImplicitSender, TestKit, TestProbe}
import akka.actor.{ActorSystem, Props}
import concurrent.duration._
import ConnectionOwner.CreateChannel
import com.rabbitmq.client.Channel
import Amqp._
import org.specs2.mutable.Specification
import org.specs2.time.NoTimeConversions
import akka.util.Timeout
import java.util.concurrent.TimeUnit

class ConnectionOwnerSpec extends TestKit(ActorSystem("TestSystem")) with Specification with NoTimeConversions with ImplicitSender {
  implicit val timeout = Timeout(5 seconds)
  sequential

  "ConnectionOwner" should {
    "provide a working userfriendly constructor" in {
      val conn = new RabbitMQConnection(vhost = "/", name = "conn")
      conn.waitForConnection.await(5, TimeUnit.SECONDS)
      val p = TestProbe()
      p.send(conn.owner, CreateChannel)
      p.expectMsgClass(2.second, classOf[Channel])
      conn.stop
    }
  }

  "ConnectionOwner" should {
    "provide channels for many child actors" in {
      val conn = new RabbitMQConnection(vhost = "/", name = "conn")
      conn.waitForConnection.await()
      val actors = 1000
      for (i <- 0 until actors) {
        val p = TestProbe()
        p.send(conn.owner, CreateChannel)
        p.expectMsgClass(2.second, classOf[Channel])
      }
      conn.stop
    }
  }
}
