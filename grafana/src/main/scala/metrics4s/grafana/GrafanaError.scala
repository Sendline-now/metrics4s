package metrics4s.grafana

enum GrafanaError:
  case UnknownLabel(panel: String, label: String, labels: List[String])
  case UnfilteredQuery(panel: String, expr: String)
  case FixedRateWindow(panel: String, expr: String)
  case EmptyPanel(panel: String)
  case NotOneLabel(panel: String, labels: List[String])
  case UnknownLabelValue(panel: String, label: String, value: String)
  case InvalidQuantile(panel: String, quantile: Double)
  case InvalidThresholds(panel: String, values: List[Double])

  def message: String =
    this match
      case UnknownLabel(panel, label, labels)     => s"$panel: there is no label \"$label\", only ${labels.mkString(", ")}"
      case UnfilteredQuery(panel, expr)           => s"$panel: every query must filter by namespace, service and pod: $expr"
      case FixedRateWindow(panel, expr)           => s"$panel: rate windows must be $$__rate_interval, not a fixed one: $expr"
      case EmptyPanel(panel)                      => s"$panel: a panel needs at least one query"
      case NotOneLabel(panel, labels)             => s"$panel: compare one label field, as in _.outcome === Outcome.Ok, not all of ${labels.mkString(", ")}"
      case UnknownLabelValue(panel, label, value) => s"$panel: $value is not a value of label \"$label\""
      case InvalidQuantile(panel, quantile)       => s"$panel: a quantile is between 0 and 1, not $quantile"
      case InvalidThresholds(panel, values)       => s"$panel: threshold values must be finite and increasing, not ${values.mkString(", ")}"
