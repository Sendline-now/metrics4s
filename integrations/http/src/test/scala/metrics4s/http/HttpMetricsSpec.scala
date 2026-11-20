package metrics4s.http

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import metrics4s.MetricDef
import metrics4s.grafana.*

final class HttpMetricsSpec extends AnyWordSpec, Matchers:
  private def shape(definition: MetricDef) =
    (definition.name.value, definition.help, definition.kind, definition.unit, definition.labelNames.toList, definition.preCreation)

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
          Stat.share(HttpServerMetrics.requests)(_.status === StatusClass.ServerError).titled("Server errors, share"),
          Stat.share(HttpClientMetrics.requests)(_.status.in(ClientStatus.ServerError, ClientStatus.Failed)).titled("Failed calls, share"),
        ),
        Row("Routes")(Panel.quantile(HttpServerMetrics.duration, 0.95).by(_.route).where(_.route =!= Route.Unmatched)),
      )

      val rendered = board.render.fold(error => fail(error.message), identity)

      (
        board.missingSeries(HttpServerMetrics.definitions ++ HttpClientMetrics.definitions),
        rendered.contains("status=\\\"5xx\\\""),
        rendered.contains("status=~\\\"5xx|failed\\\""),
        rendered.contains("route!=\\\"unmatched\\\""),
      ) shouldBe (Set.empty, true, true, true)
    }

    "leave failed to the client: a server always answers with a status, a client can be left without one" in {
      def statuses(definition: MetricDef): List[String] = definition.labelDomains.toList.flatten.lastOption.fold(Nil)(_.toList)

      (
        statuses(HttpServerMetrics(Nil).requests),
        statuses(HttpClientMetrics(Nil).requests),
        List(100, 204, 302, 404, 503, 999).map(StatusClass.of(_).value),
        StatusClass.values.toList.map(ClientStatus.of(_).value),
      ) shouldBe (
        List("1xx", "2xx", "3xx", "4xx", "5xx"),
        List("1xx", "2xx", "3xx", "4xx", "5xx", "failed"),
        List("1xx", "2xx", "3xx", "4xx", "5xx", "5xx"),
        List("1xx", "2xx", "3xx", "4xx", "5xx"),
      )
    }
  }
