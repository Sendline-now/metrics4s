package metrics4s

import scala.annotation.publicInBinary

import metrics4s.internal.Macros

opaque type Buckets = IArray[Double]

object Buckets:
  extension (buckets: Buckets) def bounds: IArray[Double] = buckets

  inline def apply(inline first: Double, inline rest: Double*): Buckets = ${ Macros.buckets('first, 'rest) }

  inline def linear(inline start: Double, inline width: Double, inline count: Int): Buckets = ${ Macros.linearBuckets('start, 'width, 'count) }

  inline def exponential(inline start: Double, inline factor: Double, inline count: Int): Buckets = ${ Macros.exponentialBuckets('start, 'factor, 'count) }

  def from(bounds: Seq[Double]): Either[MetricError, Buckets] =
    check(bounds).left.map(MetricError.InvalidBuckets(_)).map(_ => IArray.from(bounds))

  def linearFrom(start: Double, width: Double, count: Int): Either[MetricError, Buckets] =
    linearBounds(start, width, count).left.map(MetricError.InvalidBuckets(_)).flatMap(from)

  def exponentialFrom(start: Double, factor: Double, count: Int): Either[MetricError, Buckets] =
    exponentialBounds(start, factor, count).left.map(MetricError.InvalidBuckets(_)).flatMap(from)

  val Fast: Buckets   = unsafe(List(0.001, 0.0025, 0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1))
  val Normal: Buckets = unsafe(List(0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1, 2.5, 5, 10, 30))
  val Sizes: Buckets  = unsafe(List(1, 2, 5, 10, 25, 50, 100, 250, 500, 1000, 2500))

  @publicInBinary private[metrics4s] def unsafe(bounds: List[Double]): Buckets = IArray.from(bounds)

  private[metrics4s] def linearBounds(start: Double, width: Double, count: Int): Either[String, List[Double]] =
    if count < 1 then Left("at least one bound is needed")
    else if count > MaxGenerated then Left(s"at most $MaxGenerated bounds can be generated")
    else if !(start.isFinite && width.isFinite) then Left("the first bound and the width must be finite")
    else if width <= 0 then Left("the width must be positive")
    else Right(List.tabulate(count)(step => (BigDecimal(start) + BigDecimal(width) * step).toDouble))

  private[metrics4s] def exponentialBounds(start: Double, factor: Double, count: Int): Either[String, List[Double]] =
    if count < 1 then Left("at least one bound is needed")
    else if count > MaxGenerated then Left(s"at most $MaxGenerated bounds can be generated")
    else if !(start.isFinite && factor.isFinite) then Left("the first bound and the factor must be finite")
    else if start <= 0 then Left("the first bound must be positive")
    else if factor <= 1 then Left("the factor must be greater than 1")
    else Right(List.tabulate(count)(step => (BigDecimal(start) * BigDecimal(factor).pow(step)).toDouble))

  private val MaxGenerated = 100

  private[metrics4s] def check(bounds: Seq[Double]): Either[String, Unit] =
    if bounds.isEmpty then Left("at least one bound is needed")
    else if bounds.exists(bound => bound.isNaN || bound.isInfinite) then Left("every bound must be finite; +Inf is added by the exposition format")
    else if bounds.exists(_ <= 0) then Left("every bound must be positive")
    else if bounds.lazyZip(bounds.drop(1)).exists(_ >= _) then Left("bounds must be strictly increasing")
    else Right(())
