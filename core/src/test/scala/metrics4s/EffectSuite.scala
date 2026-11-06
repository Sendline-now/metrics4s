package metrics4s

import java.util.concurrent.{ConcurrentLinkedQueue, TimeoutException}
import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}

import scala.concurrent.duration.FiniteDuration
import scala.jdk.CollectionConverters.*
import scala.util.{Failure, Success, Try}

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import scala.concurrent.duration.given

abstract class EffectSuite[F[*]](runtime: String)(using F: Effect[F], dispatch: Dispatch[F]) extends AnyWordSpec, Matchers:
  protected def unsafeRun[A](fa: F[A]): A
  protected def never[A]: F[A]
  protected def timeout[A](fa: F[A], after: FiniteDuration): F[A]
  protected def useNative[A, B](allocated: Allocated[F, A])(f: A => F[B]): F[B]

  private final class Boom(message: String) extends RuntimeException(message)

  private def run[A](fa: F[A]): Either[Throwable, A] = unsafeRun(F.attempt(fa))

  private def record[A](queue: ConcurrentLinkedQueue[A], value: A): F[Unit] = F.void(F.delay(queue.add(value)))

  private def awaitSize(queue: ConcurrentLinkedQueue[?], size: Int): List[Any] =
    val deadline = System.nanoTime() + 2.seconds.toNanos
    while queue.size < size && System.nanoTime() < deadline do Thread.sleep(5)
    Thread.sleep(50)
    queue.asScala.toList

  private def onForeignThread[A](body: => A): A =
    val result = AtomicReference[Try[A]]()
    val thread = Thread(() => result.set(Try(body)))
    thread.start()
    thread.join()
    result.get.get

  private def tracked(events: ConcurrentLinkedQueue[String], name: String): Allocated[F, String] =
    Allocated.make(F.map(record(events, s"acquire $name"))(_ => name))(acquired => record(events, s"release $acquired"))

  s"Effect[$runtime]" should {
    "suspend side effects until the effect is run, and run them again on every run" in {
      val calls     = AtomicInteger(0)
      val delayed   = F.delay(calls.incrementAndGet())
      val suspended = F.suspend(F.pure(calls.incrementAndGet()))
      val before    = calls.get()

      (before, unsafeRun(F.flatMap(delayed)(_ => delayed)), unsafeRun(suspended)) shouldBe (0, 2, 3)
    }

    "raise, recover and attempt errors, including the ones thrown inside delay, map and flatMap" in {
      val boom = Boom("boom")

      (
        run(F.raiseError[Int](boom)),
        unsafeRun(F.handleErrorWith(F.raiseError[Int](boom))(_ => F.pure(1))),
        unsafeRun(F.attempt(F.raiseError[Int](boom))),
        run(Effect.fromEither[F, Int](Right(2))),
        run(F.delay[Int](throw boom)),
        run(F.map[Int, Int](F.pure(1))(_ => throw boom)),
        run(F.flatMap[Int, Int](F.pure(1))(_ => throw boom)),
      ) shouldBe (Left(boom), 1, Left(boom), Right(2), Left(boom), Left(boom), Left(boom))
    }

    "report how an effect ended to guaranteeCase, exactly once" in {
      val boom                     = Boom("boom")
      val cases                    = ConcurrentLinkedQueue[ExitCase]()
      def track[A](fa: F[A]): F[A] = F.guaranteeCase(fa)(record(cases, _))

      val results = List(run(track(F.pure(1))), run(track(F.raiseError[Int](boom))), run(timeout(track(never[Int]), 50.millis)))

      (results.map(_.isRight), awaitSize(cases, 3)) shouldBe (
        List(true, false, false),
        List(ExitCase.Succeeded, ExitCase.Errored(boom), ExitCase.Canceled),
      )
    }

    "fail a successful effect when its finalizer fails, and keep the original error with the finalizer's suppressed otherwise" in {
      val original  = Boom("original")
      val finalizer = Boom("finalizer")

      val succeeded = run(F.guaranteeCase(F.pure(1))(_ => F.raiseError(finalizer)))
      val errored   = run(F.guaranteeCase(F.raiseError[Int](original))(_ => F.raiseError(finalizer)))

      (succeeded, errored, original.getSuppressed.toList) shouldBe (Left(finalizer), Left(original), List(finalizer))
    }

    "run onError only on errors" in {
      val seen = ConcurrentLinkedQueue[String]()

      val _ = run(F.onError(F.pure(1))(_ => record(seen, "ok")))
      val _ = run(F.onError(F.raiseError[Int](Boom("boom")))(error => record(seen, error.getMessage)))

      seen.asScala.toList shouldBe List("boom")
    }

    "release what bracketCase acquired with the way the use ended, and release nothing when the acquisition fails" in {
      val boom     = Boom("boom")
      val used     = AtomicInteger(0)
      val released = ConcurrentLinkedQueue[(Int, ExitCase)]()

      def bracket(acquire: F[Int])(use: Int => F[Int]): F[Int] =
        F.bracketCase(acquire)(use)((acquired, exitCase) => record(released, (acquired, exitCase)))

      val succeeded = run(bracket(F.pure(7))(acquired => F.pure(acquired + 1)))
      val errored   = run(bracket(F.pure(7))(_ => F.raiseError(boom)))
      val canceled  = run(timeout(bracket(F.pure(7))(_ => never), 50.millis))
      val notTaken  = run(bracket(F.raiseError(boom))(_ => F.delay(used.incrementAndGet())))

      (succeeded, errored, canceled.isLeft, notTaken, used.get, awaitSize(released, 3)) shouldBe (
        Right(8),
        Left(boom),
        true,
        Left(boom),
        0,
        List((7, ExitCase.Succeeded), (7, ExitCase.Errored(boom)), (7, ExitCase.Canceled)),
      )
    }

    "traverse in order and skip whenA(false)" in {
      val seen = ConcurrentLinkedQueue[Int]()

      val _ = unsafeRun(Effect.traverse_(List(1, 2, 3))(record(seen, _)))
      val _ = unsafeRun(Effect.whenA(true)(record(seen, 4)))
      val _ = unsafeRun(Effect.whenA(false)(record(seen, 5)))

      seen.asScala.toList shouldBe List(1, 2, 3, 4)
    }

    "read a monotonic clock" in {
      val elapsed = unsafeRun(
        F.flatMap(F.monotonic)(start => F.flatMap(F.delay(Thread.sleep(5)))(_ => F.map(F.monotonic)(_ - start)))
      )

      elapsed should be >= 5.millis
    }
  }

  s"Allocated[$runtime]" should {
    "release in reverse order, also when the use, a later acquisition or a map fails" in {
      val events = ConcurrentLinkedQueue[String]()
      val both   = tracked(events, "a").flatMap(a => tracked(events, "b").map(b => a + b))

      val succeeded     = run(both.use(ab => F.map(record(events, s"use $ab"))(_ => ab)))
      val failedUse     = run(tracked(events, "c").use(_ => F.raiseError[Int](Boom("use failed"))))
      val failedAcquire = run(tracked(events, "d").flatMap(_ => Allocated.eval(F.raiseError[String](Boom("acquire failed")))).use(F.pure))
      val failedMap     = run(tracked(events, "e").map[String](_ => throw Boom("map failed")).use(F.pure))

      (
        succeeded,
        failedUse.left.map(_.getMessage),
        failedAcquire.left.map(_.getMessage),
        failedMap.left.map(_.getMessage),
        events.asScala.toList,
      ) shouldBe (
        Right("ab"),
        Left("use failed"),
        Left("acquire failed"),
        Left("map failed"),
        List("acquire a", "acquire b", "use ab", "release b", "release a", "acquire c", "release c", "acquire d", "release d", "acquire e", "release e"),
      )
    }

    "release when the use is canceled" in {
      val events   = ConcurrentLinkedQueue[String]()
      val canceled = run(timeout(tracked(events, "a").use(_ => never[Int]), 50.millis))

      (canceled.isLeft, awaitSize(events, 2)) shouldBe (true, List("acquire a", "release a"))
    }

    "become the runtime's own resource and release it in reverse order, also on failure" in {
      val events = ConcurrentLinkedQueue[String]()
      val both   = tracked(events, "a").flatMap(a => tracked(events, "b").map(b => a + b))

      val succeeded = run(useNative(both)(ab => F.map(record(events, s"use $ab"))(_ => ab)))
      val failed    = run(useNative(tracked(events, "c"))(_ => F.raiseError[Int](Boom("use failed"))))

      (succeeded, failed.left.map(_.getMessage), events.asScala.toList) shouldBe (
        Right("ab"),
        Left("use failed"),
        List("acquire a", "acquire b", "use ab", "release b", "release a", "acquire c", "release c"),
      )
    }
  }

  s"Dispatch[$runtime]" should {
    "run an effect from a foreign thread, fail with its error, and give up on a timeout" in {
      val boom = Boom("boom")

      val result = unsafeRun(dispatch.runner.use { runner =>
        F.delay {
          onForeignThread {
            val value   = Try(runner.unsafeRunTimed(F.delay(42), 1.second))
            val failed  = Try(runner.unsafeRunTimed(F.raiseError[Int](boom), 1.second))
            val expired = Try(runner.unsafeRunTimed(never[Int], 50.millis)).failed.map(_.isInstanceOf[TimeoutException])
            (value, failed, expired)
          }
        }
      })

      result shouldBe (Success(42), Failure(boom), Success(true))
    }

    "cancel the effect it gave up on" in {
      val exits = ConcurrentLinkedQueue[ExitCase]()

      val expired = unsafeRun(dispatch.runner.use { runner =>
        F.delay(onForeignThread(Try(runner.unsafeRunTimed(F.guaranteeCase(never[Int])(record(exits, _)), 50.millis)).isFailure))
      })

      (expired, awaitSize(exits, 1)) shouldBe (true, List(ExitCase.Canceled))
    }
  }
