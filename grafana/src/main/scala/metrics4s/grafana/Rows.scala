package metrics4s.grafana

object Rows:
  private def byPod(series: String, matchers: String*): String = s"sum by (pod) (${Query.selector(series, matchers*)})"

  private def rateByPod(series: String): String = s"sum by (pod) (${Query.rate(series)})"

  val catsEffect: Row = Row("Cats Effect runtime")(
    Panel.custom("CPU starvation, /s", "ops", "`cats_effect_cpu_starvation_total`: clock drifts above the starvation threshold. Should stay at zero.")(
      Target(rateByPod("cats_effect_cpu_starvation_total"), "{{pod}}", Set("cats_effect_cpu_starvation_total"))
    ),
    Panel.custom("Clock drift", "s", "`cats_effect_cpu_starvation_clock_drift_seconds`: how late the CPU starvation check wakes up.")(
      Target(Query.quantile(0.5, "cats_effect_cpu_starvation_clock_drift_seconds_bucket", Nil), "p50", Set("cats_effect_cpu_starvation_clock_drift_seconds_bucket")),
      Target(Query.quantile(0.99, "cats_effect_cpu_starvation_clock_drift_seconds_bucket", Nil), "p99", Set("cats_effect_cpu_starvation_clock_drift_seconds_bucket")),
    ),
    Panel.custom(
      "Compute threads",
      "short",
      "`cats_effect_compute_threads`, `_active` (not parked), `_blocked` (left the pool to run blocking code).",
    )(
      Target(byPod("cats_effect_compute_threads"), "{{pod}} workers", Set("cats_effect_compute_threads")),
      Target(byPod("cats_effect_compute_threads_active"), "{{pod}} active", Set("cats_effect_compute_threads_active")),
      Target(byPod("cats_effect_compute_threads_blocked"), "{{pod}} blocked", Set("cats_effect_compute_threads_blocked")),
    ),
    Panel.custom("Fibers", "short", "`cats_effect_fibers{state}`: `queued` in the local queues, `suspended` on an asynchronous operation.")(
      Target(s"sum by (pod, state) (${Query.selector("cats_effect_fibers")})", "{{pod}} {{state}}", Set("cats_effect_fibers"))
    ),
    Panel.custom(
      "Blocking and respawned workers, /s",
      "ops",
      "`cats_effect_worker_blocking_total`, `cats_effect_worker_respawn_total`: blocking code on compute threads.",
    )(
      Target(rateByPod("cats_effect_worker_blocking_total"), "{{pod}} blocking", Set("cats_effect_worker_blocking_total")),
      Target(rateByPod("cats_effect_worker_respawn_total"), "{{pod}} respawn", Set("cats_effect_worker_respawn_total")),
    ),
    Panel.custom("Outstanding timers", "short", "`cats_effect_timers_outstanding`: sleeps and timeouts waiting to fire.")(
      Target(byPod("cats_effect_timers_outstanding"), "{{pod}}", Set("cats_effect_timers_outstanding"))
    ),
  )

  val zio: Row = Row("ZIO runtime")(
    Panel.custom("Executor workers", "short", "`zio_executor_workers{executor}`: active worker threads of the default and the blocking executor.")(
      Target(s"sum by (pod, executor) (${Query.selector("zio_executor_workers")})", "{{pod}} {{executor}}", Set("zio_executor_workers"))
    ),
    Panel.custom("Executor queue", "short", "`zio_executor_queue_size{executor}`: tasks waiting in the queues of the executor.")(
      Target(s"sum by (pod, executor) (${Query.selector("zio_executor_queue_size")})", "{{pod}} {{executor}}", Set("zio_executor_queue_size"))
    ),
    Panel.custom("Fibers", "short", "`zio_fibers{state}`: fibers running on a thread or suspended, counted from the moment the metrics were registered.")(
      Target(s"sum by (pod, state) (${Query.selector("zio_fibers")})", "{{pod}} {{state}}", Set("zio_fibers"))
    ),
    Panel.custom("Fibers ended, /s", "ops", "`zio_fibers_ended_total{outcome}`: fibers that succeeded, failed or were interrupted.")(
      Target(s"sum by (pod, outcome) (${Query.rate("zio_fibers_ended_total")})", "{{pod}} {{outcome}}", Set("zio_fibers_ended_total"))
    ),
  )

  val kyo: Row = Row("Kyo runtime")(
    Panel.custom("Workers", "short", "`kyo_scheduler_workers`, `_blocked`, `_stalled`: workers the regulator lets run, and the ones stuck in a task.")(
      Target(byPod("kyo_scheduler_workers"), "{{pod}} workers", Set("kyo_scheduler_workers")),
      Target(byPod("kyo_scheduler_workers_blocked"), "{{pod}} blocked", Set("kyo_scheduler_workers_blocked")),
      Target(byPod("kyo_scheduler_workers_stalled"), "{{pod}} stalled", Set("kyo_scheduler_workers_stalled")),
    ),
    Panel.custom("Load", "short", "`kyo_scheduler_load`: tasks queued or running per worker, on average.")(
      Target(byPod("kyo_scheduler_load"), "{{pod}}", Set("kyo_scheduler_load"))
    ),
    Panel.custom("Rejected tasks, /s", "ops", "`kyo_scheduler_admission_tasks_total{decision=\"rejected\"}`: tasks the admission regulator turned away.")(
      Target(
        rateByPod("kyo_scheduler_admission_tasks_total").replace("""pod=~"$pod"}""", """pod=~"$pod", decision="rejected"}"""),
        "{{pod}}",
        Set("kyo_scheduler_admission_tasks_total")
      )
    ),
    Panel.custom("Probe delay", "s", "`kyo_scheduler_probe_delay_seconds{regulator}`: how late the regulators' probes run.")(
      Target(
        s"max by (pod, regulator) (${Query.selector("kyo_scheduler_probe_delay_seconds")})",
        "{{pod}} {{regulator}}",
        Set("kyo_scheduler_probe_delay_seconds")
      )
    ),
  )

  val jvm: Row = Row("JVM")(
    Panel.custom("Heap", "bytes", "`jvm_memory_used_bytes`, `jvm_memory_max_bytes` with `area=\"heap\"`.")(
      Target(byPod("jvm_memory_used_bytes", """area="heap""""), "{{pod}} used", Set("jvm_memory_used_bytes")),
      Target(byPod("jvm_memory_max_bytes", """area="heap""""), "{{pod}} max", Set("jvm_memory_max_bytes")),
    ),
    Panel.custom("Resident memory and non-heap", "bytes", "`process_resident_memory_bytes`, `jvm_memory_used_bytes{area=\"nonheap\"}`.")(
      Target(byPod("process_resident_memory_bytes"), "{{pod}} RSS", Set("process_resident_memory_bytes")),
      Target(byPod("jvm_memory_used_bytes", """area="nonheap""""), "{{pod}} non-heap", Set("jvm_memory_used_bytes")),
    ),
    Panel.custom("CPU, cores", "short", "`process_cpu_seconds_total`.")(
      Target(rateByPod("process_cpu_seconds_total"), "{{pod}}", Set("process_cpu_seconds_total"))
    ),
    Panel.custom("GC time, share of wall time", "percentunit", "`jvm_gc_collection_seconds_sum` per collector.")(
      Target(s"sum by (pod, gc) (${Query.rate("jvm_gc_collection_seconds_sum")})", "{{pod}} {{gc}}", Set("jvm_gc_collection_seconds_sum"))
    ),
    Panel.custom("Threads", "short", "`jvm_threads_current`, `jvm_threads_daemon`.")(
      Target(byPod("jvm_threads_current"), "{{pod}} live", Set("jvm_threads_current")),
      Target(byPod("jvm_threads_daemon"), "{{pod}} daemon", Set("jvm_threads_daemon")),
    ),
    Panel.custom("Uptime", "s", "From `process_start_time_seconds`. A drop means a restart.")(
      Target(s"time() - max by (pod) (${Query.selector("process_start_time_seconds")})", "{{pod}}", Set("process_start_time_seconds"))
    ),
  )

  val httpServer: Row = Row("HTTP server")(
    Panel.custom("Requests by route and status, /s", "reqps", "`http_server_requests_total{route, status}`: handled requests, `route` being the template.")(
      Target(s"sum by (route, status) (${Query.rate("http_server_requests_total")})", "{{route}} {{status}}", Set("http_server_requests_total"))
    ),
    Panel.custom("Request time by route, p95", "s", "`http_server_request_seconds`: from the request to the response headers.")(
      Target(Query.quantile(0.95, "http_server_request_seconds_bucket", List("route")), "{{route}}", Set("http_server_request_seconds_bucket"))
    ),
    Panel.custom("Server errors, /s", "reqps", "`http_server_requests_total{status=\"5xx\"}`: requests that failed on this side.")(
      Target(
        s"sum by (route) (${Query.rate("http_server_requests_total").replace("""pod=~"$pod"}""", """pod=~"$pod", status="5xx"}""")})",
        "{{route}}",
        Set("http_server_requests_total")
      )
    ),
    Panel.custom("Requests in flight", "short", "`http_server_in_flight`: requests being handled.")(
      Target(byPod("http_server_in_flight"), "{{pod}}", Set("http_server_in_flight"))
    ),
  )

  val httpClient: Row = Row("HTTP client")(
    Panel.custom(
      "Requests by operation and status, /s",
      "reqps",
      "`http_client_requests_total{operation, status}`: requests sent, `failed` when no response came back."
    )(
      Target(s"sum by (operation, status) (${Query.rate("http_client_requests_total")})", "{{operation}} {{status}}", Set("http_client_requests_total"))
    ),
    Panel.custom("Request time by operation, p95", "s", "`http_client_request_seconds`: from sending the request to the response headers.")(
      Target(Query.quantile(0.95, "http_client_request_seconds_bucket", List("operation")), "{{operation}}", Set("http_client_request_seconds_bucket"))
    ),
    Panel.custom("Requests in flight", "short", "`http_client_in_flight`: requests waiting for a response.")(
      Target(byPod("http_client_in_flight"), "{{pod}}", Set("http_client_in_flight"))
    ),
  )

  val mongo: Row = Row("MongoDB")(
    Panel.custom("Commands by command and collection, /s", "ops", "`mongo_command_seconds_count{command, collection}`: commands sent to the server.")(
      Target(s"sum by (command, collection) (${Query.rate("mongo_command_seconds_count")})", "{{command}} {{collection}}", Set("mongo_command_seconds_count"))
    ),
    Panel.custom("Command time by command, p95", "s", "`mongo_command_seconds`: from sending a command to its reply.")(
      Target(Query.quantile(0.95, "mongo_command_seconds_bucket", List("command")), "{{command}}", Set("mongo_command_seconds_bucket"))
    ),
    Panel.custom("Failed commands, /s", "ops", "`mongo_command_failures_total{command, collection}`: commands the server or the driver failed.")(
      Target(s"sum by (command, collection) (${Query.rate("mongo_command_failures_total")})", "{{command}} {{collection}}", Set("mongo_command_failures_total"))
    ),
    Panel.custom("Pool connections", "short", "`mongo_pool_connections{state}`: connections open and checked out of the pools.")(
      Target(s"sum by (pod, state) (${Query.selector("mongo_pool_connections")})", "{{pod}} {{state}}", Set("mongo_pool_connections"))
    ),
    Panel.custom("Connection check-out, p95", "s", "`mongo_pool_checkout_seconds`: time to get a connection from the pool.")(
      Target(Query.quantile(0.95, "mongo_pool_checkout_seconds_bucket", Nil), "p95", Set("mongo_pool_checkout_seconds_bucket"))
    ),
  )
