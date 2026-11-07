package metrics4s.it.docs.backend

import cats.effect.IO
import io.prometheus.metrics.model.registry.PrometheusRegistry

import metrics4s.Metrics
import metrics4s.runtime.cats.CatsRuntimeMetrics
import metrics4s.prometheus.{PrometheusExporter, PrometheusMetrics, PrometheusScrape}

import metrics4s.runtime.cats.syntax.given
import metrics4s.runtime.cats.CatsInstances.given

object Backend:
  def wired(registry: PrometheusRegistry): IO[Unit] =
    given Metrics[IO] = PrometheusMetrics[IO](registry) // or PrometheusMetrics[IO](registry, MetricsConfig.default.withPreCreateLimit(1024))
    PrometheusMetrics.jvm[IO](registry) // the client's own JVM metrics: jvm_memory_used_bytes, process_cpu_seconds_total, ...
    PrometheusExporter.allocated[IO](9090, registry).toResource // HTTPServer; port 0 picks a free one, exporter.port says which
    PrometheusScrape.text(registry) // the exposition, for a /metrics route of your own

    CatsRuntimeMetrics.resource[IO]().use_
