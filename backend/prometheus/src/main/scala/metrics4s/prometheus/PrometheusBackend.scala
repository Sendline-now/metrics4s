package metrics4s.prometheus

import java.util.WeakHashMap

import scala.collection.mutable.ArrayBuffer
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

import io.prometheus.metrics.core.datapoints.{CounterDataPoint, DistributionDataPoint, GaugeDataPoint}
import io.prometheus.metrics.core.metrics.{Counter as PrometheusCounter, Gauge as PrometheusGauge, Histogram as PrometheusHistogram, Info as PrometheusInfo}
import io.prometheus.metrics.model.registry.{Collector, MetricType, MultiCollector, PrometheusRegistry}
import io.prometheus.metrics.model.snapshots.{Labels as PrometheusLabels, Unit as PrometheusUnit, *}

import metrics4s.*

final class PrometheusBackend(val registry: PrometheusRegistry) extends MetricsBackend:

  def counter(definition: CounterDef[?, ?]): Either[MetricError, Series[CounterCell]] =
    PrometheusBackend.registering(definition) {
      val counter = PrometheusCounter
        .builder()
        .name(definition.name.value)
        .help(definition.help)
        .unit(PrometheusBackend.unitOf(definition.unit))
        .labelNames(definition.labelNames.toSeq*)
        .register(registry)

      PrometheusBackend.series[CounterCell, CounterDataPoint](registry, counter, counter.labelValues(_*), counter.remove(_*)) { point =>
        new CounterCell:
          def add(amount: Double): Unit = point.inc(amount)
      }
    }

  def gauge(definition: GaugeDef[?, ?]): Either[MetricError, Series[GaugeCell]] =
    PrometheusBackend.registering(definition) {
      val gauge = PrometheusGauge
        .builder()
        .name(definition.name.value)
        .help(definition.help)
        .unit(PrometheusBackend.unitOf(definition.unit))
        .labelNames(definition.labelNames.toSeq*)
        .register(registry)

      PrometheusBackend.series[GaugeCell, GaugeDataPoint](registry, gauge, gauge.labelValues(_*), gauge.remove(_*)) { point =>
        new GaugeCell:
          def set(value: Double): Unit = point.set(value)
          def add(delta: Double): Unit = point.inc(delta)
      }
    }

  def histogram(definition: HistogramDef[?, ?]): Either[MetricError, Series[HistogramCell]] =
    PrometheusBackend.registering(definition) {
      val histogram = PrometheusHistogram
        .builder()
        .name(definition.name.value)
        .help(definition.help)
        .unit(PrometheusBackend.unitOf(definition.unit))
        .labelNames(definition.labelNames.toSeq*)
        .classicOnly()
        .classicUpperBounds(definition.buckets.bounds.toSeq*)
        .register(registry)

      PrometheusBackend.series[HistogramCell, DistributionDataPoint](registry, histogram, histogram.labelValues(_*), histogram.remove(_*)) { point =>
        new HistogramCell:
          def observe(value: Double): Unit = point.observe(value)
      }
    }

  override def info(definition: InfoDef[?]): Either[MetricError, InfoSeries] =
    PrometheusBackend.registering(definition) {
      val info = PrometheusInfo
        .builder()
        .name(definition.name.value)
        .help(definition.help)
        .labelNames(definition.labelNames.toSeq*)
        .register(registry)

      new InfoSeries:
        def set(labelValues: IArray[String]): Unit = info.setLabelValues(labelValues.toSeq*)
        def unregister(): Unit                     = registry.unregister(info)
    }

  def observe(definitions: IArray[ObservableDef[?, ?]], collect: ObservedValues => Unit): Either[MetricError, Registration] =
    definitions.headOption match
      case None        => Right(() => ())
      case Some(first) =>
        PrometheusBackend.registering(first) {
          val failures  = PrometheusBackend.callbackErrors(registry).labelValues(first.name.value)
          val collector = PrometheusBackend.ObservedCollector(definitions, collect, failures)
          registry.register(collector)
          () => registry.unregister(collector)
        }

object PrometheusBackend:
  val CallbackErrors: String = "metrics4s_callback_errors"

  private val callbackErrorCounters = WeakHashMap[PrometheusRegistry, PrometheusCounter]()

  private def callbackErrors(registry: PrometheusRegistry): PrometheusCounter =
    callbackErrorCounters.synchronized {
      Option(callbackErrorCounters.get(registry)).getOrElse {
        val counter = PrometheusCounter
          .builder()
          .name(CallbackErrors)
          .help("Scrapes in which a callback metric could not be read, by the first metric of the callback")
          .labelNames("metric")
          .register(registry)
        callbackErrorCounters.put(registry, counter): Unit
        counter
      }
    }

  private[prometheus] def unitOf(unit: Option[MetricUnit]): PrometheusUnit | Null =
    unit match
      case Some(MetricUnit.Seconds) => PrometheusUnit.SECONDS
      case Some(MetricUnit.Bytes)   => PrometheusUnit.BYTES
      case None                     => null

  private def registering[A](definition: MetricDef)(body: => A): Either[MetricError, A] =
    try Right(body)
    catch
      case error: IllegalArgumentException if Option(error.getMessage).exists(_.contains("duplicate metric name")) =>
        Left(MetricError.AlreadyRegistered(definition.name))
      case error: IllegalArgumentException                                                                         =>
        Left(MetricError.Rejected(definition.name, Option(error.getMessage).getOrElse(error.toString)))
      case NonFatal(error)                                                                                         =>
        Left(MetricError.BackendFailure(definition.name, error))

  private def series[C, D](registry: PrometheusRegistry, collector: Collector, point: Array[String] => D, drop: Array[String] => Unit)(wrap: D => C): Series[C] =
    new Series[C]:
      def cell(labelValues: IArray[String]): C      = wrap(point(labelValues.asInstanceOf[Array[String]]))
      def remove(labelValues: IArray[String]): Unit = drop(labelValues.asInstanceOf[Array[String]])
      def unregister(): Unit                        = registry.unregister(collector)

  private final class ObservedCollector(definitions: IArray[ObservableDef[?, ?]], reader: ObservedValues => Unit, failures: CounterDataPoint) extends MultiCollector:

    private val metadata: IArray[MetricMetadata] =
      definitions.map(definition => MetricMetadata(definition.name.value, definition.help, unitOf(definition.unit)))

    private val descriptors: java.util.List[MetricFamilyDescriptor] =
      definitions.indices.map { index =>
        MetricFamilyDescriptor.of(typeOf(definitions(index)), metadata(index), definitions(index).labelNames.toList.asJava)
      }.asJava

    override def getMetricFamilyDescriptors: java.util.List[MetricFamilyDescriptor] = descriptors

    def collect(): MetricSnapshots =
      try snapshots(read())
      catch
        case NonFatal(_) =>
          failures.inc()
          snapshots(IArray.fill(definitions.length)(Vector.empty))

    private def read(): IArray[Vector[(IArray[String], Double)]] =
      val points = Array.fill(definitions.length)(ArrayBuffer.empty[(IArray[String], Double)])
      reader((definition, labelValues, value) => points(definition) += labelValues -> value)
      IArray.from(points.map(_.toVector))

    private def snapshots(points: IArray[Vector[(IArray[String], Double)]]): MetricSnapshots =
      MetricSnapshots.of(definitions.indices.map(index => snapshot(index, points(index)))*)

    private def snapshot(index: Int, points: Vector[(IArray[String], Double)]): MetricSnapshot =
      val definition = definitions(index)

      def labelsOf(labelValues: IArray[String]): PrometheusLabels =
        PrometheusLabels.of(definition.labelNames.toList.asJava, labelValues.toList.asJava)

      definition match
        case _: CounterDef[?, ?] =>
          CounterSnapshot(
            metadata(index),
            points.map((labelValues, value) => CounterSnapshot.CounterDataPointSnapshot.builder().labels(labelsOf(labelValues)).value(value).build()).asJava,
          )
        case _: GaugeDef[?, ?]   =>
          GaugeSnapshot(
            metadata(index),
            points.map((labelValues, value) => GaugeSnapshot.GaugeDataPointSnapshot.builder().labels(labelsOf(labelValues)).value(value).build()).asJava,
          )

    private def typeOf(definition: ObservableDef[?, ?]): MetricType =
      definition match
        case _: CounterDef[?, ?] => MetricType.COUNTER
        case _: GaugeDef[?, ?]   => MetricType.GAUGE
