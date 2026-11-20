package metrics4s.benchmarks

import java.util.concurrent.TimeUnit

import scala.concurrent.duration.FiniteDuration

import io.prometheus.metrics.core.metrics.{Counter as PrometheusCounter, Gauge as PrometheusGauge, Histogram as PrometheusHistogram}
import io.prometheus.metrics.model.registry.PrometheusRegistry
import org.openjdk.jmh.annotations.*

import metrics4s.*
import metrics4s.http.{HttpServerMetrics, HttpServerRecorder, StatusClass}
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

final case class Tenant(name: String)

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

  var rawCounter: PrometheusCounter                                     = scala.compiletime.uninitialized
  var rawProduct: PrometheusCounter                                     = scala.compiletime.uninitialized
  var rawHistogram: PrometheusHistogram                                 = scala.compiletime.uninitialized
  var rawLabelledHistogram: PrometheusHistogram                         = scala.compiletime.uninitialized
  var rawGauge: PrometheusGauge                                         = scala.compiletime.uninitialized
  var rawRequests: PrometheusCounter                                    = scala.compiletime.uninitialized
  var rawRequestTime: PrometheusHistogram                               = scala.compiletime.uninitialized
  var rawInFlight: PrometheusGauge                                      = scala.compiletime.uninitialized
  var counter: CounterOf.Unsafe[Long, Outcome]                          = scala.compiletime.uninitialized
  var product: CounterOf.Unsafe[Long, ByOutcome]                        = scala.compiletime.uninitialized
  var named: CounterOf.Unsafe[Long, (outcome: Outcome, source: Source)] = scala.compiletime.uninitialized
  var bounded: CounterOf.Unsafe[Long, Provider]                         = scala.compiletime.uninitialized
  var listed: CounterOf.Unsafe[Long, (tenant: Tenant)]                  = scala.compiletime.uninitialized
  var histogram: HistogramOf.Unsafe[FiniteDuration, NoLabels]           = scala.compiletime.uninitialized
  var labelledHistogram: HistogramOf.Unsafe[FiniteDuration, Outcome]    = scala.compiletime.uninitialized
  var gauge: GaugeOf.Unsafe[Double, Outcome]                            = scala.compiletime.uninitialized
  var timer: HistogramOf[Direct, FiniteDuration, NoLabels]              = scala.compiletime.uninitialized
  var recorder: HttpServerRecorder                                      = scala.compiletime.uninitialized

  val provider: Provider                              = Provider("cns")
  val tenant: Tenant                                  = Tenant("tenant-57")
  val labels: ByOutcome                               = ByOutcome(Outcome.Ok, Source.Kafka)
  val namedLabels: (outcome: Outcome, source: Source) = (outcome = Outcome.Ok, source = Source.Kafka)
  val duration: FiniteDuration                        = 120.millis
  val routes: List[String]                            = List.tabulate(100)(index => s"GET /resources/$index/{id}")
  val template: Option[String]                        = Some("GET /resources/57/{id}")

  @Setup
  def setup(): Unit =
    val registry = PrometheusRegistry()
    val metrics  = PrometheusMetrics[Direct](registry)

    given LabelValue[Tenant] = LabelValue.enumerated(List.tabulate(100)(index => Tenant(s"tenant-$index")))(_.name)

    rawCounter = PrometheusCounter.builder().name("raw_items").help("Raw").labelNames("outcome").register(registry)
    rawProduct = PrometheusCounter.builder().name("raw_source_items").help("Raw").labelNames("outcome", "source").register(registry)
    rawHistogram = PrometheusHistogram.builder().name("raw_process_seconds").help("Raw").classicOnly().classicUpperBounds(Buckets.Normal.bounds.toSeq*).register(registry)
    rawLabelledHistogram = PrometheusHistogram
      .builder()
      .name("raw_handle_seconds")
      .help("Raw")
      .labelNames("outcome")
      .classicOnly()
      .classicUpperBounds(Buckets.Normal.bounds.toSeq*)
      .register(registry)
    rawGauge = PrometheusGauge.builder().name("raw_in_flight").help("Raw").labelNames("outcome").register(registry)
    rawRequests = PrometheusCounter.builder().name("raw_requests").help("Raw").labelNames("route", "status").register(registry)
    rawRequestTime = PrometheusHistogram
      .builder()
      .name("raw_request_seconds")
      .help("Raw")
      .labelNames("route")
      .classicOnly()
      .classicUpperBounds(Buckets.Normal.bounds.toSeq*)
      .register(registry)

    rawInFlight = PrometheusGauge.builder().name("raw_requests_in_flight").help("Raw").register(registry)

    counter = metrics.register(Counter("typed_items", "Typed").labelled[Outcome]).unsafe
    product = metrics.register(Counter("typed_source_items", "Typed").labelled[ByOutcome]).unsafe
    named = metrics.register(Counter("typed_named_items", "Typed").labelled[(outcome: Outcome, source: Source)]).unsafe
    bounded = metrics.register(Counter("typed_provider_items", "Typed").labelled[Provider]).unsafe
    listed = metrics.register(Counter("typed_tenant_items", "Typed").labelled[(tenant: Tenant)]).unsafe
    timer = metrics.register(Histogram.seconds("typed_process_seconds", "Typed", Buckets.Normal))
    histogram = timer.unsafe
    labelledHistogram = metrics.register(Histogram.seconds("typed_handle_seconds", "Typed", Buckets.Normal).labelled[Outcome]).unsafe
    gauge = metrics.register(Gauge("typed_in_flight", "Typed").labelled[Outcome]).unsafe
    recorder = HttpServerMetrics(routes).register[Direct](using metrics)
  end setup

  @Benchmark
  def rawCounterOneLabel(): Unit = rawCounter.labelValues("ok").inc()

  @Benchmark
  def counterEnumeratedLabel(): Unit = counter.inc(Outcome.Ok)

  @Benchmark
  def rawCounterTwoLabels(): Unit = rawProduct.labelValues("ok", "kafka").inc()

  @Benchmark
  def counterTwoEnumeratedLabels(): Unit = product.inc(labels)

  @Benchmark
  def counterNamedTupleLabels(): Unit = named.inc(namedLabels)

  @Benchmark
  def counterNamedTupleLabelsBuilt(): Unit = named.inc((outcome = Outcome.Ok, source = Source.Kafka))

  @Benchmark
  def counterBoundedLabel(): Unit = bounded.inc(provider)

  @Benchmark
  def counterListedLabel(): Unit = listed.inc((tenant = tenant))

  @Benchmark
  def rawHistogramObserve(): Unit = rawHistogram.observe(0.12)

  @Benchmark
  def histogramObserveDuration(): Unit = histogram.observe(duration)

  @Benchmark
  def rawHistogramOneLabel(): Unit = rawLabelledHistogram.labelValues("ok").observe(0.12)

  @Benchmark
  def histogramEnumeratedLabel(): Unit = labelledHistogram.observe(Outcome.Ok, duration)

  @Benchmark
  def rawGaugeOneLabel(): Unit = rawGauge.labelValues("ok").set(3.0)

  @Benchmark
  def gaugeEnumeratedLabel(): Unit = gauge.set(Outcome.Ok, 3.0)

  @Benchmark
  def timedEffect(): Int = timer.timed(1)

  @Benchmark
  def rawHttpRequest(): Unit =
    rawInFlight.inc()
    val started = System.nanoTime()
    rawInFlight.dec()
    rawRequests.labelValues("GET /resources/57/{id}", "2xx").inc()
    rawRequestTime.labelValues("GET /resources/57/{id}").observe((System.nanoTime() - started) / 1e9)

  @Benchmark
  def httpRequest(): Unit =
    val started = recorder.start()
    recorder.finish(started, recorder.metrics.route(template), StatusClass.Success)
