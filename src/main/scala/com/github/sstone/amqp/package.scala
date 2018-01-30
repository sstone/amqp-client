package com.github.sstone

import akka.actor.ActorRef
import com.github.sstone.amqp.Amqp.Request

package object amqp {
  type RequestAndSender = (Request, Option[ActorRef])
}
