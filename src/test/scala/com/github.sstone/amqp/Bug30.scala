package com.github.sstone.amqp

import org.scalatest.junit.JUnitRunner
import org.junit.runner.RunWith
import akka.testkit.TestProbe
import com.github.sstone.amqp.Amqp._
import akka.actor.{Props, ActorLogging, ActorRef, Actor}
import com.github.sstone.amqp.ConnectionOwner.Create
import scala.concurrent.duration._
import com.github.sstone.amqp.Amqp.Ack
import com.github.sstone.amqp.Amqp.Publish
import com.github.sstone.amqp.Amqp.QueueParameters
import com.github.sstone.amqp.Amqp.Delivery

object Bug30 {
  class Listener(conn: ActorRef) extends Actor with ActorLogging {
    import concurrent.ExecutionContext.Implicits.global

    val consumer = ConnectionOwner.createChildActor(conn, Consumer.props(
      self,
      exchange = Amqp.StandardExchanges.amqDirect,
      queue = QueueParameters("my_queue", passive = false, durable = false, exclusive = false, autodelete = true),
      routingKey = "my_key",
      channelParams = None,
      autoack = false))

    val producer = ConnectionOwner.createChildActor(conn, ChannelOwner.props())
    Amqp.waitForConnection(context.system, consumer, producer)

    context.system.scheduler.schedule(100 milliseconds, 1 second, producer, Publish("amq.direct", "my_key", body = "test".getBytes("UTF-8")))

    var counter = 0

    def receive = {
      case Delivery(consumerTag, envelope, properties, body) => {
        val replyTo = sender
        log.info(s"receive deliveryTag ${envelope.getDeliveryTag} from $replyTo")
        context.system.scheduler.scheduleOnce(1 second, replyTo, Ack(envelope.getDeliveryTag))
        counter = counter + 1
        if (counter == 10) self ! 'crash
      }

      case 'crash => {
        producer ! Amqp.DeclareExchange(ExchangeParameters(name = "I don't exist", passive = false, exchangeType = "foo"))
      }
    }
  }
}

@RunWith(classOf[JUnitRunner])
class Bug30 extends ChannelSpec {
  "ChannelOwner" should {
    "redefine consumers when a channel fails" in {
      val listener = system.actorOf(Props(new Bug30.Listener(conn)), "listener")
      Thread.sleep(Long.MaxValue)
    }
  }
}
