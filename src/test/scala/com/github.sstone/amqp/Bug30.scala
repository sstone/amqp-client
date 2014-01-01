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
  class Listener(conn: ActorRef, tellMeWhenYoureDone: ActorRef) extends Actor with ActorLogging {
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

    context.system.scheduler.schedule(10 milliseconds, 500 milliseconds, producer, Publish("amq.direct", "my_key", body = "test".getBytes("UTF-8")))

    var counter = 0

    def receive = {
      case Delivery(consumerTag, envelope, properties, body) => {
        val replyTo = sender
        log.info(s"receive deliveryTag ${envelope.getDeliveryTag} from $replyTo")
        // wait 500 milliseconds before acking tne message: this makes sure that there are pending acknowledgments when the
        // consumer crashes
        context.system.scheduler.scheduleOnce(500 milliseconds, replyTo, Ack(envelope.getDeliveryTag))
        counter = counter + 1
        if (counter == 10) self ! 'crash
        if (counter == 20) {
          // ok, we're done: the consumer's channel crashed, everything (channel, rabbitmq consumer) was re-created properly
          // and we received 10 additional messages
          tellMeWhenYoureDone ! 'done
          context.stop(self)
        }
      }

      case 'crash => {
        // ask the consumer to "passively declare" an exchange (i.e check that the exchange exists) that does not exist
        // this will crash the channel owned by the consumer and force it to create a new one
        consumer ! Amqp.DeclareExchange(ExchangeParameters(name = "I don't exist", passive = false, exchangeType = "foo"))
      }
    }
  }
}

/**
 * see issue #30: make sure that consumers are recreated properly and that pending acks are handled properly
 * "pending acks" means Acks that are send that after the original consumer's channel crashed but refer to delivery
 * tags created by the channel that crashed.
 */
@RunWith(classOf[JUnitRunner])
class Bug30 extends ChannelSpec {
  "ChannelOwner" should {
    "redefine consumers when a channel fails" in {
      val probe = TestProbe()
      val listener = system.actorOf(Props(new Bug30.Listener(conn, probe.ref)), "listener")
      probe.expectMsg(15 seconds, 'done)
    }
  }
}
