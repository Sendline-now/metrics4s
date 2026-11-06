package metrics4s

import scala.annotation.publicInBinary

opaque type MetricName = String

object MetricName:
  extension (name: MetricName) def value: String = name

  @publicInBinary private[metrics4s] def unsafe(raw: String): MetricName = raw
