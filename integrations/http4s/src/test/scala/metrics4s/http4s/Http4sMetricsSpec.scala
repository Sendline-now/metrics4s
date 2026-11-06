package metrics4s.http4s

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec

import cats.effect.{IO, Resource}
import cats.effect.testing.scalatest.AsyncIOSpec
import org.http4s.{HttpRoutes, Method, Request, Response, Status, Uri}
import org.http4s.client.Client
import org.http4s.dsl.io.*

import metrics4s.Metrics
import metrics4s.grafana.{Dashboard, Rows, ServiceFilter}
import metrics4s.http.*
import metrics4s.testkit.MetricsTestkit

import cats.syntax.traverse.given
import metrics4s.runtime.cats.CatsInstances.given

final class Http4sMetricsSpec extends AsyncWordSpec, AsyncIOSpec, Matchers:
  private val templates = List("GET /events/{id}", "POST /events")

  private val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case GET -> Root / "events" / "boom" => IO.raiseError(IllegalStateException("boom"))
    case GET -> Root / "events" / _      => Ok("event")
    case POST -> Root / "events"         => Created("created")
  }

  private def classify(request: Request[IO]): Option[String] =
    request.pathInfo.segments.toList.map(_.encoded) match
      case List("events", _) if request.method == Method.GET => Some("GET /events/{id}")
      case List("events") if request.method == Method.POST   => Some("POST /events")
      case _                                                 => None

  "the http4s server middleware" should {
    "count requests by route template and status class and time them, without leaving anything in flight" in {
      val testkit       = MetricsTestkit()
      given Metrics[IO] = testkit.metrics[IO]
      val defs          = HttpServerMetrics(templates)

      for
        middleware <- Http4sServerMetrics[IO](templates)(classify)
        app         = middleware(routes).orNotFound
        statuses   <- List(
                        Request[IO](Method.GET, Uri.unsafeFromString("/events/1")),
                        Request[IO](Method.GET, Uri.unsafeFromString("/events/2")),
                        Request[IO](Method.POST, Uri.unsafeFromString("/events")),
                        Request[IO](Method.GET, Uri.unsafeFromString("/missing")),
                      ).traverse(request => app.run(request).map(_.status))
        failed     <- app.run(Request[IO](Method.GET, Uri.unsafeFromString("/events/boom"))).attempt
      yield
        val event = Route("GET /events/{id}")
        (
          statuses,
          failed.isLeft,
          testkit.value(defs.requests, (route = event, status = StatusClass.Success)),
          testkit.value(defs.requests, (route = event, status = StatusClass.ServerError)),
          testkit.value(defs.requests, (route = Route("POST /events"), status = StatusClass.Success)),
          testkit.value(defs.requests, (route = Route.Unmatched, status = StatusClass.ClientError)),
          testkit.value(defs.requests, (route = Route.Unmatched, status = StatusClass.Redirection)),
          testkit.histogram(defs.duration, (route = event)).map(_.count),
          testkit.value(defs.inFlight),
          testkit.missingSeries(Dashboard("api", "Line / api", "line", ServiceFilter.regex("api"))(Rows.httpServer)),
        ) shouldBe (
          List(Status.Ok, Status.Ok, Status.Created, Status.NotFound),
          true,
          Some(2.0),
          Some(1.0),
          Some(1.0),
          Some(1.0),
          Some(0.0),
          Some(3L),
          Some(0.0),
          Set.empty,
        )
    }
  }

  "the http4s client middleware" should {
    "count requests by operation and status class, and the ones that never got a response as failed" in {
      val testkit       = MetricsTestkit()
      given Metrics[IO] = testkit.metrics[IO]
      val defs          = HttpClientMetrics(List("get-event"))

      val upstream = Client.fromHttpApp(routes.orNotFound)
      val broken   = Client[IO](_ => Resource.raiseError[IO, Response[IO], Throwable](IllegalStateException("connection refused")))

      def operation(request: Request[IO]): Option[String] = Option.when(request.pathInfo.renderString.startsWith("/events/"))("get-event")

      for
        recorder <- defs.register[IO]
        client    = Http4sClientMetrics.wrap(upstream, recorder)(operation)
        failing   = Http4sClientMetrics.wrap(broken, recorder)(operation)
        _        <- client.status(Request[IO](Method.GET, Uri.unsafeFromString("/events/1")))
        _        <- client.status(Request[IO](Method.GET, Uri.unsafeFromString("/nowhere")))
        refused  <- failing.status(Request[IO](Method.GET, Uri.unsafeFromString("/events/2"))).attempt
      yield (
        testkit.value(defs.requests, (operation = Operation("get-event"), status = StatusClass.Success)),
        testkit.value(defs.requests, (operation = Operation.Other, status = StatusClass.ClientError)),
        testkit.value(defs.requests, (operation = Operation("get-event"), status = StatusClass.Failed)),
        refused.isLeft,
        testkit.value(defs.inFlight),
        testkit.missingSeries(Dashboard("api", "Line / api", "line", ServiceFilter.regex("api"))(Rows.httpClient)),
      ) shouldBe (Some(1.0), Some(1.0), Some(1.0), true, Some(0.0), Set.empty)
    }
  }
