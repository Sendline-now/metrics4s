package metrics4s.runtime.cats

import scala.concurrent.duration.Duration
import scala.jdk.CollectionConverters.*

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import cats.effect.IO
import cats.effect.unsafe.{IORuntime, IORuntimeConfig}
import io.prometheus.metrics.model.snapshots.{CounterSnapshot, GaugeSnapshot, HistogramSnapshot}

import metrics4s.Metrics
import metrics4s.grafana.{Dashboard, Rows, ServiceFilter}
import metrics4s.prometheus.PrometheusMetrics
import metrics4s.testkit.MetricsTestkit

import scala.concurrent.duration.given
import metrics4s.runtime.cats.CatsInstances.given

final class CatsRuntimeMetricsSpec extends AnyWordSpec, Matchers:
  private val runtime: IORuntime =
    IORuntime.builder().setConfig(IORuntimeConfig().copy(cpuStarvationCheckInterval = 20.millis, cpuStarvationCheckInitialDelay = Duration.Zero)).build()

  private val counters = Set(
    "cats_effect_cpu_starvation",
    "cats_effect_worker_idle_seconds",
    "cats_effect_worker_parked",
    "cats_effect_worker_polled",
    "cats_effect_worker_blocking",
    "cats_effect_worker_respawn",
    "cats_effect_worker_fibers_enqueued",
    "cats_effect_worker_fibers_spilled",
    "cats_effect_worker_steals",
    "cats_effect_worker_fibers_stolen",
    "cats_effect_timers_scheduled",
    "cats_effect_timers_executed",
    "cats_effect_timers_canceled",
  )

  private val gauges = Set(
    "cats_effect_compute_threads",
    "cats_effect_compute_threads_active",
    "cats_effect_compute_threads_searching",
    "cats_effect_compute_threads_blocked",
    "cats_effect_fibers",
    "cats_effect_worker_queue_fibers",
    "cats_effect_timers_outstanding",
  )

  private val histograms = Set("cats_effect_cpu_starvation_clock_drift_seconds")

  private def typesOf(testkit: MetricsTestkit): Map[String, String] =
    testkit.registry
      .scrape()
      .stream()
      .iterator()
      .asScala
      .toList
      .collect {
        case snapshot: CounterSnapshot   => snapshot.getMetadata.getName -> "counter"
        case snapshot: GaugeSnapshot     => snapshot.getMetadata.getName -> "gauge"
        case snapshot: HistogramSnapshot => snapshot.getMetadata.getName -> "histogram"
      }
      .toMap

  "the cats-effect runtime metrics" should {
    "export the families commons exports, with the same types" in {
      val testkit       = MetricsTestkit()
      given Metrics[IO] = testkit.metrics[IO]

      val types = CatsRuntimeMetrics.resource[IO](runtime).use(_ => IO(typesOf(testkit))).unsafeRunSync()(using runtime)

      types.removed("metrics4s_callback_errors") shouldBe (counters.map(_ -> "counter") ++ gauges.map(_ -> "gauge") ++ histograms.map(_ -> "histogram")).toMap
    }

    "report the compute pool as the runtime sees it, by worker" in {
      val testkit       = MetricsTestkit()
      given Metrics[IO] = testkit.metrics[IO]
      val pool          = runtime.metrics.workStealingThreadPool.getOrElse(fail("the runtime has no work-stealing pool"))
      val workers       = pool.workerThreadCount()

      val (threads, workerLabels, fiberStates, timerLabels) =
        CatsRuntimeMetrics
          .resource[IO](runtime)
          .use { _ =>
            IO {
              (
                testkit.value(CatsRuntimeMetrics.Definitions.computeThreads),
                testkit.labelValues(CatsRuntimeMetrics.Definitions.workerParked),
                testkit.labelValues(CatsRuntimeMetrics.Definitions.fibers),
                testkit.labelValues(CatsRuntimeMetrics.Definitions.timersScheduled),
              )
            }
          }
          .unsafeRunSync()(using runtime)

      (threads, workerLabels, fiberStates, timerLabels) shouldBe (
        Some(workers.toDouble),
        (0 until workers).map(index => List(index.toString)).toSet,
        Set(List("queued"), List("suspended")),
        (0 until workers).map(index => List(index.toString)).toSet,
      )
    }

    "sample the clock drift with the interval of the starvation check" in {
      val testkit       = MetricsTestkit()
      given Metrics[IO] = testkit.metrics[IO]

      val samples = CatsRuntimeMetrics
        .resource[IO](runtime)
        .use(_ => IO.sleep(500.millis) >> IO(testkit.histogram(CatsRuntimeMetrics.Definitions.clockDrift).map(_.count)))
        .unsafeRunSync()(using runtime)

      samples.exists(count => count >= 5 && count <= 30) shouldBe true
    }

    "export every series the shared dashboard rows read" in {
      val testkit       = MetricsTestkit()
      given Metrics[IO] = testkit.metrics[IO]

      val dashboard = Dashboard("processor", "Line / processor", "line", ServiceFilter.regex("processor"))(Rows.catsEffect, Rows.jvm)

      val missing = (PrometheusMetrics.jvm[IO](testkit.registry) >> CatsRuntimeMetrics.resource[IO](runtime).use(_ => IO(testkit.missingSeries(dashboard))))
        .unsafeRunSync()(using runtime)

      missing shouldBe (if System.getProperty("os.name").toLowerCase.contains("linux") then Set.empty else Set("process_resident_memory_bytes"))
    }

    "leave nothing behind once released" in {
      val testkit       = MetricsTestkit()
      given Metrics[IO] = testkit.metrics[IO]

      CatsRuntimeMetrics.resource[IO](runtime).use_.unsafeRunSync()(using runtime)

      testkit.families.filter(_.startsWith("cats_effect")) shouldBe Set.empty
    }
  }
