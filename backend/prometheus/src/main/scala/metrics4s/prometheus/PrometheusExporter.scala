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
    Allocated.make(F.delay(PrometheusExporter(start(None, port, registry))))(exporter => F.delay(exporter.close()))

  def allocated[F[*]](host: String, port: Int, registry: PrometheusRegistry)(using F: Effect[F]): Allocated[F, PrometheusExporter] =
    Allocated.make(F.delay(PrometheusExporter(start(Some(host), port, registry))))(exporter => F.delay(exporter.close()))

  private def start(host: Option[String], port: Int, registry: PrometheusRegistry): HTTPServer =
    val builder = HTTPServer.builder().port(port).registry(registry)

    try host.fold(builder)(builder.hostname).buildAndStart()
    catch case error: IOException => throw ExporterException(host, port, error)

final class ExporterException(val host: Option[String], val port: Int, cause: IOException)
    extends RuntimeException(s"The Prometheus exporter cannot listen on ${host.fold(s"port $port")(name => s"$name:$port")}: ${cause.getMessage}", cause)
