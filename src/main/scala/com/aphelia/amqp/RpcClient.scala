package com.aphelia.amqp

import akka.actor.ActorRef
import com.aphelia.amqp.Amqp._
import com.rabbitmq.client.{Envelope, Channel, DefaultConsumer}
import com.rabbitmq.client.AMQP.BasicProperties

object RpcClient {

  private[amqp] case class RpcResult(destination: ActorRef, expected: Int, deliveries: scala.collection.mutable.ListBuffer[Delivery])

  case class Request(publish: List[Publish], numberOfResponses: Int = 1)

  case class Response(deliveries: List[Delivery])

  case class Undelivered(msg: ReturnedMessage)

}

class RpcClient(channelParams: Option[ChannelParameters] = None) extends ChannelOwner(channelParams) {

  import RpcClient._

  var queue: String = ""
  var consumer: Option[DefaultConsumer] = None
  var counter: Int = 0
  var correlationMap = scala.collection.mutable.Map.empty[String, RpcResult]

  override def onChannel(channel: Channel) = {
    queue = declareQueue(channel, QueueParameters("", passive = false, exclusive = true)).getQueue
    consumer = Some(new DefaultConsumer(channel) {
      override def handleDelivery(consumerTag: String, envelope: Envelope, properties: BasicProperties, body: Array[Byte]) {
        self ! Delivery(consumerTag, envelope, properties, body)
      }
    })
    channel.basicConsume(queue, false, consumer.get)
    correlationMap.clear()
  }

  when(ChannelOwner.Connected) {
    case Event(Request(publish, numberOfResponses), ChannelOwner.Connected(channel)) => {
      counter = counter + 1
      publish.foreach(p => {
        val props = p.properties.getOrElse(new BasicProperties()).builder.correlationId(counter.toString).replyTo(queue).build()
        channel.basicPublish(p.exchange, p.key, p.mandatory, p.immediate, props, p.body)
      })
      correlationMap += (counter.toString -> RpcResult(sender, numberOfResponses, collection.mutable.ListBuffer.empty[Delivery]))
      stay
    }
    case Event(delivery@Delivery(consumerTag: String, envelope: Envelope, properties: BasicProperties, body: Array[Byte]), ChannelOwner.Connected(channel)) => {
      channel.basicAck(envelope.getDeliveryTag, false)
      if (correlationMap.contains(properties.getCorrelationId)) { // we only answer in case of a request/reply scenario
      val results = correlationMap.get(properties.getCorrelationId).get
        results.deliveries += delivery
        if (results.deliveries.length == results.expected) {
          results.destination ! Response(results.deliveries.toList)
          correlationMap -= properties.getCorrelationId
        }
      }
      stay
    }
    case Event(msg@ReturnedMessage(replyCode, replyText, exchange, routingKey, properties, body), ChannelOwner.Connected(channel)) => {
      if (correlationMap.contains(properties.getCorrelationId)) {
        val results: RpcResult = correlationMap.get(properties.getCorrelationId).get
        results.destination ! RpcClient.Undelivered(msg)
        correlationMap -= properties.getCorrelationId
      }
      else {
        log.warning("unexpected returned message with correlation id " + properties.getCorrelationId)
      }
      stay
    }
  }
}
