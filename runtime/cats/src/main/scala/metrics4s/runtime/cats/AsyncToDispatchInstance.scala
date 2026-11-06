package metrics4s.runtime.cats

import java.util.concurrent.TimeoutException

import scala.concurrent.Await
import scala.concurrent.duration.FiniteDuration

import cats.effect.kernel.Async
import cats.effect.std.Dispatcher

import metrics4s.{Allocated, Dispatch}

trait AsyncToDispatchInstance:
  given asyncDispatch: [F[*]] => (F: Async[F]) => Dispatch[F] = new Dispatch[F]:
    def runner: Allocated[F, Dispatch.Runner[F]] =
      Allocated(F.map(Dispatcher.parallel[F](await = false).allocated)((dispatcher, release) => (AsyncToDispatchInstance.runnerOf(dispatcher), release)))

object AsyncToDispatchInstance extends AsyncToDispatchInstance:
  private def runnerOf[F[*]](dispatcher: Dispatcher[F]): Dispatch.Runner[F] = new Dispatch.Runner[F]:
    def unsafeRunTimed[A](fa: F[A], timeout: FiniteDuration): A =
      val (result, cancel) = dispatcher.unsafeToFutureCancelable(fa)

      try Await.result(result, timeout)
      catch
        case error: TimeoutException =>
          val _ = cancel()
          throw error
