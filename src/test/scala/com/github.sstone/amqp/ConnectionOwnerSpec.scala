package com.github.sstone.amqp

import org.scalatest.matchers.ShouldMatchers
import org.scalatest.WordSpec
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import akka.testkit.{ImplicitSender, TestKit, TestProbe}
import akka.actor.ActorSystem
import akka.pattern.gracefulStop
import akka.util.Timeout
import concurrent.duration._
import concurrent.Await
import com.rabbitmq.client.Channel
import ConnectionOwner.CreateChannel

@RunWith(classOf[JUnitRunner])
class ConnectionOwnerSpec extends TestKit(ActorSystem("TestSystem")) with WordSpec with ShouldMatchers with ImplicitSender {
  implicit val timeout = Timeout(5 seconds)
  implicit val sys = system

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
      Await.result(gracefulStop(conn.owner, 5 seconds), 6 seconds)
    }
  }
}
