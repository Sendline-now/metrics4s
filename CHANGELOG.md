# Changelog

[← back to the README](README.md)

Notable changes per release. Breaking changes and how to migrate are in [COMPATIBILITY.md](COMPATIBILITY.md); what is
not covered yet is in [ROADMAP.md](ROADMAP.md).

## 0.2.0

The first public release. It carries `0.1.0`, which was only tested inside Sendline.now, and what wiring the library
into the Sendline backend asked for, with its dashboard generated and imported into Grafana.

### Added

- **`Measured`: an operation timed and counted by outcome in one call.** `Measured(histogram, counter, failed)(labels)`
  gives `measure(labels)(fa)(outcome)`. The outcome is read from the value, and an error counts as `failed` and is
  re-raised as the same instance. A cancellation records nothing. When recording fails as well, the operation's error
  is still the one raised, with the recording failure suppressed in it.
- **`registerAs[P]`** registers a named tuple of definitions into a case class. The names must be its fields in their
  order, and the instruments must fit their types, or the call does not compile. Two instruments of the same type can
  no longer swap places unnoticed, as they could in a tuple.
- **`ExporterException`.** `PrometheusExporter.allocated` names the port it cannot listen on, in the message and as
  `port`, with the bind failure as the cause.
- **The integrations' definitions without an instance.** `HttpServerMetrics.requests`, `.duration`, `.inFlight` and
  `.definitions`, the same on `HttpClientMetrics`, and `MongoMetrics.commands`, `.failures`, `.connections`,
  `.checkOut`, `.checkOutFailures` and `.definitions`. A dashboard draws from them without the route or collection
  list. An instance still defines the same series, with its values pre-created.
- `metrics4s-grafana`:
  - **typed label filters:** `.where(_.outcome =!= Outcome.Ok)`, with `===`, `.in(a, b)` and `.notIn(a, b)`, rendered
    through the label's own `LabelValue`. A value of another type, or anything but one label field compared with
    values, does not compile. Panels and stats both take it;
  - **one quantile:** `Panel.quantile(histogram, 0.95)` and `Stat.quantile(histogram, 0.95)`;
  - **shares:** `Panel.share(counter)(condition)` and `Stat.share(counter)(condition)`, the matching part of the rate
    over all of it, in `percentunit`;
  - **stat thresholds:** `.withThresholds(Color.Green, 0.01 -> Color.Orange, 0.05 -> Color.Red)`, refused by `render`
    unless the values are finite and increasing;
  - **the drift check:** `dashboard.missingSeries(definitions)` lists the series the dashboard's own panels and stats
    read that no definition serves. `dashboard.reads` is every series it reads.

- **Info metrics.** `Info("name", "help").labelled[L]` is a constant series `name_info = 1` whose labels carry the
  facts: the build's version, the stage, a configuration. `register` gives an `InfoOf[F, L]`, whose `set(labels)`
  replaces the previous labels, so one copy shows exactly one series.
  - The name is checked at compile time like the others, and a unit suffix is refused: an info carries no unit.
  - `MetricsBackend.info` is a new SPI member with a default that refuses, so an existing backend still compiles.
    The Prometheus backend implements it with the client's `Info`.
  - `metrics4s-grafana`: `Stat.info(definition)(_.label)` and `Stat.infoBy(definition, "label")` show a label's
    value as the stat's text (the version, not a count). `MetricsDoc` lists the series as `name_info`, type `info`.
- **Named-tuple label selectors in dashboards.** `Panel.rate(x).by(_.route)` now compiles when the labels are a
  named tuple, `(route: Route, status: StatusClass)`, and not only a case class.

### Fixed

- **`metrics4s-tapir` counted a request twice when its body failed after the headers.** Tapir calls both the
  response callback and the exception callback for such a request. It was counted once as its status and once as
  `failed`, and `http_server_in_flight` went below zero. A request now finishes once, whichever callback comes
  first.
- **The README's recording example did not compile.** It set a labelled gauge and observed a labelled histogram
  without labels. Every Scala block in the README is now copied from an example the integration tests compile.

### Changed

- Every module is compiled to Java 17 class files against the JDK 17 API, so it runs on JDK 17. `metrics4s-rapid`
  needs 21 and `metrics4s-kyo` needs 25, like rapid and Kyo themselves. Only the build needs JDK 25.
- `MetricKind` has a new case, `Info`. A match on it in user code that was exhaustive now warns.
- `GrafanaError` has new cases, `NotOneLabel`, `UnknownLabelValue`, `InvalidQuantile` and `InvalidThresholds`, so an
  exhaustive match on it now warns. The message of `UnknownLabel` no longer says "to group by", because a filter
  raises it too.
- `PrometheusExporter.allocated` fails with `ExporterException` where it raised the bare `IOException`.
- `Stat` is a sealed class. The metric stats are `MetricStat[L]`, typed by the labels `.where` filters on, and
  `Stat.podsUp` and `Stat.custom` are a `CustomStat`. `Stat.infoBy` refuses a label the info does not carry. A stat's
  `thresholds` are a `Thresholds` of `Color`s, no longer a list of strings.
- `.gitattributes` keeps `*.json` with LF endings, so the byte-for-byte dashboard tests pass on a Windows checkout.

## 0.1.0

Not released publicly: only tested inside Sendline.now.

### Added

- **`metrics4s-core`**, with no dependencies:
  - metric definitions (`Counter`, `Gauge`, `Histogram`) whose names, units and help are checked at compile time;
  - typed labels: `derives LabelValue` for enums, `derives Labels` for case classes, named tuples, `Labels.single`,
    bounded values;
  - series pre-created for enumerated domains, with a limit;
  - instruments with an effect-free `unsafe` side, `registerAll` with rollback;
  - `timed` and `timedBy`;
  - gauges with `replace`;
  - callbacks read at scrape time: `observe`, `observeAll`, and `observeAllF` through `Dispatch`;
  - the effect bridge (`Effect`, `Dispatch`, `Allocated`) and the backend SPI.
- **`metrics4s-prometheus`**: the backend on the Prometheus Java client 1.10 with classic histograms only, the HTTP
  exporter, the client's JVM metrics, and `metrics4s_callback_errors_total`.
- **Runtimes**: `metrics4s-cats`, `-zio`, `-kyo` and `-rapid`, each with `Effect`, `Dispatch` and the native resource
  adapter. The cats-effect, ZIO and Kyo runtime metrics export the same families as commons, names and types unchanged.
- **`metrics4s-testkit`**: a registry per test, typed reads, the raw series map, and `missingSeries(dashboard)`.
- **`metrics4s-grafana`**: Grafana dashboards generated from the definitions, validated against the metrics guide,
  with shared rows for cats-effect, ZIO, Kyo, the JVM, HTTP servers, HTTP clients and MongoDB. `MetricsDoc` writes the
  README tables.
- **Integrations**:
  - `metrics4s-http`, the shared HTTP definitions;
  - `metrics4s-http4s`, server and client middleware;
  - `metrics4s-tapir`, a metric for tapir's `MetricsRequestInterceptor`;
  - `metrics4s-sttp`, a listener backend for sttp client 4;
  - `metrics4s-mongo`, command and connection-pool listeners for the MongoDB driver, which `mongo4s` takes through
    its settings.

### Found on the way

- The Kyo `guaranteeCase` of `mongo4s` `5.1.0` reports a cancellation as `Errored(KyoException("fiber abandoned"))`
  on Kyo 1.0.0-RC7. The instance here records how the body ended and reports `Canceled`; `mongo4s` `5.1.1` does the
  same.
- commons' ZIO `Dispatch` allocates its runner inside an uninterruptible region, so a timed-out callback leaves its
  fiber running. The runner here runs `fa.interruptible`.
