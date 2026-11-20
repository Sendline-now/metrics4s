package metrics4s.it.docs.recording

import scala.concurrent.duration.FiniteDuration

import cats.effect.IO

import metrics4s.*
import metrics4s.testkit.MetricsTestkit
import metrics4s.it.docs.quickstart.{Outcome, ProcessorMetrics}

import scala.concurrent.duration.given
import metrics4s.runtime.cats.CatsInstances.given

final class Recording(
    counter: CounterOf[IO, Long, Outcome],
    total: CounterOf[IO, Long, NoLabels],
    gauge: GaugeOf[IO, Double, Outcome],
    inFlight: GaugeOf[IO, Double, Outcome],
    histogram: HistogramOf[IO, FiniteDuration, Outcome],
    lastRun: GaugeOf[IO, FiniteDuration, NoLabels],
):
  def record(fa: IO[String]): IO[Unit] =
    counter.inc(Outcome.Ok)         // F[Unit]
    counter.add(Outcome.Skipped, 5)
    total.inc()                     // a metric without labels
    gauge.set(Outcome.Ok, 42)
    inFlight.inc(Outcome.Ok); inFlight.dec(Outcome.Ok)
    inFlight.track(Outcome.Ok)(fa)  // one more while fa runs, one less once it ends, fails or is cancelled
    lastRun.timed(fa)               // a gauge of seconds keeps how long the last run took
    gauge.replace(Map(Outcome.Ok -> 3)) // the rest goes to 0, or is removed when the label is bounded
    histogram.observe(Outcome.Ok, 250.millis)
    histogram.timed(Outcome.Ok)(fa) // a success and a failure are recorded, a cancellation is not
    histogram.timedBy(fa)(result => if result.isRight then Outcome.Ok else Outcome.Error)
    counter.unsafe.inc(Outcome.Ok)  // Unit, for code outside F: driver listeners, interceptors
    counter.unregister

object Registering:
  given Metrics[IO] = MetricsTestkit().metrics[IO]

  final case class Processing(processed: CounterOf[IO, Long, Outcome], processTime: HistogramOf[IO, FiniteDuration, NoLabels])

  Metrics[IO].registerAs[Processing]((processed = ProcessorMetrics.processed, processTime = ProcessorMetrics.processTime))

enum Source derives LabelValue:
  case Kafka, Rabbit

enum FetchOutcome derives LabelValue:
  case Found, Missing, Failed

trait Repository:
  def find(id: String): IO[Option[String]]

final class Fetching(
    fetchTime: HistogramOf[IO, FiniteDuration, (source: Source)],
    fetches: CounterOf[IO, Long, (source: Source, outcome: FetchOutcome)],
    repository: Repository,
):
  def fetchOne(id: String): IO[Option[String]] =
    val fetch = Measured(fetchTime, fetches, FetchOutcome.Failed)((labels, outcome) => (source = labels.source, outcome = outcome))

    fetch.measure((source = Source.Kafka))(repository.find(id))(found => if found.isDefined then FetchOutcome.Found else FetchOutcome.Missing)

final case class Version(value: String)

object Version:
  given LabelValue[Version] = LabelValue.bounded(_.value)

enum Stage derives LabelValue:
  case Dev, Prod

object BuildInfo:
  val version = "1.0.0"

object Builds:
  def publish(using Metrics[IO]): IO[Unit] =
    val build = Info("processor_build", "The build that runs").labelled[(version: Version, stage: Stage)]

    Metrics[IO].register(build).flatMap(_.set((version = Version(BuildInfo.version), stage = Stage.Prod)))
