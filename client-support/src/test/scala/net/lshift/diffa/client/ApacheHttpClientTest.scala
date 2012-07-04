package net.lshift.diffa.client

import org.eclipse.jetty.server.{Request, Server}
import org.eclipse.jetty.server.handler.AbstractHandler
import javax.servlet.http.{HttpServletResponse, HttpServletRequest}
import org.junit.{Test, Before}
import org.junit.Assert._
import org.hamcrest.Matchers._
import scala.collection.JavaConversions._
import java.net.{ConnectException, URI}
import org.apache.commons.codec.binary.Base64
import java.io.{IOException, InputStream, InputStreamReader, BufferedReader}
import org.apache.http.client.HttpResponseException

/**
 * Copyright (C) 2010-2012 LShift Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

class ApacheHttpClientTest {
  import ApacheHttpClientTest._

  val client: DiffaHttpClient = new ApacheHttpClient(0, 0)

  @Before def reset { ApacheHttpClientTest.reset }

  @Test
  def makesCorrectRequestToServer {
    val req = DiffaHttpQuery(baseUrl + "foo").withQuery(Map("name" -> List("param")))
    client.get(req)
    println("client req seen: %s".format(lastRequest))

    assertThat[Option[URI]](
        lastRequest.map(_.fullUri), is(Some(new URI("/foo?name=param")).asInstanceOf[Option[URI]]))
  }

  @Test
  def makesCorrectRequestToServerWithQueryParameters {
    val req = DiffaHttpQuery(baseUrl + "foo?from=baseUri").withQuery(Map("name" -> List("param")))
    client.get(req)
    val expected: Option[DiffaHttpQuery] = Some(DiffaHttpQuery("/foo").withQuery(Map("from" -> List("baseUri"), "name" -> List("param"))))

    assertThat(lastRequest, is(expected))
  }


  @Test
  def shouldIncludeBasicAuthWhenSpecified {
    val req = DiffaHttpQuery(baseUrl + "auth").withBasicAuth("user", "password")
    client.get(req)
    val expected: Option[(String, String)] = Some(("user", "password"))
    assertThat(lastRequest.flatMap(_.basicAuth), equalTo(expected))

  }


  def readLine: InputStream => String = { s =>
    new BufferedReader(new InputStreamReader(s)).readLine()
  }
  @Test
  def shouldReturnInputStreamOfBodyContentOnSuccess {

    val response = client.get(DiffaHttpQuery(baseUrl)).right.map (readLine)
    val expected: Either[Throwable, String] = Right(responseString)
    assertThat(response, equalTo(expected))
  }
  @Test
  def shouldReturnErrorOnConnectionError {
    // I'm hoping, at least.
    val queryForNonListeningServer = DiffaHttpQuery("http://127.0.0.1:%d/".format(0xffff))
    val response = client.get(queryForNonListeningServer)
    assertThat(response.left.get, instanceOf(classOf[ConnectException]))
  }

  @Test
  def shouldReturnErrorOn4xxStatus {
    val response = client.get(DiffaHttpQuery(baseUrl + "400"))
    println("400 Response: %s".format(response))
    assertThat(response.left.get, instanceOf(classOf[HttpResponseException] ) )
  }
}

object ApacheHttpClientTest {
  val port = 23452
  val responseString = "[] "
  private val server = new Server(port)
  server.setHandler(new AbstractHandler {
    override def handle(target: String, jettyReq: Request, request: HttpServletRequest, response: HttpServletResponse): Unit = {
      val queryParams = request.getParameterMap.map { case (key, v) =>
        key.asInstanceOf[String] -> v.asInstanceOf[Array[String]].toSeq
      }.toMap

      // println("last req: %s".format(request.getParameterMap))
      if (target == "/auth" && jettyReq.getAuthentication == null) {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED)
        response.setHeader("WWW-Authenticate", "basic realm=\"Fnord\"" );
      } else if (target == "/400") {
        response.setStatus(HttpServletResponse.SC_BAD_REQUEST)
      }

      response.getWriter.print(responseString)

      val authHeader = jettyReq.getHeader("Authorization") match {
        case h: String => Some(h);
        case null => None
      }
      val auth: Option[(String, String)] = for {
        h <- authHeader
        Array("Basic", enc:String) <- Some(h.split(' '))
        Array(u, p) <- Some(new String(Base64.decodeBase64(enc), "utf-8").split(':'))
      } yield (u, p)

      val query = (DiffaHttpQuery(request.getPathInfo).withQuery(queryParams) /: auth) { case (query, (u, p)) => query.withBasicAuth(u, p) }
      // also writable as: val query = DiffaHttpQuery(request.getPathInfo).withQuery(queryParams);
      // val queryWithAuth = auth.foldLeft(query) { case (query, (u, p)) => query.withBasicAuth(u, p) }

      lastRequest = Some(query)
      jettyReq.setHandled(true)
    }
  })
  val baseUrl = "http://127.0.0.1:%d/".format(port)

  def ensureStarted() = if(!server.isRunning()) server.start()

  var lastRequest: Option[DiffaHttpQuery] = None

  def reset { lastRequest = None; ensureStarted() }

}
