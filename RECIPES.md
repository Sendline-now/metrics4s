# Recipes

[← back to the README](README.md)

Answers to what wiring a service asks for once the [quick start](README.md#quick-start) runs. Every Scala block on this
page is copied from an example the integration tests compile, and `RecipesItSpec` runs the ones that do something, so
the page keeps up with the library.

* [Serving /metrics from the service's own routes](#serving-metrics-from-the-services-own-routes)
* [A ZIO service](#a-zio-service)
* [Labels known only at startup](#labels-known-only-at-startup)
* [Errors as a label](#errors-as-a-label)
* [A dashboard kept in the repository](#a-dashboard-kept-in-the-repository)
* [Stats that show when something is wrong](#stats-that-show-when-something-is-wrong)

## Serving /metrics from the service's own routes

The exporter listens on a port of its own, so `/metrics` never goes out through the public ingress. A service that
already runs an internal http4s server, for health checks or administration, can serve the exposition there instead of
opening one more port:

```scala
def metricsRoutes(registry: PrometheusRegistry): HttpRoutes[IO] =
  HttpRoutes.of[IO] { case GET -> Root / "metrics" =>
    Ok(PrometheusScrape.text(registry))
  }
```

The body is the text format in UTF-8, which every Prometheus version reads. `PrometheusMetrics.jvm(registry)` adds the
JVM metrics to the same registry, so the route serves them too.

## A ZIO service

`ZioInstances` gives the instances for `Task`, and the syntax import turns an `Allocated` into a scoped `ZIO`, so the
exporter and the runtime metrics close with the app:

```scala
object Main extends ZIOAppDefault:
  private val registry = PrometheusRegistry()

  given Metrics[Task] = PrometheusMetrics[Task](registry)

  def run =
    for
      _                 <- PrometheusExporter.allocated[Task](9090, registry).toScoped
      _                 <- PrometheusMetrics.jvm[Task](registry)
      _                 <- ZioRuntimeMetrics.scoped
      (processed, _, _) <- Metrics[Task].registerAll((ProcessorMetrics.processed, ProcessorMetrics.processTime, ProcessorMetrics.cacheSize))
      _                 <- processed.inc(Outcome.Ok)
    yield ()
```

The imports are `metrics4s.runtime.zio.ZioInstances.given` and `metrics4s.runtime.zio.syntax.given`. Kyo and rapid
follow the same shape with their own `toScope` and `use`, see [Runtimes](README.md#runtimes).

## Labels known only at startup

An enum lists its values at compile time. Tenants, regions or queues read from the configuration are known when the
service starts, so their `LabelValue` is built then, and the definitions with it:

```scala
final case class Tenant(id: String)

object Tenant:
  val Other: Tenant = Tenant("other")

final class TenantMetrics(configured: List[Tenant]):
  given LabelValue[Tenant] = LabelValue.enumerated(configured :+ Tenant.Other)(_.id)

  val invoices = Counter("billing_invoices", "Invoices issued by tenant").labelled[(tenant: Tenant)]

  def tenantOf(id: String): Tenant = configured.find(_.id == id).getOrElse(Tenant.Other)
```

Every configured tenant is pre-created at zero, so the dashboard shows the ones with no invoices yet. Recording a
value outside the list fails, which is why a request's tenant goes through `tenantOf`: an id nobody configured is
counted as `other` and never becomes a series of its own.

A list that the configuration can grow past `MetricsConfig.preCreateLimit` would stop the service at start with
`DomainTooLarge`. Where that would be worse than a panel that fills in late, the definition says
`.withPreCreation(PreCreation.WithinLimit)`: its series are created at zero while they fit and on first use after.

## Errors as a label

An error message, or an exception's class name, is a new series for every new message. A closed set of results is what
a dashboard can group by and an alert can name:

```scala
enum CallResult derives LabelValue:
  case Ok, Timeout, Refused, Failed

object CallResult:
  given Labels[CallResult] = Labels.single("result")

  def of(result: Either[Throwable, ?]): CallResult =
    result match
      case Right(_)                  => CallResult.Ok
      case Left(_: TimeoutException) => CallResult.Timeout
      case Left(_: ConnectException) => CallResult.Refused
      case Left(_)                   => CallResult.Failed

val calls = Histogram.seconds("payments_call_seconds", "Calls to the payment provider by result", Buckets.Normal).labelled[CallResult]

def charged(histogram: HistogramOf[IO, FiniteDuration, CallResult])(charge: IO[Receipt]): IO[Receipt] = histogram.timedBy(charge)(CallResult.of)
```

`timedBy` times the call and picks the labels from how it ended, so the failures land in the same histogram as the
successes, each under its result. The error itself is re-raised unchanged.

## A dashboard kept in the repository

A generated dashboard committed next to the code is reviewed like code. Three tests keep it honest: the file is what
the definitions render, the dashboard reads nothing the definitions do not serve, and the README beside it lists every
metric:

```scala
"the dashboard" should {
  "be the file in the repository, so a metric and its panels change in the same commit" in {
    ProcessorMetrics.dashboard.render shouldBe Right(Files.readString(Paths.get("metrics/line-processor.json")))
  }

  "read only series the service's definitions serve" in {
    ProcessorMetrics.dashboard.missingSeries(ProcessorMetrics.definitions) shouldBe Set.empty
  }

  "have every metric in the table of the README beside it" in {
    val readme = Files.readString(Paths.get("metrics/README.md"))

    MetricsDoc.embedded(readme, ProcessorMetrics.definitions) shouldBe Right(readme)
  }
}
```

When the first test fails on purpose, `ProcessorMetrics.dashboard.writeTo(Paths.get("metrics"))` writes the new file,
and the diff shows what changed on the dashboard. `MetricsTestkit().missingSeries(dashboard)` runs the same check
against a registry, which covers the shared rows as well: register the service's metrics, the runtime metrics and
`PrometheusMetrics.jvm` on the testkit's registry first.

The README keeps its table between `<!-- metrics4s:table -->` and `<!-- /metrics4s:table -->`, and everything
outside the two is written by hand and left alone. When the third test fails, write what `MetricsDoc.embedded` gives
back to the file.

## Stats that show when something is wrong

A stat with thresholds turns orange or red by itself, and a filter narrows it to the route that matters:

```scala
Stats(
  Stat.podsUp,
  Stat
    .share(HttpServerMetrics.requests)(_.status === StatusClass.ServerError)
    .titled("Server errors")
    .withThresholds(Color.Green, 0.01 -> Color.Orange, 0.05 -> Color.Red),
  Stat
    .quantile(HttpServerMetrics.duration, 0.99)
    .where(_.route === Route("POST /payments"))
    .titled("Payments, p99")
    .withThresholds(Color.Green, 0.5 -> Color.Orange, 2.0 -> Color.Red),
)
```

The shares are in `percentunit`, so `0.01` is 1%, and the quantile is in seconds. Threshold values must increase, or
`render` fails with `GrafanaError.InvalidThresholds`. The same numbers make a good first alert, written in the alerting
rules rather than on the dashboard.
