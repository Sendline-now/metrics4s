package metrics4s.testkit

import scala.jdk.CollectionConverters.*

import io.prometheus.metrics.model.registry.PrometheusRegistry
import io.prometheus.metrics.model.snapshots.{Labels as PrometheusLabels, *}

import metrics4s.*
import metrics4s.grafana.Dashboard
import metrics4s.prometheus.{PrometheusMetrics, PrometheusScrape}

final case class HistogramValue(count: Long, sum: Double, buckets: List[(Double, Long)])

final class MetricsTestkit private (val registry: PrometheusRegistry):
  def metrics[F[*]](using Effect[F]): Metrics[F] = PrometheusMetrics[F](registry)

  def metrics[F[*]](config: MetricsConfig)(using Effect[F]): Metrics[F] = PrometheusMetrics[F](registry, config)

  def text: String = PrometheusScrape.text(registry)

  def series: Map[String, Double] = MetricsTestkit.parse(text)

  def families: Set[String] = registry.scrape().stream().iterator().asScala.map(_.getMetadata.getName).toSet

  def exposedSeries: Set[String] = series.keySet.map(_.takeWhile(char => char != '{' && char != ' '))

  def missingSeries(dashboard: Dashboard): Set[String] = dashboard.reads -- exposedSeries

  def value[V, L](definition: ObservableDef[V, L], labels: L): Option[Double] =
    point(definition, definition.labels.render(labels)).collect {
      case counter: CounterSnapshot.CounterDataPointSnapshot => counter.getValue
      case gauge: GaugeSnapshot.GaugeDataPointSnapshot       => gauge.getValue
    }

  def value[V](definition: ObservableDef[V, NoLabels]): Option[Double] = value(definition, NoLabels)

  def histogram[V, L](definition: HistogramDef[V, L], labels: L): Option[HistogramValue] =
    point(definition, definition.labels.render(labels)).collect { case histogram: HistogramSnapshot.HistogramDataPointSnapshot =>
      val buckets = histogram.getClassicBuckets.iterator().asScala.toList
      val counts  = buckets.map(_.getCount).scanLeft(0L)(_ + _).drop(1)
      HistogramValue(histogram.getCount, histogram.getSum, buckets.map(_.getUpperBound).zip(counts))
    }

  def histogram[V](definition: HistogramDef[V, NoLabels]): Option[HistogramValue] = histogram(definition, NoLabels)

  def info[L](definition: InfoDef[L]): Option[Map[String, String]] =
    snapshotOf(definition).flatMap(_.getDataPoints.asScala.headOption).map { point =>
      definition.labelNames.toList.map(name => name -> point.getLabels.get(name)).toMap
    }

  def labelValues(definition: MetricDef): Set[List[String]] =
    snapshotOf(definition).toList.flatMap(_.getDataPoints.asScala.map(point => definition.labelNames.toList.map(point.getLabels.get))).toSet

  private def snapshotOf(definition: MetricDef): Option[MetricSnapshot] =
    registry.scrape().stream().iterator().asScala.find(_.getMetadata.getName == definition.name.value)

  private def point(definition: MetricDef, labelValues: IArray[String]): Option[DataPointSnapshot] =
    val expected = PrometheusLabels.of(definition.labelNames.toList.asJava, labelValues.toList.asJava)
    snapshotOf(definition).flatMap(_.getDataPoints.asScala.find(_.getLabels == expected))

object MetricsTestkit:
  def apply(): MetricsTestkit = new MetricsTestkit(PrometheusRegistry())

  def of(registry: PrometheusRegistry): MetricsTestkit = new MetricsTestkit(registry)

  def parse(exposition: String): Map[String, Double] =
    exposition.linesIterator
      .filterNot(line => line.isEmpty || line.startsWith("#"))
      .map { line =>
        val separator = line.lastIndexOf(' ')
        line.take(separator) -> valueOf(line.drop(separator + 1))
      }
      .toMap

  private def valueOf(value: String): Double =
    value match
      case "+Inf" => Double.PositiveInfinity
      case "-Inf" => Double.NegativeInfinity
      case other  => other.toDouble
