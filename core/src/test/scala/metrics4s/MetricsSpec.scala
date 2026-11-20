package metrics4s

import java.util.concurrent.atomic.AtomicInteger

import scala.concurrent.duration.FiniteDuration
import scala.compiletime.testing.typeCheckErrors

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import metrics4s.MetricsSpec.Processing

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
      val refused = failure(metrics.register(bySource))

      (
        refused,
        refused.map(_.message),
        backend.isRegistered("processor_source_items"),
        Eval.attempt(metrics.register(bySource.withoutPreCreation)).isRight
      ) shouldBe (
        Some(MetricError.DomainTooLarge(bySource.name, List("outcome" -> 3, "source" -> 2), 1, 6, 5)),
        Some(
          "processor_source_items: 3 outcome x 2 source = 6 series, more than the 5 a metric creates up front; " +
            "raise MetricsConfig.preCreateLimit, or define it withoutPreCreation, or withPreCreation(PreCreation.WithinLimit)"
        ),
        false,
        true,
      )
    }

    "count the limit in series, so a histogram spends its buckets, sum and count on every combination" in withMetrics(
      MetricsConfig.default.withPreCreateLimit(20)
    ) { (metrics, backend) =>
      val narrow = Histogram.seconds("processor_step_seconds", "One step", Buckets(0.1, 1)).labelled[Outcome]
      val wide   = Histogram.seconds("processor_stage_seconds", "One stage", Buckets(0.1, 1, 10, 60)).labelled[Outcome]

      val fits    = Eval.attempt(metrics.register(narrow)).isRight
      val refused = failure(metrics.register(wide))

      (fits, backend.observed("processor_step_seconds", "ok"), refused, refused.map(_.message.takeWhile(_ != ','))) shouldBe (
        true,
        Some(Vector.empty),
        Some(MetricError.DomainTooLarge(wide.name, List("outcome" -> 3), 7, 21, 20)),
        Some("processor_stage_seconds: 3 outcome x 7 series each = 21 series"),
      )
    }

    "create a domain within the limit up front and a larger one on first use, when asked for what fits" in withMetrics(
      MetricsConfig.default.withPreCreateLimit(5)
    ) { (metrics, backend) =>
      val small  = Eval.run(metrics.register(processed.withPreCreation(PreCreation.WithinLimit)))
      val large  = Eval.run(metrics.register(bySource.withPreCreation(PreCreation.WithinLimit)))
      val before = (backend.series("processor_items").size, backend.series("processor_source_items").size)

      Eval.run(small.inc(Outcome.Ok))
      Eval.run(large.inc(ByOutcome(Outcome.Error, Source.Rabbit)))

      (before, backend.series("processor_items")(List("ok")), backend.series("processor_source_items")) shouldBe (
        (3, 0),
        1.0,
        Map(List("error", "rabbit") -> 1.0),
      )
    }

    "keep the way its series are created through new labels" in {
      (
        processed.preCreation,
        processed.withoutPreCreation.labelled[ByOutcome].preCreation,
        processTime.withPreCreation(PreCreation.WithinLimit).labelled[Outcome].preCreation,
        Info("processor_build", "The build").preCreation,
      ) shouldBe (PreCreation.Always, PreCreation.Never, PreCreation.WithinLimit, PreCreation.Never)
    }

    "refuse a reserved or repeated label name and two values rendered the same" in withMetrics() { (metrics, _) =>
      final case class ByBucket(bucket: Outcome)
      given Labels[ByBucket]    = Labels.fromFields(List("le"), List(summon[LabelValue[Outcome]].asInstanceOf[LabelValue[Any]]), (labels, _) => labels.bucket)
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

    "not compile with anything but definitions" in withMetrics() { (metrics, _) =>
      (
        typeCheckErrors("""metrics.registerAll((processed, "processor_items"))""").nonEmpty,
        typeCheckErrors("""metrics.registerAll((processed, cacheSize))""").isEmpty,
      ) shouldBe (true, true)
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

  "registering into a case class" should {
    "fill each field with the instrument of the definition named after it" in withMetrics() { (metrics, backend) =>
      val processing = Eval.run(metrics.registerAs[Processing]((items = processed, time = processTime, cache = cacheSize)))

      Eval.run(processing.items.inc(Outcome.Ok))
      Eval.run(processing.time.observe(2.seconds))
      Eval.run(processing.cache.set(7))

      (backend.series("processor_items")(List("ok")), backend.observed("processor_process_seconds"), backend.series("processor_cache_entries")) shouldBe (
        1.0,
        Some(Vector(2.0)),
        Map(Nil -> 7.0),
      )
    }

    "refuse to compile definitions named or ordered otherwise than the fields, or registering as another instrument" in withMetrics() { (metrics, _) =>
      val named = "Name the definitions after the fields of the case class they fill, in their order, as in (requests = requestsDef, duration = durationDef)"
      val typed = "Give each field of the case class the type of the instrument its definition registers as"

      (
        typeCheckErrors("metrics.registerAs[Processing]((items = processed, cache = cacheSize, time = processTime))").map(_.message),
        typeCheckErrors("metrics.registerAs[Processing]((items = processed, time = processTime, entries = cacheSize))").map(_.message),
        typeCheckErrors("metrics.registerAs[Processing]((processed, processTime, cacheSize))").map(_.message),
        typeCheckErrors("metrics.registerAs[Processing]((items = received, time = processTime, cache = cacheSize))").map(_.message),
      ) shouldBe (List(named), List(named), List(named), List(typed))
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

    "remove from a bounded domain what was set one series at a time as well, and everything when nothing is left" in withMetrics() { (metrics, backend) =>
      val byProvider = Eval.run(metrics.register(providers))

      Eval.run(byProvider.set(Provider("cns"), 1))
      Eval.run(byProvider.add(Provider("betradar"), 4))
      Eval.run(byProvider.replace(Map(Provider("lsports") -> 5)))

      val recalculated = backend.series("processor_provider_entries")

      Eval.run(byProvider.replace(Map.empty))

      (recalculated, backend.series("processor_provider_entries")) shouldBe (Map(List("lsports") -> 5.0), Map.empty)
    }

    "record bytes and seconds in the base unit, and a ratio as it is" in withMetrics() { (metrics, backend) =>
      val size  = Eval.run(metrics.register(Gauge.bytes("cache_size_bytes", "Cache size")))
      val delay = Eval.run(metrics.register(Gauge.seconds("probe_delay_seconds", "Probe delay")))
      val usage = Eval.run(metrics.register(Gauge.ratio("cache_usage_ratio", "Used part of the cache")))

      Eval.run(size.set(2048L))
      Eval.run(delay.set(1500.micros))
      Eval.run(usage.set(0.75))

      (backend.series("cache_size_bytes"), backend.series("probe_delay_seconds"), backend.series("cache_usage_ratio")) shouldBe (
        Map(Nil -> 2048.0),
        Map(Nil -> 0.0015),
        Map(Nil -> 0.75),
      )
    }

    "count what is running: one more while an effect runs, one less once it ends, a failure included" in withMetrics() { (metrics, backend) =>
      val running = Eval.run(metrics.register(inFlight))
      val total   = Eval.run(metrics.register(cacheSize))
      val boom    = IllegalStateException("boom")
      val effect  = summon[Effect[Eval]]

      def now(labels: String*): Double = backend.series(if labels.isEmpty then "processor_cache_entries" else "processor_in_flight")(labels.toList)

      val during = Eval.run(running.track(Outcome.Ok)(effect.delay(now("ok"))))
      val nested = Eval.run(total.track(total.track(effect.delay(now()))))
      val failed = Eval.attempt(running.track(Outcome.Error)(effect.raiseError[Int](boom)))

      (during, nested, failed, backend.series("processor_in_flight"), now()) shouldBe (
        1.0,
        2.0,
        Left(boom),
        Map(List("ok") -> 0.0, List("skipped") -> 0.0, List("error") -> 0.0),
        0.0,
      )
    }

    "keep how long the last run of an effect took, whether it succeeded or failed" in withMetrics() { (metrics, backend) =>
      val seeding = Eval.run(metrics.register(Gauge.seconds("processor_seed_seconds", "Seeding at start")))
      val byEnd   = Eval.run(metrics.register(Gauge.seconds("processor_sync_seconds", "The last synchronisation").labelled[Outcome]))
      val boom    = IllegalStateException("boom")
      val effect  = summon[Effect[Eval]]

      val before = backend.series("processor_seed_seconds")
      val seeded = Eval.run(seeding.timed(effect.delay { Thread.sleep(20); 7 }))
      val failed = Eval.attempt(byEnd.timed(Outcome.Error)(effect.delay { Thread.sleep(20); throw boom }))

      (
        before,
        seeded,
        failed,
        backend.series("processor_seed_seconds")(Nil) >= 0.02,
        backend.series("processor_sync_seconds")(List("error")) >= 0.02,
        backend.series("processor_sync_seconds")(List("ok")),
      ) shouldBe (Map(Nil -> 0.0), 7, Left(boom), true, true, 0.0)
    }

    "move without an effect and without labels on its unsafe side" in withMetrics() { (metrics, backend) =>
      val entries = Eval.run(metrics.register(cacheSize)).unsafe

      entries.set(10)
      entries.add(-3)
      entries.inc()
      entries.inc()
      entries.dec()

      backend.series("processor_cache_entries") shouldBe Map(Nil -> 8.0)
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

object MetricsSpec:
  final case class Processing(items: CounterOf[Eval, Long, Outcome], time: HistogramOf[Eval, FiniteDuration, NoLabels], cache: GaugeOf[Eval, Double, NoLabels])
