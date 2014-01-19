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
  // Invoke a WS as a post
  def wsPostWithIterators(remoteUrl: String) = Action.async { implicit request =>
    // create a channel that is connected to an enumerator which is returned in the result
    val (resultEnumerator, channelForIteratee) = Concurrent.broadcast[Array[Byte]]
    channelForIteratee.push("Never an empty enumerator".getBytes)
    // define our consumer a.k.a. our iteratee -- It just pushes into the channel for the enumerator returned in the response
    val resultIteratee = Iteratee.foreach[Array[Byte]] { chunk =>  channelForIteratee.push(chunk)   }
    // WS doesn't end the iteratee so w/out the channelForIteratee.eofAndEnd the response never terminates
    val postResultFuture = WS.url(remoteUrl).postAndRetrieveStream(request.body.asJson.getOrElse(new JsObject(Seq()))) { 
      headers => resultIteratee 
    }.map {it =>  
        channelForIteratee.push(Input.EOF)
        Future{ it.run }
    }
    Await.result(postResultFuture, 2 seconds)
    Future { Ok.chunked(resultEnumerator).withHeaders(("Content-Type" -> "text/plain")) }
  }
  
  def wsPostWithIteratorsWithStream(remoteUrl: String) = Action { implicit request =>
    import java.io.{OutputStream, BufferedOutputStream, FileOutputStream, ByteArrayOutputStream}
	val outputStream: OutputStream = new ByteArrayOutputStream()
	outputStream.write("We always have a non-empty stream. ".getBytes());
	def fromStream(stream: OutputStream): Iteratee[Array[Byte], Unit] = Cont {
	  case e@Input.EOF =>
	    stream.flush(); stream.close()
	    Logger.info(s"iteratee is at EOF, outputStream contents = ${outputStream}")
	    Done((), e)
	  case Input.El(data) =>
	    Logger.info(s">>> data pushed into stream = ${new String(data)}")
	    stream.write(data)
	    fromStream(stream)
	  case Input.Empty =>
	    fromStream(stream)
	}
	val futureResponse = WS.url(remoteUrl).postAndRetrieveStream(request.body.asJson.getOrElse(new JsObject(Seq()))) {
	  headers => fromStream(outputStream)
	}.map {it8ee => Future {it8ee.run} }
	Await.result(futureResponse, 2 seconds)
	val out = outputStream.toString
	Logger.info(">>>> out = " + out)
	Ok(outputStream.toString())
  }

  // Called from my wsPostWithIterators action as a test. This is just a test fixture
  def postTestEndpoint = Action { implicit request =>
    // Thread.sleep(1000) // fake delay for latency simulation -- doesn't make any difference 
  	Ok("Here is a response @ " + new java.util.Date() + " and your body was: " + request.body)
  }

  // This is my test entry point... it calls the "system under test" aka the wsPostWithIterators which is
  // calling the "test fixture" postTestEndpoint
  def getPostEndpoint(remoteUrl: String) = Action.async { implicit request => 
    WS.url(remoteUrl).post("ghostBody").map { response =>
    	val contentType = response.header("Content-Type").getOrElse("text/plain")
        Ok(response.body).as(contentType)
    }     
  }   
}