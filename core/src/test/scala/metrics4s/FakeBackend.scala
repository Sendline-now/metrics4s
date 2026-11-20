package metrics4s

import java.util.concurrent.atomic.AtomicInteger

import scala.collection.concurrent.TrieMap

final class FakeBackend extends MetricsBackend:
  private val registered   = TrieMap.empty[String, MetricDef]
  private val values       = TrieMap.empty[(String, List[String]), Double]
  private val observations = TrieMap.empty[(String, List[String]), Vector[Double]]
  private val collectors   = TrieMap.empty[Registration, (IArray[ObservableDef[?, ?]], ObservedValues => Unit)]

  val cellRequests: AtomicInteger = AtomicInteger(0)

  def isRegistered(name: String): Boolean = registered.contains(name)

  def series(name: String): Map[List[String], Double] =
    values.collect { case ((metric, labels), value) if metric == name => labels -> value }.toMap

  def observed(name: String, labels: String*): Option[Vector[Double]] = observations.get((name, labels.toList))

  def scrape(): Map[(String, List[String]), Double] =
    collectors.values.toList.flatMap { (definitions, collect) =>
      val reported = List.newBuilder[((String, List[String]), Double)]
      collect((definition, labelValues, value) => reported += (definitions(definition).name.value, labelValues.toList) -> value)
      reported.result()
    }.toMap

  def counter(definition: CounterDef[?, ?]): Either[MetricError, Series[CounterCell]] =
    claim(definition).map { _ =>
      seriesOf(definition) { key =>
        values.putIfAbsent(key, 0.0): Unit
        key
      }(key => new CounterCell { def add(amount: Double): Unit = values.updateWith(key)(current => Some(current.getOrElse(0.0) + amount)): Unit })
    }

  def gauge(definition: GaugeDef[?, ?]): Either[MetricError, Series[GaugeCell]] =
    claim(definition).map { _ =>
      seriesOf(definition) { key =>
        values.putIfAbsent(key, 0.0): Unit
        key
      } { key =>
        new GaugeCell:
          def set(value: Double): Unit = values.update(key, value)
          def add(delta: Double): Unit = values.updateWith(key)(current => Some(current.getOrElse(0.0) + delta)): Unit
      }
    }

  def histogram(definition: HistogramDef[?, ?]): Either[MetricError, Series[HistogramCell]] =
    claim(definition).map { _ =>
      seriesOf(definition) { key =>
        observations.putIfAbsent(key, Vector.empty): Unit
        key
      }(key => new HistogramCell { def observe(value: Double): Unit = observations.updateWith(key)(current => Some(current.getOrElse(Vector.empty) :+ value)): Unit })
    }

  def observe(definitions: IArray[ObservableDef[?, ?]], collect: ObservedValues => Unit): Either[MetricError, Registration] =
    val claimed = definitions.toList.foldLeft[Either[MetricError, List[MetricDef]]](Right(Nil)) { (acc, definition) =>
      acc.flatMap(done =>
        claim(definition).map(_ => definition :: done).left.map { error =>
          done.foreach(previous => registered.remove(previous.name.value))
          error
        }
      )
    }

    claimed.map { _ =>
      lazy val registration: Registration = () =>
        collectors.remove(registration): Unit
        definitions.foreach(definition => registered.remove(definition.name.value))
      collectors.update(registration, (definitions, collect))
      registration
    }

  private def claim(definition: MetricDef): Either[MetricError, Unit] =
    registered.putIfAbsent(definition.name.value, definition) match
      case None    => Right(())
      case Some(_) => Left(MetricError.AlreadyRegistered(definition.name))

  private def seriesOf[K, C](definition: MetricDef)(open: ((String, List[String])) => K)(cellOf: K => C): Series[C] =
    new Series[C]:
      def cell(labelValues: IArray[String]): C =
        cellRequests.incrementAndGet(): Unit
        cellOf(open((definition.name.value, labelValues.toList)))

      def remove(labelValues: IArray[String]): Unit =
        values.remove((definition.name.value, labelValues.toList)): Unit
        observations.remove((definition.name.value, labelValues.toList)): Unit

      def labelValues: Iterable[IArray[String]] =
        (values.keys ++ observations.keys).collect { case (metric, labels) if metric == definition.name.value => IArray.from(labels) }.toList

      def unregister(): Unit =
        registered.remove(definition.name.value): Unit
        values.filterInPlace((key, _) => key._1 != definition.name.value): Unit
        observations.filterInPlace((key, _) => key._1 != definition.name.value): Unit
