/*
 * Copyright (C) 2011 Mathias Doenitz
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

package cc.spray
package test

import cc.spray.RequestContext
import http._
import util.DynamicVariable
import utils.{NoLog, Logging}
import java.util.concurrent.{CountDownLatch, TimeUnit}

/**
 * Mix this trait into the class or trait containing your route and service tests.
 * Use the {{test}} and {{testService}} methods to test the behavior of your routes and services for different HTTP
 * request examples.
 */
trait SprayTest {

  private val defaultTimeout = 2000

  def test(request: HttpRequest)(route: Route): RoutingResultWrapper = {
    test(request, defaultTimeout)(route)
  }

  def test(request: HttpRequest, timeout: Long)(route: Route): RoutingResultWrapper = {
    var result: Option[RoutingResult] = None;
    //use a countdownlatch as a flag to block until the route actually completes or timeout is hit
    val latch = new CountDownLatch(1)
    route(RequestContext(request, {ctx => result = Some(ctx);latch.countDown()}, request.path))
    latch.await(timeout, TimeUnit.MILLISECONDS)
    new RoutingResultWrapper(result.getOrElse(doFail("No response received")))
  }

  class RoutingResultWrapper(rr: RoutingResult) {
    def handled: Boolean = rr.isInstanceOf[Respond]
    def response: HttpResponse = rr match {
      case Respond(response) => response
      case Reject(_) => doFail("Request was rejected")
    }
    def rawRejections: Set[Rejection] = rr match {
      case Respond(_) => doFail("Request was not rejected")
      case Reject(rejections) => rejections 
    }
    def rejections: Set[Rejection] = Rejections.applyCancellations(rawRejections)   
  }
  
  trait ServiceTest extends HttpServiceLogic with Logging {
    override lazy val log = NoLog // in the tests we don't log
    private[SprayTest] val responder = new DynamicVariable[RoutingResult => Unit]( _ =>
      throw new IllegalStateException("SprayTest.HttpService instances can only be used with the SprayTest.test(service, request) method")
    )
    protected[spray] def responderForRequest(request: HttpRequest) = responder.value
  }

  /**
   * The default HttpServiceLogic for testing.
   * If you have derived your own CustomHttpServiceLogic that you would like to test, create an implicit conversion
   * similar to this:
   * {{{
   * implicit def customWrapRootRoute(rootRoute: Route): ServiceTest = new CustomHttpServiceLogic with ServiceTest {
   *   val route = routeRoute
   * }
   * }}}
   */
  implicit def wrapRootRoute(rootRoute: Route): ServiceTest = new ServiceTest {
    val route = rootRoute
    val setDateHeader = false
  }

  def testService(request: HttpRequest)(service: ServiceTest): ServiceResultWrapper = {
    //use a default value of 2000 milliseconds
    testService(request, defaultTimeout)(service)
  }

  def testService(request: HttpRequest, timeout: Int)(service: ServiceTest): ServiceResultWrapper = {
    var response: Option[Option[HttpResponse]] = None
    val latch = new CountDownLatch(1)

    service.responder.withValue(rr => { response = Some(service.responseFromRoutingResult(rr));latch.countDown() }) {
      service.handle(request)
      //service.handle returns when either the request has been processed or detached
      //in the case of a detached request, we should wait some amount of timeout before we move on - the responder
      //will fire the countdownlatch and thus continue processing from this point.
      latch.await(timeout, TimeUnit.MILLISECONDS)
    }
    new ServiceResultWrapper(response.getOrElse(doFail("No response received")))
  }


  class ServiceResultWrapper(responseOption: Option[HttpResponse]) {
    def handled: Boolean = responseOption.isDefined
    def response: HttpResponse = responseOption.getOrElse(doFail("Request was not handled"))
  }

  def captureRequestContext(route: (Route => Route) => Unit): RequestContext = {
    var result: Option[RequestContext] = None;
    route { inner => { ctx => { result = Some(ctx); inner(ctx) }}}
    result.getOrElse(doFail("No RequestContext received"))
  }

  private def doFail(msg: String): Nothing = {
    try {
      this.asInstanceOf[{ def fail(msg: String): Nothing }].fail(msg)
    } catch {
      case e: NoSuchMethodException => {
        try {
          this.asInstanceOf[{ def failure(msg: String): Nothing }].failure(msg)
        } catch {
          case e: NoSuchMethodException =>
            throw new RuntimeException("Illegal mixin: the SprayTest trait can only be mixed into test classes that " +
              "supply a fail(String) or failure(String) method (e.g. ScalaTest, Specs or Specs2 specifications)")
        }
      }
    }
  }
}

object SprayTest extends SprayTest
