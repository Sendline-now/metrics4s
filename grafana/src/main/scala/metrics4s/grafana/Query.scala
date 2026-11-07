package metrics4s.grafana

final class Target private (val expr: String, val legend: Option[String], val reads: Set[String]):
  def legendFormat(legend: String): Target = new Target(expr, Some(legend), reads)

  override def toString: String = s"Target($expr)"

object Target:
  def apply(expr: String, reads: Set[String]): Target = new Target(expr, None, reads)

  def apply(expr: String, legend: String, reads: Set[String]): Target = new Target(expr, Some(legend), reads)

object Query:
  val Filter: String = """namespace="$namespace", service=~"$service", pod=~"$pod""""

  val RateInterval: String = "$__rate_interval"

  def selector(series: String, matchers: String*): String = s"$series{${(Filter +: matchers).mkString(", ")}}"

  def rate(series: String, matchers: String*): String = s"rate(${selector(series, matchers*)}[$RateInterval])"

  def sumBy(labels: Seq[String])(expr: String): String =
    if labels.isEmpty
    then s"sum($expr)"
    else s"sum by (${labels.mkString(", ")}) ($expr)"

  def quantile(quantile: Double, bucket: String, by: Seq[String], matchers: String*): String =
    s"histogram_quantile($quantile, ${sumBy("le" +: by)(rate(bucket, matchers*))})"

  def legendOf(labels: Seq[String]): Option[String] =
    Option.when(labels.nonEmpty)(labels.map(label => s"{{$label}}").mkString(" "))
