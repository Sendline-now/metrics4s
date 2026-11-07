package metrics4s.it.docs.recipes.route

import cats.effect.IO
import org.http4s.dsl.io.*
import org.http4s.HttpRoutes
import io.prometheus.metrics.model.registry.PrometheusRegistry

import metrics4s.prometheus.PrometheusScrape

def metricsRoutes(registry: PrometheusRegistry): HttpRoutes[IO] =
  HttpRoutes.of[IO] { case GET -> Root / "metrics" =>
    Ok(PrometheusScrape.text(registry))
  }
