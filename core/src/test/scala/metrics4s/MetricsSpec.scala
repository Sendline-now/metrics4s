package metrics4s

import java.util.concurrent.atomic.AtomicInteger

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import scala.concurrent.duration.given
import metrics4s.Eval.given

final class MetricsSpec extends AnyWordSpec, Matchers:
  private val processed   = Counter("processor_items", "Processed items by outcome").labelled[Outcome]
  private val bySource    = Counter("processor_source_items", "Processed items by outcome and source").labelled[ByOutcome]
  private val received    = Counter("processor_received", "Received items")
  private val processTime = Histogram.seconds("processor_process_seconds", "Processing of one item", Buckets.Normal)
  private val batchSize   = Histogram.sizes("processor_batch_items", "Items in a batch", Buckets.Sizes).labelled[Outcome]
  private val cacheSize   = Gauge("processor_cache_entries", "Entries in the cache")
  private val inFlight    = Gauge("processor_in_flight", "Items being processed").labelled[Outcome]
  private val providers   = Gauge("processor_provider_entries", "Entries by provider").labelled[Provider]

  private def withMetrics[A](config: MetricsConfig = MetricsConfig.default)(body: (Metrics[Eval], FakeBackend) => A): A =
    val backend = FakeBackend()
    body(Metrics.of[Eval](backend, config), backend)

  private def failure(fa: Eval[?]): Option[MetricError] =
    Eval.attempt(fa).left.toOption.collect { case error: MetricException => error.error }

  "registering a metric" should {
    "create every series of an enumerated domain at zero, once" in withMetrics() { (metrics, backend) =>
      val _ = Eval.run(metrics.register(processed))

      (backend.series("processor_items"), backend.cellRequests.get) shouldBe (
        Map(List("ok") -> 0.0, List("skipped") -> 0.0, List("error") -> 0.0),
        3,
      )
    }

    "create the product of the domains for several labels" in withMetrics() { (metrics, backend) =>
      val counter = Eval.run(metrics.register(bySource))
      Eval.run(counter.inc(ByOutcome(Outcome.Error, Source.Rabbit)))

      backend.series("processor_source_items") shouldBe Map(
        List("ok", "kafka")       -> 0.0,
        List("ok", "rabbit")      -> 0.0,
        List("skipped", "kafka")  -> 0.0,
        List("skipped", "rabbit") -> 0.0,
        List("error", "kafka")    -> 0.0,
        List("error", "rabbit")   -> 1.0,
      )
    }

    "create no series up front for a bounded label, nor when asked not to" in withMetrics() { (metrics, backend) =>
      val byProvider = Eval.run(metrics.register(providers))
      val lazily     = Eval.run(metrics.register(processed.withoutPreCreation))
      val before     = (backend.series("processor_provider_entries"), backend.series("processor_items"))

      Eval.run(byProvider.set(Provider("cns"), 3))
      Eval.run(lazily.inc(Outcome.Skipped))

      (before, backend.series("processor_provider_entries"), backend.series("processor_items")) shouldBe (
        (Map.empty, Map.empty),
        Map(List("cns")     -> 3.0),
        Map(List("skipped") -> 1.0),
      )
    }

    "refuse a domain larger than the pre-creation limit" in withMetrics(MetricsConfig.default.withPreCreateLimit(5)) { (metrics, backend) =>
      (
        failure(metrics.register(bySource)),
        backend.isRegistered("processor_source_items"),
        Eval.attempt(metrics.register(bySource.withoutPreCreation)).isRight
      ) shouldBe (
        Some(MetricError.DomainTooLarge(bySource.name, 6, 5)),
        false,
        true,
      )
    }

    "refuse a reserved or repeated label name and two values rendered the same" in withMetrics() { (metrics, _) =>
      final case class ByBucket(le: Outcome) derives Labels
      final case class Twice(outcome: Outcome, source: Source)
      given Labels[Twice]       = Labels.fromFields(
        List("outcome", "outcome"),
        List(summon[LabelValue[Outcome]], summon[LabelValue[Source]]).map(_.asInstanceOf[LabelValue[Any]]),
        (twice, field) => twice.productElement(field)
      )
      given LabelValue[Boolean] = LabelValue.enumerated(List(false, true))(_ => "same")
      given Labels[Boolean]     = Labels.single("flag")

      (
        failure(metrics.register(Counter("processor_by_le", "By bucket").labelled[ByBucket])),
        failure(metrics.register(Counter("processor_twice", "Twice").labelled[Twice])),
        failure(metrics.register(Counter("processor_flagged", "Flagged").labelled[Boolean])),
      ) shouldBe (
        Some(MetricError.InvalidLabelName(MetricName.unsafe("processor_by_le"), "le", "is written by the exposition format itself")),
        Some(MetricError.DuplicateLabelName(MetricName.unsafe("processor_twice"), "outcome")),
        Some(MetricError.DuplicateLabelValue(MetricName.unsafe("processor_flagged"), "flag", "same")),
      )
    }

    "refuse a name the backend already has" in withMetrics() { (metrics, _) =>
      val _ = Eval.run(metrics.register(received))

      failure(metrics.register(received)) shouldBe Some(MetricError.AlreadyRegistered(received.name))
    }
  }

  "registering several metrics at once" should {
    "give back one instrument per definition, with its own type" in withMetrics() { (metrics, backend) =>
      val (counter, time, gauge) = Eval.run(metrics.registerAll((processed, processTime, cacheSize)))

      Eval.run(counter.inc(Outcome.Ok))
      Eval.run(time.observe(2.seconds))
      Eval.run(gauge.set(7))

      (backend.series("processor_items")(List("ok")), backend.observed("processor_process_seconds"), backend.series("processor_cache_entries")) shouldBe (
        1.0,
        Some(Vector(2.0)),
        Map(Nil -> 7.0),
      )
    }

    "unregister the ones already registered when a later one fails" in withMetrics() { (metrics, backend) =>
      val _       = Eval.run(metrics.register(received))
      val refused = failure(metrics.registerAll((processed, cacheSize, received)))

      (refused, backend.isRegistered("processor_items"), backend.isRegistered("processor_cache_entries"), backend.isRegistered("processor_received")) shouldBe (
        Some(MetricError.AlreadyRegistered(received.name)),
        false,
        false,
        true,
      )
    }
  }

  "a counter" should {
    "add to the series of its labels, and to its only series without labels" in withMetrics() { (metrics, backend) =>
      val counter = Eval.run(metrics.register(processed))
      val total   = Eval.run(metrics.register(received))

      Eval.run(counter.inc(Outcome.Ok))
      Eval.run(counter.inc(Outcome.Ok))
      Eval.run(counter.add(Outcome.Skipped, 5))
      Eval.run(total.inc())
      Eval.run(total.add(41))
      counter.unsafe.inc(Outcome.Error)

      (backend.series("processor_items"), backend.series("processor_received")) shouldBe (
        Map(List("ok") -> 2.0, List("skipped") -> 5.0, List("error") -> 1.0),
        Map(Nil        -> 42.0),
      )
    }

    "record seconds when it counts time" in withMetrics() { (metrics, backend) =>
      val idle = Eval.run(metrics.register(Counter.seconds("worker_idle_seconds", "Idle time")))

      Eval.run(idle.add(1500.millis))
      Eval.run(idle.add(250.millis))

      backend.series("worker_idle_seconds") shouldBe Map(Nil -> 1.75)
    }

    "be gone from the backend once unregistered" in withMetrics() { (metrics, backend) =>
      val counter = Eval.run(metrics.register(processed))
      Eval.run(counter.unregister)

      (backend.isRegistered("processor_items"), backend.series("processor_items")) shouldBe (false, Map.empty)
    }
  }

  "a gauge" should {
    "set, add, increment and decrement" in withMetrics() { (metrics, backend) =>
      val entries = Eval.run(metrics.register(cacheSize))
      val running = Eval.run(metrics.register(inFlight))

      Eval.run(entries.set(10))
      Eval.run(entries.add(-3))
      Eval.run(running.inc(Outcome.Ok))
      Eval.run(running.inc(Outcome.Ok))
      Eval.run(running.dec(Outcome.Ok))
      Eval.run(running.dec(Outcome.Error))

      (backend.series("processor_cache_entries"), backend.series("processor_in_flight")) shouldBe (
        Map(Nil        -> 7.0),
        Map(List("ok") -> 1.0, List("skipped") -> 0.0, List("error") -> -1.0),
      )
    }

    "reset what a full recalculation leaves out to zero when its domain is enumerated" in withMetrics() { (metrics, backend) =>
      val running = Eval.run(metrics.register(inFlight))

      Eval.run(running.set(Outcome.Error, 5))
      Eval.run(running.replace(Map(Outcome.Ok -> 3)))

      backend.series("processor_in_flight") shouldBe Map(List("ok") -> 3.0, List("skipped") -> 0.0, List("error") -> 0.0)
    }

    "remove what a full recalculation leaves out when its domain is bounded" in withMetrics() { (metrics, backend) =>
      val byProvider = Eval.run(metrics.register(providers))

      Eval.run(byProvider.replace(Map(Provider("cns") -> 1, Provider("lsports") -> 2)))
      Eval.run(byProvider.replace(Map(Provider("lsports") -> 5)))

      backend.series("processor_provider_entries") shouldBe Map(List("lsports") -> 5.0)
    }

    "record bytes and seconds in the base unit" in withMetrics() { (metrics, backend) =>
      val size  = Eval.run(metrics.register(Gauge.bytes("cache_size_bytes", "Cache size")))
      val delay = Eval.run(metrics.register(Gauge.seconds("probe_delay_seconds", "Probe delay")))

      Eval.run(size.set(2048L))
      Eval.run(delay.set(1500.micros))

      (backend.series("cache_size_bytes"), backend.series("probe_delay_seconds")) shouldBe (Map(Nil -> 2048.0), Map(Nil -> 0.0015))
    }
  }

  "a histogram" should {
    "observe durations in seconds and sizes as they are" in withMetrics() { (metrics, backend) =>
      val time  = Eval.run(metrics.register(processTime))
      val batch = Eval.run(metrics.register(batchSize))

      Eval.run(time.observe(250.millis))
      Eval.run(time.observe(3.seconds))
      Eval.run(batch.observe(Outcome.Ok, 40L))
      batch.unsafe.observe(Outcome.Ok, 2L)

      (backend.observed("processor_process_seconds"), backend.observed("processor_batch_items", "ok"), backend.observed("processor_batch_items", "error")) shouldBe (
        Some(Vector(0.25, 3.0)),
        Some(Vector(40.0, 2.0)),
        Some(Vector.empty),
      )
    }

    "time an effect that succeeds or fails, and label it by how it ended" in withMetrics() { (metrics, backend) =>
      val time   = Eval.run(metrics.register(processTime))
      val byEnd  = Eval.run(metrics.register(Histogram.seconds("processor_handle_seconds", "Handling of one item", Buckets.Normal).labelled[Outcome]))
      val boom   = IllegalStateException("boom")
      val effect = summon[Effect[Eval]]

      val succeeded = Eval.attempt(time.timed(effect.delay { Thread.sleep(20); 1 }))
      val failed    = Eval.attempt(time.timed(effect.raiseError[Int](boom)))
      val labelled  = Eval.attempt(byEnd.timedBy(effect.raiseError[Int](boom))(result => if result.isRight then Outcome.Ok else Outcome.Error))

      val observed = backend.observed("processor_process_seconds").getOrElse(Vector.empty)

      (
        succeeded,
        failed,
        labelled,
        observed.size,
        observed.headOption.exists(_ >= 0.02),
        backend.observed("processor_handle_seconds", "error").map(_.size),
        backend.observed("processor_handle_seconds", "ok").map(_.size),
      ) shouldBe (
        Right(1),
        Left(boom),
        Left(boom),
        2,
        true,
        Some(1),
        Some(0),
      )
    }
  }

  "measuring an operation" should {
    "time it and count it by the outcome of its value, or as failed when it raises, re-raising the very same error" in withMetrics() { (metrics, backend) =>
      val time     = Eval.run(metrics.register(Histogram.seconds("processor_fetch_seconds", "Fetching of one item", Buckets.Normal).labelled[(source: Source)]))
      val outcomes = Eval.run(metrics.register(bySource))
      val fetch    = Measured(time, outcomes, Outcome.Error)((labels, outcome) => ByOutcome(outcome, labels.source))
      val boom     = IllegalStateException("boom")
      val effect   = summon[Effect[Eval]]

      def fetched(source: Source)(fa: Eval[Option[Int]]) = Eval.attempt(fetch.measure((source = source))(fa)(_.fold(Outcome.Skipped)(_ => Outcome.Ok)))

      val found   = fetched(Source.Kafka)(effect.delay { Thread.sleep(20); Some(1) })
      val missing = fetched(Source.Kafka)(effect.pure(None))
      val failed  = fetched(Source.Rabbit)(effect.raiseError(boom))

      val counted = backend.series("processor_source_items")

      (
        found,
        missing,
        failed.left.toOption.map(error => (error eq boom, error.getSuppressed.toList)),
        (counted(List("ok", "kafka")), counted(List("skipped", "kafka")), counted(List("error", "rabbit")), counted(List("ok", "rabbit"))),
        backend.observed("processor_fetch_seconds", "kafka").map(observed => (observed.size, observed.headOption.exists(_ >= 0.02))),
        backend.observed("processor_fetch_seconds", "rabbit").map(_.size),
      ) shouldBe (
        Right(Some(1)),
        Right(None),
        Some((true, Nil)),
        (1.0, 1.0, 1.0, 0.0),
        Some((2, true)),
        Some(1),
      )
    }

    "measure an operation without labels, its outcomes counted by themselves" in withMetrics() { (metrics, backend) =>
      val (time, counter) = Eval.run(metrics.registerAll((processTime, processed)))
      val process         = Measured(time, counter, Outcome.Error)((_, outcome) => outcome)

      val result = Eval.run(process.measure(summon[Effect[Eval]].pure(3))(count => if count > 0 then Outcome.Ok else Outcome.Skipped))

      (result, backend.series("processor_items")(List("ok")), backend.observed("processor_process_seconds").map(_.size)) shouldBe (3, 1.0, Some(1))
    }

    "re-raise the operation's own error when its outcome cannot be recorded, the recording failure suppressed in it" in withMetrics() { (metrics, backend) =>
      val (time, counter) = Eval.run(metrics.registerAll((processTime, processed)))
      val unlabelled      = IllegalArgumentException("no such outcome")
      val process         = Measured(time, counter, Outcome.Error)((_, _) => throw unlabelled)
      val boom            = IllegalStateException("boom")
      val effect          = summon[Effect[Eval]]

      val failed    = Eval.attempt(process.measure(effect.raiseError[Int](boom))(_ => Outcome.Ok))
      val succeeded = Eval.attempt(process.measure(effect.pure(1))(_ => Outcome.Ok))

      (failed.left.toOption.map(error => (error eq boom, error.getSuppressed.toList)), succeeded, backend.series("processor_items").values.sum) shouldBe (
        Some((true, List(unlabelled))),
        Left(unlabelled),
        0.0,
      )
    }
  }

  "observing" should {
    "report values read at scrape time, and stop once released" in withMetrics() { (metrics, backend) =>
      val reads = AtomicInteger(0)

      val during = Eval.run(
        metrics
          .observe(inFlight.withoutPreCreation)(out =>
            reads.incrementAndGet(): Unit
            out.report(Outcome.Ok, 2.0)
            out.report(Outcome.Error, 1.0)
          )
          .use(_ => summon[Effect[Eval]].delay((backend.scrape(), backend.scrape())))
      )

      (during._1, during._2 == during._1, reads.get, backend.scrape(), backend.isRegistered("processor_in_flight")) shouldBe (
        Map(("processor_in_flight", List("ok")) -> 2.0, ("processor_in_flight", List("error")) -> 1.0),
        true,
        2,
        Map.empty,
        false,
      )
    }

    "read the shared state once per scrape for every metric of a batch" in withMetrics() { (metrics, backend) =>
      val reads = AtomicInteger(0)
      val idle  = Counter.seconds("worker_idle_seconds", "Idle time")

      val scraped = Eval.run(
        metrics.observeAll { reads.incrementAndGet(); (entries = 12.0, idle = 3.seconds) }(
          Observation(cacheSize)((state, out) => out.report(state.entries)),
          Observation(idle)((state, out) => out.report(state.idle)),
        )
          .use(_ => summon[Effect[Eval]].delay(backend.scrape()))
      )

      (scraped, reads.get) shouldBe (
        Map(("processor_cache_entries", Nil) -> 12.0, ("worker_idle_seconds", Nil) -> 3.0),
        1,
      )
    }
  }
