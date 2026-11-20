package metrics4s.sttpclient

import scala.util.Try
import scala.compiletime.testing.typeCheckErrors

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import sttp.client4.*
import sttp.client4.testing.SyncBackendStub
import sttp.model.StatusCode

import metrics4s.{Eval, Metrics}
import metrics4s.http.*
import metrics4s.testkit.MetricsTestkit

import metrics4s.Eval.given

final class SttpMetricsSpec extends AnyWordSpec, Matchers:
  "the sttp client metrics" should {
    "count requests by operation and status class, and the ones that never got a response as failed" in {
      val testkit         = MetricsTestkit()
      given Metrics[Eval] = testkit.metrics[Eval]
      val defs            = HttpClientMetrics(List("get-user"))

      val stub = SyncBackendStub
        .whenRequestMatches(_.uri.path == List("users", "1"))
        .thenRespondAdjust("user")
        .whenRequestMatches(_.uri.path == List("users", "2"))
        .thenRespondAdjust("down", StatusCode.ServiceUnavailable)
        .whenRequestMatches(_.uri.path == List("users", "3"))
        .thenThrow(IllegalStateException("connection refused"))
        .whenAnyRequest
        .thenRespondNotFound()

      val backend =
        SttpMetrics.wrap(stub, Eval.run(SttpMetrics.register[Eval](List("get-user"))))(request => Option.when(request.uri.path.headOption.contains("users"))("get-user"))

      val _       = basicRequest.get(uri"http://users/users/1").send(backend)
      val _       = basicRequest.get(uri"http://users/users/2").send(backend)
      val refused = Try(basicRequest.get(uri"http://users/users/3").send(backend))
      val _       = basicRequest.get(uri"http://users/nowhere").send(backend)

      (
        testkit.value(defs.requests, (operation = Operation("get-user"), status = ClientStatus.Success)),
        testkit.value(defs.requests, (operation = Operation("get-user"), status = ClientStatus.ServerError)),
        testkit.value(defs.requests, (operation = Operation("get-user"), status = ClientStatus.Failed)),
        testkit.value(defs.requests, (operation = Operation.Other, status = ClientStatus.ClientError)),
        refused.isFailure,
        testkit.histogram(defs.duration, (operation = Operation("get-user"))).map(_.count),
        testkit.value(defs.inFlight),
      ) shouldBe (Some(1.0), Some(1.0), Some(1.0), Some(1.0), true, Some(3L), Some(0.0))
    }

    "leave nothing in flight and count nothing of a call that is interrupted, which is how sttp reports a cancellation" in {
      val testkit         = MetricsTestkit()
      given Metrics[Eval] = testkit.metrics[Eval]
      val defs            = HttpClientMetrics(List("get-user"))
      val stub            = SyncBackendStub.whenAnyRequest.thenThrow(InterruptedException("canceled"))
      val backend         = SttpMetrics.wrap(stub, Eval.run(SttpMetrics.register[Eval](List("get-user"))))(_ => Some("get-user"))

      val interrupted =
        try
          val _ = basicRequest.get(uri"http://users/users/1").send(backend)
          false
        catch case _: InterruptedException => true

      (
        interrupted,
        testkit.value(defs.inFlight),
        ClientStatus.values.toList.flatMap(status => testkit.value(defs.requests, (operation = Operation("get-user"), status = status))).sum,
        testkit.histogram(defs.duration, (operation = Operation("get-user"))).map(_.count),
      ) shouldBe (true, Some(0.0), 0.0, Some(0L))
    }

    "wrap a backend of any kind and give back the same kind" in {
      val recorder = Eval.run(SttpMetrics.register[Eval](Nil)(using MetricsTestkit().metrics[Eval]))

      (
        typeCheckErrors("""val wrapped: StreamBackend[Try, Any] = SttpMetrics.wrap(null.asInstanceOf[StreamBackend[Try, Any]], recorder)(_ => None)"""),
        typeCheckErrors("""val wrapped: WebSocketStreamBackend[Try, Any] = SttpMetrics.wrap(null.asInstanceOf[WebSocketStreamBackend[Try, Any]], recorder)(_ => None)"""),
        typeCheckErrors("""val wrapped: WebSocketBackend[Try] = SttpMetrics.wrap(null.asInstanceOf[WebSocketBackend[Try]], recorder)(_ => None)"""),
        typeCheckErrors("""val wrapped: WebSocketSyncBackend = SttpMetrics.wrap(null.asInstanceOf[WebSocketSyncBackend], recorder)(_ => None)"""),
        typeCheckErrors("""val wrapped: Backend[Try] = SttpMetrics.wrap(null.asInstanceOf[Backend[Try]], recorder)(_ => None)"""),
        typeCheckErrors("""val wrapped: StreamBackend[Try, Any] = SttpMetrics.wrap(null.asInstanceOf[Backend[Try]], recorder)(_ => None)""").nonEmpty,
      ) shouldBe (Nil, Nil, Nil, Nil, Nil, true)
    }
  }
