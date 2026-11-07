# Contributing to metrics4s

[← back to the README](README.md)

Thanks for taking the time to contribute. This page covers what you need to build the project, run its tests, where
common kinds of changes go, and how a release is cut.

## Building

**JDK 25 is required**, and the build checks it when it loads: on an older JDK it stops with a message naming the
version it found, instead of letting most modules compile and `metrics4s-kyo` fail with
`UnsupportedClassVersionError`. Kyo ships Java 25 class files and its `Frame` macro loads them inside the compiler, so
the whole build follows the module with the highest floor. `cs java --jvm 25` or `brew install openjdk@25` gets you
one; CI uses the same.

What the build produces runs on JDK 17: `-java-output-version 17` writes Java 17 class files and refuses any JDK API
newer than 17, and `JavaVersionItSpec` checks a class of every published module. Only `metrics4s-rapid` (21) and
`metrics4s-kyo` (25) need more, because the libraries they wrap do.

Every published module compiles on `Versions.scala3` (`3.9`, the LTS) in `project/Dependencies.scala`. `it` is the
exception on purpose: it compiles on `Versions.scala3Next` (`3.10`) against the library compiled by `3.9`. The name,
help and bucket checks are inline macros that expand in the caller, so what a user on a newer compiler sees is only
visible from a module that compiler builds. Keep `scala3Next` on the newest Scala 3 release.

```bash
sbt compile                 # compile everything
sbt "testOnly *"            # run the unit tests of every aggregated module
sbt "it/testOnly *"         # run the integration tests, needs Docker
sbt "scalafmtCheckAll; scalafmtSbtCheck; it/scalafmtCheck; it/Test/scalafmtCheck"  # verify formatting, as CI does
```

sbt 2 takes several tasks as one quoted string separated by `;`. It also caches test runs and replays a cached run's
log, so a repeated `test` with unchanged inputs runs nothing; `testOnly *` is what CI uses, and changing the filter
forces a real run. `scalafmtCheckAll` does not cover `it/`, which is not aggregated, nor the `.sbt` files, which is why
the format check above names them.

To run one module's tests:

```bash
sbt core/test
sbt "kyo/testOnly metrics4s.runtime.kyo.KyoEffectSpec"
```

Publishing credentials live in `~/.sbt/sonatype_credentials`, outside the repository. A checkout without the file
builds and tests exactly the same; the build only adds the credentials when the file is there.

## Project layout

| Path | What lives there |
| --- | --- |
| `core` | definitions, typed labels, instruments, callbacks, `Effect`/`Dispatch`/`Allocated` and the backend SPI; no dependencies |
| `backend/prometheus` | the backend on the Prometheus Java client 1.x, the exporter, JVM metrics |
| `runtime/cats`, `runtime/zio`, `runtime/kyo`, `runtime/rapid` | `given Effect[F]` and `given Dispatch[F]` per runtime, the adapter to its resource type, its runtime metrics |
| `testkit` | `MetricsTestkit`: a real registry read back as typed values, and `missingSeries` for dashboards |
| `grafana` | dashboards and README tables generated from the definitions |
| `integrations/http` | the HTTP server and client metrics shared by `integrations/http4s`, `integrations/tapir` and `integrations/sttp` |
| `integrations/mongo` | command and pool listeners for the MongoDB driver |
| `it` | Prometheus, Grafana and MongoDB in Docker via [Testcontainers](https://testcontainers.com/), and the README's examples, compiled |
| `benchmarks` | JMH benchmarks of the hot path against the raw Prometheus client |

## Adding a runtime

A runtime needs a `given Effect[F]` and a `given Dispatch[F]`. `Effect` has seven abstract members: `pure`, `delay`,
`map`, `flatMap`, `raiseError`, `handleErrorWith` and `guaranteeCase`; everything else has a default and may be
overridden. `Dispatch` runs an effect from a callback thread, which is what scrape-time observations need. See
`runtime/cats` for the reference.

The instances are tested by extending `EffectSuite` from `core`'s test sources, which the runtime modules depend on
through `core % "test->test"`. It asks for four things: how to run an effect, an effect that never ends, a timeout and
the runtime's native way to use an `Allocated`. A new runtime gets the same coverage as the existing four by passing
it, and where it cannot pass a case, a spec of its own pins down what it does instead, as `RapidCancellationSpec` does.

`guaranteeCase` must report cancellation as `ExitCase.Canceled`. Kyo and rapid do not surface it as such by default:
Kyo closes an abandoned fiber with `KyoException("fiber abandoned")`, and rapid interrupts a thread, which surfaces as
an `InterruptedException`. [DESIGN.md](DESIGN.md#what-the-shared-suite-found) has the details.

If the runtime exports metrics of its own, write them as definitions in the module, check their names and types
against what services already scrape, and run `missingSeries` against the dashboard row that reads them, as the
`*RuntimeMetricsSpec` of each runtime does.

## Adding an integration

An integration is a set of definitions plus whatever hooks into the library. Every label comes from a closed domain:
routes, operations and collections are given when the metrics are built, and anything else is counted under an
`unmatched` or `other` value. A raw path or a raw collection name never becomes a label value. The hooks record
through the instruments' `unsafe` side, so they need no effect. Add a dashboard row for the new metrics to `Rows` in
`grafana`, and test both through `MetricsTestkit` with the values a real registry holds.

## Tests

- Specs check real values read from a real registry, not only that a series exists.
- `grafana` compares a rendered dashboard with `grafana/src/test/resources/processor.json` byte for byte. A deliberate
  change to rendering means rewriting that file; the failing spec shows the new JSON.
- `it/` needs Docker: `prom/prometheus:v3.5.0`, `grafana/grafana:12.0.0` and `mongo:8.2`. It imports a generated
  dashboard into Grafana and runs every panel's queries through Grafana's datasource proxy. On Windows the forks point
  Testcontainers at `tcp://localhost:2375`, so enable Docker Desktop's "Expose daemon on tcp://localhost:2375 without
  TLS", or set `DOCKER_HOST` yourself.
- Every Scala block in [README.md](README.md) and [RECIPES.md](RECIPES.md) is copied from an example under
  `it/src/test/scala/metrics4s/it/docs`, which the `it` module compiles with the same warnings as the library.
  `DocsItSpec` fails on a block that matches no example, whitespace aside, and on a dependency line whose version is
  not the one `build.sbt` publishes. `RecipesItSpec` runs the recipes that do something. Change the example and the
  block together.
- Numbers in [BENCHMARKS.md](BENCHMARKS.md) come from `sbt "benchmarks/Jmh/run"`; rerun it when the hot path changes.

## Code style

Formatting is enforced by [scalafmt](https://scalameta.org/scalafmt/) (`.scalafmt.conf`) and checked in CI. Run
`sbt scalafmtAll` before opening a PR.

The code carries no comments and no scaladoc. Make it type-safe and obvious instead; where the logic is subtle enough
to need explaining, the explanation belongs in [README.md](README.md), [DESIGN.md](DESIGN.md) or
[COMPATIBILITY.md](COMPATIBILITY.md), not in a comment nobody reading the docs will find.

The build turns on more warnings than the compiler does by default: `-Wunused:all`, `-Wvalue-discard`,
`-WunstableInlineAccessors`, `-Wshadow:all`, `-Winfer-union`, `-Wimplausible-patterns` and `-Wrecurse-with-default`.
Any warning fails the build (`-Werror`), except under the `-source:future` check, whose point is to see what a future
compiler will say. One message is silenced: Kyo's `Frame` macro expands to `@nowarn` annotations that suppress
nothing, which is Kyo's to fix.

## Releasing

1. Set `version` in `build.sbt` to the release and give it its section in [CHANGELOG.md](CHANGELOG.md), in Added /
   Changed / Fixed order. Before `1.0.0` a minor release may break the API; every break gets an entry saying what to
   change.
2. Check that CI on `main` is green, the integration tests included.
3. Sign and stage the artifacts, then upload them to Maven Central and release them:

   ```bash
   sbt publishSigned
   sbt sonaRelease
   ```

   `publishSigned` writes the signed artifacts to `target/sona-staging`, which is what `publishTo` points at;
   `sonaRelease` uploads that bundle with the credentials from `~/.sbt/sonatype_credentials` and publishes it.
4. Tag the release, `v<version>`, and write the GitHub release notes from the changelog section.

From `1.0.0` two more steps apply, and nothing fails when they are missed:

- **Move the MiMa baseline.** `binaryCompatibleWith` in `build.sbt` names the releases the artifacts are checked
  against. It stays `Set.empty` until `1.0.0` is on Maven Central, and then becomes `Set("1.0.0")`; otherwise the
  compatibility promise is asserted and never checked.
- **Record any deliberate break.** A break inside a major version needs a MiMa exclusion in `build.sbt` and an entry in
  [COMPATIBILITY.md](COMPATIBILITY.md).

## Opening a pull request

- Keep PRs focused: one runtime, one integration or one fix per PR is easier to review than a mix.
- Add or extend tests for anything behavioural you change.
- The format check and `sbt "testOnly *"` should pass locally before you push. The integration tests need Docker and
  are not required for most changes, but CI runs them too.
