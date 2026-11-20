package metrics4s

import scala.compiletime.testing.typeCheckErrors

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import metrics4s.internal.Naming

final class NamingSpec extends AnyWordSpec, Matchers:
  private def errorsOf(errors: List[scala.compiletime.testing.Error]): List[String] = errors.map(_.message)

  "a metric definition" should {
    "keep a name that follows the conventions, with the unit its constructor records" in {
      val counter   = Counter("processor_items", "Processed items")
      val seconds   = Counter.seconds("worker_idle_seconds", "Idle time")
      val gauge     = Gauge.bytes("cache_size_bytes", "Cache size")
      val histogram = Histogram.seconds("processor_process_seconds", "Processing of one item", Buckets.Normal)

      (
        (counter.name.value, counter.kind, counter.unit),
        (seconds.name.value, seconds.unit),
        (gauge.name.value, gauge.kind, gauge.unit),
        (histogram.name.value, histogram.kind, histogram.unit, histogram.buckets.bounds.toList),
      ) shouldBe (
        ("processor_items", MetricKind.Counter, None),
        ("worker_idle_seconds", Some(MetricUnit.Seconds)),
        ("cache_size_bytes", MetricKind.Gauge, Some(MetricUnit.Bytes)),
        ("processor_process_seconds", MetricKind.Histogram, Some(MetricUnit.Seconds), Buckets.Normal.bounds.toList),
      )
    }

    "not compile with a counter named _total" in {
      errorsOf(typeCheckErrors("""Counter("processor_items_total", "Processed items")""")) shouldBe List(
        "Invalid metric name \"processor_items_total\": it must not end with _total: every counter is exposed with _total added"
      )
    }

    "not compile with a name that is not snake case" in {
      errorsOf(typeCheckErrors("""Gauge("ProcessorItems", "Processed items")""")) shouldBe List(
        "Invalid metric name \"ProcessorItems\": it must be snake case: a lowercase letter, then lowercase letters, digits and underscores"
      )
    }

    "not compile with a double underscore, a trailing underscore or a suffix the exposition adds" in {
      (
        errorsOf(typeCheckErrors("""Counter("processor__items", "Processed items")""")),
        errorsOf(typeCheckErrors("""Counter("processor_items_", "Processed items")""")),
        errorsOf(typeCheckErrors("""Gauge("processor_bucket", "Processed items")""")),
        errorsOf(typeCheckErrors("""Gauge("processor_total", "Processed items")""")),
      ) shouldBe (
        List("Invalid metric name \"processor__items\": it must not contain a double underscore"),
        List("Invalid metric name \"processor_items_\": it must not end with an underscore"),
        List("Invalid metric name \"processor_bucket\": it must not end with _bucket, which the exposition format adds itself"),
        List("Invalid metric name \"processor_total\": it must not end with _total, which marks a counter"),
      )
    }

    "not compile with a unit suffix its constructor does not record, nor without the one it does" in {
      (
        errorsOf(typeCheckErrors("""Gauge("cache_size_bytes", "Cache size")""")),
        errorsOf(typeCheckErrors("""Histogram.sizes("batch_seconds", "Batch", Buckets.Sizes)""")),
        errorsOf(typeCheckErrors("""Histogram.seconds("processor_process_time", "Processing", Buckets.Normal)""")),
        errorsOf(typeCheckErrors("""Counter.bytes("sent", "Sent")""")),
        errorsOf(typeCheckErrors("""Gauge("cache_usage_ratio", "Used part of the cache")""")),
        errorsOf(typeCheckErrors("""Gauge.ratio("cache_usage", "Used part of the cache")""")),
      ) shouldBe (
        List("Invalid metric name \"cache_size_bytes\": it must not end with _bytes: use Gauge.bytes, which records the unit"),
        List("Invalid metric name \"batch_seconds\": it must not end with _seconds: use Histogram.seconds, which records the unit"),
        List("Invalid metric name \"processor_process_time\": it must end with _seconds"),
        List("Invalid metric name \"sent\": it must end with _bytes"),
        List("Invalid metric name \"cache_usage_ratio\": it must not end with _ratio: use Gauge.ratio, which records the unit"),
        List("Invalid metric name \"cache_usage\": it must end with _ratio"),
      )
    }

    "keep a ratio for gauges: a histogram may be named after one, there being no constructor that records it" in {
      val gauge = Gauge.ratio("cache_usage_ratio", "Used part of the cache")

      (
        (gauge.name.value, gauge.kind, gauge.unit),
        Histogram.sizes("codec_compression_ratio", "Compressed size over the original", Buckets.Sizes).unit,
      ) shouldBe (("cache_usage_ratio", MetricKind.Gauge, Some(MetricUnit.Ratio)), None)
    }

    "not compile with an info metric named _info or carrying a unit" in {
      (
        errorsOf(typeCheckErrors("""Info("processor_build_info", "The build")""")),
        errorsOf(typeCheckErrors("""Info("processor_uptime_seconds", "The build")""")),
        Info("processor_build", "The build").name.value,
      ) shouldBe (
        List("Invalid metric name \"processor_build_info\": it must not end with _info, which the exposition format adds itself"),
        List("Invalid metric name \"processor_uptime_seconds\": it must not end with _seconds: an info metric carries no unit"),
        "processor_build",
      )
    }

    "not compile without help, nor with a name that is not a literal" in {
      (
        errorsOf(typeCheckErrors("""Counter("processor_items", "  ")""")),
        errorsOf(typeCheckErrors("""{ val name = "processor_items"; Counter(name, "Processed items") }""")),
      ) shouldBe (
        List("Invalid metric help: help must not be empty"),
        List("A metric name must be a string literal, so that it is checked where it is written"),
      )
    }

    "not record a duration where a count is expected" in {
      val processTime = Histogram.seconds("processor_process_seconds", "Processing of one item", Buckets.Normal)

      typeCheckErrors("""processTime.labelled[Outcome]: HistogramDef[Long, Outcome]""").nonEmpty shouldBe true
      processTime.recorded.toDouble(scala.concurrent.duration.FiniteDuration(250, "ms")) shouldBe 0.25
    }
  }

  "snake case" should {
    "split words at case changes, keep acronyms together and keep digits with what they follow" in {
      List("Ok", "OK", "NotFound", "HTTPVersionNotSupported", "Timeout2Late", "errorKind", "already_snake").map(Naming.snakeCase) shouldBe List(
        "ok",
        "ok",
        "not_found",
        "http_version_not_supported",
        "timeout2_late",
        "error_kind",
        "already_snake",
      )
    }
  }
