package metrics4s.testkit

import java.net.URI
import java.net.{BindException, ServerSocket}
import java.net.http.{HttpClient, HttpRequest, HttpResponse}

import scala.jdk.CollectionConverters.*
import scala.util.Try

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import io.prometheus.metrics.model.snapshots.Unit as PrometheusUnit

import metrics4s.*
import metrics4s.prometheus.{ExporterException, PrometheusBackend, PrometheusExporter, PrometheusMetrics}

import scala.concurrent.duration.given
import metrics4s.Eval.given

final class PrometheusBackendSpec extends AnyWordSpec, Matchers:
  private val processed   = Counter("processor_items", "Processed items by outcome").labelled[Outcome]
  private val received    = Counter("processor_received", "Received items")
  private val processTime = Histogram.seconds("processor_process_seconds", "Processing of one item", Buckets(0.1, 0.5, 1))
  private val providers   = Gauge("processor_provider_entries", "Entries by provider").labelled[Provider]
  private val cacheSize   = Gauge("processor_cache_entries", "Entries in the cache")
  private val release     = Info("processor_build", "The build that runs").labelled[(provider: Provider, outcome: Outcome)]

  private def failure(fa: Eval[?]): Option[MetricError] =
    Eval.attempt(fa).left.toOption.collect { case error: MetricException => error.error }

  "the Prometheus backend" should {
    "export every series of an enumerated domain from the start, and the counter with _total" in {
      val testkit = MetricsTestkit()
      val counter = Eval.run(testkit.metrics[Eval].register(processed))
      val before  = testkit.series

      Eval.run(counter.inc(Outcome.Ok))
      Eval.run(counter.add(Outcome.Error, 4))

      (
        before.filter(_._1.startsWith("processor_items")),
        testkit.value(processed, Outcome.Ok),
        testkit.value(processed, Outcome.Error),
        testkit.series.get("""processor_items_total{outcome="error"}"""),
      ) shouldBe (
        Map(
          """processor_items_total{outcome="ok"}"""      -> 0.0,
          """processor_items_total{outcome="skipped"}""" -> 0.0,
          """processor_items_total{outcome="error"}"""   -> 0.0,
        ),
        Some(1.0),
        Some(4.0),
        Some(4.0),
      )
    }

    "keep histograms classic, in seconds, with cumulative buckets" in {
      val testkit = MetricsTestkit()
      val time    = Eval.run(testkit.metrics[Eval].register(processTime))

      Eval.run(time.observe(50.millis))
      Eval.run(time.observe(300.millis))
      Eval.run(time.observe(2.seconds))

      val snapshot = testkit.registry.scrape().stream().iterator().asScala.find(_.getMetadata.getName == "processor_process_seconds")

      (
        testkit.histogram(processTime),
        snapshot.map(_.getMetadata.getUnit),
        testkit.text.linesIterator.filter(_.startsWith("processor_process_seconds_bucket")).toList,
      ) shouldBe (
        Some(HistogramValue(3, 2.35, List(0.1 -> 1L, 0.5 -> 2L, 1.0 -> 2L, Double.PositiveInfinity -> 3L))),
        Some(PrometheusUnit.SECONDS),
        List(
          """processor_process_seconds_bucket{le="0.1"} 1""",
          """processor_process_seconds_bucket{le="0.5"} 2""",
          """processor_process_seconds_bucket{le="1.0"} 2""",
          """processor_process_seconds_bucket{le="+Inf"} 3""",
        ),
      )
    }

    "remove what a full recalculation of a bounded gauge leaves out" in {
      val testkit = MetricsTestkit()
      val gauge   = Eval.run(testkit.metrics[Eval].register(providers))

      Eval.run(gauge.replace(Map(Provider("cns") -> 1, Provider("lsports") -> 2)))
      Eval.run(gauge.replace(Map(Provider("lsports") -> 5)))

      (testkit.labelValues(providers), testkit.value(providers, Provider("lsports")), testkit.value(providers, Provider("cns"))) shouldBe (
        Set(List("lsports")),
        Some(5.0),
        None,
      )
    }

    "remove from a bounded gauge what was set one series at a time, whatever the order of its labels" in {
      val testkit  = MetricsTestkit()
      val byOrigin = Gauge("processor_origin_entries", "Entries by zone and provider").labelled[(zone: Provider, provider: Provider)]
      val gauge    = Eval.run(testkit.metrics[Eval].register(byOrigin))

      Eval.run(gauge.set((zone = Provider("eu"), provider = Provider("cns")), 1))
      Eval.run(gauge.set((zone = Provider("us"), provider = Provider("lsports")), 2))
      Eval.run(gauge.replace(Map((zone = Provider("us"), provider = Provider("lsports")) -> 7)))

      (testkit.labelValues(byOrigin), testkit.value(byOrigin, (zone = Provider("us"), provider = Provider("lsports")))) shouldBe (
        Set(List("us", "lsports")),
        Some(7.0),
      )
    }

    "refuse a name registered twice, and a name taken by a metric of another type" in {
      val metrics = MetricsTestkit().metrics[Eval]
      val _       = Eval.run(metrics.register(received))

      (
        failure(metrics.register(received)),
        failure(metrics.register(Gauge("processor_received", "Received items"))).map(_.getClass.getSimpleName),
      ) shouldBe (
        Some(MetricError.AlreadyRegistered(received.name)),
        Some("Rejected"),
      )
    }

    "expose an info metric as one series named with _info, its labels replaced by the next set" in {
      val testkit = MetricsTestkit()
      val build   = Eval.run(testkit.metrics[Eval].register(release))
      val before  = testkit.series.keySet.filter(_.startsWith("processor_build"))

      Eval.run(build.set((provider = Provider("cns"), outcome = Outcome.Ok)))
      Eval.run(build.set((provider = Provider("lsports"), outcome = Outcome.Ok)))

      (before, testkit.series.filter(_._1.startsWith("processor_build")), testkit.labelValues(release)) shouldBe (
        Set.empty,
        Map("""processor_build_info{outcome="ok",provider="lsports"}""" -> 1.0),
        Set(List("lsports", "ok")),
      )
    }

    "read back what an info metric says, and nothing before it is set" in {
      val testkit = MetricsTestkit()
      val build   = Eval.run(testkit.metrics[Eval].register(release))
      val before  = testkit.info(release)

      Eval.run(build.set((provider = Provider("cns"), outcome = Outcome.Ok)))

      (before, testkit.info(release)) shouldBe (None, Some(Map("provider" -> "cns", "outcome" -> "ok")))
    }

    "expose a ratio gauge with its unit" in {
      val testkit = MetricsTestkit()
      val usage   = Gauge.ratio("cache_usage_ratio", "Used part of the cache")
      val gauge   = Eval.run(testkit.metrics[Eval].register(usage))

      Eval.run(gauge.set(0.25))

      val snapshot = testkit.registry.scrape().stream().iterator().asScala.find(_.getMetadata.getName == "cache_usage_ratio")

      (testkit.value(usage), snapshot.map(_.getMetadata.getUnit), testkit.series.get("cache_usage_ratio")) shouldBe (Some(0.25), Some(PrometheusUnit.RATIO), Some(0.25))
    }

    "drop a family from the scrape once it is unregistered" in {
      val testkit = MetricsTestkit()
      val gauge   = Eval.run(testkit.metrics[Eval].register(cacheSize))
      val before  = testkit.families

      Eval.run(gauge.unregister)

      (before("processor_cache_entries"), testkit.families("processor_cache_entries"), Eval.attempt(testkit.metrics[Eval].register(cacheSize)).isRight) shouldBe (
        true,
        false,
        true,
      )
    }
  }

  "a callback on the Prometheus backend" should {
    "be read on every scrape, with the descriptors the registry checks" in {
      val testkit = MetricsTestkit()
      var entries = 3.0

      val (snapshots, conflict) = Eval.run(
        testkit
          .metrics[Eval]
          .observe(cacheSize)(_.report(entries))
          .use { _ =>
            summon[Effect[Eval]].delay {
              val first = testkit.value(cacheSize)
              entries = 8.0
              ((first, testkit.value(cacheSize)), failure(testkit.metrics[Eval].register(cacheSize)))
            }
          }
      )

      (snapshots, conflict, testkit.families("processor_cache_entries")) shouldBe ((Some(3.0), Some(8.0)), Some(MetricError.AlreadyRegistered(cacheSize.name)), false)
    }

    "keep the scrape alive when it fails, and count every failed scrape" in {
      val testkit = MetricsTestkit()
      val broken  = Gauge("processor_broken_entries", "Entries that cannot be read")
      val errors  = s"""${PrometheusBackend.CallbackErrors}_total{metric="processor_broken_entries"}"""

      val (first, scrapes, after) = Eval.run(
        testkit
          .metrics[Eval]
          .observe(broken)(_ => throw IllegalStateException("cannot read"))
          .use { _ =>
            summon[Effect[Eval]].delay {
              val first   = testkit.series.get(errors)
              val scrapes = List(Try(testkit.text), Try(testkit.text))
              (first, scrapes, testkit.series.get(errors))
            }
          }
      )

      (first, scrapes.map(_.isSuccess), scrapes.exists(_.toOption.exists(_.contains("processor_broken_entries "))), after) shouldBe (
        Some(0.0),
        List(true, true),
        false,
        Some(3.0),
      )
    }
  }

  "the exporter" should {
    "serve the registry over HTTP until it is released" in {
      val testkit = MetricsTestkit()
      val client  = HttpClient.newHttpClient()
      val _       = Eval.run(testkit.metrics[Eval].register(received))

      def scrape(port: Int): Try[String] =
        Try(client.send(HttpRequest.newBuilder(URI.create(s"http://localhost:$port/metrics")).build(), HttpResponse.BodyHandlers.ofString()).body())

      val (port, served) =
        Eval.run(PrometheusExporter.allocated[Eval](0, testkit.registry).use(exporter => summon[Effect[Eval]].delay((exporter.port, scrape(exporter.port)))))

      (served.map(_.contains("processor_received_total 0.0")), scrape(port).isFailure) shouldBe (Try(true), true)
    }

    "fail with the port it cannot listen on, the bind failure kept as the cause" in {
      val taken = ServerSocket(0)
      val port  = taken.getLocalPort

      val refused =
        try Eval.attempt(PrometheusExporter.allocated[Eval](port, MetricsTestkit().registry).use(_ => summon[Effect[Eval]].unit))
        finally taken.close()

      val named = refused.left.toOption.collect { case error: ExporterException =>
        (error.host, error.port, error.getMessage.startsWith(s"The Prometheus exporter cannot listen on port $port: "), error.getCause.isInstanceOf[BindException])
      }

      named shouldBe Some((None, port, true, true))
    }

    "listen only on the interface it is given, and name it when it cannot" in {
      val testkit = MetricsTestkit()
      val client  = HttpClient.newHttpClient()
      val _       = Eval.run(testkit.metrics[Eval].register(received))

      def scrape(host: String, port: Int): Try[Int] =
        Try(client.send(HttpRequest.newBuilder(URI.create(s"http://$host:$port/metrics")).build(), HttpResponse.BodyHandlers.ofString()).statusCode())

      val served =
        Eval.run(PrometheusExporter.allocated[Eval]("127.0.0.1", 0, testkit.registry).use(exporter => summon[Effect[Eval]].delay(scrape("127.0.0.1", exporter.port))))

      val refused = Eval.attempt(PrometheusExporter.allocated[Eval]("192.0.2.1", 0, testkit.registry).use(_ => summon[Effect[Eval]].unit))

      val named = refused.left.toOption.collect { case error: ExporterException =>
        (error.host, error.getMessage.startsWith("The Prometheus exporter cannot listen on 192.0.2.1:0: "))
      }

      (served, named) shouldBe (Try(200), Some((Some("192.0.2.1"), true)))
    }
  }

  "JVM metrics" should {
    "be the client's own, under the names the dashboards read" in {
      val testkit = MetricsTestkit()

      Eval.run(PrometheusMetrics.jvm[Eval](testkit.registry))
      Eval.run(PrometheusMetrics.jvm[Eval](testkit.registry))

      Set("jvm_memory_used_bytes", "jvm_threads_current", "process_cpu_seconds", "jvm_gc_collection_seconds").subsetOf(testkit.families) shouldBe true
    }
  }
