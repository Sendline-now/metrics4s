package metrics4s.http

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import metrics4s.MetricDef
import metrics4s.grafana.*

final class HttpMetricsSpec extends AnyWordSpec, Matchers:
  private def shape(definition: MetricDef) =
    (definition.name.value, definition.help, definition.kind, definition.unit, definition.labelNames.toList, definition.preCreated)

  private def dashboard(sections: Section*): Dashboard = Dashboard("api", "Line / api", "line", ServiceFilter.regex("api"))(sections*)

  "the HTTP definitions" should {
    "describe the same series without the routes and operations as with them" in {
      (HttpServerMetrics.definitions.map(shape), HttpClientMetrics.definitions.map(shape)) shouldBe (
        HttpServerMetrics(List("GET /events/{id}")).definitions.map(shape),
        HttpClientMetrics(List("get-event")).definitions.map(shape),
      )
    }

    "serve every series the shared HTTP rows read" in {
      val board = dashboard(Row("HTTP server")(Rows.httpServer.panels*), Row("HTTP client")(Rows.httpClient.panels*))

      (board.reads.size, board.missingSeries(HttpServerMetrics.definitions ++ HttpClientMetrics.definitions)) shouldBe (6, Set.empty)
    }

    "draw a service's stats and panels without its routes, the errors picked by their status class" in {
      val board = dashboard(
        Stats(
          Stat.rate(HttpServerMetrics.requests).titled("Requests, /s"),
          Stat.quantile(HttpServerMetrics.duration, 0.95).titled("Request time, p95"),
          Stat.share(HttpServerMetrics.requests)(_.status.in(StatusClass.ServerError, StatusClass.Failed)).titled("Server errors, share"),
        ),
        Row("Routes")(Panel.quantile(HttpServerMetrics.duration, 0.95).by(_.route).where(_.route =!= Route.Unmatched)),
      )

      val rendered = board.render.fold(error => fail(error.message), identity)

      (
        board.missingSeries(HttpServerMetrics.definitions),
        rendered.contains("status=~\\\"5xx|failed\\\""),
        rendered.contains("route!=\\\"unmatched\\\""),
      ) shouldBe (Set.empty, true, true)
    }
  }
