package metrics4s.runtime.cats

import scala.concurrent.duration.{Duration, FiniteDuration, NANOSECONDS}

import cats.effect.kernel.{Async, Resource}
import cats.effect.unsafe.IORuntime
import cats.effect.unsafe.metrics.{WorkStealingThreadPoolMetrics, WorkerThreadMetrics}

import metrics4s.*

import cats.effect.syntax.spawn.given
import cats.syntax.flatMap.given
import metrics4s.runtime.cats.syntax.given

object CatsRuntimeMetrics:
  enum FiberState derives LabelValue:
    case Queued, Suspended

  object FiberState:
    given Labels[FiberState] = Labels.single("state")

  final case class Worker(index: Int)

  object Worker:
    given LabelValue[Worker] = LabelValue.bounded(_.index.toString)
    given Labels[Worker]     = Labels.single("worker")

  val ClockDriftBuckets: Buckets = Buckets(0.001, 0.0025, 0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1, 2.5, 5, 10)

  object Definitions:
    val cpuStarvation = Counter("cats_effect_cpu_starvation", "Clock drifts above the CPU starvation threshold")

    val clockDrift = Histogram.seconds(
      "cats_effect_cpu_starvation_clock_drift_seconds",
      "Clock drifts measured with the interval of the CPU starvation check",
      ClockDriftBuckets,
    )

    val computeThreads          = Gauge("cats_effect_compute_threads", "Worker threads of the compute pool")
    val computeThreadsActive    = Gauge("cats_effect_compute_threads_active", "Compute worker threads that are not parked")
    val computeThreadsSearching = Gauge("cats_effect_compute_threads_searching", "Compute worker threads looking for fibers to steal")

    val computeThreadsBlocked = Gauge(
      "cats_effect_compute_threads_blocked",
      "Threads that left the compute pool to run blocking code, still running it or kept for reuse",
    )

    val fibers = Gauge(
      "cats_effect_fibers",
      "Fibers waiting in the local queues of the compute pool or suspended on an asynchronous operation",
    ).labelled[FiberState]

    val workerIdle     = Counter.seconds("cats_effect_worker_idle_seconds", "Time a compute worker thread spent parked").labelled[Worker]
    val workerParked   = Counter("cats_effect_worker_parked", "Times a compute worker thread parked").labelled[Worker]
    val workerPolled   = Counter("cats_effect_worker_polled", "Times a compute worker thread polled for I/O events").labelled[Worker]
    val workerBlocking = Counter("cats_effect_worker_blocking", "Times a compute worker thread switched to blocking code and was replaced").labelled[Worker]
    val workerRespawn  = Counter("cats_effect_worker_respawn", "Times a compute worker thread was replaced by a new thread").labelled[Worker]

    val workerQueueFibers    = Gauge("cats_effect_worker_queue_fibers", "Fibers in the local queue of a compute worker thread").labelled[Worker]
    val workerFibersEnqueued = Counter("cats_effect_worker_fibers_enqueued", "Fibers enqueued on the local queue of a compute worker thread").labelled[Worker]

    val workerFibersSpilled = Counter(
      "cats_effect_worker_fibers_spilled",
      "Fibers moved from the local queue of a compute worker thread to the external queue",
    ).labelled[Worker]

    val workerSteals = Counter(
      "cats_effect_worker_steals",
      "Successful steals from the local queue of a compute worker thread by other threads",
    ).labelled[Worker]

    val workerFibersStolen = Counter(
      "cats_effect_worker_fibers_stolen",
      "Fibers stolen from the local queue of a compute worker thread by other threads",
    ).labelled[Worker]

    val timersOutstanding = Gauge(
      "cats_effect_timers_outstanding",
      "Timers scheduled on a compute worker thread that have not fired or been canceled yet",
    ).labelled[Worker]

    val timersScheduled = Counter("cats_effect_timers_scheduled", "Timers scheduled on a compute worker thread").labelled[Worker]
    val timersExecuted  = Counter("cats_effect_timers_executed", "Timers fired on a compute worker thread").labelled[Worker]
    val timersCanceled  = Counter("cats_effect_timers_canceled", "Timers canceled on a compute worker thread").labelled[Worker]

    val all: List[MetricDef] = List(
      cpuStarvation,
      clockDrift,
      computeThreads,
      computeThreadsActive,
      computeThreadsSearching,
      computeThreadsBlocked,
      fibers,
      workerIdle,
      workerParked,
      workerPolled,
      workerBlocking,
      workerRespawn,
      workerQueueFibers,
      workerFibersEnqueued,
      workerFibersSpilled,
      workerSteals,
      workerFibersStolen,
      timersOutstanding,
      timersScheduled,
      timersExecuted,
      timersCanceled,
    )
  end Definitions

  def resource[F[*]](runtime: IORuntime = IORuntime.global)(using metrics: Metrics[F], F: Async[F]): Resource[F, Unit] =
    for
      _     <- metrics.observeAll(runtime)(observations*).toResource
      drift <- Resource.make(metrics.register(Definitions.clockDrift))(_.unregister)
      _     <- sampleClockDrift(drift, runtime.config.cpuStarvationCheckInterval, runtime.config.cpuStarvationCheckInitialDelay).background
    yield ()

  def allocated[F[*]](runtime: IORuntime = IORuntime.global)(using Metrics[F], Async[F]): Allocated[F, Unit] =
    Allocated(resource[F](runtime).allocated)

  private def sampleClockDrift[F[*]](drift: HistogramOf[F, FiniteDuration, NoLabels], interval: FiniteDuration, initialDelay: Duration)(using
      F: Async[F]
  ): F[Unit] =
    def loop(start: FiniteDuration): F[Unit] =
      F.sleep(interval) >> F.monotonic.flatMap { now =>
        drift.observe((now - start - interval).max(Duration.Zero)) >> loop(now)
      }

    initialDelay match
      case delay: FiniteDuration => F.sleep(delay) >> F.monotonic.flatMap(loop)
      case _                     => F.unit

  private val observations: List[Observation[IORuntime]] =
    List(
      Observation(Definitions.cpuStarvation)((runtime, out) => out.report(runtime.metrics.cpuStarvation.starvationCount())),
      pool(Definitions.computeThreads)(_.workerThreadCount().toDouble),
      pool(Definitions.computeThreadsActive)(_.activeThreadCount().toDouble),
      pool(Definitions.computeThreadsSearching)(_.searchingThreadCount().toDouble),
      pool(Definitions.computeThreadsBlocked)(_.blockedWorkerThreadCount().toDouble),
      Observation(Definitions.fibers) { (runtime, out) =>
        runtime.metrics.workStealingThreadPool.foreach { pool =>
          out.report(FiberState.Queued, pool.localQueueFiberCount().toDouble)
          out.report(FiberState.Suspended, pool.suspendedFiberCount().toDouble)
        }
      },
      worker(Definitions.workerIdle)(worker => FiniteDuration(worker.idleTime(), NANOSECONDS)),
      worker(Definitions.workerParked)(_.parkedCount()),
      worker(Definitions.workerPolled)(_.polledCount()),
      worker(Definitions.workerBlocking)(_.blockingCount()),
      worker(Definitions.workerRespawn)(_.respawnCount()),
      worker(Definitions.workerQueueFibers)(_.localQueue.fiberCount().toDouble),
      worker(Definitions.workerFibersEnqueued)(_.localQueue.totalFiberCount()),
      worker(Definitions.workerFibersSpilled)(_.localQueue.totalSpilloverCount()),
      worker(Definitions.workerSteals)(_.localQueue.successfulStealAttemptCount()),
      worker(Definitions.workerFibersStolen)(_.localQueue.stolenFiberCount()),
      worker(Definitions.timersOutstanding)(_.timerHeap.timersOutstandingCount().toDouble),
      worker(Definitions.timersScheduled)(_.timerHeap.totalTimersScheduledCount()),
      worker(Definitions.timersExecuted)(_.timerHeap.totalTimersExecutedCount()),
      worker(Definitions.timersCanceled)(_.timerHeap.totalTimersCanceledCount()),
    )

  private def pool(definition: GaugeDef[Double, NoLabels])(value: WorkStealingThreadPoolMetrics => Double): Observation[IORuntime] =
    Observation(definition)((runtime, out) => runtime.metrics.workStealingThreadPool.foreach(pool => out.report(value(pool))))

  private def worker[V](definition: ObservableDef[V, Worker])(value: WorkerThreadMetrics => V): Observation[IORuntime] =
    Observation(definition) { (runtime, out) =>
      runtime.metrics.workStealingThreadPool.foreach(_.workerThreads.foreach(worker => out.report(Worker(worker.index), value(worker))))
    }
