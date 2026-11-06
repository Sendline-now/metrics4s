package metrics4s.runtime.cats

import java.util.concurrent.atomic.AtomicInteger

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec

import cats.effect.{IO, Ref}
import cats.effect.testing.scalatest.AsyncIOSpec

import metrics4s.*
import metrics4s.prometheus.PrometheusBackend
import metrics4s.testkit.MetricsTestkit

import scala.concurrent.duration.given
import metrics4s.runtime.cats.CatsInstances.given
import metrics4s.runtime.cats.syntax.given

final class CatsMetricsSpec extends AsyncWordSpec, AsyncIOSpec, Matchers:
  private val processTime = Histogram.seconds("processor_process_seconds", "Processing of one item", Buckets.Normal)
  private val queueSize   = Gauge("processor_queue_items", "Items waiting in the queue")

  "timing an IO" should {
    "record a success and a failure but not a cancellation" in {
      val testkit       = MetricsTestkit()
      given Metrics[IO] = testkit.metrics[IO]

      for
        time    <- Metrics[IO].register(processTime)
        _       <- time.timed(IO.sleep(30.millis))
        _       <- time.timed(IO.raiseError[Unit](IllegalStateException("boom"))).attempt
        _       <- time.timed(IO.never[Unit]).timeoutTo(30.millis, IO.unit)
        recorded = testkit.histogram(processTime)
      yield (recorded.map(_.count), recorded.exists(_.sum >= 0.03)) shouldBe (Some(2L), true)
    }
  }

  "a callback read from IO" should {
    "report what the effect returns on every scrape" in {
      val testkit       = MetricsTestkit()
      given Metrics[IO] = testkit.metrics[IO]

      for
        queue  <- Ref.of[IO, Double](4)
        values <- Metrics[IO]
                    .observeAllF(queue.get, 1.second)(Observation(queueSize)((size, out) => out.report(size)))
                    .toResource
                    .use(_ => IO.blocking(testkit.value(queueSize)).flatMap(first => queue.set(9) >> IO.blocking((first, testkit.value(queueSize)))))
      yield values shouldBe (Some(4.0), Some(9.0))
    }

    "give up on an effect that does not finish in time, cancel it and count the failure" in {
      val testkit       = MetricsTestkit()
      val canceled      = AtomicInteger(0)
      val errors        = s"""${PrometheusBackend.CallbackErrors}_total{metric="processor_queue_items"}"""
      given Metrics[IO] = testkit.metrics[IO]

      val stuck = IO.never[Double].onCancel(IO(canceled.incrementAndGet()).void)

      for
        scraped <- Metrics[IO]
                     .observeAllF(stuck, 50.millis)(Observation(queueSize)((size, out) => out.report(size)))
                     .toResource
                     .use(_ => IO.blocking(testkit.text) >> IO.blocking(testkit.series))
        _       <- IO.sleep(100.millis)
      yield (scraped.get(errors), scraped.contains("processor_queue_items"), canceled.get) shouldBe (Some(1.0), false, 2)
    }
  }
