# Benchmarks

[← back to the README](README.md)

What one update costs on the hot path, against the same update on the raw Prometheus client 1.10. The benchmarks are in
[`benchmarks/`](benchmarks/src/main/scala/metrics4s/benchmarks/HotPathBenchmark.scala) and use the `unsafe` side of
the instruments, so the numbers are the library's own cost and not the cost of an effect system.

```
sbt "benchmarks/Jmh/run -i 5 -wi 3 -w 2s -r 3s -f 1 -t 1 .*HotPathBenchmark.*"
```

Intel Core i7-13700KF, 16 cores, Windows 11, OpenJDK 25.0.1, one thread, throughput in operations per microsecond
(higher is better). The machine was doing other work at the time, which is where the two wide intervals come from:

| Update | Raw client | metrics4s |
| --- | ---: | ---: |
| Counter, one label from an enum | 111.9 ± 2.4 (`labelValues("ok").inc()`) | **125.4 ± 6.3** (`inc(Outcome.Ok)`) |
| Counter, two labels from enums, in a case class | 97.4 ± 2.4 (`labelValues("ok", "kafka").inc()`) | **127.0 ± 1.0** (`inc(ByOutcome(Ok, Kafka))`) |
| Counter, the same two labels in a named tuple built on the call | — | **125.0 ± 1.1** (`inc((outcome = Ok, source = Kafka))`) |
| Counter, the same named tuple kept in a `val` | — | 123.1 ± 15.0 |
| Gauge, one label from an enum | 114.8 ± 1.6 (`labelValues("ok").set(3)`) | **175.3 ± 2.0** (`set(Outcome.Ok, 3)`) |
| Histogram, no labels | 53.5 ± 1.3 (`observe(0.12)`) | 53.9 ± 0.6 (`observe(120.millis)`) |
| Histogram, one label from an enum | 38.3 ± 22.9 (`labelValues("ok").observe(0.12)`) | **50.0 ± 1.0** (`observe(Outcome.Ok, 120.millis)`) |
| Counter, one label listed at startup, 100 values | — | 67.1 ± 2.8 (`inc((tenant = tenant))`) |
| Counter, one bounded label | — | 64.0 ± 6.3 (`inc(Provider("cns"))`) |
| A whole HTTP request: in flight, the counter by route and status, the time by route, 100 routes | **12.5 ± 0.1** | 9.9 ± 0.1 (`recorder.start()`, `finish`) |
| An effect timed into a histogram | — | 16.2 ± 0.3 (`timed(fa)`) |

**Why the enumerated case is faster than the raw client.** For a label whose values are all known, every data point
is resolved once at registration into an array, and an update is an array read at the combination's index. The raw
client builds a varargs array and looks the series up in a concurrent map on every call.

**A named tuple costs what a case class costs.** `(outcome = Ok, source = Kafka)` written at the call measures the
same as a value kept in a field: the JIT does not let the tuple out of the call.

**Why a label listed at startup is slower than an enum.** Routes, tenants or collections read from the configuration
have no ordinal, so finding one is a hash lookup: about 15 ns an update against 8 ns for an enum. Until `1.0.0` it
was a lookup in an immutable map with a closure built on every call, and measured 48.5 ± 5.2. A list that is an
enum's own cases in their order, as in `LabelValue.enumerated(Level.values)(_.value)`, is indexed by the ordinal and
costs what a derived enum costs.

**Why a bounded label is slower.** A bounded value has no index, so an update renders it to its string and then does
the same lookup as the raw client. The raw benchmark passes a string literal and does not render anything, so the gap
is the cost of rendering through `LabelValue` and of the label array. The first version of this path, before the
label array went to the client without a copy, measured about half of this.

**What a whole request costs.** The HTTP integrations do four updates for a request: the in-flight gauge up and down,
the counter by route and status, the histogram by route. With 100 routes that is 101 ns on this machine, against 80 ns
for the same four updates written against the raw client with the labels as string literals. The 21 ns are three
hash lookups, the route by its string and then its index in each of the two metrics. Until `1.0.0` the same request
measured 7.7 ± 1.6, about 130 ns. A request that is answered in a millisecond spends a ten-thousandth of it here.

**What `timed` costs.** 62 ns for an effect that does nothing, on an effect type that is a plain function. The
observation is 19 ns of it and the rest is mostly the two reads of the clock. A real runtime adds what its own
finalizer costs.

These numbers say the typed API costs nothing on the hot path for enumerated labels, and a hash lookup for a label
known only at startup; they are not a promise about any other machine.
