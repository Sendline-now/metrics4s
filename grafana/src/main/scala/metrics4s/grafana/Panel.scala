package metrics4s.grafana

import metrics4s.{CounterDef, GaugeDef, HistogramDef, InfoDef, MetricDef, MetricUnit}

enum Pods:
  case Each, Sum, Max

sealed abstract class Panel:
  def title: String
  def description: String
  def unit: String
  private[grafana] def targets: Either[GrafanaError, List[Target]]

final class MetricPanel[L] private[grafana] (
    val title: String,
    val description: String,
    val unit: String,
    labelNames: List[String],
    grouping: List[String],
    build: List[String] => List[Target],
) extends Panel:
  inline def by(inline field: L => Any): MetricPanel[L] = byLabel(LabelSelector.name(field))

  def byLabel(label: String): MetricPanel[L] = MetricPanel(title, description, unit, labelNames, grouping :+ label, build)

  def byAll: MetricPanel[L] = MetricPanel(title, description, unit, labelNames, labelNames, build)

  def titled(next: String): MetricPanel[L] = MetricPanel(next, description, unit, labelNames, grouping, build)

  def described(next: String): MetricPanel[L] = MetricPanel(title, next, unit, labelNames, grouping, build)

  def withUnit(next: String): MetricPanel[L] = MetricPanel(title, description, next, labelNames, grouping, build)

  private[grafana] def targets: Either[GrafanaError, List[Target]] =
    grouping.find(label => !labelNames.contains(label)) match
      case Some(label) => Left(GrafanaError.UnknownLabel(title, label, labelNames))
      case None        => Right(build(grouping.distinct))

final class CustomPanel private[grafana] (val title: String, val description: String, val unit: String, queries: List[Target]) extends Panel:
  private[grafana] def targets: Either[GrafanaError, List[Target]] = Right(queries)

object Panel:
  private val Quantiles = List(0.5 -> "p50", 0.95 -> "p95", 0.99 -> "p99")

  def rate[V, L](counter: CounterDef[V, L]): MetricPanel[L] =
    val series = s"${counter.name.value}_total"

    MetricPanel(
      s"${counter.help}, /s",
      s"`$series`: ${counter.help}.",
      rateUnit(counter.unit),
      counter.labelNames.toList,
      Nil,
      by => List(Target(Query.sumBy(by)(Query.rate(series)), Query.legendOf(by).getOrElse(counter.name.value), Set(series))),
    )

  def latency[V, L](histogram: HistogramDef[V, L]): MetricPanel[L] =
    val bucket = s"${histogram.name.value}_bucket"

    MetricPanel(
      histogram.help,
      s"`${histogram.name.value}`: ${histogram.help}.",
      valueUnit(histogram.unit),
      histogram.labelNames.toList,
      Nil,
      by =>
        Quantiles.map { (quantile, tag) =>
          Target(Query.quantile(quantile, bucket, by), (tag :: Query.legendOf(by).toList).mkString(" "), Set(bucket))
        },
    )

  def average[V, L](histogram: HistogramDef[V, L]): MetricPanel[L] =
    val sum   = s"${histogram.name.value}_sum"
    val count = s"${histogram.name.value}_count"

    MetricPanel(
      s"${histogram.help}, average",
      s"`${histogram.name.value}`: ${histogram.help}, the sum divided by the count.",
      valueUnit(histogram.unit),
      histogram.labelNames.toList,
      Nil,
      by =>
        List(
          Target(
            s"${Query.sumBy(by)(Query.rate(sum))} / ${Query.sumBy(by)(Query.rate(count))}",
            Query.legendOf(by).getOrElse("average"),
            Set(sum, count),
          )
        ),
    )

  def gauge[V, L](gauge: GaugeDef[V, L], across: Pods = Pods.Each): MetricPanel[L] =
    val series = gauge.name.value

    MetricPanel(
      gauge.help,
      s"`$series`: ${gauge.help}.",
      valueUnit(gauge.unit),
      gauge.labelNames.toList,
      Nil,
      by =>
        val labels      = across match
          case Pods.Each => "pod" :: by
          case _         => by
        val aggregation = across match
          case Pods.Max => "max"
          case _        => "sum"
        val expr        =
          if labels.isEmpty
          then s"$aggregation(${Query.selector(series)})"
          else s"$aggregation by (${labels.mkString(", ")}) (${Query.selector(series)})"
        List(Target(expr, Query.legendOf(labels).getOrElse(series), Set(series))),
    )

  def custom(title: String, unit: String, description: String)(targets: Target*): Panel = CustomPanel(title, description, unit, targets.toList)

  private[grafana] def rateUnit(unit: Option[MetricUnit]): String =
    unit match
      case Some(MetricUnit.Seconds) => "percentunit"
      case Some(MetricUnit.Bytes)   => "Bps"
      case None                     => "ops"

  private[grafana] def valueUnit(unit: Option[MetricUnit]): String =
    unit match
      case Some(MetricUnit.Seconds) => "s"
      case Some(MetricUnit.Bytes)   => "bytes"
      case None                     => "short"

final class Stat private[grafana] (
    val title: String,
    val description: String,
    val unit: String,
    val target: Target,
    val thresholds: List[(String, Option[Double])],
    val named: Boolean = false,
):
  def titled(next: String): Stat = Stat(next, description, unit, target, thresholds, named)

object Stat:
  private val Green = List("green" -> None)

  val podsUp: Stat =
    Stat("Pods up", "Scrape targets that answer (`up`).", "short", Target(s"sum(${Query.selector("up")})", Set.empty), List("red" -> None, "green" -> Some(1.0)))

  def rate(counter: CounterDef[?, ?]): Stat =
    val series = s"${counter.name.value}_total"
    Stat(s"${counter.help}, /s", s"`$series`: ${counter.help}.", Panel.rateUnit(counter.unit), Target(s"sum(${Query.rate(series)})", Set(series)), Green)

  def p95(histogram: HistogramDef[?, ?]): Stat =
    val bucket = s"${histogram.name.value}_bucket"
    Stat(
      s"${histogram.help}, p95",
      s"`${histogram.name.value}`: ${histogram.help}.",
      Panel.valueUnit(histogram.unit),
      Target(Query.quantile(0.95, bucket, Nil), Set(bucket)),
      Green
    )

  def gauge(gauge: GaugeDef[?, ?], across: Pods = Pods.Sum): Stat =
    val aggregation = across match
      case Pods.Max => "max"
      case _        => "sum"
    Stat(
      gauge.help,
      s"`${gauge.name.value}`: ${gauge.help}.",
      Panel.valueUnit(gauge.unit),
      Target(s"$aggregation(${Query.selector(gauge.name.value)})", Set(gauge.name.value)),
      Green
    )

  inline def info[L](info: InfoDef[L])(inline field: L => Any): Stat = infoBy(info, LabelSelector.name(field))

  def infoBy(info: InfoDef[?], label: String): Stat =
    val series = s"${info.name.value}_info"
    Stat(
      info.help,
      s"`$series`: ${info.help}, `$label` shown.",
      "none",
      Target(s"count by ($label) (${Query.selector(series)})", s"{{$label}}", Set(series)),
      Green,
      named = true,
    )

  def custom(title: String, unit: String, description: String)(target: Target): Stat = Stat(title, description, unit, target, Green)

sealed trait Section

final class Stats private (val stats: List[Stat]) extends Section

object Stats:
  def apply(stats: Stat*): Stats = new Stats(stats.toList)

final class Row private (val title: String, val panels: List[Panel]) extends Section

object Row:
  def apply(title: String)(panels: Panel*): Row = new Row(title, panels.toList)

private[grafana] def seriesOf(definition: MetricDef): Set[String] =
  definition match
    case counter: CounterDef[?, ?]     => Set(s"${counter.name.value}_total")
    case gauge: GaugeDef[?, ?]         => Set(gauge.name.value)
    case histogram: HistogramDef[?, ?] => Set("_bucket", "_sum", "_count").map(histogram.name.value + _)
    case info: InfoDef[?]              => Set(s"${info.name.value}_info")
