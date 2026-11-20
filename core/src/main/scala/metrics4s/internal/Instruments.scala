package metrics4s.internal

import scala.concurrent.duration.FiniteDuration

import metrics4s.*

private[metrics4s] final class CounterImpl[F[*], V, L](val definition: CounterDef[V, L], cells: Cells[CounterCell, L])(using F: Effect[F]) extends CounterOf[F, V, L]:

  private[metrics4s] def series: Series[CounterCell] = cells.series

  val unsafe: CounterOf.Unsafe[V, L] = new CounterOf.Unsafe[V, L]:
    def add(labels: L, amount: V): Unit = cells(labels).add(definition.recorded.toDouble(amount))

  def add(labels: L, amount: V): F[Unit] = F.delay(unsafe.add(labels, amount))

  def unregister: F[Unit] = F.delay(cells.series.unregister())

private[metrics4s] final class GaugeImpl[F[*], V, L](val definition: GaugeDef[V, L], cells: Cells[GaugeCell, L])(using F: Effect[F]) extends GaugeOf[F, V, L]:

  private[metrics4s] def series: Series[GaugeCell] = cells.series

  val unsafe: GaugeOf.Unsafe[V, L] = new GaugeOf.Unsafe[V, L]:
    def set(labels: L, value: V): Unit = cells(labels).set(definition.recorded.toDouble(value))
    def add(labels: L, delta: V): Unit = cells(labels).add(definition.recorded.toDouble(delta))

  def set(labels: L, value: V): F[Unit] = F.delay(unsafe.set(labels, value))

  def add(labels: L, delta: V): F[Unit] = F.delay(unsafe.add(labels, delta))

  def replace(values: Map[L, V]): F[Unit] = F.delay(synchronized(replaceNow(values)))

  def unregister: F[Unit] = F.delay(cells.series.unregister())

  def track[A](labels: L)(fa: => F[A])(using one: Double =:= V): F[A] =
    F.bracket(add(labels, one(1.0)))(_ => fa)(_ => add(labels, one(-1.0)))

  def timed[A](labels: L)(fa: => F[A])(using seconds: FiniteDuration =:= V): F[A] =
    F.flatMap(F.monotonic) { start =>
      F.guaranteeCase(F.suspend(fa)) {
        case ExitCase.Canceled => F.unit
        case _                 => F.flatMap(F.monotonic)(end => set(labels, seconds(end - start)))
      }
    }

  private def replaceNow(values: Map[L, V]): Unit =
    cells match
      case precreated: Cells.Precreated[GaugeCell, L] =>
        val present = values.map((labels, value) => precreated.labels.index(labels) -> definition.recorded.toDouble(value))
        (0 until precreated.precreated.getOrElse(0)).foreach(index => precreated.at(index).set(present.getOrElse(index, 0.0)))
      case lookup: Cells.Lookup[GaugeCell, L]         =>
        val present: Map[Seq[String], Double] = values.map((labels, value) => lookup.labels.render(labels).toSeq -> definition.recorded.toDouble(value))
        present.foreach((labelValues, value) => lookup.series.cell(IArray.from(labelValues)).set(value))
        lookup.series.labelValues.filterNot(labelValues => present.contains(labelValues.toSeq)).foreach(lookup.series.remove)

private[metrics4s] final class HistogramImpl[F[*], V, L](val definition: HistogramDef[V, L], cells: Cells[HistogramCell, L])(using F: Effect[F])
    extends HistogramOf[F, V, L]:

  private[metrics4s] def series: Series[HistogramCell] = cells.series

  val unsafe: HistogramOf.Unsafe[V, L] = new HistogramOf.Unsafe[V, L]:
    def observe(labels: L, value: V): Unit = cells(labels).observe(definition.recorded.toDouble(value))

  def observe(labels: L, value: V): F[Unit] = F.delay(unsafe.observe(labels, value))

  def unregister: F[Unit] = F.delay(cells.series.unregister())

  def timed[A](labels: L)(fa: => F[A])(using seconds: FiniteDuration =:= V): F[A] =
    F.flatMap(F.monotonic) { start =>
      F.guaranteeCase(F.suspend(fa)) {
        case ExitCase.Canceled => F.unit
        case _                 => F.flatMap(F.monotonic)(end => observe(labels, seconds(end - start)))
      }
    }

  def timedBy[A](fa: => F[A])(labels: Either[Throwable, A] => L)(using seconds: FiniteDuration =:= V): F[A] =
    F.flatMap(F.monotonic) { start =>
      F.flatMap(F.attempt(F.suspend(fa))) { result =>
        val recorded = F.flatMap(F.monotonic)(end => observe(labels(result), seconds(end - start)))
        F.flatMap(recorded)(_ => Effect.fromEither(result))
      }
    }

private[metrics4s] final class InfoImpl[F[*], L](val definition: InfoDef[L], series: InfoSeries)(using F: Effect[F]) extends InfoOf[F, L]:

  private[metrics4s] def registration: Registration = series

  val unsafe: InfoOf.Unsafe[L] = new InfoOf.Unsafe[L]:
    def set(labels: L): Unit = series.set(definition.labels.render(labels))

  def set(labels: L): F[Unit] = F.delay(unsafe.set(labels))

  def unregister: F[Unit] = F.delay(series.unregister())

private[metrics4s] final class MeasuredImpl[F[*], L, O, C](
    duration: HistogramOf[F, FiniteDuration, L],
    outcomes: CounterOf[F, Long, C],
    failed: O,
    labelled: (L, O) => C,
)(using F: Effect[F])
    extends Measured[F, L, O]:

  def measure[A](labels: L)(fa: => F[A])(outcome: A => O): F[A] =
    F.flatMap(F.monotonic) { start =>
      F.flatMap(F.attempt(F.suspend(fa))) { result =>
        val recorded = F.flatMap(F.monotonic)(end => record(labels, end - start, result.fold(_ => failed, outcome)))

        result match
          case Right(value) => F.map(recorded)(_ => value)
          case Left(error)  => F.flatMap(F.handleErrorWith(recorded)(problem => F.delay(error.addSuppressed(problem))))(_ => F.raiseError(error))
      }
    }

  private def record(labels: L, elapsed: FiniteDuration, outcome: => O): F[Unit] =
    F.flatMap(duration.observe(labels, elapsed))(_ => F.flatMap(F.delay(labelled(labels, outcome)))(counted => outcomes.add(counted, 1L)))
