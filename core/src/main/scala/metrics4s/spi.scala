package metrics4s

trait MetricsBackend:
  def counter(definition: CounterDef[?, ?]): Either[MetricError, Series[CounterCell]]
  def gauge(definition: GaugeDef[?, ?]): Either[MetricError, Series[GaugeCell]]
  def histogram(definition: HistogramDef[?, ?]): Either[MetricError, Series[HistogramCell]]
  def info(definition: InfoDef[?]): Either[MetricError, InfoSeries] = Left(MetricError.Rejected(definition.name, "this backend has no info metrics"))
  def observe(definitions: IArray[ObservableDef[?, ?]], collect: ObservedValues => Unit): Either[MetricError, Registration]

trait Registration:
  def unregister(): Unit

trait Series[C] extends Registration:
  def cell(labelValues: IArray[String]): C
  def remove(labelValues: IArray[String]): Unit

trait CounterCell:
  def add(amount: Double): Unit

trait GaugeCell:
  def set(value: Double): Unit
  def add(delta: Double): Unit

trait HistogramCell:
  def observe(value: Double): Unit

trait ObservedValues:
  def report(definition: Int, labelValues: IArray[String], value: Double): Unit

trait InfoSeries extends Registration:
  def set(labelValues: IArray[String]): Unit
