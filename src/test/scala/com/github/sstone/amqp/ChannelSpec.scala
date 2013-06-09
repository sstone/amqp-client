package com.github.sstone.amqp

import akka.testkit.{ImplicitSender, TestKit}
import akka.actor.{ActorRef, Props, ActorSystem}
import akka.util.Timeout
import akka.pattern.{ask, gracefulStop}
import org.scalatest.{BeforeAndAfter, WordSpec}
import org.scalatest.matchers.ShouldMatchers
import java.util.concurrent.TimeUnit
import scala.concurrent.Await
import scala.concurrent.duration._
import com.rabbitmq.client.ConnectionFactory
import com.github.sstone.amqp.Amqp._

class ChannelSpec extends TestKit(ActorSystem("TestSystem")) with WordSpec with ShouldMatchers with BeforeAndAfter with ImplicitSender {
  implicit val timeout = Timeout(5 seconds)
  val connFactory = new ConnectionFactory()
  var conn: ActorRef = _
  var channelOwner: ActorRef = _

  before {
    println("before")
    conn = system.actorOf(Props(new ConnectionOwner(connFactory)))
    channelOwner = ConnectionOwner.createActor(conn, Props(new ChannelOwner()))
    waitForConnection(system, conn, channelOwner).await(5, TimeUnit.SECONDS)
  }

  after {
    println("after")
    Await.result(gracefulStop(conn, 5 seconds)(system), 6 seconds)
  }
}
