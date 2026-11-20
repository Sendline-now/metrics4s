package metrics4s

import scala.deriving.Mirror
import scala.NamedTuple.NamedTuple
import scala.annotation.implicitNotFound
import scala.compiletime.{error, summonFrom, summonInline}
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
  def register[L](definition: InfoDef[L]): F[InfoOf[F, L]]
  def registerAll[T <: Tuple](definitions: T)(using Tuple.Union[T] <:< MetricDef): F[Metrics.Instruments[F, T]]

  def observe[V, L](definition: ObservableDef[V, L])(report: Observer[V, L] => Unit): Allocated[F, Unit]
  def observeAll[S](read: => S)(observations: Observation[S]*): Allocated[F, Unit]
  def observeAllF[S](read: F[S], timeout: FiniteDuration)(observations: Observation[S]*)(using Dispatch[F]): Allocated[F, Unit]

  def backend: MetricsBackend

  inline def registerAs[P](using mirror: Mirror.ProductOf[P])[N <: Tuple, V <: Tuple](definitions: NamedTuple[N, V])(using F: Effect[F]): F[P] =
    summonFrom {
      case _: (N =:= mirror.MirroredElemLabels) =>
        summonFrom {
          case _: (Metrics.Instruments[F, V] <:< mirror.MirroredElemTypes) =>
            F.map(registerAll(definitions.toTuple)(using summonInline[Tuple.Union[V] <:< MetricDef]))(mirror.fromProduct)
          case _                                                           => error("Give each field of the case class the type of the instrument its definition registers as")
        }
      case _                                    => error("Name the definitions after the fields of the case class they fill, in their order, as in (requests = requestsDef, duration = durationDef)")
    }

object Metrics:
  type Instrument[F[*], D] = D match
    case CounterDef[v, l]   => CounterOf[F, v, l]
    case GaugeDef[v, l]     => GaugeOf[F, v, l]
    case HistogramDef[v, l] => HistogramOf[F, v, l]
    case InfoDef[l]         => InfoOf[F, l]

  type Instruments[F[*], T <: Tuple] = Tuple.Map[T, [D] =>> Instrument[F, D]]

  inline def apply[F[*]](using metrics: Metrics[F]): metrics.type = metrics

  def of[F[*]](backend: MetricsBackend)(using Effect[F]): Metrics[F] = of(backend, MetricsConfig.default)

  def of[F[*]](backend: MetricsBackend, config: MetricsConfig)(using Effect[F]): Metrics[F] = MetricsImpl(backend, config)
