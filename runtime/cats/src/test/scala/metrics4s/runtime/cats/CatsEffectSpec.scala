package metrics4s.runtime.cats

import scala.concurrent.duration.FiniteDuration

import cats.effect.IO

import metrics4s.{Allocated, EffectSuite}

import cats.effect.unsafe.implicits.global
import metrics4s.runtime.cats.CatsInstances.given
import metrics4s.runtime.cats.syntax.given

final class CatsEffectSpec extends EffectSuite[IO]("cats IO"):
  protected def unsafeRun[A](fa: IO[A]): A                                         = fa.unsafeRunSync()
  protected def never[A]: IO[A]                                                    = IO.never
  protected def timeout[A](fa: IO[A], after: FiniteDuration): IO[A]                = fa.timeout(after)
  protected def useNative[A, B](allocated: Allocated[IO, A])(f: A => IO[B]): IO[B] = allocated.toResource.use(f)
