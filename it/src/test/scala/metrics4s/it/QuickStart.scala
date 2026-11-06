package metrics4s.it.quickstart

import scala.concurrent.duration.FiniteDuration

import cats.effect.{IO, IOApp, Resource}
import io.prometheus.metrics.model.registry.PrometheusRegistry

import metrics4s.*
import metrics4s.prometheus.{PrometheusExporter, PrometheusMetrics}
import metrics4s.runtime.cats.CatsRuntimeMetrics

import metrics4s.runtime.cats.CatsInstances.given
import metrics4s.runtime.cats.syntax.given

enum Outcome derives LabelValue:
  case Ok, Skipped, Error

object Outcome:
  given Labels[Outcome] = Labels.single("outcome")

object ProcessorMetrics:
  val processed   = Counter("processor_items", "Processed items by outcome").labelled[Outcome]
  val processTime = Histogram.seconds("processor_process_seconds", "Processing of one item", Buckets.Normal)
  val cacheSize   = Gauge("processor_cache_entries", "Entries in the cache")

final class Processor(
    processed: CounterOf[IO, Long, Outcome],
    processTime: HistogramOf[IO, FiniteDuration, NoLabels],
):
  def handle(item: String): IO[Unit] =
    processTime.timed(IO.println(item)) >> processed.inc(Outcome.Ok)

object Main extends IOApp.Simple:
  def run: IO[Unit] =
    val registry      = PrometheusRegistry()
    given Metrics[IO] = PrometheusMetrics[IO](registry)

    val processor =
      for
        _                                   <- PrometheusExporter.allocated[IO](9090, registry).toResource
        _                                   <- Resource.eval(PrometheusMetrics.jvm[IO](registry))
        _                                   <- CatsRuntimeMetrics.resource[IO]()
        (processed, processTime, cacheSize) <- Resource.eval(
                                                 Metrics[IO].registerAll((ProcessorMetrics.processed, ProcessorMetrics.processTime, ProcessorMetrics.cacheSize))
                                               )
        _                                   <- Resource.eval(cacheSize.set(0))
      yield Processor(processed, processTime)

    processor.use(_.handle("first item"))
