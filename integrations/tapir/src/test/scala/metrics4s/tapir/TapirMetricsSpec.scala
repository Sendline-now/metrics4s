package metrics4s.tapir

import java.nio.charset.StandardCharsets

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec

import fs2.Stream
import cats.effect.IO
import cats.effect.testing.scalatest.AsyncIOSpec
import org.http4s.{Method, Request, Status, Uri}
import sttp.model.StatusCode
import sttp.tapir.*
import sttp.capabilities.fs2.Fs2Streams
import sttp.tapir.server.http4s.{Http4sServerInterpreter, Http4sServerOptions}

import metrics4s.Metrics
import metrics4s.http.*
import metrics4s.testkit.MetricsTestkit

import cats.syntax.traverse.given
import metrics4s.runtime.cats.CatsInstances.given

final class TapirMetricsSpec extends AsyncWordSpec, AsyncIOSpec, Matchers:
  private val getEvent    = endpoint.get.in("events" / path[Int]("id")).out(stringBody)
  private val createEvent = endpoint.post.in("events").out(statusCode(StatusCode.Created))
  private val failing     = endpoint.get.in("boom").out(stringBody)
  private val streaming   = endpoint.get.in("stream").out(streamTextBody(Fs2Streams[IO])(CodecFormat.TextPlain(), Some(StandardCharsets.UTF_8)))

  private val serverEndpoints = List(
    getEvent.serverLogicSuccess[IO](id => IO.pure(s"event $id")),
    createEvent.serverLogicSuccess[IO](_ => IO.unit),
    failing.serverLogicSuccess[IO](_ => IO.raiseError(IllegalStateException("boom"))),
  )

  "the tapir metrics" should {
    "take the route templates from the endpoints" in {
      List(getEvent, createEvent, failing).map(TapirMetrics.template) shouldBe List("GET /events/{id}", "POST /events", "GET /boom")
    }

    "count every request by the template of the endpoint that handled it, and the rest as unmatched" in {
      val testkit       = MetricsTestkit()
      given Metrics[IO] = testkit.metrics[IO]
      val defs          = HttpServerMetrics(List(getEvent, createEvent, failing).map(TapirMetrics.template))

      for
        metrics  <- TapirMetrics[IO](List(getEvent, createEvent, failing))
        options   = Http4sServerOptions.customiseInterceptors[IO].metricsInterceptor(metrics.interceptor).options
        app       = Http4sServerInterpreter[IO](options).toRoutes(serverEndpoints).orNotFound
        statuses <- List(
                      Request[IO](Method.GET, Uri.unsafeFromString("/events/1")),
                      Request[IO](Method.POST, Uri.unsafeFromString("/events")),
                      Request[IO](Method.GET, Uri.unsafeFromString("/boom")),
                      Request[IO](Method.GET, Uri.unsafeFromString("/missing")),
                    ).traverse(request => app.run(request).map(_.status))
      yield (
        statuses,
        testkit.value(defs.requests, (route = Route("GET /events/{id}"), status = StatusClass.Success)),
        testkit.value(defs.requests, (route = Route("POST /events"), status = StatusClass.Success)),
        testkit.value(defs.requests, (route = Route("GET /boom"), status = StatusClass.ServerError)),
        testkit.value(defs.requests, (route = Route.Unmatched, status = StatusClass.ClientError)),
        testkit.histogram(defs.duration, (route = Route("GET /events/{id}"))).map(_.count),
        testkit.value(defs.inFlight),
      ) shouldBe (
        List(Status.Ok, Status.Created, Status.InternalServerError, Status.NotFound),
        Some(1.0),
        Some(1.0),
        Some(1.0),
        Some(1.0),
        Some(1L),
        Some(0.0),
      )
    }

    "count a request once when its body fails after the headers went out" in {
      val testkit       = MetricsTestkit()
      given Metrics[IO] = testkit.metrics[IO]
      val defs          = HttpServerMetrics(List(TapirMetrics.template(streaming)))
      val cut           = Stream.emits("first".getBytes(StandardCharsets.UTF_8)).covary[IO] ++ Stream.raiseError[IO](IllegalStateException("cut"))

      for
        metrics  <- TapirMetrics[IO](List(streaming))
        options   = Http4sServerOptions.customiseInterceptors[IO].metricsInterceptor(metrics.interceptor).options
        app       = Http4sServerInterpreter[IO](options).toRoutes(streaming.serverLogicSuccess[IO](_ => IO.pure(cut))).orNotFound
        response <- app.run(Request[IO](Method.GET, Uri.unsafeFromString("/stream")))
        drained  <- response.body.compile.drain.attempt
      yield (
        response.status,
        drained.isLeft,
        testkit.value(defs.requests, (route = Route("GET /stream"), status = StatusClass.Success)),
        testkit.value(defs.requests, (route = Route("GET /stream"), status = StatusClass.ServerError)),
        testkit.histogram(defs.duration, (route = Route("GET /stream"))).map(_.count),
        testkit.value(defs.inFlight),
      ) shouldBe (Status.Ok, true, Some(1.0), Some(0.0), Some(1L), Some(0.0))
    }
  }
