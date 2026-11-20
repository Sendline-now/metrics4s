package metrics4s

enum MetricError:
  case InvalidBuckets(reason: String)
  case InvalidLabelName(metric: MetricName, label: String, reason: String)
  case DuplicateLabelName(metric: MetricName, label: String)
  case DuplicateLabelValue(metric: MetricName, label: String, value: String)
  case DomainTooLarge(metric: MetricName, domains: List[(String, Int)], seriesEach: Int, series: Long, limit: Int)
  case AlreadyRegistered(metric: MetricName)
  case Rejected(metric: MetricName, reason: String)
  case BackendFailure(metric: MetricName, cause: Throwable)

  def message: String =
    this match
      case InvalidBuckets(reason)                                     => s"Invalid buckets: $reason"
      case InvalidLabelName(metric, label, reason)                    => s"${metric.value}: label name \"$label\" $reason"
      case DuplicateLabelName(metric, label)                          => s"${metric.value}: label \"$label\" is declared twice"
      case DuplicateLabelValue(metric, label, value)                  => s"${metric.value}: two values of label \"$label\" are both rendered as \"$value\""
      case DomainTooLarge(metric, domains, seriesEach, series, limit) =>
        val product = domains.map((label, size) => s"$size $label").mkString(" x ")
        val each    = if seriesEach > 1 then s" x $seriesEach series each" else ""

        s"${metric.value}: $product$each = $series series, more than the $limit a metric creates up front; " +
          "raise MetricsConfig.preCreateLimit, or define it withoutPreCreation, or withPreCreation(PreCreation.WithinLimit)"
      case AlreadyRegistered(metric)                                  => s"${metric.value} is already registered"
      case Rejected(metric, reason)                                   => s"${metric.value} was rejected by the backend: $reason"
      case BackendFailure(metric, cause)                              => s"${metric.value}: the backend failed: ${cause.getMessage}"

  def toThrowable: MetricException = MetricException(this)

final class MetricException(val error: MetricError) extends RuntimeException(
      error.message,
      error match
        case MetricError.BackendFailure(_, cause) => cause
        case _                                    => null,
    )
