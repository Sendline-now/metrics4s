package metrics4s.runtime.zio

import java.util.concurrent.atomic.LongAdder

import zio.{Executor, Exit, Fiber, Runtime, Scope, Supervisor, Task, Trace, UIO, Unsafe, ZEnvironment, ZIO}
import zio.internal.ExecutionMetrics

import metrics4s.*

import metrics4s.runtime.zio.syntax.given

object ZioRuntimeMetrics:
  enum ExecutorKind derives LabelValue:
    case Default, Blocking

  object ExecutorKind:
    given Labels[ExecutorKind] = Labels.single("executor")

  enum FiberState derives LabelValue:
    case Running, Suspended

  object FiberState:
    given Labels[FiberState] = Labels.single("state")

  enum FiberOutcome derives LabelValue:
    case Success, Failure, Interrupted

  object FiberOutcome:
    given Labels[FiberOutcome] = Labels.single("outcome")

  object Definitions:
    val executorWorkers     = Gauge("zio_executor_workers", "Active worker threads of the executor").labelled[ExecutorKind]
    val executorConcurrency = Gauge("zio_executor_concurrency", "Max number of tasks the executor runs at the same time").labelled[ExecutorKind]
    val executorQueueSize   = Gauge("zio_executor_queue_size", "Tasks waiting in the queues of the executor").labelled[ExecutorKind]
    val executorEnqueued    = Counter("zio_executor_enqueued", "Tasks submitted to the executor").labelled[ExecutorKind]
    val executorDequeued    = Counter("zio_executor_dequeued", "Tasks taken from the queues of the executor").labelled[ExecutorKind]

    val fibers        = Gauge("zio_fibers", "Fibers started after the metrics were registered, running on a thread or suspended").labelled[FiberState]
    val fibersStarted = Counter("zio_fibers_started", "Fibers started after the metrics were registered")
    val fibersEnded   = Counter("zio_fibers_ended", "Fibers ended after the metrics were registered").labelled[FiberOutcome]

    val all: List[MetricDef] = List(executorWorkers, executorConcurrency, executorQueueSize, executorEnqueued, executorDequeued, fibers, fibersStarted, fibersEnded)

  def scoped(using metrics: Metrics[Task]): ZIO[Scope, Throwable, Unit] =
    for
      default  <- ZIO.executor
      blocking <- ZIO.blockingExecutor
      counters <- ZIO.succeed(FiberCounters())
      _        <- metrics.observeAll(State(List(ExecutorKind.Default -> default, ExecutorKind.Blocking -> blocking), counters))(observations*).toScoped
      _        <- Runtime.addSupervisor(counters).build
    yield ()

  private final case class State(executors: List[(ExecutorKind, Executor)], fibers: FiberCounters):
    def executionMetrics: List[(ExecutorKind, ExecutionMetrics)] =
      Unsafe.unsafe(unsafe ?=> executors.flatMap((kind, executor) => executor.metrics(using unsafe).map(kind -> _)))

  private val observations: List[Observation[State]] =
    List(
      executor(Definitions.executorWorkers)(_.workersCount.toDouble),
      executor(Definitions.executorConcurrency)(_.concurrency.toDouble),
      executor(Definitions.executorQueueSize)(_.size.toDouble),
      executor(Definitions.executorEnqueued)(_.enqueuedCount),
      executor(Definitions.executorDequeued)(_.dequeuedCount),
      Observation(Definitions.fibers) { (state, out) =>
        val running = state.fibers.running
        out.report(FiberState.Running, running.toDouble)
        out.report(FiberState.Suspended, math.max(state.fibers.alive - running, 0L).toDouble)
      },
      Observation(Definitions.fibersStarted)((state, out) => out.report(state.fibers.started.sum())),
      Observation(Definitions.fibersEnded) { (state, out) =>
        out.report(FiberOutcome.Success, state.fibers.succeeded.sum())
        out.report(FiberOutcome.Failure, state.fibers.failed.sum())
        out.report(FiberOutcome.Interrupted, state.fibers.interrupted.sum())
      },
    )

  private def executor[V](definition: ObservableDef[V, ExecutorKind])(value: ExecutionMetrics => V): Observation[State] =
    Observation(definition)((state, out) => state.executionMetrics.foreach((kind, metrics) => out.report(kind, value(metrics))))

  private final class FiberCounters extends Supervisor[Unit]:
    val started: LongAdder     = adder(1)
    val succeeded: LongAdder   = adder(0)
    val failed: LongAdder      = adder(0)
    val interrupted: LongAdder = adder(0)
    val resumed: LongAdder     = adder(1)
    val suspended: LongAdder   = adder(0)

    def alive: Long   = started.sum() - succeeded.sum() - failed.sum() - interrupted.sum()
    def running: Long = math.max(resumed.sum() - suspended.sum(), 0L)

    def value(using trace: Trace): UIO[Unit] = ZIO.unit

    def onStart[R, E, A](environment: ZEnvironment[R], effect: ZIO[R, E, A], parent: Option[Fiber.Runtime[Any, Any]], fiber: Fiber.Runtime[E, A])(using
        unsafe: Unsafe
    ): Unit = started.increment()

    def onEnd[R, E, A](value: Exit[E, A], fiber: Fiber.Runtime[E, A])(using unsafe: Unsafe): Unit =
      value match
        case Exit.Success(_)                                => succeeded.increment()
        case Exit.Failure(cause) if cause.isInterruptedOnly => interrupted.increment()
        case Exit.Failure(_)                                => failed.increment()

    override def onResume[E, A](fiber: Fiber.Runtime[E, A])(using unsafe: Unsafe): Unit = resumed.increment()

    override def onSuspend[E, A](fiber: Fiber.Runtime[E, A])(using unsafe: Unsafe): Unit = suspended.increment()

    private def adder(initial: Long): LongAdder =
      val adder = LongAdder()
      adder.add(initial)
      adder
