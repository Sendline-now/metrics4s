package metrics4s

import scala.annotation.publicInBinary

import metrics4s.internal.Macros

opaque type Buckets = IArray[Double]

object Buckets:
  extension (buckets: Buckets) def bounds: IArray[Double] = buckets

  inline def apply(inline first: Double, inline rest: Double*): Buckets = ${ Macros.buckets('first, 'rest) }

  def from(bounds: Seq[Double]): Either[MetricError, Buckets] =
    check(bounds).left.map(MetricError.InvalidBuckets(_)).map(_ => IArray.from(bounds))

  val Fast: Buckets   = unsafe(List(0.001, 0.0025, 0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1))
  val Normal: Buckets = unsafe(List(0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1, 2.5, 5, 10, 30))
  val Sizes: Buckets  = unsafe(List(1, 2, 5, 10, 25, 50, 100, 250, 500, 1000, 2500))

  @publicInBinary private[metrics4s] def unsafe(bounds: List[Double]): Buckets = IArray.from(bounds)

  private[metrics4s] def check(bounds: Seq[Double]): Either[String, Unit] =
    if bounds.isEmpty then Left("at least one bound is needed")
    else if bounds.exists(bound => bound.isNaN || bound.isInfinite) then Left("every bound must be finite; +Inf is added by the exposition format")
    else if bounds.exists(_ <= 0) then Left("every bound must be positive")
    else if bounds.lazyZip(bounds.drop(1)).exists(_ >= _) then Left("bounds must be strictly increasing")
    else Right(())
