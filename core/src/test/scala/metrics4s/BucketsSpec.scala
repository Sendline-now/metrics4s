package metrics4s

import scala.compiletime.testing.typeCheckErrors

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

final class BucketsSpec extends AnyWordSpec, Matchers:
  "buckets" should {
    "keep literal bounds that are positive and strictly increasing" in {
      Buckets(0.1, 0.5, 1, 2.5).bounds.toList shouldBe List(0.1, 0.5, 1.0, 2.5)
    }

    "not compile with bounds out of order, not positive or not literal" in {
      (
        typeCheckErrors("""Buckets(1, 0.5)""").map(_.message),
        typeCheckErrors("""Buckets(0, 1)""").map(_.message),
        typeCheckErrors("""{ val bound = 1.0; Buckets(bound) }""").map(_.message),
      ) shouldBe (
        List("Invalid buckets: bounds must be strictly increasing"),
        List("Invalid buckets: every bound must be positive"),
        List("A bucket bound must be a literal; build buckets from computed bounds with Buckets.from"),
      )
    }

    "be checked at runtime when built from a sequence" in {
      (
        Buckets.from(Seq(0.001, 0.01, 0.1)).map(_.bounds.toList),
        Buckets.from(Nil),
        Buckets.from(Seq(1.0, Double.PositiveInfinity)),
        Buckets.from(Seq(1.0, 1.0)),
      ) shouldBe (
        Right(List(0.001, 0.01, 0.1)),
        Left(MetricError.InvalidBuckets("at least one bound is needed")),
        Left(MetricError.InvalidBuckets("every bound must be finite; +Inf is added by the exposition format")),
        Left(MetricError.InvalidBuckets("bounds must be strictly increasing")),
      )
    }

    "come in the sets the services already use" in {
      (Buckets.Fast.bounds.toList, Buckets.Normal.bounds.toList, Buckets.Sizes.bounds.toList) shouldBe (
        List(0.001, 0.0025, 0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1.0),
        List(0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1.0, 2.5, 5.0, 10.0, 30.0),
        List(1.0, 2.0, 5.0, 10.0, 25.0, 50.0, 100.0, 250.0, 500.0, 1000.0, 2500.0),
      )
    }
  }
