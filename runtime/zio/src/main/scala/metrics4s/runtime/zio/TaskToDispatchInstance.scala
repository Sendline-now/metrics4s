package metrics4s.runtime.zio

import java.util.concurrent.TimeoutException

import scala.concurrent.Await
import scala.concurrent.duration.FiniteDuration

import zio.{Runtime, Task, Unsafe, ZIO}

import metrics4s.{Allocated, Dispatch}

trait TaskToDispatchInstance:
  given taskDispatch: Dispatch[Task] = new Dispatch[Task]:
    def runner: Allocated[Task, Dispatch.Runner[Task]] =
      Allocated(ZIO.runtime[Any].map(runtime => (TaskToDispatchInstance.runnerOf(runtime), ZIO.unit)))

object TaskToDispatchInstance extends TaskToDispatchInstance:
  private def runnerOf(runtime: Runtime[Any]): Dispatch.Runner[Task] = new Dispatch.Runner[Task]:
    def unsafeRunTimed[A](fa: Task[A], timeout: FiniteDuration): A =
      val result = Unsafe.unsafe(unsafe ?=> runtime.unsafe.runToFuture(fa.interruptible))

      try Await.result(result, timeout)
      catch
        case error: TimeoutException =>
          val _ = result.cancel()
          throw error
