package com.github.sstone.amqp.samples

import akka.actor.{ActorRef, Actor, Props, ActorSystem}
import com.github.sstone.amqp.Amqp.{AddStatusListener, QueueParameters, DeclareQueue}
import com.github.sstone.amqp.ConnectionOwner.Create
import com.github.sstone.amqp.{ConnectionOwner, Amqp, ChannelOwner}
import com.rabbitmq.client.AMQP.Queue
import com.rabbitmq.client.ConnectionFactory
import concurrent.duration._

class AdminActor extends Actor {
  val connFactory = new ConnectionFactory()
  val conn = context.actorOf(ConnectionOwner.props(connFactory, reconnectionDelay = 10 seconds))
  conn ! AddStatusListener(self)

  def receive = {
    case ConnectionOwner.Connected => conn ! Create(ChannelOwner.props(), name = Some("channel"))
    case channel: ActorRef => {
      channel ! AddStatusListener(self)
      context.become(pending(channel))
    }
  }

  def pending(channel: ActorRef) : Receive = {
    case ChannelOwner.Connected => {
      channel ! DeclareQueue(QueueParameters(name = "my_queue", passive = true))
      context.become(connected(channel))
    }
  }

  def connected(channel: ActorRef) : Receive = {
    case Amqp.Ok(request: DeclareQueue, Some(result: Queue.DeclareOk)) => {
      println(s"there are ${result.getMessageCount} in queue ${result.getQueue}")
      context.system.shutdown()
    }
  }
}

object Admin extends App {
  implicit val system = ActorSystem("mySystem")
  system.actorOf(Props[AdminActor])
}
