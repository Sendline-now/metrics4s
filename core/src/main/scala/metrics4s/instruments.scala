package metrics4s

import scala.concurrent.duration.FiniteDuration

trait CounterOf[F[*], V, L]:
  def definition: CounterDef[V, L]
  def add(labels: L, amount: V): F[Unit]
  def unsafe: CounterOf.Unsafe[V, L]
  def unregister: F[Unit]

  final def add(amount: V)(using labels: NoLabels =:= L): F[Unit] = add(labels(NoLabels), amount)

  final def inc(labels: L)(using one: Long =:= V): F[Unit] = add(labels, one(1L))

  final def inc()(using labels: NoLabels =:= L, one: Long =:= V): F[Unit] = add(labels(NoLabels), one(1L))

object CounterOf:
  trait Unsafe[V, L]:
    def add(labels: L, amount: V): Unit

    final def add(amount: V)(using labels: NoLabels =:= L): Unit = add(labels(NoLabels), amount)

    final def inc(labels: L)(using one: Long =:= V): Unit = add(labels, one(1L))

    final def inc()(using labels: NoLabels =:= L, one: Long =:= V): Unit = add(labels(NoLabels), one(1L))

trait GaugeOf[F[*], V, L]:
  def definition: GaugeDef[V, L]
  def set(labels: L, value: V): F[Unit]
  def add(labels: L, delta: V): F[Unit]
  def replace(values: Map[L, V]): F[Unit]
  def unsafe: GaugeOf.Unsafe[V, L]
  def unregister: F[Unit]

  final def set(value: V)(using labels: NoLabels =:= L): F[Unit] = set(labels(NoLabels), value)

  final def add(delta: V)(using labels: NoLabels =:= L): F[Unit] = add(labels(NoLabels), delta)

  final def inc(labels: L)(using one: Double =:= V): F[Unit] = add(labels, one(1.0))

  final def dec(labels: L)(using one: Double =:= V): F[Unit] = add(labels, one(-1.0))

  final def inc()(using labels: NoLabels =:= L, one: Double =:= V): F[Unit] = add(labels(NoLabels), one(1.0))

  final def dec()(using labels: NoLabels =:= L, one: Double =:= V): F[Unit] = add(labels(NoLabels), one(-1.0))

object GaugeOf:
  trait Unsafe[V, L]:
    def set(labels: L, value: V): Unit
    def add(labels: L, delta: V): Unit

    final def set(value: V)(using labels: NoLabels =:= L): Unit = set(labels(NoLabels), value)

    final def inc(labels: L)(using one: Double =:= V): Unit = add(labels, one(1.0))

    final def dec(labels: L)(using one: Double =:= V): Unit = add(labels, one(-1.0))

trait HistogramOf[F[*], V, L]:
  def definition: HistogramDef[V, L]
  def observe(labels: L, value: V): F[Unit]
  def unsafe: HistogramOf.Unsafe[V, L]
  def unregister: F[Unit]

  def timed[A](labels: L)(fa: => F[A])(using seconds: FiniteDuration =:= V): F[A]

  def timedBy[A](fa: => F[A])(labels: Either[Throwable, A] => L)(using seconds: FiniteDuration =:= V): F[A]

  final def observe(value: V)(using labels: NoLabels =:= L): F[Unit] = observe(labels(NoLabels), value)

  final def timed[A](fa: => F[A])(using labels: NoLabels =:= L, seconds: FiniteDuration =:= V): F[A] = timed(labels(NoLabels))(fa)

object HistogramOf:
  trait Unsafe[V, L]:
    def observe(labels: L, value: V): Unit

    final def observe(value: V)(using labels: NoLabels =:= L): Unit = observe(labels(NoLabels), value)

trait InfoOf[F[*], L]:
  def definition: InfoDef[L]
  def set(labels: L): F[Unit]
  def unsafe: InfoOf.Unsafe[L]
  def unregister: F[Unit]

object InfoOf:
  trait Unsafe[L]:
    def set(labels: L): Unit
