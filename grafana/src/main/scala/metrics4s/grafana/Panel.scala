package metrics4s.grafana

import scala.annotation.publicInBinary

import metrics4s.{CounterDef, GaugeDef, HistogramDef, InfoDef, Labels, MetricDef, MetricUnit}

enum Pods:
  case Each, Sum, Max

sealed abstract class Panel:
  def title: String
  def description: String
  def unit: String
  private[grafana] def targets: Either[GrafanaError, List[Target]]

private[grafana] final case class Selection(title: String, by: List[String], filters: List[String])

final class MetricPanel[L] private[grafana] (
    val title: String,
    val description: String,
    val unit: String,
    labels: Labels[L],
    grouping: List[String],
    filters: List[LabelMatch],
    build: Selection => Either[GrafanaError, List[Target]],
) extends Panel:
  inline def by(inline field: L => Any): MetricPanel[L] = byLabel(LabelSelector.name(field))

  def byLabel(label: String): MetricPanel[L] = changed(grouping = grouping :+ label)

  def byAll: MetricPanel[L] = changed(grouping = labels.names.toList)

  inline def where(inline condition: LabelMatchers ?=> L => LabelMatch): MetricPanel[L] = filtered(LabelSelector.matching(condition))

  def titled(next: String): MetricPanel[L] = changed(title = next)

  def described(next: String): MetricPanel[L] = changed(description = next)

  def withUnit(next: String): MetricPanel[L] = changed(unit = next)

  @publicInBinary private[grafana] def filtered(condition: LabelMatch): MetricPanel[L] = changed(filters = filters :+ condition)

  private[grafana] def targets: Either[GrafanaError, List[Target]] =
    val names = labels.names.toList

    grouping.find(label => !names.contains(label)) match
      case Some(label) => Left(GrafanaError.UnknownLabel(title, label, names))
      case None        => LabelMatch.matchers(title, labels, filters).flatMap(matchers => build(Selection(title, grouping.distinct, matchers)))

  private def changed(
      title: String = this.title,
      description: String = this.description,
      unit: String = this.unit,
      grouping: List[String] = this.grouping,
      filters: List[LabelMatch] = this.filters,
  ): MetricPanel[L] = MetricPanel(title, description, unit, labels, grouping, filters, build)

object MetricPanel:
  private[grafana] def of[L](title: String, description: String, unit: String, labels: Labels[L])(
      build: Selection => Either[GrafanaError, List[Target]]
  ): MetricPanel[L] =
    MetricPanel(title, description, unit, labels, Nil, Nil, build)

final class CustomPanel private[grafana] (val title: String, val description: String, val unit: String, queries: List[Target]) extends Panel:
  private[grafana] def targets: Either[GrafanaError, List[Target]] = Right(queries)

object Panel:
  private val Quantiles = List(0.5, 0.95, 0.99)

  def rate[V, L](counter: CounterDef[V, L]): MetricPanel[L] =
    val series = s"${counter.name.value}_total"

    MetricPanel.of(s"${counter.help}, /s", s"`$series`: ${counter.help}.", rateUnit(counter.unit), counter.labels) { selection =>
      Right(
        List(Target(Query.sumBy(selection.by)(Query.rate(series, selection.filters*)), Query.legendOf(selection.by).getOrElse(counter.name.value), Set(series)))
      )
    }

  def latency[V, L](histogram: HistogramDef[V, L]): MetricPanel[L] =
    val bucket = s"${histogram.name.value}_bucket"

    MetricPanel.of(histogram.help, s"`${histogram.name.value}`: ${histogram.help}.", valueUnit(histogram.unit), histogram.labels) { selection =>
      Right(Quantiles.map { quantile =>
        val legend = (tagOf(quantile) :: Query.legendOf(selection.by).toList).mkString(" ")
        Target(Query.quantile(quantile, bucket, selection.by, selection.filters*), legend, Set(bucket))
      })
    }

  def quantile[V, L](histogram: HistogramDef[V, L], quantile: Double): MetricPanel[L] =
    val bucket = s"${histogram.name.value}_bucket"
    val tag    = tagOf(quantile)

    MetricPanel.of(s"${histogram.help}, $tag", s"`${histogram.name.value}`: ${histogram.help}.", valueUnit(histogram.unit), histogram.labels) { selection =>
      if quantile > 0 && quantile < 1
      then Right(List(Target(Query.quantile(quantile, bucket, selection.by, selection.filters*), Query.legendOf(selection.by).getOrElse(tag), Set(bucket))))
      else Left(GrafanaError.InvalidQuantile(selection.title, quantile))
    }

  def average[V, L](histogram: HistogramDef[V, L]): MetricPanel[L] =
    val sum   = s"${histogram.name.value}_sum"
    val count = s"${histogram.name.value}_count"

    MetricPanel.of(
      s"${histogram.help}, average",
      s"`${histogram.name.value}`: ${histogram.help}, the sum divided by the count.",
      valueUnit(histogram.unit),
      histogram.labels,
    ) { selection =>
      Right(
        List(
          Target(
            s"${Query.sumBy(selection.by)(Query.rate(sum, selection.filters*))} / ${Query.sumBy(selection.by)(Query.rate(count, selection.filters*))}",
            Query.legendOf(selection.by).getOrElse("average"),
            Set(sum, count),
          )
        )
      )
    }

  def gauge[V, L](gauge: GaugeDef[V, L], across: Pods = Pods.Each): MetricPanel[L] =
    val series = gauge.name.value

    MetricPanel.of(gauge.help, s"`$series`: ${gauge.help}.", valueUnit(gauge.unit), gauge.labels) { selection =>
      val labels      = across match
        case Pods.Each => "pod" :: selection.by
        case _         => selection.by
      val aggregation = across match
        case Pods.Max => "max"
        case _        => "sum"
      val expr        =
        if labels.isEmpty
        then s"$aggregation(${Query.selector(series, selection.filters*)})"
        else s"$aggregation by (${labels.mkString(", ")}) (${Query.selector(series, selection.filters*)})"
      Right(List(Target(expr, Query.legendOf(labels).getOrElse(series), Set(series))))
    }

  inline def share[V, L](counter: CounterDef[V, L])(inline condition: LabelMatchers ?=> L => LabelMatch): MetricPanel[L] =
    shareOf(counter, LabelSelector.matching(condition))

  @publicInBinary private[grafana] def shareOf[V, L](counter: CounterDef[V, L], condition: LabelMatch): MetricPanel[L] =
    val series = s"${counter.name.value}_total"

    MetricPanel.of(s"${counter.help}, share", s"`$series`: ${counter.help}, the share that matches.", "percentunit", counter.labels) { selection =>
      condition.matcher(selection.title, counter.labels).map { part =>
        val matching = Query.sumBy(selection.by)(Query.rate(series, (selection.filters :+ part)*))
        val all      = Query.sumBy(selection.by)(Query.rate(series, selection.filters*))
        List(Target(s"$matching / $all", Query.legendOf(selection.by).getOrElse("share"), Set(series)))
      }
    }

  def custom(title: String, unit: String, description: String)(targets: Target*): Panel = CustomPanel(title, description, unit, targets.toList)

  private[grafana] def tagOf(quantile: Double): String = s"p${(BigDecimal(quantile.toString) * 100).bigDecimal.stripTrailingZeros.toPlainString}"

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
    resolve: String => Either[GrafanaError, Target],
    val thresholds: List[(String, Option[Double])],
    private[grafana] val named: Boolean,
):
  def titled(next: String): Stat = Stat(next, description, unit, resolve, thresholds, named)

  def described(next: String): Stat = Stat(title, next, unit, resolve, thresholds, named)

  def withUnit(next: String): Stat = Stat(title, description, next, resolve, thresholds, named)

  private[grafana] def target: Either[GrafanaError, Target] = resolve(title)

object Stat:
  private val Green = List("green" -> None)

  val podsUp: Stat =
    fixed("Pods up", "Scrape targets that answer (`up`).", "short", Target(s"sum(${Query.selector("up")})", Set.empty), List("red" -> None, "green" -> Some(1.0)))

  def rate(counter: CounterDef[?, ?]): Stat =
    val series = s"${counter.name.value}_total"
    fixed(s"${counter.help}, /s", s"`$series`: ${counter.help}.", Panel.rateUnit(counter.unit), Target(s"sum(${Query.rate(series)})", Set(series)))

  def quantile(histogram: HistogramDef[?, ?], quantile: Double): Stat =
    val bucket = s"${histogram.name.value}_bucket"
    Stat(
      s"${histogram.help}, ${Panel.tagOf(quantile)}",
      s"`${histogram.name.value}`: ${histogram.help}.",
      Panel.valueUnit(histogram.unit),
      title =>
        if quantile > 0 && quantile < 1
        then Right(Target(Query.quantile(quantile, bucket, Nil), Set(bucket)))
        else Left(GrafanaError.InvalidQuantile(title, quantile)),
      Green,
      named = false,
    )

  def p95(histogram: HistogramDef[?, ?]): Stat = quantile(histogram, 0.95)

  def gauge(gauge: GaugeDef[?, ?], across: Pods = Pods.Sum): Stat =
    val aggregation = across match
      case Pods.Max => "max"
      case _        => "sum"
    fixed(
      gauge.help,
      s"`${gauge.name.value}`: ${gauge.help}.",
      Panel.valueUnit(gauge.unit),
      Target(s"$aggregation(${Query.selector(gauge.name.value)})", Set(gauge.name.value))
    )

  inline def share[V, L](counter: CounterDef[V, L])(inline condition: LabelMatchers ?=> L => LabelMatch): Stat =
    shareOf(counter, LabelSelector.matching(condition))

  @publicInBinary private[grafana] def shareOf[V, L](counter: CounterDef[V, L], condition: LabelMatch): Stat =
    val series = s"${counter.name.value}_total"
    Stat(
      s"${counter.help}, share",
      s"`$series`: ${counter.help}, the share that matches.",
      "percentunit",
      title => condition.matcher(title, counter.labels).map(part => Target(s"sum(${Query.rate(series, part)}) / sum(${Query.rate(series)})", Set(series))),
      Green,
      named = false,
    )

  inline def info[L](info: InfoDef[L])(inline field: L => Any): Stat = infoBy(info, LabelSelector.name(field))

  def infoBy(info: InfoDef[?], label: String): Stat =
    val series = s"${info.name.value}_info"
    Stat(
      info.help,
      s"`$series`: ${info.help}, `$label` shown.",
      "none",
      _ => Right(Target(s"count by ($label) (${Query.selector(series)})", s"{{$label}}", Set(series))),
      Green,
      named = true,
    )

  def custom(title: String, unit: String, description: String)(target: Target): Stat = fixed(title, description, unit, target)

  private def fixed(title: String, description: String, unit: String, target: Target, thresholds: List[(String, Option[Double])] = Green): Stat =
    Stat(title, description, unit, _ => Right(target), thresholds, named = false)

sealed trait Section

final class Stats private (val stats: List[Stat]) extends Section

object Stats:
  def apply(stats: Stat*): Stats = new Stats(stats.toList)

final class Row private (val title: String, val panels: List[Panel], private[grafana] val shared: Boolean) extends Section

object Row:
  def apply(title: String)(panels: Panel*): Row = new Row(title, panels.toList, shared = false)

  private[grafana] def shared(title: String)(panels: Panel*): Row = new Row(title, panels.toList, shared = true)

private[grafana] def seriesOf(definition: MetricDef): Set[String] =
  definition match
    case counter: CounterDef[?, ?]     => Set(s"${counter.name.value}_total")
    case gauge: GaugeDef[?, ?]         => Set(gauge.name.value)
    case histogram: HistogramDef[?, ?] => Set("_bucket", "_sum", "_count").map(histogram.name.value + _)
    case info: InfoDef[?]              => Set(s"${info.name.value}_info")
