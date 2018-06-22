package com.github.sstone.amqp

import akka.actor.ActorDSL._
import akka.actor.{PoisonPill, ActorLogging, Props}
import akka.testkit.TestProbe
import com.github.sstone.amqp.Amqp._
import org.junit.runner.RunWith
import org.scalatest.WordSpecLike
import org.scalatest.junit.JUnitRunner
import scala.concurrent.duration._
import java.util.concurrent.TimeUnit

@RunWith(classOf[JUnitRunner])
class PendingAcksSpec extends ChannelSpec with WordSpecLike {
  "consumers" should {
    "receive messages that were delivered to another consumer that crashed before it acked them" in {
      val exchange = ExchangeParameters(name = "amq.direct", exchangeType = "", passive = true)
      val queue = randomQueue
      val routingKey = randomKey
      var counter = 0

      ignoreMsg {
        case Amqp.Ok(p:Publish, _) => true
      }

      val probe = TestProbe()

      // create a consumer that does not ack messages
      val badListener = system.actorOf(Props {
        new Act with ActorLogging {
          become {
            case Delivery(consumerTag, envelope, properties, body) => {
              log.info(s"received ${new String(body, "UTF-8")} tag = ${envelope.getDeliveryTag} redeliver = ${envelope.isRedeliver}")
              counter = counter + 1
              if (counter == 10) probe.ref ! 'done
            }
          }
        }
      })
      val consumer = ConnectionOwner.createChildActor(conn, Consumer.props(badListener, autoack = false, channelParams = None), name = Some("badConsumer"))
      val producer = ConnectionOwner.createChildActor(conn, ChannelOwner.props())
      Amqp.waitForConnection(system, consumer, producer).await(1, TimeUnit.SECONDS)

      consumer ! AddBinding(Binding(exchange, queue, routingKey))
      val Amqp.Ok(AddBinding(Binding(_, _, _)), _) = receiveOne(1 second)

      val message = "yo!".getBytes

      for (i <- 1 to 10) producer ! Publish(exchange.name, routingKey, message)

      probe.expectMsg(1 second, 'done)
      assert(counter == 10)

      // now we should see 10 pending acks in the broker

      // create a consumer that does ack messages
      var counter1 = 0
      val goodListener = system.actorOf(Props {
        new Act with ActorLogging {
          become {
            case Delivery(consumerTag, envelope, properties, body) => {
              log.info(s"received ${new String(body, "UTF-8")} tag = ${envelope.getDeliveryTag} redeliver = ${envelope.isRedeliver}")
              counter1 = counter1 + 1
              sender ! Ack(envelope.getDeliveryTag)
              if (counter1 == 10) probe.ref ! 'done
            }
          }
        }
      })
      val consumer1 = ConnectionOwner.createChildActor(conn, Consumer.props(goodListener, autoack = false, channelParams = None), name = Some("goodConsumer"))
      Amqp.waitForConnection(system, consumer1).await(1, TimeUnit.SECONDS)


      consumer1 ! AddBinding(Binding(exchange, queue, routingKey))
      val Amqp.Ok(AddBinding(Binding(_, _, _)), _) = receiveOne(1 second)

      // kill first consumer
      consumer ! PoisonPill

      probe.expectMsg(1 second, 'done)
      assert(counter1 == 10)
    }
  }
}
