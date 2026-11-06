package metrics4s.runtime.zio

import java.util.concurrent.TimeoutException

import scala.concurrent.duration.FiniteDuration

import zio.{Duration, Runtime, Task, Unsafe, ZIO}

import metrics4s.{Allocated, EffectSuite}

import metrics4s.runtime.zio.ZioInstances.given
import metrics4s.runtime.zio.syntax.given

final class ZioEffectSpec extends EffectSuite[Task]("zio Task"):
  protected def unsafeRun[A](fa: Task[A]): A                                             = Unsafe.unsafe(unsafe ?=> Runtime.default.unsafe.run(fa).getOrThrow())
  protected def never[A]: Task[A]                                                        = ZIO.never
  protected def timeout[A](fa: Task[A], after: FiniteDuration): Task[A]                  = fa.timeoutFail(TimeoutException(after.toString))(Duration.fromScala(after))
  protected def useNative[A, B](allocated: Allocated[Task, A])(f: A => Task[B]): Task[B] = ZIO.scoped(allocated.toScoped.flatMap(f))
