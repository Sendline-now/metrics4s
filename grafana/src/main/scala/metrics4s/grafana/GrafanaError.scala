package metrics4s.grafana

enum GrafanaError:
  case UnknownLabel(panel: String, label: String, labels: List[String])
  case UnfilteredQuery(panel: String, expr: String)
  case FixedRateWindow(panel: String, expr: String)
  case EmptyPanel(panel: String)

  def message: String =
    this match
      case UnknownLabel(panel, label, labels) => s"$panel: there is no label \"$label\" to group by, only ${labels.mkString(", ")}"
      case UnfilteredQuery(panel, expr)       => s"$panel: every query must filter by namespace, service and pod: $expr"
      case FixedRateWindow(panel, expr)       => s"$panel: rate windows must be $$__rate_interval, not a fixed one: $expr"
      case EmptyPanel(panel)                  => s"$panel: a panel needs at least one query"
