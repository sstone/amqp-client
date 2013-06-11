package com.github.sstone.amqp

import org.scalatest.junit.JUnitRunner
import org.junit.runner.RunWith
import akka.testkit.TestProbe
import akka.actor.Props
import java.util.concurrent.TimeUnit
import concurrent.duration._
import com.rabbitmq.client.AMQP.Queue
import com.github.sstone.amqp.Amqp._

@RunWith(classOf[JUnitRunner])
class ChannelOwnerSpec extends ChannelSpec  {
  "ChannelOwner" should {
    "implement basic error handling" in {
      channelOwner ! DeclareQueue(QueueParameters("no_such_queue", passive = true))
      expectMsgClass(1 second, classOf[Amqp.Error])
    }

    "allow users to create, bind, purge and delete queues" in {
      val queue = "my_test_queue"

      // declare a queue, bind it to "my_test_key" on "amq.direct" and publish a message
      channelOwner ! DeclareQueue(QueueParameters(queue, passive = false, durable = false, autodelete = true))
      channelOwner ! QueueBind(queue, "amq.direct", "my_test_key")
      channelOwner ! Publish("amq.direct", "my_test_key", "yo!".getBytes)
      receiveN(3, 2 seconds)

      // check that there is 1 message in the queue
      channelOwner ! DeclareQueue(QueueParameters(queue, passive = true))
      val Amqp.Ok(_, Some(check1: Queue.DeclareOk)) = receiveOne(1 second)

      // purge the queue
      channelOwner ! PurgeQueue(queue)
      receiveOne(1 second)

      // check that there are no more messages in the queue
      channelOwner ! DeclareQueue(QueueParameters(queue, passive = true))
      val Amqp.Ok(_, Some(check2: Queue.DeclareOk)) = receiveOne(1 second)

      // delete the queue
      channelOwner ! DeleteQueue(queue)
      val Amqp.Ok(_, Some(check3: Queue.DeleteOk)) = receiveOne(1 second)

      assert(check1.getMessageCount === 1)
      assert(check2.getMessageCount === 0)
    }
  }

  "Multiple ChannelOwners" should {
    "each transition from Disconnected to Connected when they receive a channel" in {
      val concurrent = 10
      val actors = for (i <- 1 until concurrent) yield ConnectionOwner.createActor(conn, Props(new ChannelOwner()), name = Some(i + "-instance"))
      val latch = waitForConnection(system, actors: _*)
      latch.await(10000, TimeUnit.MILLISECONDS)
      latch.getCount should be(0)
    }
  }
}
