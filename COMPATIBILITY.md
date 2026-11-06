# Compatibility

[← back to the README](README.md)

## What the artifacts promise

* **Before `1.0.0`, a minor release may break the API.** Every break is listed in [CHANGELOG.md](CHANGELOG.md) with
  what to change. `versionScheme := "semver-spec"` says so to sbt and to Scala Steward.
* **From `1.0.0` on, MiMa checks** binary compatibility against the previous release on every build. Every deliberate
  break inside a major version is then an exclusion in `build.sbt` and an entry here, so nothing is waived silently.
* New members of `Effect`, `Dispatch` and the backend SPI carry default implementations wherever one exists, so an
  instance of your own keeps compiling across minor releases.
* Configuration types (`MetricsConfig`) are builders with a private constructor, not case classes, so adding an option
  is not a binary break.

## What it is built and tested against

| | Version |
| --- | --- |
| Scala | `3.9.0` (built on the LTS); the `it` module compiles on `3.10.0` against it |
| JDK | 25 or newer, checked when the build loads (Kyo ships Java 25 class files) |
| Prometheus Java client | `1.10.0` |
| cats-effect | `3.7.1` |
| ZIO | `2.1.27` |
| Kyo | `1.0.0-RC7` |
| rapid | `2.9.9` |
| http4s | `0.23.38` |
| tapir | `1.14.1` |
| sttp client | `4.1.1` |
| MongoDB driver | `5.13.0`, and `mongo4s` `5.1.0` in the integration tests |
| Prometheus server | `v3.5.0` in the integration tests |
| Grafana | `12.0.0` in the integration tests; the dashboards use schema version 39 |

## Coming from commons

The metric names are unchanged, so dashboards and alerts keep working. Code changes:

| commons | metrics4s |
| --- | --- |
| `Counter.builder().name(...).labelNames("outcome").register(registry)` | `Counter("...", "...").labelled[Outcome]`, registered once with `Metrics[F].register` |
| `labelValues(outcome.value).inc()` | `inc(Outcome.Ok)` |
| `Histograms.seconds(...)` and `record(time)` | `Histogram.seconds(...)` and `observe(time)` |
| `histogram.timed(fa)` (`metrics-effect`) | `histogram.timed(fa)` |
| `CallbackGauge.resource(builder, source)` | `Metrics[F].observeAllF(source, timeout)(Observation(...))` |
| `CatsRuntimeMetrics.resource(registry)` | `metrics4s.runtime.cats.CatsRuntimeMetrics.resource()` with a `given Metrics[F]` |
| `ZioRuntimeMetrics.resource(registry)` | `metrics4s.runtime.zio.ZioRuntimeMetrics.scoped` |
| `KyoRuntimeMetrics.resource(registry)` | `metrics4s.runtime.kyo.KyoRuntimeMetrics.scoped` |
| `MetricsTestkit.snapshot(registry)` | `MetricsTestkit().series`, or the typed `value` and `histogram` |
| `PrometheusMetrics.start(port)` | `PrometheusExporter.allocated(port, registry)` plus `PrometheusMetrics.jvm(registry)` |
