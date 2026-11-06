package metrics4s.runtime.kyo

import scala.concurrent.duration.FiniteDuration

import kyo.{Async, Duration, KyoApp, Scope}

import metrics4s.{Allocated, EffectSuite}

import kyo.AllowUnsafe.embrace.danger
import metrics4s.runtime.kyo.KyoInstances.given
import metrics4s.runtime.kyo.syntax.given

final class KyoEffectSpec extends EffectSuite[KIO]("kyo"):
  protected def unsafeRun[A](fa: KIO[A]): A                                           = KyoApp.Unsafe.runAndBlock(Duration.Infinity)(fa).getOrThrow
  protected def never[A]: KIO[A]                                                      = Async.never
  protected def timeout[A](fa: KIO[A], after: FiniteDuration): KIO[A]                 = Async.timeout(Duration.fromScala(after))(fa)
  protected def useNative[A, B](allocated: Allocated[KIO, A])(f: A => KIO[B]): KIO[B] = Scope.run(allocated.toScope.flatMap(f))
