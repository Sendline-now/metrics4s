package metrics4s

import scala.annotation.implicitNotFound
import scala.concurrent.duration.FiniteDuration

@implicitNotFound(
  "No Dispatch[${F}]. Running an effect from a scrape needs an asynchronous runtime: import `metrics4s.runtime.cats.CatsInstances.given` " +
    "(any F with cats.effect.Async), `metrics4s.runtime.zio.ZioInstances.given`, `metrics4s.runtime.kyo.KyoInstances.given` or `metrics4s.runtime.rapid.RapidInstances.given`."
)
trait Dispatch[F[*]]:
  def runner: Allocated[F, Dispatch.Runner[F]]

object Dispatch:
  inline def apply[F[*]](using F: Dispatch[F]): F.type = F

  trait Runner[F[*]]:
    def unsafeRunTimed[A](fa: F[A], timeout: FiniteDuration): A
