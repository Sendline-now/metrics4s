package metrics4s

import scala.annotation.publicInBinary
import scala.concurrent.duration.FiniteDuration

import metrics4s.internal.Checked

enum MetricKind(val value: String):
  case Counter   extends MetricKind("counter")
  case Gauge     extends MetricKind("gauge")
  case Histogram extends MetricKind("histogram")
  case Info      extends MetricKind("info")

enum MetricUnit(val value: String):
  case Seconds extends MetricUnit("seconds")
  case Bytes   extends MetricUnit("bytes")

final class Recorded[V] private[metrics4s] (val toDouble: V => Double)

object Recorded:
  private[metrics4s] val long: Recorded[Long]              = Recorded(_.toDouble)
  private[metrics4s] val double: Recorded[Double]          = Recorded(identity)
  private[metrics4s] val seconds: Recorded[FiniteDuration] = Recorded(_.toNanos.toDouble / 1e9)

sealed trait MetricDef:
  def name: MetricName
  def help: String
  def kind: MetricKind
  def unit: Option[MetricUnit]
  def labelNames: IArray[String]
  def labelDomains: IArray[Option[IArray[String]]]
  def preCreated: Boolean

sealed trait ObservableDef[V, L] extends MetricDef:
  def labels: Labels[L]
  def recorded: Recorded[V]

final class CounterDef[V, L] private (
    val name: MetricName,
    val help: String,
    val unit: Option[MetricUnit],
    val labels: Labels[L],
    val preCreated: Boolean,
    val recorded: Recorded[V],
) extends ObservableDef[V, L]:
  def kind: MetricKind                                        = MetricKind.Counter
  def labelNames: IArray[String]                              = labels.names
  def labelDomains: IArray[Option[IArray[String]]]            = labels.domains
  def labelled[L2](using next: Labels[L2]): CounterDef[V, L2] = CounterDef(name, help, unit, next, preCreated, recorded)
  def withoutPreCreation: CounterDef[V, L]                    = CounterDef(name, help, unit, labels, false, recorded)
  override def toString: String                               = s"CounterDef(${name.value})"

object CounterDef:
  @publicInBinary private[metrics4s] def plain(name: MetricName, help: String): CounterDef[Long, NoLabels] =
    CounterDef(name, help, None, Labels.none, true, Recorded.long)

  @publicInBinary private[metrics4s] def seconds(name: MetricName, help: String): CounterDef[FiniteDuration, NoLabels] =
    CounterDef(name, help, Some(MetricUnit.Seconds), Labels.none, true, Recorded.seconds)

  @publicInBinary private[metrics4s] def bytes(name: MetricName, help: String): CounterDef[Long, NoLabels] =
    CounterDef(name, help, Some(MetricUnit.Bytes), Labels.none, true, Recorded.long)

final class GaugeDef[V, L] private (
    val name: MetricName,
    val help: String,
    val unit: Option[MetricUnit],
    val labels: Labels[L],
    val preCreated: Boolean,
    val recorded: Recorded[V],
) extends ObservableDef[V, L]:
  def kind: MetricKind                                      = MetricKind.Gauge
  def labelNames: IArray[String]                            = labels.names
  def labelDomains: IArray[Option[IArray[String]]]          = labels.domains
  def labelled[L2](using next: Labels[L2]): GaugeDef[V, L2] = GaugeDef(name, help, unit, next, preCreated, recorded)
  def withoutPreCreation: GaugeDef[V, L]                    = GaugeDef(name, help, unit, labels, false, recorded)
  override def toString: String                             = s"GaugeDef(${name.value})"

object GaugeDef:
  @publicInBinary private[metrics4s] def plain(name: MetricName, help: String): GaugeDef[Double, NoLabels] =
    GaugeDef(name, help, None, Labels.none, true, Recorded.double)

  @publicInBinary private[metrics4s] def seconds(name: MetricName, help: String): GaugeDef[FiniteDuration, NoLabels] =
    GaugeDef(name, help, Some(MetricUnit.Seconds), Labels.none, true, Recorded.seconds)

  @publicInBinary private[metrics4s] def bytes(name: MetricName, help: String): GaugeDef[Long, NoLabels] =
    GaugeDef(name, help, Some(MetricUnit.Bytes), Labels.none, true, Recorded.long)

final class HistogramDef[V, L] private (
    val name: MetricName,
    val help: String,
    val unit: Option[MetricUnit],
    val buckets: Buckets,
    val labels: Labels[L],
    val preCreated: Boolean,
    val recorded: Recorded[V],
) extends MetricDef:
  def kind: MetricKind                                          = MetricKind.Histogram
  def labelNames: IArray[String]                                = labels.names
  def labelDomains: IArray[Option[IArray[String]]]              = labels.domains
  def labelled[L2](using next: Labels[L2]): HistogramDef[V, L2] = HistogramDef(name, help, unit, buckets, next, preCreated, recorded)
  def withoutPreCreation: HistogramDef[V, L]                    = HistogramDef(name, help, unit, buckets, labels, false, recorded)
  override def toString: String                                 = s"HistogramDef(${name.value})"

object HistogramDef:
  @publicInBinary private[metrics4s] def seconds(name: MetricName, help: String, buckets: Buckets): HistogramDef[FiniteDuration, NoLabels] =
    HistogramDef(name, help, Some(MetricUnit.Seconds), buckets, Labels.none, true, Recorded.seconds)

  @publicInBinary private[metrics4s] def bytes(name: MetricName, help: String, buckets: Buckets): HistogramDef[Long, NoLabels] =
    HistogramDef(name, help, Some(MetricUnit.Bytes), buckets, Labels.none, true, Recorded.long)

  @publicInBinary private[metrics4s] def sizes(name: MetricName, help: String, buckets: Buckets): HistogramDef[Long, NoLabels] =
    HistogramDef(name, help, None, buckets, Labels.none, true, Recorded.long)

final class InfoDef[L] private (val name: MetricName, val help: String, val labels: Labels[L]) extends MetricDef:
  def kind: MetricKind                                  = MetricKind.Info
  def unit: Option[MetricUnit]                          = None
  def labelNames: IArray[String]                        = labels.names
  def labelDomains: IArray[Option[IArray[String]]]      = labels.domains
  def preCreated: Boolean                               = false
  def labelled[L2](using next: Labels[L2]): InfoDef[L2] = InfoDef(name, help, next)
  override def toString: String                         = s"InfoDef(${name.value})"

object InfoDef:
  @publicInBinary private[metrics4s] def plain(name: MetricName, help: String): InfoDef[NoLabels] =
    InfoDef(name, help, Labels.none)

object Counter:
  inline def apply(inline name: String, inline help: String): CounterDef[Long, NoLabels] =
    CounterDef.plain(Checked.counter(name), Checked.help(help))

  inline def seconds(inline name: String, inline help: String): CounterDef[FiniteDuration, NoLabels] =
    CounterDef.seconds(Checked.counterSeconds(name), Checked.help(help))

  inline def bytes(inline name: String, inline help: String): CounterDef[Long, NoLabels] =
    CounterDef.bytes(Checked.counterBytes(name), Checked.help(help))

object Gauge:
  inline def apply(inline name: String, inline help: String): GaugeDef[Double, NoLabels] =
    GaugeDef.plain(Checked.gauge(name), Checked.help(help))

  inline def seconds(inline name: String, inline help: String): GaugeDef[FiniteDuration, NoLabels] =
    GaugeDef.seconds(Checked.gaugeSeconds(name), Checked.help(help))

  inline def bytes(inline name: String, inline help: String): GaugeDef[Long, NoLabels] =
    GaugeDef.bytes(Checked.gaugeBytes(name), Checked.help(help))

object Histogram:
  inline def seconds(inline name: String, inline help: String, buckets: Buckets): HistogramDef[FiniteDuration, NoLabels] =
    HistogramDef.seconds(Checked.histogramSeconds(name), Checked.help(help), buckets)

  inline def bytes(inline name: String, inline help: String, buckets: Buckets): HistogramDef[Long, NoLabels] =
    HistogramDef.bytes(Checked.histogramBytes(name), Checked.help(help), buckets)

  inline def sizes(inline name: String, inline help: String, buckets: Buckets): HistogramDef[Long, NoLabels] =
    HistogramDef.sizes(Checked.histogramSizes(name), Checked.help(help), buckets)

object Info:
  inline def apply(inline name: String, inline help: String): InfoDef[NoLabels] =
    InfoDef.plain(Checked.info(name), Checked.help(help))
