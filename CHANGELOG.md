# Changelog

[← back to the README](README.md)

Notable changes per release. Breaking changes and how to migrate are in [COMPATIBILITY.md](COMPATIBILITY.md); what is
not covered yet is in [ROADMAP.md](ROADMAP.md).

## 0.1.0

The first release.

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
