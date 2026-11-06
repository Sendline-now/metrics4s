package metrics4s.internal

import metrics4s.MetricName

object Checked:
  inline def counter(inline name: String): MetricName        = ${ Macros.metricName('name, "counter", "") }
  inline def counterSeconds(inline name: String): MetricName = ${ Macros.metricName('name, "counter", "seconds") }
  inline def counterBytes(inline name: String): MetricName   = ${ Macros.metricName('name, "counter", "bytes") }

  inline def gauge(inline name: String): MetricName        = ${ Macros.metricName('name, "gauge", "") }
  inline def gaugeSeconds(inline name: String): MetricName = ${ Macros.metricName('name, "gauge", "seconds") }
  inline def gaugeBytes(inline name: String): MetricName   = ${ Macros.metricName('name, "gauge", "bytes") }

  inline def histogramSizes(inline name: String): MetricName   = ${ Macros.metricName('name, "histogram", "") }
  inline def histogramSeconds(inline name: String): MetricName = ${ Macros.metricName('name, "histogram", "seconds") }
  inline def histogramBytes(inline name: String): MetricName   = ${ Macros.metricName('name, "histogram", "bytes") }

  inline def help(inline help: String): String = ${ Macros.help('help) }

  inline def labelName(inline name: String): String = ${ Macros.labelName('name) }
