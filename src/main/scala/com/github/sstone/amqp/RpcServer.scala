package com.github.sstone.amqp

import Amqp._
import com.rabbitmq.client.AMQP.BasicProperties
import com.rabbitmq.client.{Envelope, Channel}
import concurrent.{ExecutionContext, Future}
import util.{Failure, Success}


object RpcServer {

  /**
   * represents the response to a RPimport com.github.sstone.amqp.ChannelOwner
import com.github.sstone.amqp.Amqp
import com.github.sstone.amqp.Consumer
C request
   * @param value optional response message body; if None, nothing will be sent back ("fire and forget" pattern)
   * @param properties optional response message properties
   */
  case class ProcessResult(value: Option[Array[Byte]], properties: Option[BasicProperties] = None)

  /**
   * generic processor trait
   */
  trait IProcessor {
    /**
     * process an incoming AMQP message
     * @param delivery AMQP message
     * @return a Future[ProcessResult] instance
     */
    def process(delivery: Delivery): Future[ProcessResult]

    /**
     * create a message that describes why processing a request failed. You would typically serialize the exception along with
     * some context information. 
     * @param delivery delivery which cause process() to throw an exception
     * @param e exception that was thrown in process()
     * @return a ProcessResult instance
     */
    def onFailure(delivery: Delivery, e: Throwable): ProcessResult
  }

}

/**
 * RPC Server, which
 * <ul>
 *   <Li>consume messages from a set of queues</li>
 *   <li>passes the message bodies to a "processor"</li>
 *   <li>sends back the result queue specified in the "replyTo" property</li>
 * </ul>
 * @param bindings list of (queue, exchange, key) bindings.
 * @param processor [[com.github.sstone.amqp.RpcServer.IProcessor]] implementation
 * @param channelParams optional channel parameters
 */
class RpcServer(bindings: List[Binding], processor: RpcServer.IProcessor, channelParams: Option[ChannelParameters] = None) extends Consumer(bindings, None, channelParams, autoack = false) {
  import ExecutionContext.Implicits.global

  def this(queue: QueueParameters, exchange: ExchangeParameters, routingKey: String, processor: RpcServer.IProcessor, channelParams: Option[ChannelParameters] = None)
  = this(List(Binding(exchange, queue, routingKey)), processor, channelParams)

  import RpcServer._

  private def sendResponse(result: ProcessResult, properties: BasicProperties, channel: Channel) {
    result match {
      // send a reply only if processor return something *and* replyTo is set
      case ProcessResult(Some(data), customProperties) if (properties.getReplyTo != null) => {
        // publish the response with the same correlation id as the request
        val props = customProperties.getOrElse(new BasicProperties()).builder().correlationId(properties.getCorrelationId).build()
        channel.basicPublish("", properties.getReplyTo, true, false, props, data)
      }
      case _ => {}
    }
  }

  when(ChannelOwner.Connected) {
    case Event(delivery@Delivery(consumerTag: String, envelope: Envelope, properties: BasicProperties, body: Array[Byte]), ChannelOwner.Connected(channel)) => {
      log.debug("processing delivery")
      processor.process(delivery).onComplete {
        case Success(result) => {
          sendResponse(result, properties, channel)
          channel.basicAck(envelope.getDeliveryTag, false)
        }
        case Failure(error) => {
          envelope.isRedeliver match {
            // first failure: reject and requeue the message
            case false => {
              log.error(error, "processing {} failed, rejecting message", delivery)
              channel.basicReject(envelope.getDeliveryTag, true)
            }
            // second failure: reply with an error message, reject (but don't requeue) the message
            case true => {
              log.error(error, "processing {} failed for the second time, acking message", delivery)
              val result = processor.onFailure(delivery, error)
              sendResponse(result, properties, channel)
              channel.basicReject(envelope.getDeliveryTag, false)
            }
          }
        }
      }
      stay
    }
  }
}

