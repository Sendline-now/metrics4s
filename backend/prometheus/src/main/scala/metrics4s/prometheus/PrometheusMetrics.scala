package metrics4s.prometheus

import io.prometheus.metrics.instrumentation.jvm.JvmMetrics
import io.prometheus.metrics.model.registry.PrometheusRegistry

import metrics4s.{Effect, Metrics, MetricsConfig}

object PrometheusMetrics:
  def apply[F[*]](registry: PrometheusRegistry)(using Effect[F]): Metrics[F] =
    Metrics.of[F](PrometheusBackend(registry))

  def apply[F[*]](registry: PrometheusRegistry, config: MetricsConfig)(using Effect[F]): Metrics[F] =
    Metrics.of[F](PrometheusBackend(registry), config)

  def jvm[F[*]](registry: PrometheusRegistry)(using F: Effect[F]): F[Unit] =
    F.delay(JvmMetrics.builder().register(registry))
