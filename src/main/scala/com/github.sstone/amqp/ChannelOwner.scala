package com.github.sstone.amqp

import collection.JavaConversions._
import com.rabbitmq.client.AMQP.BasicProperties
import com.rabbitmq.client._
import akka.actor.{Props, Actor, FSM}
import java.io.IOException
import com.github.sstone.amqp.ConnectionOwner.{CreateChannel, Shutdown}
import com.github.sstone.amqp.Amqp._
import scala.util.{Try, Failure, Success}

object ChannelOwner {

  sealed trait State

  case object Disconnected extends State

  case object Connected extends State

  def props(init: Seq[Request] = Seq.empty[Request], channelParams: Option[ChannelParameters] = None): Props = Props(new ChannelOwner(init, channelParams))

  private[amqp] sealed trait Data

  private[amqp] case object Uninitialized extends Data

  private[amqp] case class Connected(channel: com.rabbitmq.client.Channel) extends Data


  def withChannel[T](channel: Channel, request: Request)(f: Channel => T) = {
    Try(f(channel)) match {
      case Success(()) => {
        Ok(request)
      }
      case Success(result) => {
        Ok(request, Some(result))
      }
      case Failure(cause) => {
        Amqp.Error(request, cause)
      }
    }
  }
}


/**
 * Channel owners are created by connection owners and hold an AMQP channel which is used to do
 * basically everything: create queues and bindings, publish messages, consume messages...
 * @param channelParams
 */
class ChannelOwner(init: Seq[Request] = Seq.empty[Request], channelParams: Option[ChannelParameters] = None) extends Actor with FSM[ChannelOwner.State, ChannelOwner.Data] {

  import ChannelOwner._

  var requestLog: Vector[Request] = init.toVector

  startWith(Disconnected, Uninitialized)

  override def preStart() {
    context.parent ! CreateChannel
  }

  override def preRestart(reason: Throwable, message: Option[Any]) {
    log.warning("preRestart {} {}", reason, message)
    super.preRestart(reason, message)
  }

  override def postRestart(reason: Throwable) {
    log.warning("preRestart {} {}", reason)
    super.postRestart(reason)
  }

  /**
   * additional setup step that gets called each when this actor receives a channel
   * from its ConnectionOwner parent.
   * override this to implement custom steps
   * @param channel AMQP channel sent by the actor's parent
   */
  def onChannel(channel: Channel) {}

  def setup(channel: Channel) {
    channelParams.foreach(p => channel.basicQos(p.qos))
    channel.addReturnListener(new ReturnListener() {
      def handleReturn(replyCode: Int, replyText: String, exchange: String, routingKey: String, properties: BasicProperties, body: Array[Byte]) {
        log.warning("returned message code=%d text=%s exchange=%s routing_key=%s".format(replyCode, replyText, exchange, routingKey))
        self ! ReturnedMessage(replyCode, replyText, exchange, routingKey, properties, body)
      }
    })
    channel.addShutdownListener(new ShutdownListener {
      def shutdownCompleted(cause: ShutdownSignalException) {
        if (!cause.isInitiatedByApplication) {
          log.error(cause, "channel was shut down")
          self ! Shutdown(cause)
          context.parent ! CreateChannel
        }
      }
    })
    onChannel(channel)
  }

  when(Disconnected) {
    case Event(channel: Channel, _) => {
      setup(channel)
      goto(Connected) using Connected(channel)
    }
    case Event(Record(request), _) => {
      requestLog :+= request
      self forward request
      stay()
    }
  }

  when(Connected) {
    case Event(channel: Channel, _) => {
      // we already have a channel, close this one to prevent resource leaks
      log.warning("closing unexpected channel {}", channel)
      channel.close()
      stay()
    }
    /*
     * sent by the actor's parent when the AMQP connection is lost
     */
    case Event(Shutdown(cause), _) => goto(Disconnected)

    case Event(Record(request), _) => {
      requestLog :+= request
      self forward request
      stay()
    }

    case Event(request@Publish(exchange, routingKey, body, properties, mandatory, immediate), Connected(channel)) => {
      log.debug("publishing %s".format(request))
      val props = properties getOrElse new AMQP.BasicProperties.Builder().build()
      stay replying withChannel(channel, request)(c => c.basicPublish(exchange, routingKey, mandatory, immediate, props, body))
    }
    case Event(request@Transaction(publish), Connected(channel)) => {
      stay replying withChannel(channel, request) {
        c => {
          c.txSelect()
          publish.foreach(p => c.basicPublish(p.exchange, p.key, p.mandatory, p.immediate, p.properties getOrElse new AMQP.BasicProperties.Builder().build(), p.body))
          c.txCommit()
        }
      }
    }
    case Event(request@Ack(deliveryTag), Connected(channel)) => {
      log.debug("acking %d on %s".format(deliveryTag, channel))
      stay replying withChannel(channel, request)(c => c.basicAck(deliveryTag, false))
    }
    case Event(request@Reject(deliveryTag, requeue), Connected(channel)) => {
      log.debug("rejecting %d on %s".format(deliveryTag, channel))
      stay replying withChannel(channel, request)(c => c.basicReject(deliveryTag, requeue))
    }
    case Event(request@DeclareExchange(exchange), Connected(channel)) => {
      log.debug("declaring exchange {}", exchange)
      stay replying withChannel(channel, request)(c => declareExchange(c, exchange))
    }
    case Event(request@DeleteExchange(exchange, ifUnused), Connected(channel)) => {
      log.debug("deleting exchange {} ifUnused {}", exchange, ifUnused)
      stay replying withChannel(channel, request)(c => c.exchangeDelete(exchange, ifUnused))
    }
    case Event(request@DeclareQueue(queue), Connected(channel)) => {
      log.debug("declaring queue {}", queue)
      stay replying withChannel(channel, request)(c => declareQueue(c, queue))
    }
    case Event(request@PurgeQueue(queue), Connected(channel)) => {
      log.debug("purging queue {}", queue)
      stay replying withChannel(channel, request)(c => c.queuePurge(queue))
    }
    case Event(request@DeleteQueue(queue, ifUnused, ifEmpty), Connected(channel)) => {
      log.debug("deleting queue {} ifUnused {} ifEmpty {}", queue, ifUnused, ifEmpty)
      stay replying withChannel(channel, request)(c => c.queueDelete(queue, ifUnused, ifEmpty))
    }
    case Event(request@QueueBind(queue, exchange, routingKey, args), Connected(channel)) => {
      log.debug("binding queue {} to key {} on exchange {}", queue, routingKey, exchange)
      stay replying withChannel(channel, request)(c => c.queueBind(queue, exchange, routingKey, args))
    }
    case Event(request@QueueUnbind(queue, exchange, routingKey, args), Connected(channel)) => {
      log.debug("unbinding queue {} to key {} on exchange {}", queue, routingKey, exchange)
      stay replying withChannel(channel, request)(c => c.queueUnbind(queue, exchange, routingKey, args))
    }
  }

  whenUnhandled {
    case Event(ok@Ok(_, _), _) => {
      log.debug("ignoring successful reply to self: {}", ok)
      stay()
    }
  }

  onTransition {
    case Disconnected -> Connected => {
      log.info("connected")
      requestLog.foreach(r => self ! r)
    }
    case Connected -> Disconnected => {
      log.warning("disconnected")
    }
  }

  onTermination {
    case StopEvent(_, Connected, Connected(channel)) => {
      try {
        log.info("closing channel")
        channel.close()
      }
      catch {
        case e: Exception => log.warning(e.toString)
      }
    }
  }

  initialize
}
