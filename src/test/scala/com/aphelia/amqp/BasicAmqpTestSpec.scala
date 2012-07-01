package com.aphelia.amqp

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.{BeforeAndAfter, WordSpec}
import org.scalatest.matchers.ShouldMatchers
import com.rabbitmq.client.ConnectionFactory
import com.aphelia.amqp.Amqp.{QueueParameters, ExchangeParameters}
import akka.testkit.TestKit
import akka.actor.ActorSystem

@RunWith(classOf[JUnitRunner])
class BasicAmqpTestSpec extends TestKit(ActorSystem("TestSystem")) with WordSpec with ShouldMatchers with BeforeAndAfter {
  val connFactory = new ConnectionFactory()

  /**
   * standard direct exchange
   */
  val amqDirect = ExchangeParameters(name = "amq.direct", passive = false, exchangeType = " direct ", durable = true)

  /**
   * "standard" reply queue: exclusive, the broker with generate a random name when it declares it
   */
  val privateReplyQueueParameters = QueueParameters(name = "", passive = false, exclusive = true, autodelete = true)

  initialize

  def initialize {
    checkConnection
    println("Connection test successful\n")
  }


  /**
   * check that we can actually connect to the broker
   */
  def checkConnection {
    try {
      val conn = connFactory.newConnection()
      conn.close()
    }
    catch {
      case e: Exception => info("cannot connect to local amqp broker, tests will not be run"); pending
    }
  }
}
