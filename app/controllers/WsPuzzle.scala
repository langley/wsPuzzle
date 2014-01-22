package controllers

import scala.util.Either
import play.api._
import play.api.mvc._
import play.api.libs.json._
import play.api.libs.iteratee._
import scala.concurrent.duration._
import scala.util.{Success, Failure}

import play.api.Logger
import play.api.libs.ws.WS
import scala.concurrent.{Future, Promise, Await}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.language.postfixOps

object WsPuzzle extends Controller  {
  // Invoke a WS as a post -- The below show's a "conceptual bug" in understanding the Iteratee/Enumerator/Channel
  //
  // I thought that you would see should see 
  // "New before here is a response @ Tue Jan 21 20:58:06 EST 2014 and your body was: AnyContentAsJson({}) after "
  // BUT, most often you get a 0 byte response, or a "before" response
  // Rarely you'll get 
  // "before here is a response @ Tue Jan 21 20:58:06 EST 2014 and your body was: AnyContentAsJson({}) after "
  // Any way you cut it, this is non-deterministic. My guess is that it's race condition. 
  // 
  // However the problem as James Roper explained to me in an email is that.. 
  //  The broadcast enumerator fans out messages pushed into its channel to whatever iteratees are connected 
  //  to it at the time - if there happen to be no iteratees connected to it, the message is lost, 
  //  it does not do any buffering.  So, if you create the enumerator, THEN push to the channel, THEN attach 
  //  an iteratee to it, the iteratee won't get the message 
  // (though, everything is quite asynchronous, so it is possible that the attaching the iteratee will overtake 
  // the message pushed to the channel).  This explains the race condition.
  // 
  def wsPostWithIteratorsBroken(remoteUrl: String) = Action { implicit request =>
    // create a channel that is connected to an enumerator which is returned in the result
    val (resultEnumerator, channelForIteratee) = Concurrent.broadcast[Array[Byte]]
    channelForIteratee.push("New ".getBytes)
    // define our consumer a.k.a. our iteratee -- It just pushes into the channel for the enumerator returned in the response
    val resultIteratee = Iteratee.foreach[Array[Byte]] { chunk =>
      channelForIteratee.push("before ".getBytes)
      channelForIteratee.push(chunk)   
      channelForIteratee.push(" after ".getBytes)
    }
    // WS doesn't end the iteratee so w/out the channelForIteratee.eofAndEnd the response never terminates
    val postResultFuture = WS.url(remoteUrl).postAndRetrieveStream(request.body.asJson.getOrElse(new JsObject(Seq()))){ 
      headers => resultIteratee 
    }.map {it => channelForIteratee.eofAndEnd; it.run}

    Ok.chunked(resultEnumerator).withHeaders(("Content-Type" -> "text/plain"))
  }

  // Invoke a WS as a post -- Creates a response that honors back pressure from the WS call
  // without ever having to read all the response into memory, block or be bound to a thread!
  // 
  // This is a fixed version of the above that honors back pressure of the WS as well 
  // by creating a joined enumerator and iteratee. The code then creates a new iteratee 
  // linked through to the first using Enumeratee.map which essentially allows us to define
  // a call back. Note all the "New, before, after" decoration is just so you can see
  // what's going on and it illustrates the flaws with original design being fixed. 
  def wsPostWithIterators(remoteUrl: String) = Action { implicit request =>
  	val (iteratee, enumerator) = Concurrent.joined[Array[Byte]]
  	val resultIteratee: Iteratee[Array[Byte], Unit] =  Enumeratee.map { chunk: Array[Byte] =>
  	  "before ".getBytes ++ chunk ++ " after".getBytes
  	} &>> iteratee // Note &>> is an alias for transform 
  	val postResultFuture = WS.url(remoteUrl).postAndRetrieveStream(request.body.asJson.getOrElse(Json.obj())){
  	  headers => resultIteratee}.map{it => it.run}
  	Ok.chunked(Enumerator("New ".getBytes) >>> enumerator) // Note: >>> is an alias for andThen 
  	.withHeaders(("Content-Type" -> "text/plain"))
  }
  
  
  // Called from my wsPostWithIterators action as a test. This is just a test fixture
  def postTestEndpoint = Action { implicit request =>
    // Thread.sleep(1000) // fake delay for latency simulation -- doesn't make any difference 
  	Ok("here is a response @ " + new java.util.Date() + " and your body was: " + request.body)
  }

  // This is my test entry point... it calls the "system under test" aka the wsPostWithIterators which is
  // calling the "test fixture" postTestEndpoint
  def getPostEndpoint = Action.async { implicit request => 
    WS.url("http://localhost:9000/wsPostWithIterators").post("ghostBody").map { response =>
    	val contentType = response.header("Content-Type").getOrElse("text/plain")
        Ok(response.body).as(contentType)
    }     
  }   
}