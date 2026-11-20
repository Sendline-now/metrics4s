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

    "be generated in equal steps or by a factor, without the noise of floating-point sums" in {
      (
        Buckets.linear(0.1, 0.1, 5).bounds.toList,
        Buckets.exponential(0.001, 2, 6).bounds.toList,
        Buckets.exponential(1, 10, 4).bounds.toList,
        Buckets.linearFrom(0.1, 0.1, 3).map(_.bounds.toList),
        Buckets.exponentialFrom(0.5, 1.5, 3).map(_.bounds.toList),
      ) shouldBe (
        List(0.1, 0.2, 0.3, 0.4, 0.5),
        List(0.001, 0.002, 0.004, 0.008, 0.016, 0.032),
        List(1.0, 10.0, 100.0, 1000.0),
        Right(List(0.1, 0.2, 0.3)),
        Right(List(0.5, 0.75, 1.125)),
      )
    }

    "not be generated from a start, a step or a count that cannot make increasing positive bounds" in {
      (
        typeCheckErrors("""Buckets.linear(0, 1, 3)""").map(_.message),
        typeCheckErrors("""Buckets.linear(1, 0, 3)""").map(_.message),
        typeCheckErrors("""Buckets.exponential(1, 1, 3)""").map(_.message),
        typeCheckErrors("""Buckets.exponential(1, 2, 0)""").map(_.message),
        typeCheckErrors("""{ val count = 3; Buckets.linear(1, 1, count) }""").map(_.message),
        Buckets.linearFrom(1, -1, 3),
        Buckets.exponentialFrom(-1, 2, 3),
        Buckets.exponentialFrom(1, 2, 101),
      ) shouldBe (
        List("Invalid buckets: every bound must be positive"),
        List("Invalid buckets: the width must be positive"),
        List("Invalid buckets: the factor must be greater than 1"),
        List("Invalid buckets: at least one bound is needed"),
        List("Generated buckets take literals; build them from computed values with Buckets.linearFrom or Buckets.exponentialFrom"),
        Left(MetricError.InvalidBuckets("the width must be positive")),
        Left(MetricError.InvalidBuckets("the first bound must be positive")),
        Left(MetricError.InvalidBuckets("at most 100 bounds can be generated")),
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
