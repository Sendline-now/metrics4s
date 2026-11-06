package metrics4s.prometheus

import io.prometheus.metrics.exporter.httpserver.HTTPServer
import io.prometheus.metrics.model.registry.PrometheusRegistry

import metrics4s.{Allocated, Effect}

final class PrometheusExporter private (server: HTTPServer):
  def port: Int = server.getPort

  private def close(): Unit = server.close()

object PrometheusExporter:
  def allocated[F[*]](port: Int, registry: PrometheusRegistry)(using F: Effect[F]): Allocated[F, PrometheusExporter] =
    Allocated.make(F.delay(PrometheusExporter(HTTPServer.builder().port(port).registry(registry).buildAndStart())))(exporter => F.delay(exporter.close()))
