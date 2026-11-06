package metrics4s.grafana

import scala.compiletime.testing.typeCheckErrors

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import metrics4s.*

enum Outcome derives LabelValue:
  case Ok, Skipped, Error

enum Source derives LabelValue:
  case Kafka, Rabbit

final case class ByOutcome(outcome: Outcome, source: Source) derives Labels

object Definitions:
  val processed   = Counter("processor_items", "Processed items").labelled[ByOutcome]
  val received    = Counter("processor_received", "Received items")
  val processTime = Histogram.seconds("processor_process_seconds", "Processing of one item", Buckets.Normal).labelled[ByOutcome]
  val cacheSize   = Gauge("processor_cache_entries", "Entries in the cache")
  val payload     = Histogram.bytes("processor_payload_bytes", "Size of a payload", Buckets(512, 4096, 65536))

final class DashboardSpec extends AnyWordSpec, Matchers:
  private val F = Query.Filter

  private def exprs(panel: Panel): List[(String, Option[String])] =
    panel.targets.fold(error => fail(error.message), _.map(target => target.expr -> target.legend))

  private def dashboard(sections: Section*): Dashboard =
    Dashboard("processor", "Line / processor", "line", ServiceFilter.regex("([a-z]+-)?processor.*"))(sections*)

  private def panelsOf(json: Json): List[Json] =
    json.field("panels") match
      case Some(Json.Arr(panels)) => panels
      case other                  => fail(s"no panels in $other")

  private def int(json: Json, path: String*): Int =
    path.foldLeft(Option(json))((acc, key) => acc.flatMap(_.field(key))) match
      case Some(Json.Num(value)) => value.toInt
      case other                 => fail(s"no number at ${path.mkString(".")}: $other")

  private def string(json: Json, path: String*): String =
    path.foldLeft(Option(json))((acc, key) => acc.flatMap(_.field(key))) match
      case Some(Json.Str(value)) => value
      case other                 => fail(s"no string at ${path.mkString(".")}: $other")

  "a rate panel" should {
    "sum the counter's rate, grouped by the labels it is asked for" in {
      val panel = Panel.rate(Definitions.processed)

      (exprs(panel), exprs(panel.by(_.outcome)), exprs(panel.byAll), panel.title, panel.unit) shouldBe (
        List(s"sum(rate(processor_items_total{$F}[$$__rate_interval]))"                       -> Some("processor_items")),
        List(s"sum by (outcome) (rate(processor_items_total{$F}[$$__rate_interval]))"         -> Some("{{outcome}}")),
        List(s"sum by (outcome, source) (rate(processor_items_total{$F}[$$__rate_interval]))" -> Some("{{outcome}} {{source}}")),
        "Processed items, /s",
        "ops",
      )
    }

    "refuse a label the counter does not have, and not compile with anything but a field" in {
      (
        Panel.rate(Definitions.processed).byLabel("provider").targets,
        typeCheckErrors("Panel.rate(Definitions.processed).by(_.provider)").nonEmpty,
        typeCheckErrors("Panel.rate(Definitions.processed).by(labels => labels.outcome.ordinal)").map(_.message),
      ) shouldBe (
        Left(GrafanaError.UnknownLabel("Processed items, /s", "provider", List("outcome", "source"))),
        true,
        List("Select one label field of the metric, as in _.outcome"),
      )
    }
  }

  "a latency panel" should {
    "draw p50, p95 and p99 from the buckets, grouped by le and the labels asked for" in {
      exprs(Panel.latency(Definitions.processTime).by(_.source)) shouldBe List(0.5 -> "p50", 0.95 -> "p95", 0.99 -> "p99").map { (quantile, tag) =>
        s"histogram_quantile($quantile, sum by (le, source) (rate(processor_process_seconds_bucket{$F}[$$__rate_interval])))" -> Some(s"$tag {{source}}")
      }
    }

    "take its unit from the histogram" in {
      (Panel.latency(Definitions.processTime).unit, Panel.latency(Definitions.payload).unit, Panel.average(Definitions.payload).unit) shouldBe ("s", "bytes", "bytes")
    }
  }

  "a gauge panel" should {
    "show each pod, or aggregate them" in {
      (
        exprs(Panel.gauge(Definitions.cacheSize)),
        exprs(Panel.gauge(Definitions.cacheSize, Pods.Max)),
        exprs(Panel.gauge(Definitions.cacheSize, Pods.Sum)),
      ) shouldBe (
        List(s"sum by (pod) (processor_cache_entries{$F})" -> Some("{{pod}}")),
        List(s"max(processor_cache_entries{$F})"           -> Some("processor_cache_entries")),
        List(s"sum(processor_cache_entries{$F})"           -> Some("processor_cache_entries")),
      )
    }
  }

  "a dashboard" should {
    "lay out stats six to a row, then a row header and panels two to a line, with unique ids" in {
      val json = dashboard(
        Stats(Stat.podsUp, Stat.rate(Definitions.received), Stat.p95(Definitions.processTime), Stat.gauge(Definitions.cacheSize), Stat.podsUp, Stat.podsUp, Stat.podsUp),
        Row("Processing")(Panel.rate(Definitions.processed), Panel.latency(Definitions.processTime), Panel.gauge(Definitions.cacheSize)),
        Rows.jvm,
      ).json.fold(error => fail(error.message), identity)

      val panels = panelsOf(json)
      val layout = panels.map(panel =>
        (int(panel, "id"), string(panel, "type"), int(panel, "gridPos", "x"), int(panel, "gridPos", "y"), int(panel, "gridPos", "w"), int(panel, "gridPos", "h"))
      )

      val cells = layout.flatMap((_, _, x, y, w, h) => for column <- x until x + w; line <- y until y + h yield (column, line))

      (layout.take(12), layout.map(_._1).distinct.size == layout.size, cells.distinct.size == cells.size, layout.forall((_, _, x, _, w, _) => x + w <= 24)) shouldBe (
        List(
          (1, "stat", 0, 0, 4, 4),
          (2, "stat", 4, 0, 4, 4),
          (3, "stat", 8, 0, 4, 4),
          (4, "stat", 12, 0, 4, 4),
          (5, "stat", 16, 0, 4, 4),
          (6, "stat", 20, 0, 4, 4),
          (7, "stat", 0, 4, 4, 4),
          (8, "row", 0, 8, 24, 1),
          (9, "timeseries", 0, 9, 12, 8),
          (10, "timeseries", 12, 9, 12, 8),
          (11, "timeseries", 0, 17, 12, 8),
          (12, "row", 0, 25, 24, 1),
        ),
        true,
        true,
        true,
      )
    }

    "carry the header and variables the guide asks for, with the service filter in both" in {
      val json = dashboard(Stats(Stat.podsUp)).json.fold(error => fail(error.message), identity)

      val variables = json.field("templating").flatMap(_.field("list")) match
        case Some(Json.Arr(list)) => list
        case other                => fail(s"no variables: $other")

      (
        string(json, "uid"),
        json.field("__inputs"),
        json.field("tags"),
        variables.map(string(_, "name")),
        string(variables(2), "definition"),
        string(variables(2), "allValue"),
        string(variables(3), "allValue"),
        string(panelsOf(json).head, "datasource", "uid"),
      ) shouldBe (
        "processor",
        None,
        Some(Json.arr(Json.str("line"))),
        List("datasource", "namespace", "service", "pod"),
        """label_values(up{namespace="$namespace", service=~"([a-z]+-)?processor.*"},service)""",
        "([a-z]+-)?processor.*",
        ".*",
        "${datasource}",
      )
    }

    "refuse a query that does not filter by namespace, service and pod, or uses a fixed rate window" in {
      val unfiltered = Panel.custom("Raw", "short", "A raw query")(Target("sum(processor_cache_entries)", Set("processor_cache_entries")))
      val fixed      = Panel.custom("Fixed", "ops", "A fixed window")(Target(s"sum(rate(processor_items_total{$F}[1m]))", Set("processor_items_total")))

      (dashboard(Row("Raw")(unfiltered)).render, dashboard(Row("Fixed")(fixed)).render) shouldBe (
        Left(GrafanaError.UnfilteredQuery("Raw", "sum(processor_cache_entries)")),
        Left(GrafanaError.FixedRateWindow("Fixed", s"sum(rate(processor_items_total{$F}[1m]))")),
      )
    }

    "list every series its queries read" in {
      dashboard(
        Stats(Stat.podsUp, Stat.rate(Definitions.received)),
        Row("Processing")(Panel.latency(Definitions.processTime), Panel.average(Definitions.payload))
      ).reads shouldBe Set(
        "processor_received_total",
        "processor_process_seconds_bucket",
        "processor_payload_bytes_sum",
        "processor_payload_bytes_count",
      )
    }

    "render the same JSON every time, byte for byte" in {
      val rendered = dashboard(Stats(Stat.podsUp), Row("Processing")(Panel.rate(Definitions.processed).by(_.outcome)), Rows.catsEffect, Rows.jvm).render
      val expected = String(getClass.getResourceAsStream("/processor.json").readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)

      rendered shouldBe Right(expected)
    }
  }

  "the shared rows" should {
    "query the runtime and the JVM exactly as the hand-written merger and descriptions dashboards do" in {
      val cats = Rows.catsEffect.panels.map(panel => panel.title -> exprs(panel))
      val jvm  = Rows.jvm.panels.map(panel => panel.title -> exprs(panel).map(_._1))

      (cats.map(_._1), cats.head._2, cats(2)._2.map(_._2), jvm) shouldBe (
        List("CPU starvation, /s", "Clock drift", "Compute threads", "Fibers", "Blocking and respawned workers, /s", "Outstanding timers"),
        List(s"sum by (pod) (rate(cats_effect_cpu_starvation_total{$F}[$$__rate_interval]))" -> Some("{{pod}}")),
        List(Some("{{pod}} workers"), Some("{{pod}} active"), Some("{{pod}} blocked")),
        List(
          "Heap"                                                                             -> List(s"""sum by (pod) (jvm_memory_used_bytes{$F, area="heap"})""", s"""sum by (pod) (jvm_memory_max_bytes{$F, area="heap"})"""),
          "Resident memory and non-heap"                                                     -> List(s"sum by (pod) (process_resident_memory_bytes{$F})", s"""sum by (pod) (jvm_memory_used_bytes{$F, area="nonheap"})"""),
          "CPU, cores"                                                                       -> List(s"sum by (pod) (rate(process_cpu_seconds_total{$F}[$$__rate_interval]))"),
          "GC time, share of wall time"                                                      -> List(s"sum by (pod, gc) (rate(jvm_gc_collection_seconds_sum{$F}[$$__rate_interval]))"),
          "Threads"                                                                          -> List(s"sum by (pod) (jvm_threads_current{$F})", s"sum by (pod) (jvm_threads_daemon{$F})"),
          "Uptime"                                                                           -> List(s"time() - max by (pod) (process_start_time_seconds{$F})"),
        ),
      )
    }
  }

  "the README table" should {
    "list each metric under its exposed name, with its type, unit and label values" in {
      MetricsDoc.markdown(Definitions.processed, Definitions.processTime, Definitions.cacheSize, Definitions.payload) shouldBe
        """| Metric | Type | Labels | What |
          !|---|---|---|---|
          !| `processor_items_total` | counter | `outcome`: `ok`, `skipped`, `error`; `source`: `kafka`, `rabbit` | Processed items |
          !| `processor_process_seconds` | histogram, seconds | `outcome`: `ok`, `skipped`, `error`; `source`: `kafka`, `rabbit` | Processing of one item |
          !| `processor_cache_entries` | gauge |  | Entries in the cache |
          !| `processor_payload_bytes` | histogram, bytes |  | Size of a payload |
          !""".stripMargin('!')
    }
  }

  "JSON" should {
    "escape strings and print numbers without trailing zeros" in {
      Json
        .obj("text" -> Json.str("a \"quote\"\n\\ and \u0001"), "whole" -> Json.num(2.0), "part" -> Json.num(0.25), "list" -> Json.arr(), "none" -> Json.Null)
        .render shouldBe
        """{
          !  "text": "a \"quote\"\n\\ and \u0001",
          !  "whole": 2,
          !  "part": 0.25,
          !  "list": [],
          !  "none": null
          !}""".stripMargin('!')
    }
  }
