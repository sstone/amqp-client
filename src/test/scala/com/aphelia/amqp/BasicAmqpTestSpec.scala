package com.aphelia.amqp

import org.junit.runner._
import org.specs2.runner.JUnitRunner
import com.rabbitmq.client.ConnectionFactory
import com.aphelia.amqp.Amqp.{QueueParameters, ExchangeParameters}
import akka.testkit.TestKit
import akka.actor.ActorSystem
import org.specs2.mutable.Specification
import org.specs2.time.NoTimeConversions


@RunWith(classOf[JUnitRunner])
class BasicAmqpTestSpec extends TestKit(ActorSystem("TestSystem")) with Specification with NoTimeConversions {
  val connFactory = new ConnectionFactory()

  sequential

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
      case e: Exception => failure("cannot connect to local amqp broker, tests will not be run"); pending
    }
  }
}
