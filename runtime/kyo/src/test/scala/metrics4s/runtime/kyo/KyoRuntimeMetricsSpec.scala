package metrics4s.runtime.kyo

import scala.jdk.CollectionConverters.*

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import io.prometheus.metrics.model.snapshots.{CounterSnapshot, GaugeSnapshot}
import kyo.{<, Abort, Async, Duration, KyoApp, Scope, Sync}
import kyo.scheduler.Scheduler

import metrics4s.Metrics
import metrics4s.grafana.{Dashboard, Rows, ServiceFilter}
import metrics4s.testkit.MetricsTestkit

import kyo.AllowUnsafe.embrace.danger
import metrics4s.runtime.kyo.KyoInstances.given

final class KyoRuntimeMetricsSpec extends AnyWordSpec, Matchers:
  private def run[A](task: A < (Async & Scope & Abort[Throwable])): A = KyoApp.Unsafe.runAndBlock(Duration.Infinity)(task).getOrThrow

  private def typesOf(testkit: MetricsTestkit): Map[String, String] =
    testkit.registry
      .scrape()
      .stream()
      .iterator()
      .asScala
      .toList
      .collect {
        case snapshot: CounterSnapshot => snapshot.getMetadata.getName -> "counter"
        case snapshot: GaugeSnapshot   => snapshot.getMetadata.getName -> "gauge"
      }
      .toMap

  "the Kyo runtime metrics" should {
    "export the families commons exports, with the same types" in {
      val testkit        = MetricsTestkit()
      given Metrics[KIO] = testkit.metrics[KIO]

      val types = run(Scope.run(KyoRuntimeMetrics.scoped.andThen(Sync.defer(typesOf(testkit)))))

      types.removed("metrics4s_callback_errors") shouldBe Map(
        "kyo_scheduler_workers"             -> "gauge",
        "kyo_scheduler_workers_allocated"   -> "gauge",
        "kyo_scheduler_workers_blocked"     -> "gauge",
        "kyo_scheduler_workers_stalled"     -> "gauge",
        "kyo_scheduler_load"                -> "gauge",
        "kyo_scheduler_threads"             -> "gauge",
        "kyo_scheduler_admission_percent"   -> "gauge",
        "kyo_scheduler_admission_tasks"     -> "counter",
        "kyo_scheduler_probe_delay_seconds" -> "gauge",
        "kyo_worker_load"                   -> "gauge",
        "kyo_worker_executions"             -> "counter",
        "kyo_worker_completions"            -> "counter",
        "kyo_worker_preemptions"            -> "counter",
        "kyo_worker_stolen_tasks"           -> "counter",
        "kyo_worker_lost_tasks"             -> "counter",
        "kyo_worker_mounts"                 -> "counter",
      )
    }

    "report the scheduler as its status describes it, by worker" in {
      val testkit        = MetricsTestkit()
      given Metrics[KIO] = testkit.metrics[KIO]
      import KyoRuntimeMetrics.Definitions

      val (workers, allocated, workerLabels, executions) = run(
        Scope.run(
          KyoRuntimeMetrics.scoped
            .andThen(
              Async.collectAllDiscard(List.fill(200)(Async.sleep(Duration.fromScala(scala.concurrent.duration.FiniteDuration(1, "ms")))), concurrency = 50)
            )
            .andThen(
              Sync.defer(
                (
                  testkit.value(Definitions.workers),
                  Scheduler.get.status().allocatedWorkers,
                  testkit.labelValues(Definitions.workerExecutions),
                  testkit
                    .labelValues(Definitions.workerExecutions)
                    .toList
                    .flatMap(labels => testkit.value(Definitions.workerExecutions, KyoRuntimeMetrics.Worker(labels.head.toInt)))
                    .sum,
                )
              )
            )
        )
      )

      (workers.exists(_ >= 1), workerLabels.size == allocated, executions > 0) shouldBe (true, true, true)
    }

    "export every series the shared dashboard row reads" in {
      val testkit        = MetricsTestkit()
      given Metrics[KIO] = testkit.metrics[KIO]

      val dashboard = Dashboard("processor", "Line / processor", "line", ServiceFilter.regex("processor"))(Rows.kyo)

      run(
        Scope.run(
          KyoRuntimeMetrics.scoped
            .andThen(Async.sleep(Duration.fromScala(scala.concurrent.duration.FiniteDuration(200, "ms"))))
            .andThen(Sync.defer(testkit.missingSeries(dashboard)))
        )
      ) shouldBe Set.empty
    }

    "leave nothing behind once its scope closes" in {
      val testkit        = MetricsTestkit()
      given Metrics[KIO] = testkit.metrics[KIO]

      run(Scope.run(KyoRuntimeMetrics.scoped))

      testkit.families.filter(_.startsWith("kyo_")) shouldBe Set.empty
    }
  }
