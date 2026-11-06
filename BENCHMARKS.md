# Benchmarks

[← back to the README](README.md)

What one update costs on the hot path, against the same update on the raw Prometheus client 1.10. The benchmarks are in
[`benchmarks/`](benchmarks/src/main/scala/metrics4s/benchmarks/HotPathBenchmark.scala) and use the `unsafe` side of
the instruments, so the numbers are the library's own cost and not the cost of an effect system.

```
sbt "benchmarks/Jmh/run -i 3 -wi 2 -w 2s -r 3s -f 1 -t 1 .*HotPathBenchmark.*"
```

Apple M4 Max, 14 cores, OpenJDK 25.0.4.1, one thread, throughput in operations per microsecond (higher is better):

| Update | Raw client | metrics4s |
| --- | ---: | ---: |
| Counter, one label from an enum | 152.5 ± 3.8 (`labelValues("ok").inc()`) | **196.3 ± 1.8** (`inc(Outcome.Ok)`) |
| Counter, two labels from enums | 137.2 ± 6.1 (`labelValues("ok", "kafka").inc()`) | **154.5 ± 1.9** (`inc(ByOutcome(Ok, Kafka))`) |
| Histogram, no labels | 130.8 ± 9.4 (`observe(0.12)`) | **137.6 ± 1.1** (`observe(120.millis)`) |
| Counter, one bounded label | — | 93.8 ± 1.4 (`inc(Provider("cns"))`) |

**Why the enumerated case is faster than the raw client.** For a label whose values are all known, every data point
is resolved once at registration into an array, and an update is an array read at the combination's index. The raw
client builds a varargs array and looks the series up in a concurrent map on every call.

**Why a bounded label is slower.** A bounded value has no index, so an update renders it to its string and then does
the same lookup as the raw client. The raw benchmark passes a string literal and does not render anything, so the gap
is the cost of rendering through `LabelValue` and of the label array. The first version of this path, before the
label array went to the client without a copy, measured 52 operations per microsecond.

These numbers say the typed API costs nothing on the hot path for enumerated labels; they are not a promise about any
other machine.
