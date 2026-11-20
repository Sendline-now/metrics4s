package metrics4s.grafana

import metrics4s.{CounterDef, GaugeDef, HistogramDef, InfoDef, MetricDef, MetricUnit}

object MetricsDoc:
  val Begin: String = "<!-- metrics4s:table -->"

  val End: String = "<!-- /metrics4s:table -->"

  def markdown(definitions: MetricDef*): String =
    val rows = definitions.map(definition => s"| `${exposedName(definition)}` | ${typeOf(definition)} | ${labelsOf(definition)} | ${definition.help} |")

    ("| Metric | Type | Labels | What |" :: "|---|---|---|---|" :: rows.toList).mkString("", "\n", "\n")

  def embedded(document: String, definitions: Seq[MetricDef], begin: String = Begin, end: String = End): Either[GrafanaError, String] =
    val from = document.indexOf(begin)
    val to   = document.indexOf(end, from + begin.length)

    val line = if document.contains("\r\n") then "\r\n" else "\n"

    if from < 0 || to < 0
    then Left(GrafanaError.MissingMarkers(begin, end))
    else Right(document.take(from + begin.length) + line + line + markdown(definitions*).replace("\n", line) + line + document.drop(to))

  private def exposedName(definition: MetricDef): String =
    definition match
      case counter: CounterDef[?, ?] => s"${counter.name.value}_total"
      case info: InfoDef[?]          => s"${info.name.value}_info"
      case other                     => other.name.value

  private def typeOf(definition: MetricDef): String =
    val kind = definition match
      case _: CounterDef[?, ?]   => "counter"
      case _: GaugeDef[?, ?]     => "gauge"
      case _: HistogramDef[?, ?] => "histogram"
      case _: InfoDef[?]         => "info"

    definition.unit match
      case Some(MetricUnit.Seconds) => s"$kind, seconds"
      case Some(MetricUnit.Bytes)   => s"$kind, bytes"
      case Some(MetricUnit.Ratio)   => s"$kind, ratio"
      case None                     => kind

  private def labelsOf(definition: MetricDef): String =
    definition.labelNames.toList
      .zip(definition.labelDomains.toList)
      .map { (name, domain) =>
        domain.fold(s"`$name`")(values => s"`$name`: ${values.map(value => s"`$value`").mkString(", ")}")
      }
      .mkString("; ")
