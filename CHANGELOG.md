# Changelog

[← back to the README](README.md)

Notable changes per release. Breaking changes and how to migrate are in [COMPATIBILITY.md](COMPATIBILITY.md); what is
not covered yet is in [ROADMAP.md](ROADMAP.md).

## 1.0.0

The first stable release. `0.2.0` went into the Sendline backend as a preview, and this release is what running it
there asked for: the bugs it showed, the helpers its wrappers wrote by hand and the limits it ran into. Nothing but
that backend depends on `0.2.0`, so the breaks below were made now rather than carried. From this release on, MiMa
checks every build against the previous one.

### Fixed

- **`metrics4s-tapir` left a cancelled request in flight.** tapir calls none of a metric's callbacks when the fiber
  of a request is cancelled, which is what a caller that hangs up does. `http_server_in_flight` rose by one for each
  such request and never came back. The interceptor now lets go of a request however its handling ends.
- **`metrics4s-sttp` counted a cancelled call as `failed`.** sttp reports a cancellation to its listeners as an
  `InterruptedException`. Such a call is now counted nowhere and timed nowhere, as the http4s client already did, so
  a timeout that cancels a call is not a failure of the server it called.
- **The http4s server middleware left a request in flight when its classifier threw.** The route is worked out
  before the request is counted in, so a classifier that throws fails the request and nothing else.
- **`mongo_command_failures_total` appeared with the first failure.** The first failure of a command on a collection
  was a new series, not a rise, and `rate()` shows nothing for the first sample of a series. The counters are now
  created at zero while they fit the pre-creation limit.
- **`gauge.replace` on a bounded gauge kept the series it had not set itself.** It removed what an earlier `replace`
  had set and the new map left out, but a series that came from `set` or `add` stayed with its last value. It now
  removes every series the map leaves out, which is what it was said to do.

### Added

- **Gauges that follow an effect.** `gauge.track(labels)(fa)` is one more while `fa` runs and one less once it ends,
  fails or is cancelled: the in-flight gauge every service writes by hand. `gauge.timed(labels)(fa)`, on a gauge of
  seconds, keeps how long the last run took; a cancelled run leaves the previous value.
- **The unsafe side of a gauge without labels** has `add(delta)`, `inc()` and `dec()`, as the effectful side does.
- **Generated buckets.** `Buckets.linear(start, width, count)` and `Buckets.exponential(start, factor, count)` take
  literals and are checked by the compiler; `linearFrom` and `exponentialFrom` take computed values and give an
  `Either`. The bounds are computed in decimal, so the third of `linear(0.1, 0.1, 5)` is `0.3`.
- **`Gauge.ratio`,** a gauge between 0 and 1 whose name ends with `_ratio`. The backend exposes the unit, a dashboard
  draws it as a percentage and the README table says `gauge, ratio`.
- **`PreCreation`.** `definition.withPreCreation(PreCreation.WithinLimit)` creates the series at zero while they fit
  the limit and on first use when they do not, for a domain that grows with the service: its collections, its
  tenants. `Always` is the default and `Never` is what `.withoutPreCreation` says.
- **The exporter on one interface:** `PrometheusExporter.allocated(host, port, registry)`. `ExporterException` has
  the `host` as well.
- **`testkit.info(definition)`** reads the labels an info metric says now, as a map, or `None` before it is set.
- **`MetricsDoc.embedded(document, definitions)`** replaces the table a document keeps between
  `<!-- metrics4s:table -->` and `<!-- /metrics4s:table -->`, or between markers of your own. A document that is up
  to date comes back as it was, so a test compares the two.
- **`GrafanaError.toThrowable`,** a `GrafanaException` that keeps the error, for a build that stops on it.
- **`SttpMetrics.wrap` for every kind of backend:** `StreamBackend`, `WebSocketBackend`, `WebSocketStreamBackend` and
  `WebSocketSyncBackend` come back as the kind they were. Only `Backend` and `SyncBackend` could be wrapped before.
- **`metrics4s-mongo` names three more commands:** `bulk_write`, `commit_transaction` and `abort_transaction`, which
  were `other`.
- **Benchmarks for what a service does on its hot path:** labels in a named tuple, a label listed at startup, a
  labelled histogram and gauge, a timed effect and a whole HTTP request, each beside the raw client. See
  [BENCHMARKS.md](BENCHMARKS.md).

### Changed

Every item here breaks code written against `0.2.0`; [COMPATIBILITY.md](COMPATIBILITY.md#coming-from-020) has what to
write instead.

- **`MetricsConfig.preCreateLimit` counts series, and is 10 000 by default.** It counted label combinations and was
  512, which the HTTP request counter of a service with 87 endpoints passed. A counter or a gauge spends one series on
  a combination and a histogram spends its buckets, `+Inf`, `_sum` and `_count`, so the limit now measures what a
  metric costs Prometheus.
- **`MetricError.DomainTooLarge` shows the sum:** `700 route x 15 series each = 10500 series`. Its fields are the
  size of each label's domain, the series of one combination, their product and the limit.
- **`MetricDef.preCreation: PreCreation`** replaces `preCreated: Boolean`.
- **The names of derived labels are checked by the compiler.** A case class or a named tuple with a field named `le`,
  `quantile` or `__anything`, or with two fields that come to the same snake case, does not compile. Registration
  refused them before.
- **`registerAll` compiles only for a tuple of definitions.** Anything else was an `IllegalArgumentException` when
  the service started.
- **A gauge named `_ratio` is `Gauge.ratio`;** `Gauge("x_ratio", ...)` no longer compiles.
- **A server's status has no `failed`.** `StatusClass` is `1xx` to `5xx`. Nothing ever recorded `failed` for a
  server: an exception is `5xx`. Every route carried the series at zero, and a dashboard that added it to the errors
  added nothing. The client's label is the new `ClientStatus`, `1xx` to `5xx` and `failed`. The series a client
  exports are the same as before.
- **A label listed at startup is found faster.** `LabelValue.enumerated` took a lookup in an immutable map and a
  closure for every update. It is now one hash lookup, and the ordinal alone when the list is an enum's cases in
  their order, so `LabelValue.enumerated(Level.values)(_.value)` costs what `derives LabelValue` does. The HTTP and
  MongoDB integrations find a route, an operation or a collection by its string instead of building a value to look
  up. The numbers before and after are in [BENCHMARKS.md](BENCHMARKS.md).
- **`Series` in the backend SPI has `labelValues`,** the series a metric has now, which is what `replace` reads. A
  backend of your own has one more method to write.
- **`TapirMetrics.interceptor` is the only way in;** `TapirMetrics.metric` is gone. A bare `Metric` cannot see a
  cancellation, which is the leak fixed above.
- **`ExporterException`** is built with the host, the port and the cause.
- **`Dashboard.writeTo`** fails with a `GrafanaException`, where it raised an `IllegalArgumentException`.
- **`GrafanaError` has a new case,** `MissingMarkers`, and `MetricUnit` has `Ratio`, so an exhaustive match on either
  now warns.
- Built against http4s `0.23.39`, sttp client `4.1.3`, rapid `2.9.10` and the MongoDB driver `5.15.0`, with sbt
  `2.1.0` and sbt-pgp `2.4.0`. The integration tests compile on Scala `3.10.1` with `mongo4s` `6.0.0` and run against
  Prometheus `v3.16.0`, Grafana `13.3.1` and MongoDB `9.0`. Everything else was already on its latest release.

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
- **[RECIPES.md](RECIPES.md):** `/metrics` on an internal http4s server, a ZIO service, labels known only at
  startup, errors as a label, a dashboard kept in the repository and stats with thresholds. The integration tests
  compile every block and run the recipes that do something.

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
- `metrics4s-mongo` is built against the MongoDB driver `5.14.0`, from `5.13.0`. Drivers `5.6.0` to `5.13.0` nest one
  more callback for every partial read of a reply, so a large reply read in small pieces overflows the IO thread's
  stack and never completes ([JAVA-6313](https://jira.mongodb.org/browse/JAVA-6313)). A service that takes the
  driver from `metrics4s-mongo` now gets the fixed one. The listeners play no part in the bug.
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
