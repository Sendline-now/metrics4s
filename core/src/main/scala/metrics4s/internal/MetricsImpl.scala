package metrics4s.internal

import scala.compiletime.asMatchable
import scala.concurrent.duration.FiniteDuration
import scala.util.control.NonFatal

import metrics4s.*

private[metrics4s] final class MetricsImpl[F[*]](val backend: MetricsBackend, config: MetricsConfig)(using F: Effect[F]) extends Metrics[F]:

  def register[V, L](definition: CounterDef[V, L]): F[CounterOf[F, V, L]] =
    raised(registerCounter(definition))

  def register[V, L](definition: GaugeDef[V, L]): F[GaugeOf[F, V, L]] =
    raised(registerGauge(definition))

  def register[V, L](definition: HistogramDef[V, L]): F[HistogramOf[F, V, L]] =
    raised(registerHistogram(definition))

  def register[L](definition: InfoDef[L]): F[InfoOf[F, L]] =
    raised(registerInfo(definition))

  def registerAll[T <: Tuple](definitions: T)(using Tuple.Union[T] <:< MetricDef): F[Metrics.Instruments[F, T]] =
    raised {
      val registered = definitions.productIterator.foldLeft[Either[MetricError, List[(Any, Registration)]]](Right(Nil)) { (acc, definition) =>
        acc.flatMap { done =>
          registerAny(definition) match
            case Right(instrument) => Right(instrument :: done)
            case Left(error)       =>
              done.foreach((_, registration) => registration.unregister())
              Left(error)
        }
      }

      registered.map(done => Tuple.fromArray(done.reverse.map(_._1.asInstanceOf[Object]).toArray).asInstanceOf[Metrics.Instruments[F, T]])
    }

  def observe[V, L](definition: ObservableDef[V, L])(report: Observer[V, L] => Unit): Allocated[F, Unit] =
    observeAll(())(Observation(definition)((_, observer) => report(observer)))

  def observeAll[S](read: => S)(observations: Observation[S]*): Allocated[F, Unit] =
    val all = IArray.from(observations)

    Allocated(
      F.flatMap(F.delay(registerObservations(all, () => read))) { registered =>
        F.map(Effect.fromEither(registered.left.map(_.toThrowable)))(registration => ((), F.delay(registration.unregister())))
      }
    )

  def observeAllF[S](read: F[S], timeout: FiniteDuration)(observations: Observation[S]*)(using dispatch: Dispatch[F]): Allocated[F, Unit] =
    dispatch.runner.flatMap(runner => observeAll(runner.unsafeRunTimed(read, timeout))(observations*))

  private def raised[A](register: => Either[MetricError, A]): F[A] =
    F.flatMap(F.delay(register))(result => Effect.fromEither(result.left.map(_.toThrowable)))

  private def registerAny(definition: Any): Either[MetricError, (Any, Registration)] =
    definition.asMatchable match
      case counter: CounterDef[?, ?]     => registerCounter(counter).map(instrument => (instrument, instrument.series))
      case gauge: GaugeDef[?, ?]         => registerGauge(gauge).map(instrument => (instrument, instrument.series))
      case histogram: HistogramDef[?, ?] => registerHistogram(histogram).map(instrument => (instrument, instrument.series))
      case info: InfoDef[?]              => registerInfo(info).map(instrument => (instrument, instrument.registration))
      case other                         => throw IllegalArgumentException(s"registerAll takes metric definitions, not $other")

  private def registerCounter[V, L](definition: CounterDef[V, L]): Either[MetricError, CounterImpl[F, V, L]] =
    cellsOf(definition, definition.labels, backend.counter(definition)).map(CounterImpl(definition, _))

  private def registerGauge[V, L](definition: GaugeDef[V, L]): Either[MetricError, GaugeImpl[F, V, L]] =
    cellsOf(definition, definition.labels, backend.gauge(definition)).map(GaugeImpl(definition, _))

  private def registerHistogram[V, L](definition: HistogramDef[V, L]): Either[MetricError, HistogramImpl[F, V, L]] =
    cellsOf(definition, definition.labels, backend.histogram(definition)).map(HistogramImpl(definition, _))

  private def registerInfo[L](definition: InfoDef[L]): Either[MetricError, InfoImpl[F, L]] =
    for
      _      <- MetricsImpl.validateLabels(definition)
      series <- MetricsImpl.attempt(definition)(backend.info(definition))
    yield InfoImpl(definition, series)

  private def cellsOf[C, L](definition: MetricDef, labels: Labels[L], open: => Either[MetricError, Series[C]]): Either[MetricError, Cells[C, L]] =
    for
      _          <- MetricsImpl.validateLabels(definition)
      precreated <- MetricsImpl.precreation(definition, labels, config)
      series     <- MetricsImpl.attempt(definition)(open)
      cells      <- precreated match
                      case Some(size) =>
                        MetricsImpl.attempt(definition)(Right(Cells.precreate(series, labels, size))).left.map { error =>
                          series.unregister()
                          error
                        }
                      case None       => Right(Cells.Lookup(series, labels))
    yield cells

  private def registerObservations[S](observations: IArray[Observation[S]], read: () => S): Either[MetricError, Registration] =
    val definitions = observations.map(_.definition)

    definitions.headOption match
      case None        => Right(MetricsImpl.NoRegistration)
      case Some(first) =>
        for
          _            <- definitions.foldLeft[Either[MetricError, Unit]](Right(()))((acc, definition) => acc.flatMap(_ => MetricsImpl.validateLabels(definition)))
          registration <- MetricsImpl.attempt(first) {
                            backend.observe(
                              definitions,
                              sink =>
                                val state = read()
                                var index = 0
                                while index < observations.length do
                                  observations(index).collect(state, sink, index)
                                  index += 1
                            )
                          }
        yield registration

private[metrics4s] object MetricsImpl:
  private[internal] val NoRegistration: Registration = () => ()

  private[internal] def attempt[A](definition: MetricDef)(body: => Either[MetricError, A]): Either[MetricError, A] =
    try body
    catch case NonFatal(error) => Left(MetricError.BackendFailure(definition.name, error))

  private[internal] def validateLabels(definition: MetricDef): Either[MetricError, Unit] =
    val names = definition.labelNames.toList

    val invalid = names.view.flatMap(name => Naming.checkLabelName(name).left.toOption.map(reason => MetricError.InvalidLabelName(definition.name, name, reason)))

    val duplicated = names.diff(names.distinct).headOption.map(MetricError.DuplicateLabelName(definition.name, _))

    val clashing = names.view
      .zip(definition.labelDomains)
      .flatMap((name, domain) => domain.flatMap(values => values.toList.diff(values.distinct).headOption.map(MetricError.DuplicateLabelValue(definition.name, name, _))))

    invalid.headOption.orElse(duplicated).orElse(clashing.headOption).toLeft(())

  private[internal] def precreation[L](definition: MetricDef, labels: Labels[L], config: MetricsConfig): Either[MetricError, Option[Int]] =
    labels.size.filter(_ => definition.preCreation != PreCreation.Never) match
      case None               => Right(None)
      case Some(combinations) =>
        val each   = seriesEach(definition)
        val series = if combinations > Long.MaxValue / each then Long.MaxValue else combinations * each

        if series <= config.preCreateLimit then Right(Some(combinations.toInt))
        else if definition.preCreation == PreCreation.WithinLimit then Right(None)
        else
          val domains = definition.labelNames.toList.zip(definition.labelDomains.toList.map(_.fold(0)(_.length)))
          Left(MetricError.DomainTooLarge(definition.name, domains, each, series, config.preCreateLimit))

  private def seriesEach(definition: MetricDef): Int =
    definition match
      case histogram: HistogramDef[?, ?] => histogram.buckets.bounds.length + 3
      case _                             => 1
