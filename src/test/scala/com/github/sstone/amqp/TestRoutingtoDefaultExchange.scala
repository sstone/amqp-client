package com.github.sstone.amqp

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import org.junit.runner.RunWith
import org.scalatest.BeforeAndAfter
import org.scalatest.WordSpec
import org.scalatest.junit.JUnitRunner
import org.scalatest.matchers.ShouldMatchers
import com.github.sstone.amqp.Amqp._
import com.github.sstone.amqp.Amqp.Delivery
import com.github.sstone.amqp.Amqp.QueueParameters
import com.github.sstone.amqp.RpcServer._
import com.github.sstone.amqp.RpcServer.ProcessResult
import akka.actor.ActorSystem
import akka.pattern.ask
import akka.testkit.ImplicitSender
import akka.testkit.TestKit
import akka.util.Timeout
import com.github.sstone.amqp.RpcClient.Request
import com.github.sstone.amqp.RpcClient.Response
import scala.concurrent.Await

@RunWith(classOf[JUnitRunner])
class TestRoutingtoDefaultExchange extends TestKit(ActorSystem("TestSystem")) with WordSpec with ShouldMatchers with BeforeAndAfter with ImplicitSender{
  "RPC Servers" should {
    "reply to clients" in {
      
        val conn = new RabbitMQConnection(host = "localhost", name = "Connection")

        val queueParams = QueueParameters("test_queue", passive = false, durable = false, exclusive = false, autodelete = true)
        
          // create 2 equivalent servers
		  val rpcServers = for (i <- 1 to 2) yield {
		    // create a "processor"
		    // in real life you would use a serialization framework (json, protobuf, ....), define command messages, etc...
		    // check the Akka AMQP proxies project for examples
		    val processor = new IProcessor {
		      def process(delivery: Delivery) = {
		        // assume that the message body is a string
		        val response = "response to " + new String(delivery.body)
		        Future(ProcessResult(Some(response.getBytes)))
		      }
		      def onFailure(delivery: Delivery, e: Throwable) = ProcessResult(None) // we don't return anything
		    }
		    conn.createRpcServer(StandardExchanges.amqDefault, queueParams, "test_queue", processor, Some(ChannelParameters(qos = 1)))
		  }
        
        val rpcClient = conn.createRpcClient()
        // wait till everyone is actually connected to the broker
		  Amqp.waitForConnection(system, rpcServers: _*).await()
		  Amqp.waitForConnection(system, rpcClient).await()
		
		  implicit val timeout: Timeout = 2 seconds
		   
		 // for (i <- 0 to 5) {
	            try {
				    val request = ("request " + 0).getBytes
				    val f = (rpcClient ? Request(List(Publish("", "test_queue", request)))).mapTo[Response]
				    val result = Await.result(f, 1000.millis).asInstanceOf[Response]
		            println("result2 " + new String(result.deliveries.head.body))
		            Thread.sleep(300)
	            }
		  		catch {
		            case e: Exception => println("Exception : " +e.toString)
		        }
		  //}
		  // wait 10 seconds and shut down
		  // run the Producer sample now and see what happens
		  Thread.sleep(10000)
		  system.shutdown()
        
     }
  }
}
