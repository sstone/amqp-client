package com.github.sstone.amqp

import akka.testkit.{ImplicitSender, TestKit}
import akka.actor.{ActorRef, Props, ActorSystem}
import akka.util.Timeout
import akka.pattern.{ask, gracefulStop}
import org.scalatest.{BeforeAndAfter, WordSpecLike}
import org.scalatest.matchers.ShouldMatchers
import java.util.concurrent.TimeUnit
import scala.concurrent.Await
import scala.concurrent.duration._
import com.rabbitmq.client.ConnectionFactory
import com.github.sstone.amqp.Amqp._
import scala.util.Random

trait ChannelSpecNoTestKit extends WordSpecLike with ShouldMatchers with BeforeAndAfter {
  implicit val system: ActorSystem

  implicit val timeout = Timeout(5 seconds)
  val connFactory = new ConnectionFactory()
  var conn: ActorRef = _
  var channelOwner: ActorRef = _
  val random = new Random()

  def randomQueueName = "queue" + random.nextInt()

  def randomExchangeName = "exchange" + random.nextInt()

  def randomQueue = QueueParameters(name = randomQueueName, passive = false, exclusive = false)

  def randomKey = "key" + random.nextInt()

  before {
    println("before")
    val uri = system.settings.config.getString("amqp-client-test.rabbitmq.uri")
    connFactory.setUri(uri)
    conn = system.actorOf(ConnectionOwner.props(connFactory, 1 second))
    channelOwner = ConnectionOwner.createChildActor(conn, ChannelOwner.props())
    waitForConnection(system, conn, channelOwner).await(5, TimeUnit.SECONDS)
  }

  after {
    println("after")
    Await.result(gracefulStop(conn, 5 seconds), 6 seconds)
  }
}

class ChannelSpec extends TestKit(ActorSystem("TestSystem")) with ChannelSpecNoTestKit with ImplicitSender
