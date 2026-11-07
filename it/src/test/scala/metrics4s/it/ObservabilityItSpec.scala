package metrics4s.it

import scala.concurrent.duration.FiniteDuration

import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import cats.effect.{IO, Resource}
import io.prometheus.metrics.model.registry.PrometheusRegistry
import org.testcontainers.containers.Network

import metrics4s.*
import metrics4s.runtime.cats.CatsRuntimeMetrics
import metrics4s.grafana.*
import metrics4s.prometheus.{PrometheusExporter, PrometheusMetrics}

import cats.effect.unsafe.implicits.global
import scala.concurrent.duration.given
import metrics4s.runtime.cats.CatsInstances.given
import metrics4s.runtime.cats.syntax.given

enum Outcome derives LabelValue:
  case Ok, Skipped, Error

enum Source derives LabelValue:
  case Kafka, Rabbit

final case class ByOutcome(outcome: Outcome, source: Source) derives Labels

object ProcessorMetrics:
  val processed   = Counter("processor_items", "Processed items").labelled[ByOutcome]
  val processTime = Histogram.seconds("processor_process_seconds", "Processing of one item", Buckets.Normal).labelled[(outcome: Outcome)]
  val cacheSize   = Gauge("processor_cache_entries", "Entries in the cache")

  val dashboard: Dashboard = Dashboard("line-processor", "Line / processor", "line", ServiceFilter.regex("([a-z]+-)?processor.*"))(
    Stats(
      Stat.podsUp,
      Stat.rate(processed),
      Stat.p95(processTime),
      Stat.gauge(cacheSize),
      Stat.quantile(processTime, 0.99),
      Stat.share(processed)(_.outcome === Outcome.Error),
    ),
    Row("Processing")(
      Panel.rate(processed).by(_.outcome),
      Panel.rate(processed).byAll,
      Panel.latency(processTime).byAll,
      Panel.average(processTime),
      Panel.gauge(cacheSize),
    ),
    Row("Filtered")(
      Panel.rate(processed).by(_.source).where(_.outcome.in(Outcome.Ok, Outcome.Error)),
      Panel.quantile(processTime, 0.95).by(_.outcome).where(_.outcome =!= Outcome.Skipped),
      Panel.share(processed)(_.outcome === Outcome.Error).by(_.source),
    ),
    Rows.catsEffect,
    Rows.jvm,
  )

final case class Processing(
    processed: CounterOf[IO, Long, ByOutcome],
    processTime: HistogramOf[IO, FiniteDuration, (outcome: Outcome)],
    cacheSize: GaugeOf[IO, Double, NoLabels],
)

final class ObservabilityItSpec extends AnyWordSpec, Matchers, BeforeAndAfterAll:
  import ProcessorMetrics.*

  private val registry = PrometheusRegistry()
  private val network  = Network.newNetwork()

  given Metrics[IO] = PrometheusMetrics[IO](registry)

  private var release: IO[Unit]               = IO.unit
  private var prometheus: PrometheusContainer = null
  private var grafana: GrafanaContainer       = null
  private val rateInterval                    = "30s"
  private val onLinux                         = System.getProperty("os.name").toLowerCase.contains("linux")

  private def traffic(processing: Processing): IO[Unit] =
    val once =
      processing.processed.inc(ByOutcome(Outcome.Ok, Source.Kafka)) >>
        processing.processed.add(ByOutcome(Outcome.Error, Source.Rabbit), 2) >>
        processing.processTime.observe((outcome = Outcome.Ok), 120.millis) >>
        processing.processTime.observe((outcome = Outcome.Error), 2.seconds) >>
        processing.cacheSize.set(42)
    (once >> IO.sleep(100.millis)).foreverM

  override def beforeAll(): Unit =
    val stack =
      for
        exporter   <- PrometheusExporter.allocated[IO](0, registry).toResource
        _          <- Resource.eval(PrometheusMetrics.jvm[IO](registry))
        _          <- CatsRuntimeMetrics.resource[IO]()
        processing <- Resource.eval(Metrics[IO].registerAs[Processing]((processed = processed, processTime = processTime, cacheSize = cacheSize)))
        _          <- traffic(processing).background
      yield exporter.port

    val (port, close) = stack.allocated.unsafeRunSync()
    release = close

    Exposed.port(port)
    prometheus = PrometheusContainer(network, port)
    grafana = GrafanaContainer(network)
    prometheus.start()
    grafana.start()

    val datasource     =
      ujson.Obj("name" -> "Prometheus", "type" -> "prometheus", "uid" -> "prometheus", "url" -> "http://prometheus:9090", "access" -> "proxy", "isDefault" -> true)
    val (status, body) = Http.post(s"${grafana.url}/api/datasources", datasource.render(), grafana = true)
    if status != 200 then throw IllegalStateException(s"Grafana refused the datasource: $status $body")
  end beforeAll

  override def afterAll(): Unit =
    Option(grafana).foreach(_.stop())
    Option(prometheus).foreach(_.stop())
    network.close()
    release.unsafeRunSync()

  private def resultOf(json: ujson.Value): Vector[ujson.Value] = json("data")("result").arr.toVector

  private def boundOf(le: String): Double = if le == "+Inf" then Double.PositiveInfinity else le.toDouble

  private def panels: List[ujson.Value] = ujson.read(ProcessorMetrics.dashboard.render.fold(error => fail(error.message), identity))("panels").arr.toList

  private def throughGrafana(expr: String): Vector[ujson.Value] =
    val substituted =
      expr.replace("$namespace", "it").replace("$service", "line-processor").replace("$pod", "processor-0").replace("$__rate_interval", rateInterval)
    resultOf(Http.query(s"${grafana.url}/api/datasources/proxy/uid/prometheus", substituted, grafana = true))

  "Prometheus scraping the exporter" should {
    "find every combination of an enumerated domain under the name the code defines, the ones never recorded at zero" in {
      val series = Eventually(30.seconds) {
        val result = resultOf(Http.query(prometheus.url, """processor_items_total{namespace="it"}"""))
        Option.when(result.size == 6)(result.map(point => (point("metric")("outcome").str, point("metric")("source").str) -> point("value")(1).str.toDouble).toMap)
      }

      series.map(_.keySet) shouldBe Some((for outcome <- List("ok", "skipped", "error"); source <- List("kafka", "rabbit") yield (outcome, source)).toSet)
      series.flatMap(_.get(("skipped", "kafka"))) shouldBe Some(0.0)
      series.flatMap(_.get(("ok", "kafka"))).exists(_ > 0) shouldBe true
    }

    "see a classic histogram with the defined buckets and the metadata of each family" in {
      val bounds = Eventually(30.seconds) {
        val result = resultOf(Http.query(prometheus.url, """count by (le) (processor_process_seconds_bucket{namespace="it", outcome="ok"})"""))
        Option.when(result.nonEmpty)(result.map(point => boundOf(point("metric")("le").str)).toSet)
      }

      val metadata = Eventually(30.seconds) {
        val data = ujson.read(Http.get(s"${prometheus.url}/api/v1/metadata")._2)("data")
        Option.when(data.obj.contains("processor_process_seconds") && data.obj.contains("processor_items"))(
          (data("processor_process_seconds")(0)("type").str, data("processor_process_seconds")(0)("help").str, data("processor_items")(0)("type").str)
        )
      }

      (bounds, metadata) shouldBe (
        Some((Buckets.Normal.bounds.toList :+ Double.PositiveInfinity).toSet),
        Some(("histogram", "Processing of one item", "counter")),
      )
    }

    "find the service through the labels the dashboard variables read" in {
      val namespaces = Eventually(30.seconds) {
        val body =
          Http.get(s"${prometheus.url}/api/v1/label/namespace/values?match[]=${java.net.URLEncoder.encode("""up{service=~"([a-z]+-)?processor.*"}""", "UTF-8")}")._2
        Option(ujson.read(body)("data").arr.map(_.str).toList).filter(_.nonEmpty)
      }

      namespaces shouldBe Some(List("it"))
    }
  }

  "Grafana" should {
    "import the generated dashboard as it is" in {
      val dashboard      = ujson.read(ProcessorMetrics.dashboard.render.fold(error => fail(error.message), identity))
      val (status, body) = Http.post(s"${grafana.url}/api/dashboards/db", ujson.Obj("dashboard" -> dashboard, "overwrite" -> true).render(), grafana = true)
      val stored         = ujson.read(Http.get(s"${grafana.url}/api/dashboards/uid/line-processor", grafana = true)._2)("dashboard")

      (status, ujson.read(body)("status").str, stored("panels").arr.size, stored("templating")("list").arr.map(_("name").str).toList) shouldBe (
        200,
        "success",
        dashboard("panels").arr.size,
        List("datasource", "namespace", "service", "pod"),
      )
    }

    "answer every query of every panel through its Prometheus datasource" in {
      val targets = panels.flatMap(panel => panel.obj.get("targets").toList.flatMap(_.arr).map(target => panel("title").str -> target("expr").str))

      def empty(): List[(String, String)] = targets.filter((_, expr) => throughGrafana(expr).isEmpty)

      val expected = if onLinux then Nil else targets.filter(_._2.contains("process_resident_memory_bytes"))

      Eventually(60.seconds)(Option(empty()).filter(_ == expected)).getOrElse(empty()) shouldBe expected
    }

    "draw the share a typed filter picks out of a counter, all of each source's errors and none of its successes" in {
      val share = panels.find(panel => panel("title").str == "Processed items, share" && panel("type").str != "stat").map(_("targets")(0)("expr").str)

      val bySource = Eventually(60.seconds) {
        share.map(throughGrafana).map(_.map(point => point("metric")("source").str -> point("value")(1).str.toDouble).toMap).filter(_.size == 2)
      }

      bySource shouldBe Some(Map("kafka" -> 0.0, "rabbit" -> 1.0))
    }
  }
