package metrics4s.runtime.kyo

import java.util.concurrent.TimeoutException

import scala.concurrent.Await
import scala.concurrent.duration.FiniteDuration

import kyo.{Fiber, Kyo}

import metrics4s.{Allocated, Dispatch}

import kyo.AllowUnsafe.embrace.danger

trait KyoDispatchInstance:
  given kyoDispatch: Dispatch[KIO] = new Dispatch[KIO]:
    def runner: Allocated[KIO, Dispatch.Runner[KIO]] = Allocated(Kyo.lift((KyoDispatchInstance.runner, Kyo.unit)))

object KyoDispatchInstance extends KyoDispatchInstance:
  private val runner: Dispatch.Runner[KIO] = new Dispatch.Runner[KIO]:
    def unsafeRunTimed[A](fa: KIO[A], timeout: FiniteDuration): A =
      val fiber = Fiber.Unsafe.init(fa)

      try Await.result(fiber.toFuture(), timeout)
      catch
        case error: TimeoutException =>
          val _ = fiber.interrupt()
          throw error
