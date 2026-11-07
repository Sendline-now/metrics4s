package metrics4s.it.docs.dashboards

import java.nio.file.{Path, Paths}

import metrics4s.grafana.*
import metrics4s.it.docs.quickstart.ProcessorMetrics
import metrics4s.http.{HttpServerMetrics, StatusClass}

object Dashboards:
  def written(): Either[Throwable, Path] =
    val dashboard = Dashboard("processor", "Line / processor", "line", ServiceFilter.regex("([a-z]+-)?processor.*"))(
      Stats(Stat.podsUp, Stat.rate(ProcessorMetrics.processed), Stat.p95(ProcessorMetrics.processTime)),
      Row("Processing")(
        Panel.rate(ProcessorMetrics.processed).byAll,
        Panel.latency(ProcessorMetrics.processTime),
        Panel.gauge(ProcessorMetrics.cacheSize, Pods.Max),
      ),
      Rows.catsEffect,
      Rows.jvm,
    )

    dashboard.render // Either[GrafanaError, String]
    dashboard.writeTo(Paths.get("metrics"))

  def drift(dashboard: Dashboard): Set[String] =
    Stats(
      Stat.rate(HttpServerMetrics.requests),
      Stat.quantile(HttpServerMetrics.duration, 0.95),
      Stat.share(HttpServerMetrics.requests)(_.status.in(StatusClass.ServerError, StatusClass.Failed)),
    )

    dashboard.missingSeries(List(ProcessorMetrics.processed, ProcessorMetrics.processTime) ++ HttpServerMetrics.definitions)
