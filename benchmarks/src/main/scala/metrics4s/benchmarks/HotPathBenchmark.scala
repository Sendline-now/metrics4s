package metrics4s.benchmarks

import java.util.concurrent.TimeUnit

import scala.concurrent.duration.FiniteDuration

import io.prometheus.metrics.core.metrics.{Counter as PrometheusCounter, Histogram as PrometheusHistogram}
import io.prometheus.metrics.model.registry.PrometheusRegistry
import org.openjdk.jmh.annotations.*

import metrics4s.*
import metrics4s.prometheus.PrometheusMetrics

import scala.concurrent.duration.given

enum Outcome derives LabelValue:
  case Ok, Skipped, Error

object Outcome:
  given Labels[Outcome] = Labels.single("outcome")

enum Source derives LabelValue:
  case Kafka, Rabbit

final case class ByOutcome(outcome: Outcome, source: Source) derives Labels

final case class Provider(name: String)

object Provider:
  given LabelValue[Provider] = LabelValue.bounded(_.name)
  given Labels[Provider]     = Labels.single("provider")

type Direct[A] = A

object Direct:
  given Effect[Direct] = new Effect[Direct]:
    def pure[A](a: A): A                                        = a
    def delay[A](a: => A): A                                    = a
    def map[A, B](fa: A)(f: A => B): B                          = f(fa)
    def flatMap[A, B](fa: A)(f: A => B): B                      = f(fa)
    def raiseError[A](error: Throwable): A                      = throw error
    def handleErrorWith[A](fa: A)(f: Throwable => A): A         = fa
    def guaranteeCase[A](fa: A)(finalizer: ExitCase => Unit): A =
      finalizer(ExitCase.Succeeded)
      fa

@State(Scope.Benchmark)
@BenchmarkMode(Array(Mode.Throughput))
@OutputTimeUnit(TimeUnit.MICROSECONDS)
class HotPathBenchmark:
  import Direct.given

  var rawCounter: PrometheusCounter                           = scala.compiletime.uninitialized
  var rawProduct: PrometheusCounter                           = scala.compiletime.uninitialized
  var rawHistogram: PrometheusHistogram                       = scala.compiletime.uninitialized
  var counter: CounterOf.Unsafe[Long, Outcome]                = scala.compiletime.uninitialized
  var product: CounterOf.Unsafe[Long, ByOutcome]              = scala.compiletime.uninitialized
  var bounded: CounterOf.Unsafe[Long, Provider]               = scala.compiletime.uninitialized
  var histogram: HistogramOf.Unsafe[FiniteDuration, NoLabels] = scala.compiletime.uninitialized

  val provider: Provider       = Provider("cns")
  val labels: ByOutcome        = ByOutcome(Outcome.Ok, Source.Kafka)
  val duration: FiniteDuration = 120.millis

  @Setup
  def setup(): Unit =
    val registry = PrometheusRegistry()
    val metrics  = PrometheusMetrics[Direct](registry)

    rawCounter = PrometheusCounter.builder().name("raw_items").help("Raw").labelNames("outcome").register(registry)
    rawProduct = PrometheusCounter.builder().name("raw_source_items").help("Raw").labelNames("outcome", "source").register(registry)
    rawHistogram = PrometheusHistogram.builder().name("raw_process_seconds").help("Raw").classicOnly().classicUpperBounds(Buckets.Normal.bounds.toSeq*).register(registry)

    counter = metrics.register(Counter("typed_items", "Typed").labelled[Outcome]).unsafe
    product = metrics.register(Counter("typed_source_items", "Typed").labelled[ByOutcome]).unsafe
    bounded = metrics.register(Counter("typed_provider_items", "Typed").labelled[Provider]).unsafe
    histogram = metrics.register(Histogram.seconds("typed_process_seconds", "Typed", Buckets.Normal)).unsafe

  @Benchmark
  def rawCounterOneLabel(): Unit = rawCounter.labelValues("ok").inc()

  @Benchmark
  def counterEnumeratedLabel(): Unit = counter.inc(Outcome.Ok)

  @Benchmark
  def rawCounterTwoLabels(): Unit = rawProduct.labelValues("ok", "kafka").inc()

  @Benchmark
  def counterTwoEnumeratedLabels(): Unit = product.inc(labels)

  @Benchmark
  def counterBoundedLabel(): Unit = bounded.inc(provider)

  @Benchmark
  def rawHistogramObserve(): Unit = rawHistogram.observe(0.12)

  @Benchmark
  def histogramObserveDuration(): Unit = histogram.observe(duration)
