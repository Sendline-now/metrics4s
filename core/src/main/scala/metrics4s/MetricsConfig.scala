package metrics4s

final class MetricsConfig private (val preCreateLimit: Int):
  def withPreCreateLimit(limit: Int): MetricsConfig = MetricsConfig(limit)

  override def toString: String = s"MetricsConfig(preCreateLimit = $preCreateLimit)"

object MetricsConfig:
  val default: MetricsConfig = MetricsConfig(10000)
