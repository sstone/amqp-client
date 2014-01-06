package com.github.sstone.amqp

import akka.actor.ActorDSL._
import akka.actor.{PoisonPill, ActorLogging}
import akka.testkit.TestProbe
import com.github.sstone.amqp.Amqp._
import org.junit.runner.RunWith
import org.scalatest.WordSpec
import org.scalatest.junit.JUnitRunner
import scala.concurrent.duration._
import java.util.concurrent.TimeUnit

@RunWith(classOf[JUnitRunner])
class PendingAcks1Spec extends ChannelSpec with WordSpec {
  "consumers" should {
    "receive messages that were delivered to another consumer that crashed before if ack'ed them" in {
      val exchange = ExchangeParameters(name = "amq.direct", exchangeType = "", passive = true)
      val queue = QueueParameters(name = "remeber-to-delete-me", passive = false, autodelete = false)
      val routingKey = randomKey
      var counter = 0

      ignoreMsg {
        case Amqp.Ok(p:Publish, _) => true
      }

      val probe = TestProbe()

      // create a consumer that does not ack the first 10 messages
      val badListener = actor {
        new Act with ActorLogging {
          become {
            case Delivery(consumerTag, envelope, properties, body) => {
              log.info(s"received ${new String(body, "UTF-8")} tag = ${envelope.getDeliveryTag} redeliver = ${envelope.isRedeliver}")
              counter = counter + 1
              if (counter % 10 == 0) probe.ref ! 'done
              if (counter > 10) sender ! Ack(envelope.getDeliveryTag)
            }
          }
        }
      }
      val consumer = ConnectionOwner.createChildActor(conn, Consumer.props(badListener, autoack = false, channelParams = None), name = Some("badConsumer"))
      val producer = ConnectionOwner.createChildActor(conn, ChannelOwner.props())
      Amqp.waitForConnection(system, consumer, producer).await(1, TimeUnit.SECONDS)

      consumer ! Record(AddBinding(Binding(exchange, queue, routingKey)))
      val Amqp.Ok(AddBinding(Binding(_, _, _)), _) = receiveOne(1 second)

      val message = "yo!".getBytes

      for (i <- 1 to 10) producer ! Publish(exchange.name, routingKey, message)

      probe.expectMsg(1 second, 'done)
      assert(counter == 10)

      // crash the consumer's channem
      consumer ! DeclareExchange(ExchangeParameters(name = "foo", passive = true, exchangeType =""))

      probe.expectMsg(5 seconds, 'done)
      assert(counter == 20)
    }
  }
}
