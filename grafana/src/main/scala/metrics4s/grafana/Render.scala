package metrics4s.grafana

import metrics4s.grafana.Json.{arr, bool, num, obj, str}

private[grafana] object Render:
  private val Datasource: Json = obj("type" -> str("prometheus"), "uid" -> str("${datasource}"))

  private val FixedWindow = """rate\([^)]*\[\d+[smhdw]\]""".r

  private final case class Layout(y: Int, id: Int, panels: Vector[Json])

  def dashboard(dashboard: Dashboard): Either[GrafanaError, Json] =
    dashboard.sections
      .foldLeft[Either[GrafanaError, Layout]](Right(Layout(0, 1, Vector.empty))) { (acc, section) =>
        acc.flatMap(layout => place(section, layout))
      }
      .map(layout => header(dashboard, layout.panels.toList))

  private def place(section: Section, layout: Layout): Either[GrafanaError, Layout] =
    section match
      case stats: Stats =>
        stats.stats
          .foldLeft[Either[GrafanaError, Vector[Json]]](Right(Vector.empty)) { (acc, stat) =>
            acc.flatMap { done =>
              val index = done.size
              thresholdsOf(stat).flatMap(_ => stat.target).flatMap { target =>
                validated(stat.title, List(target)).map(_ => done :+ statJson(stat, target, layout.id + index, (index % 6) * 4, layout.y + (index / 6) * 4))
              }
            }
          }
          .map(panels => Layout(layout.y + rowsOf(panels.size, 6) * 4, layout.id + panels.size, layout.panels ++ panels))
      case row: Row     =>
        val header = rowJson(row.title, layout.id, layout.y)
        row.panels
          .foldLeft[Either[GrafanaError, Vector[Json]]](Right(Vector.empty)) { (acc, panel) =>
            acc.flatMap { done =>
              val index = done.size
              panel.targets.flatMap(targets => validated(panel.title, targets)).map { targets =>
                done :+ timeseriesJson(panel, targets, layout.id + 1 + index, (index % 2) * 12, layout.y + 1 + (index / 2) * 8)
              }
            }
          }
          .map(panels => Layout(layout.y + 1 + rowsOf(panels.size, 2) * 8, layout.id + 1 + panels.size, (layout.panels :+ header) ++ panels))

  private def rowsOf(count: Int, perRow: Int): Int = (count + perRow - 1) / perRow

  private def validated(title: String, targets: List[Target]): Either[GrafanaError, List[Target]] =
    if targets.isEmpty then Left(GrafanaError.EmptyPanel(title))
    else
      targets.collectFirst {
        case target if !target.expr.contains(Query.Filter)            => GrafanaError.UnfilteredQuery(title, target.expr)
        case target if FixedWindow.findFirstIn(target.expr).isDefined => GrafanaError.FixedRateWindow(title, target.expr)
      }
        .toLeft(targets)

  private def thresholdsOf(stat: Stat): Either[GrafanaError, Thresholds] =
    val values = stat.thresholds.steps.map(_._1)

    if values.forall(_.isFinite) && values.zip(values.drop(1)).forall(_ < _)
    then Right(stat.thresholds)
    else Left(GrafanaError.InvalidThresholds(stat.title, values))

  private def header(dashboard: Dashboard, panels: List[Json]): Json =
    obj(
      "uid"           -> str(dashboard.uid),
      "title"         -> str(dashboard.title),
      "description"   -> str(dashboard.description),
      "tags"          -> arr(str(dashboard.group)),
      "timezone"      -> str("browser"),
      "editable"      -> bool(true),
      "graphTooltip"  -> num(1),
      "schemaVersion" -> num(39),
      "version"       -> num(1),
      "refresh"       -> str("30s"),
      "time"          -> obj("from" -> str("now-6h"), "to" -> str("now")),
      "links"         -> arr(
        obj(
          "type"        -> str("dashboards"),
          "tags"        -> arr(str(dashboard.group)),
          "asDropdown"  -> bool(true),
          "title"       -> str(dashboard.group.capitalize),
          "includeVars" -> bool(true),
          "keepTime"    -> bool(true),
        )
      ),
      "annotations"   -> obj(
        "list" -> arr(
          obj(
            "builtIn"    -> num(1),
            "datasource" -> obj("type" -> str("grafana"), "uid" -> str("-- Grafana --")),
            "enable"     -> bool(true),
            "hide"       -> bool(true),
            "iconColor"  -> str("rgba(0, 211, 255, 1)"),
            "name"       -> str("Annotations & Alerts"),
            "type"       -> str("dashboard"),
          )
        )
      ),
      "templating"    -> obj("list" -> arr(variables(dashboard.services)*)),
      "panels"        -> Json.Arr(panels),
    )

  private def variables(services: ServiceFilter): List[Json] =
    val namespaces = s"""label_values(up{service=~"${services.regex}"},namespace)"""
    val inService  = s"""label_values(up{namespace="$$namespace", service=~"${services.regex}"},service)"""
    val pods       = """label_values(up{namespace="$namespace", service=~"$service"},pod)"""

    List(
      obj(
        "name"             -> str("datasource"),
        "label"            -> str("Data source"),
        "type"             -> str("datasource"),
        "query"            -> str("prometheus"),
        "current"          -> obj(),
        "hide"             -> num(0),
        "refresh"          -> num(1),
        "regex"            -> str(""),
        "allowCustomValue" -> bool(false),
        "options"          -> arr(),
      ),
      queryVariable("namespace", "Namespace", namespaces, multi = None),
      queryVariable("service", "Service", inService, multi = Some(services.regex)),
      queryVariable("pod", "Pod", pods, multi = Some(".*")),
    )

  private def queryVariable(name: String, label: String, query: String, multi: Option[String]): Json =
    val fields = List(
      "name"             -> str(name),
      "label"            -> str(label),
      "type"             -> str("query"),
      "datasource"       -> Datasource,
      "definition"       -> str(query),
      "query"            -> obj("qryType" -> num(1), "query" -> str(query), "refId" -> str("PrometheusVariableQueryEditor-VariableQuery")),
      "refresh"          -> num(1),
      "regex"            -> str(""),
      "regexApplyTo"     -> str("value"),
      "sort"             -> num(1),
      "allowCustomValue" -> bool(false),
      "current"          -> obj(),
      "options"          -> arr(),
      "hide"             -> num(0),
    )

    Json.Obj(fields ++ multi.toList.flatMap(all => List("multi" -> bool(true), "includeAll" -> bool(true), "allValue" -> str(all))))

  private def rowJson(title: String, id: Int, y: Int): Json =
    obj(
      "type"      -> str("row"),
      "title"     -> str(title),
      "collapsed" -> bool(false),
      "id"        -> num(id),
      "gridPos"   -> gridPos(1, 24, 0, y),
      "panels"    -> arr(),
    )

  private def statJson(stat: Stat, target: Target, id: Int, x: Int, y: Int): Json =
    obj(
      "type"        -> str("stat"),
      "title"       -> str(stat.title),
      "description" -> str(stat.description),
      "id"          -> num(id),
      "datasource"  -> Datasource,
      "gridPos"     -> gridPos(4, 4, x, y),
      "fieldConfig" -> obj(
        "defaults"  -> obj(
          "unit"       -> str(stat.unit),
          "color"      -> obj("mode" -> str("thresholds")),
          "thresholds" -> obj(
            "mode"  -> str("absolute"),
            "steps" -> Json.Arr(
              obj("color" -> str(stat.thresholds.base.value), "value" -> Json.Null) ::
                stat.thresholds.steps.map((value, color) => obj("color" -> str(color.value), "value" -> num(value)))
            ),
          ),
        ),
        "overrides" -> arr(),
      ),
      "options"     -> obj(
        "reduceOptions" -> obj("calcs" -> arr(str("lastNotNull")), "fields" -> str(""), "values" -> bool(false)),
        "colorMode"     -> str(if stat.named then "none" else "value"),
        "graphMode"     -> str(if stat.named then "none" else "area"),
        "textMode"      -> str(if stat.named then "name" else "auto"),
      ),
      "targets"     -> arr(targetJson(target, 0, legend = stat.named)),
    )

  private def timeseriesJson(panel: Panel, targets: List[Target], id: Int, x: Int, y: Int): Json =
    obj(
      "type"        -> str("timeseries"),
      "title"       -> str(panel.title),
      "description" -> str(panel.description),
      "id"          -> num(id),
      "datasource"  -> Datasource,
      "gridPos"     -> gridPos(8, 12, x, y),
      "fieldConfig" -> obj(
        "defaults"  -> obj(
          "unit"   -> str(panel.unit),
          "custom" -> obj(
            "lineWidth"   -> num(1),
            "fillOpacity" -> num(10),
            "showPoints"  -> str("auto"),
            "stacking"    -> obj("mode" -> str("none"), "group" -> str("A")),
          ),
          "min"    -> num(0),
        ),
        "overrides" -> arr(),
      ),
      "options"     -> obj(
        "legend"  -> obj("displayMode" -> str("table"), "placement" -> str("bottom"), "calcs" -> arr(str("mean"), str("max"), str("lastNotNull"))),
        "tooltip" -> obj("mode" -> str("multi"), "sort" -> str("desc")),
      ),
      "targets"     -> Json.Arr(targets.zipWithIndex.map((target, index) => targetJson(target, index, legend = true))),
    )

  private def targetJson(target: Target, index: Int, legend: Boolean): Json =
    val fields = List(
      "refId"      -> str(('A' + index).toChar.toString),
      "datasource" -> Datasource,
      "editorMode" -> str("code"),
      "expr"       -> str(target.expr),
    ) ++ target.legend.filter(_ => legend).map("legendFormat" -> str(_)).toList ++ List(
      "instant" -> bool(false),
      "range"   -> bool(true),
    )

    Json.Obj(fields)

  private def gridPos(h: Int, w: Int, x: Int, y: Int): Json = obj("h" -> num(h), "w" -> num(w), "x" -> num(x), "y" -> num(y))
