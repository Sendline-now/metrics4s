package metrics4s.runtime.kyo

import scala.concurrent.duration.{FiniteDuration, NANOSECONDS}

import kyo.{<, Abort, Async, Scope}
import kyo.scheduler.Scheduler
import kyo.scheduler.top.{Status, WorkerStatus}

import metrics4s.*

import metrics4s.runtime.kyo.syntax.given

object KyoRuntimeMetrics:
  final case class Worker(id: Int)

  object Worker:
    given LabelValue[Worker] = LabelValue.bounded(_.id.toString)
    given Labels[Worker]     = Labels.single("worker")

  enum ThreadState derives LabelValue:
    case Active, Total

  object ThreadState:
    given Labels[ThreadState] = Labels.single("state")

  enum Decision derives LabelValue:
    case Allowed, Rejected

  object Decision:
    given Labels[Decision] = Labels.single("decision")

  enum Regulator derives LabelValue:
    case Admission, Concurrency

  object Regulator:
    given Labels[Regulator] = Labels.single("regulator")

  object Definitions:
    val workers          = Gauge("kyo_scheduler_workers", "Workers the concurrency regulator lets run tasks")
    val workersAllocated = Gauge("kyo_scheduler_workers_allocated", "Workers allocated since start, including the ones the regulator stopped using")
    val workersBlocked   = Gauge("kyo_scheduler_workers_blocked", "Workers whose thread is blocked in the task it runs")
    val workersStalled   = Gauge("kyo_scheduler_workers_stalled", "Workers that run one task for longer than the time slice")
    val load             = Gauge("kyo_scheduler_load", "Tasks queued or running per worker, on average")
    val threads          = Gauge("kyo_scheduler_threads", "Threads of the worker pool, the active ones and all of them").labelled[ThreadState]
    val admissionPercent = Gauge("kyo_scheduler_admission_percent", "Percentage of tasks the admission regulator lets in")
    val admissionTasks   = Counter("kyo_scheduler_admission_tasks", "Tasks checked by the admission regulator").labelled[Decision]

    val probeDelay = Gauge
      .seconds(
        "kyo_scheduler_probe_delay_seconds",
        "Moving average of the delays the regulators measure: how late a probe task starts (admission), how much a 1 ms thread sleep overshoots (concurrency)",
      )
      .labelled[Regulator]

    val workerLoad        = Gauge("kyo_worker_load", "Tasks queued on a worker or running on it").labelled[Worker]
    val workerExecutions  = Counter("kyo_worker_executions", "Task runs on a worker, a preempted task counts once per run").labelled[Worker]
    val workerCompletions = Counter("kyo_worker_completions", "Tasks a worker ran to completion").labelled[Worker]
    val workerPreemptions = Counter("kyo_worker_preemptions", "Tasks a worker preempted at the end of their time slice").labelled[Worker]
    val workerStolenTasks = Counter("kyo_worker_stolen_tasks", "Tasks a worker stole from the queues of other workers").labelled[Worker]
    val workerLostTasks   = Counter("kyo_worker_lost_tasks", "Tasks other workers stole from the queue of a worker").labelled[Worker]
    val workerMounts      = Counter("kyo_worker_mounts", "Times a worker started on a thread of the pool").labelled[Worker]

    val all: List[MetricDef] = List(
      workers,
      workersAllocated,
      workersBlocked,
      workersStalled,
      load,
      threads,
      admissionPercent,
      admissionTasks,
      probeDelay,
      workerLoad,
      workerExecutions,
      workerCompletions,
      workerPreemptions,
      workerStolenTasks,
      workerLostTasks,
      workerMounts,
    )
  end Definitions

  def scoped(using metrics: Metrics[KIO]): Unit < (Scope & Async & Abort[Throwable]) = scopedOn(Scheduler.get)

  def scopedOn(scheduler: Scheduler)(using metrics: Metrics[KIO]): Unit < (Scope & Async & Abort[Throwable]) =
    allocated(scheduler).toScope

  def allocated(scheduler: Scheduler)(using metrics: Metrics[KIO]): Allocated[KIO, Unit] =
    metrics.observeAll(scheduler.status())(observations*)

  private def workersOf(status: Status): Seq[WorkerStatus] = status.workers.filter(_ != null).toSeq

  private val observations: List[Observation[Status]] =
    List(
      gauge(Definitions.workers)(_.currentWorkers.toDouble),
      gauge(Definitions.workersAllocated)(_.allocatedWorkers.toDouble),
      gauge(Definitions.workersBlocked)(workersOf(_).count(_.isBlocked).toDouble),
      gauge(Definitions.workersStalled)(workersOf(_).count(_.isStalled).toDouble),
      gauge(Definitions.load)(_.loadAvg),
      Observation(Definitions.threads) { (status, out) =>
        if status.totalThreads >= 0
        then
          out.report(ThreadState.Active, status.activeThreads.toDouble)
          out.report(ThreadState.Total, status.totalThreads.toDouble)
      },
      gauge(Definitions.admissionPercent)(_.admission.admissionPercent.toDouble),
      Observation(Definitions.admissionTasks) { (status, out) =>
        out.report(Decision.Allowed, status.admission.allowed)
        out.report(Decision.Rejected, status.admission.rejected)
      },
      Observation(Definitions.probeDelay) { (status, out) =>
        val admission   = status.admission.regulator.measurementsAvg * 1e6
        val concurrency = status.concurrency.regulator.measurementsAvg
        if !admission.isNaN then out.report(Regulator.Admission, FiniteDuration(admission.toLong, NANOSECONDS))
        if !concurrency.isNaN then out.report(Regulator.Concurrency, FiniteDuration(concurrency.toLong, NANOSECONDS))
      },
      worker(Definitions.workerLoad)(_.load.toDouble),
      worker(Definitions.workerExecutions)(_.executions),
      worker(Definitions.workerCompletions)(_.completions),
      worker(Definitions.workerPreemptions)(_.preemptions),
      worker(Definitions.workerStolenTasks)(_.stolenTasks),
      worker(Definitions.workerLostTasks)(_.lostTasks),
      worker(Definitions.workerMounts)(_.mounts),
    )

  private def gauge(definition: GaugeDef[Double, NoLabels])(value: Status => Double): Observation[Status] =
    Observation(definition)((status, out) => out.report(value(status)))

  private def worker[V](definition: ObservableDef[V, Worker])(value: WorkerStatus => V): Observation[Status] =
    Observation(definition)((status, out) => workersOf(status).foreach(worker => out.report(Worker(worker.id), value(worker))))
