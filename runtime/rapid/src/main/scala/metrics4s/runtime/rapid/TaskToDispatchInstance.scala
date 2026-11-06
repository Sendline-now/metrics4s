package metrics4s.runtime.rapid

import scala.concurrent.duration.FiniteDuration

import rapid.Task

import metrics4s.{Allocated, Dispatch}

trait TaskToDispatchInstance:
  given rapidDispatch: Dispatch[Task] = new Dispatch[Task]:
    def runner: Allocated[Task, Dispatch.Runner[Task]] = Allocated(Task.pure((TaskToDispatchInstance.runner, Task.pure(()))))

object TaskToDispatchInstance extends TaskToDispatchInstance:
  private val runner: Dispatch.Runner[Task] = new Dispatch.Runner[Task]:
    def unsafeRunTimed[A](fa: Task[A], timeout: FiniteDuration): A = fa.timeout(timeout).sync()
