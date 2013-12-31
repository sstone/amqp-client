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
import scala.util.Random

class ChannelSpec extends TestKit(ActorSystem("TestSystem")) with WordSpec with ShouldMatchers with BeforeAndAfter with ImplicitSender {
  implicit val timeout = Timeout(5 seconds)
  val connFactory = new ConnectionFactory()
  var conn: ActorRef = _
  var channelOwner: ActorRef = _
  val random = new Random()

  def randomQueueName = "queue" + random.nextInt()

  def randomQueue = QueueParameters(name = randomQueueName, passive = false, exclusive = false)

  def randomKey = "key" + random.nextInt()

  before {
    conn = system.actorOf(Props(new ConnectionOwner(connFactory)))
    channelOwner = ConnectionOwner.createChildActor(conn, ChannelOwner.props())
    waitForConnection(system, conn, channelOwner).await(5, TimeUnit.SECONDS)
  }

  after {
    Await.result(gracefulStop(conn, 5 seconds), 6 seconds)
  }
}
