package metrics4s.prometheus

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

import io.prometheus.metrics.config.EscapingScheme
import io.prometheus.metrics.expositionformats.PrometheusTextFormatWriter
import io.prometheus.metrics.model.registry.PrometheusRegistry

object PrometheusScrape:
  def text(registry: PrometheusRegistry): String =
    val out = ByteArrayOutputStream()
    PrometheusTextFormatWriter.create().write(out, registry.scrape(), EscapingScheme.UNDERSCORE_ESCAPING)
    out.toString(StandardCharsets.UTF_8)
