package metrics4s

import scala.annotation.implicitNotFound
import scala.concurrent.duration.FiniteDuration

import metrics4s.internal.MetricsImpl

@implicitNotFound(
  "No Metrics[${F}]. Build one from a backend, e.g. `given Metrics[IO] = PrometheusMetrics[IO](registry)` from metrics4s-prometheus, " +
    "or `MetricsTestkit().metrics[IO]` in a test."
)
trait Metrics[F[*]]:
  def register[V, L](definition: CounterDef[V, L]): F[CounterOf[F, V, L]]
  def register[V, L](definition: GaugeDef[V, L]): F[GaugeOf[F, V, L]]
  def register[V, L](definition: HistogramDef[V, L]): F[HistogramOf[F, V, L]]
  def registerAll[T <: Tuple](definitions: T): F[Metrics.Instruments[F, T]]

  def observe[V, L](definition: ObservableDef[V, L])(report: Observer[V, L] => Unit): Allocated[F, Unit]
  def observeAll[S](read: => S)(observations: Observation[S]*): Allocated[F, Unit]
  def observeAllF[S](read: F[S], timeout: FiniteDuration)(observations: Observation[S]*)(using Dispatch[F]): Allocated[F, Unit]

  def backend: MetricsBackend

object Metrics:
  type Instrument[F[*], D] = D match
    case CounterDef[v, l]   => CounterOf[F, v, l]
    case GaugeDef[v, l]     => GaugeOf[F, v, l]
    case HistogramDef[v, l] => HistogramOf[F, v, l]

  type Instruments[F[*], T <: Tuple] = Tuple.Map[T, [D] =>> Instrument[F, D]]

  inline def apply[F[*]](using metrics: Metrics[F]): metrics.type = metrics

  def of[F[*]](backend: MetricsBackend)(using Effect[F]): Metrics[F] = of(backend, MetricsConfig.default)

  def of[F[*]](backend: MetricsBackend, config: MetricsConfig)(using Effect[F]): Metrics[F] = MetricsImpl(backend, config)
