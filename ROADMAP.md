# Roadmap

[← back to the README](README.md)

Nothing is released yet. On 2026-11-04 the first release was agreed as the core, the Prometheus backend, the four
runtimes with their runtime metrics, the testkit and the dashboard generator. Later the same day it was widened to the
integrations that were planned for `0.2.0` and `0.3.0`. `0.1.0` was only tested inside Sendline.now, so the first
public release is `0.2.0`, which also carries what wiring the library into the Sendline backend asked for. The
reasoning behind each module is in [DESIGN.md](DESIGN.md).

## 0.2.0: the first release

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
* The coordinates are settled and the first release is published. **Partly done.** The artifacts are `now.sendline`
  and the repository is [Sendline-now/metrics4s](https://github.com/Sendline-now/metrics4s), decided on 2026-11-07.
  Nothing is published yet; the first release is `0.2.0`.

## Wanted, not yet scheduled

| | Why it is not here yet | Workaround today |
| --- | --- | --- |
| **OpenTelemetry backend** | Nobody needs it yet. It also brings a naming question that only a real OTel pipeline can answer, because OTel adds suffixes of its own. The SPI is kept free of Prometheus so the backend can arrive without a break. | The Prometheus backend. |
| **rapid runtime metrics** | rapid 2.9.9 has no hook to install a `TaskMonitor`. | JVM metrics; propose the hook upstream. |
| **Alert rules from definitions** | The same definitions could produce `PrometheusRule` YAML (error ratio, p99 over a threshold, CPU starvation). Stats have thresholds now; a rule also needs a duration and a severity. | Rules written by hand. |
| **Exemplars** | A trace id on a bucket needs a tracing integration, which none of the runtimes share. | None. |
| **A `/metrics` route for http4s and tapir** | The exporter's own server covers the services today. | `PrometheusScrape.text(registry)` in a route of your own. |
| **Pushing dashboards to Grafana** | Importing by hand works, and an API client is a separate concern. | Dashboards → Import; the fixed `uid` overwrites. |
| **Native histograms** | See DESIGN.md: our Prometheus and dashboards read classic buckets. | Classic histograms. |

Our rabbit and kafka clients are not on this list on purpose. They are not OSS, so their metrics live in those
libraries, written against `metrics4s-core`.

## How these land

Before `1.0.0` a minor release may break the API, and every break is written in [CHANGELOG.md](CHANGELOG.md) with what
to change. `1.0.0` comes once commons and at least two services run on the library. From then on the rules are
`mongo4s`'s:

- MiMa against the previous release in CI;
- every deliberate break is an exclusion in `build.sbt` and an entry in [COMPATIBILITY.md](COMPATIBILITY.md);
- new typeclass members come with default implementations;
- configuration types are builders rather than case classes.

If you need one of these, open an issue saying what you are building. Demand is what moves an item up this list.
