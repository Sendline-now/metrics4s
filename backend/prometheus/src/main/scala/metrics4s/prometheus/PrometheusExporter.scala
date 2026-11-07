package metrics4s.prometheus

import java.io.IOException

import io.prometheus.metrics.exporter.httpserver.HTTPServer
import io.prometheus.metrics.model.registry.PrometheusRegistry

import metrics4s.{Allocated, Effect}

final class PrometheusExporter private (server: HTTPServer):
  def port: Int = server.getPort

  private def close(): Unit = server.close()

object PrometheusExporter:
  def allocated[F[*]](port: Int, registry: PrometheusRegistry)(using F: Effect[F]): Allocated[F, PrometheusExporter] =
    Allocated.make(F.delay(PrometheusExporter(start(port, registry))))(exporter => F.delay(exporter.close()))

  private def start(port: Int, registry: PrometheusRegistry): HTTPServer =
    try HTTPServer.builder().port(port).registry(registry).buildAndStart()
    catch case error: IOException => throw ExporterException(port, error)

final class ExporterException(val port: Int, cause: IOException)
    extends RuntimeException(s"The Prometheus exporter cannot listen on port $port: ${cause.getMessage}", cause)
