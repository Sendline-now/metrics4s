package metrics4s.it.docs.recipes.zioservice

import zio.{Task, ZIOAppDefault}
import io.prometheus.metrics.model.registry.PrometheusRegistry

import metrics4s.Metrics
import metrics4s.runtime.zio.ZioRuntimeMetrics
import metrics4s.it.docs.quickstart.{Outcome, ProcessorMetrics}
import metrics4s.prometheus.{PrometheusExporter, PrometheusMetrics}

import metrics4s.runtime.zio.syntax.given
import metrics4s.runtime.zio.ZioInstances.given

object Main extends ZIOAppDefault:
  private val registry = PrometheusRegistry()

  given Metrics[Task] = PrometheusMetrics[Task](registry)

  def run =
    for
      _                 <- PrometheusExporter.allocated[Task](9090, registry).toScoped
      _                 <- PrometheusMetrics.jvm[Task](registry)
      _                 <- ZioRuntimeMetrics.scoped
      (processed, _, _) <- Metrics[Task].registerAll((ProcessorMetrics.processed, ProcessorMetrics.processTime, ProcessorMetrics.cacheSize))
      _                 <- processed.inc(Outcome.Ok)
    yield ()
