package metrics4s.runtime.zio

import scala.jdk.CollectionConverters.*

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import io.prometheus.metrics.model.snapshots.{CounterSnapshot, GaugeSnapshot}
import zio.{Runtime, Task, Unsafe, ZIO}

import metrics4s.Metrics
import metrics4s.grafana.{Dashboard, Rows, ServiceFilter}
import metrics4s.testkit.MetricsTestkit

import metrics4s.runtime.zio.ZioInstances.given

final class ZioRuntimeMetricsSpec extends AnyWordSpec, Matchers:
  private def run[A](task: Task[A]): A = Unsafe.unsafe(unsafe ?=> Runtime.default.unsafe.run(task).getOrThrow())

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

  "the ZIO runtime metrics" should {
    "export the families commons exports, with the same types" in {
      val testkit         = MetricsTestkit()
      given Metrics[Task] = testkit.metrics[Task]

      run(ZIO.scoped(ZioRuntimeMetrics.scoped *> ZIO.attempt(typesOf(testkit)))).removed("metrics4s_callback_errors") shouldBe Map(
        "zio_executor_workers"     -> "gauge",
        "zio_executor_concurrency" -> "gauge",
        "zio_executor_queue_size"  -> "gauge",
        "zio_executor_enqueued"    -> "counter",
        "zio_executor_dequeued"    -> "counter",
        "zio_fibers"               -> "gauge",
        "zio_fibers_started"       -> "counter",
        "zio_fibers_ended"         -> "counter",
      )
    }

    "count the fibers started, ended and interrupted inside its scope" in {
      val testkit         = MetricsTestkit()
      given Metrics[Task] = testkit.metrics[Task]
      import ZioRuntimeMetrics.{Definitions, FiberOutcome}

      val (started, succeeded, interrupted, executors) = run(
        ZIO.scoped {
          for
            _      <- ZioRuntimeMetrics.scoped
            before <- ZIO.attempt((testkit.value(Definitions.fibersStarted), testkit.value(Definitions.fibersEnded, FiberOutcome.Success)))
            _      <- ZIO.foreachParDiscard(1 to 100)(_ => ZIO.succeed(()))
            fiber  <- ZIO.never.fork
            _      <- fiber.interrupt
            after  <- ZIO.attempt(
                        (
                          testkit.value(Definitions.fibersStarted),
                          testkit.value(Definitions.fibersEnded, FiberOutcome.Success),
                          testkit.value(Definitions.fibersEnded, FiberOutcome.Interrupted),
                          testkit.labelValues(Definitions.executorWorkers),
                        )
                      )
          yield (
            for
              startedBefore   <- before._1
              startedAfter    <- after._1
              succeededBefore <- before._2
              succeededAfter  <- after._2
            yield (startedAfter - startedBefore, succeededAfter - succeededBefore),
            after._3,
            after._4,
          )
        }.map((deltas, interrupted, executors) => (deltas.map(_._1), deltas.map(_._2), interrupted, executors))
      )

      (started.exists(_ >= 101), succeeded.exists(_ >= 100), interrupted.exists(_ >= 1), executors) shouldBe (
        true,
        true,
        true,
        Set(List("default"), List("blocking")),
      )
    }

    "export every series the shared dashboard row reads" in {
      val testkit         = MetricsTestkit()
      given Metrics[Task] = testkit.metrics[Task]

      val dashboard = Dashboard("processor", "Line / processor", "line", ServiceFilter.regex("processor"))(Rows.zio)

      run(ZIO.scoped(ZioRuntimeMetrics.scoped *> ZIO.attempt(testkit.missingSeries(dashboard)))) shouldBe Set.empty
    }

    "leave nothing behind once its scope closes" in {
      val testkit         = MetricsTestkit()
      given Metrics[Task] = testkit.metrics[Task]

      run(ZIO.scoped(ZioRuntimeMetrics.scoped))

      testkit.families.filter(_.startsWith("zio_")) shouldBe Set.empty
    }
  }
