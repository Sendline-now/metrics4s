package metrics4s.it.docs.testing

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import cats.effect.IO

import metrics4s.Metrics
import metrics4s.testkit.MetricsTestkit
import metrics4s.it.docs.quickstart.{Outcome, ProcessorMetrics}

import cats.effect.unsafe.implicits.global
import metrics4s.runtime.cats.CatsInstances.given

final class TestingExampleItSpec extends AnyWordSpec, Matchers:
  "the README's testing example" should {
    "read back from a real registry what it records" in {
      val example =
        val testkit       = MetricsTestkit()
        given Metrics[IO] = testkit.metrics[IO]

        for
          counter <- Metrics[IO].register(ProcessorMetrics.processed)
          _       <- counter.inc(Outcome.Ok)
        yield
          testkit.value(ProcessorMetrics.processed, Outcome.Ok) shouldBe Some(1.0)
          testkit.value(ProcessorMetrics.processed, Outcome.Error) shouldBe Some(0.0)

      example.unsafeRunSync()
    }
  }
