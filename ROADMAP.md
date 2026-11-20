# Roadmap

[← back to the README](README.md)

`0.2.0` was published on 2026-11-07 as a preview and went into the Sendline backend. `1.0.0` is what running it there
asked for, and is the first release whose API is kept. The reasoning behind each module is in [DESIGN.md](DESIGN.md).

## 1.0.0: the first stable release

| | State |
| --- | --- |
| Bugs the backend showed: a cancelled request left in flight by tapir, a cancelled call counted as failed by sttp, a classifier that throws, MongoDB failure counters that appeared late | fixed |
| The pre-creation limit counted in series, 10 000 by default, with `PreCreation.WithinLimit` for domains that grow | done |
| What the backend's wrappers wrote by hand: `gauge.track`, `gauge.timed`, `MetricsDoc.embedded`, `testkit.info`, `GrafanaError.toThrowable` | done |
| Label names and `registerAll` checked by the compiler | done |
| `Gauge.ratio`, generated buckets, the exporter on one interface, sttp backends of every kind | done |
| A server status without `failed`; `ClientStatus` for clients | done |
| http4s `0.23.39`, sttp `4.1.3`, rapid `2.9.10`, MongoDB driver `5.15.0`, sbt `2.1.0`; integration tests on Scala `3.10.1`, `mongo4s` `6.0.0`, Prometheus `v3.16.0`, Grafana `13.3.1`, MongoDB `9.0` | done |
| Benchmarks rerun and widened: named tuples, labels listed at startup, gauges, a whole HTTP request | done |
| The Sendline backend moved to `1.0.0`, its dashboard regenerated | to do, in that repository |
| Published to Maven Central | to do |

## 0.2.0: the preview

| Module | State |
| --- | --- |
| `core`: definitions, typed labels, instruments, callbacks, `Effect`/`Dispatch`/`Allocated` | done |
| `prometheus`: backend, exporter, JVM metrics | done |
| `cats`, `zio`, `kyo` with their runtime metrics | done |
| `rapid` (`Effect`/`Dispatch` only) | done |
| `testkit` | done |
| `grafana`: dashboards and README tables | done |
| `http`, `http4s`, `tapir`, `sttp`, `mongo` | done |
| From the Sendline backend: info metrics, `Measured`, `registerAs`, the exporter's port in its error, the integrations' definitions on their companions | done |
| From the Sendline backend, in `grafana`: typed filters, one quantile, shares, `missingSeries(definitions)` | done |
| Stats filtered by `.where` and coloured by thresholds; Java 17 class files; the README and the recipes compiled | done |

**Done means**, and where each item stands:

* Every module has specs that check real values read from a real registry, not only that a series exists. **Done:**
  170 tests, plus 16 integration tests.
* The four `Effect` instances pass one shared `EffectSuite`. **Done.**
* The runtime metrics export exactly the names, types and labels commons exports, and a spec compares them. **Done.**
* The shared dashboard rows read only series the runtime metrics and the JVM export. **Done**, checked by
  `missingSeries` in every runtime module.
* A generated dashboard imports into Grafana and every panel's queries return data from Prometheus. **Done**, against
  Grafana 12 and Prometheus 3.5 in Docker.
* `BENCHMARKS.md` has the hot-path numbers against the raw client. **Done.**
* Every Scala block of the README and the recipes compiles, and the recipes run. **Done**, checked by `DocsItSpec`
  and `RecipesItSpec`.
* README, DESIGN, CHANGELOG, COMPATIBILITY, CONTRIBUTING and SECURITY in the `mongo4s` format. **Done.**
* The descriptions and merger dashboards are regenerated from definitions and diffed panel by panel against the
  hand-written ones. **Partly done.** The shared cats-effect and JVM rows are diffed and pinned by a spec. The services'
  own rows need their metric definitions, which belong in those services once they move to `metrics4s`.
* The coordinates are settled and the first release is published. **Done:** the artifacts are `now.sendline`, the
  repository is [sendline-now/metrics4s](https://github.com/sendline-now/metrics4s), and `0.2.0` is on Maven Central
  since 2026-11-07.

## Wanted, not yet scheduled

| | Why it is not here yet | Workaround today |
| --- | --- | --- |
| **OpenTelemetry backend** | Nobody needs it yet. It also brings a naming question that only a real OTel pipeline can answer, because OTel adds suffixes of its own. The SPI is kept free of Prometheus so the backend can arrive without a break. | The Prometheus backend. |
| **rapid runtime metrics** | rapid 2.9.10 has no hook to install a `TaskMonitor`. | JVM metrics; propose the hook upstream. |
| **Dashboards for a Prometheus that is not on Kubernetes** | Every query filters by `namespace`, `service` and `pod`, and the variables read them from `up`. A scrape labelled by `job` and `instance` has none of the three. Every query takes the filter from one place, `Query.Filter`, so a second scope is an addition and not a break. | Add the three labels when relabelling. |
| **Alert rules from definitions** | The same definitions could produce `PrometheusRule` YAML (error ratio, p99 over a threshold, CPU starvation). Stats have thresholds now; a rule also needs a duration and a severity. | Rules written by hand. |
| **Exemplars** | A trace id on a bucket needs a tracing integration, which none of the runtimes share. | None. |
| **A `/metrics` route for http4s and tapir** | The exporter's own server covers the services today. | `PrometheusScrape.text(registry)` in a route of your own. |
| **Pushing dashboards to Grafana** | Importing by hand works, and an API client is a separate concern. | Dashboards → Import; the fixed `uid` overwrites. |
| **Native histograms** | See DESIGN.md: our Prometheus and dashboards read classic buckets. | Classic histograms. |

Our rabbit and kafka clients are not on this list on purpose. They are not OSS, so their metrics live in those
libraries, written against `metrics4s-core`.

## How these land

`1.0.0` came earlier than first planned, which was once commons and two services ran on the library: one backend
ran `0.2.0`, nothing else depended on it, and that is the cheapest moment to break what needed breaking. From `1.0.0`
on the rules are `mongo4s`'s:

- MiMa against the previous release in CI;
- every deliberate break is an exclusion in `build.sbt` and an entry in [COMPATIBILITY.md](COMPATIBILITY.md);
- new typeclass members come with default implementations;
- configuration types are builders rather than case classes.

If you need one of these, open an issue saying what you are building. Demand is what moves an item up this list.
