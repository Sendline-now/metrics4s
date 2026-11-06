package metrics4s.runtime.rapid

import scala.concurrent.duration.FiniteDuration

import rapid.Task

import metrics4s.{Allocated, EffectSuite}

import metrics4s.runtime.rapid.RapidInstances.given

final class RapidEffectSpec extends EffectSuite[Task]("rapid Task"):
  protected def unsafeRun[A](fa: Task[A]): A                                             = fa.sync()
  protected def never[A]: Task[A]                                                        = Task[A](blockForever())
  protected def timeout[A](fa: Task[A], after: FiniteDuration): Task[A]                  = fa.timeout(after)
  protected def useNative[A, B](allocated: Allocated[Task, A])(f: A => Task[B]): Task[B] = allocated.use(f)

  private def blockForever(): Nothing =
    Thread.sleep(Long.MaxValue)
    throw IllegalStateException("woke up from an endless sleep")
