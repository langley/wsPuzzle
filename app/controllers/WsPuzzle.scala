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
  // Invoke a WS as a post -- I believe this shows a bug. 
  // I believe you should see 
  // "New before here is a response @ Tue Jan 21 20:58:06 EST 2014 and your body was: AnyContentAsJson({}) after "
  // BUT, most often you get a 0 byte response, or a "before" response
  // Rarely you'll get 
  // "before here is a response @ Tue Jan 21 20:58:06 EST 2014 and your body was: AnyContentAsJson({}) after "
  // Any way you cut it, this is non-deterministic. My guess is that it's race condition. 
  // (Perhaps it arises due to my misunderstanding the APIs in use; either way, there's a race somewhere) 
  def wsPostWithIterators(remoteUrl: String) = Action { implicit request =>
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
    }.map {it => channelForIteratee.eofAndEnd }

    Ok.chunked(resultEnumerator).withHeaders(("Content-Type" -> "text/plain"))
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