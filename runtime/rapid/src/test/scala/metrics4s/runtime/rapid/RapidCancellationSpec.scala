package metrics4s.runtime.rapid

import java.util.concurrent.ConcurrentLinkedQueue

import scala.jdk.CollectionConverters.*
import scala.util.Try

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import rapid.Task

import metrics4s.{Effect, ExitCase}

import scala.concurrent.duration.given
import metrics4s.runtime.rapid.RapidInstances.given

final class RapidCancellationSpec extends AnyWordSpec, Matchers:
  "rapid" should {
    "run no finalizer of a fiber canceled while it is parked on a callback" in {
      val exits    = ConcurrentLinkedQueue[ExitCase]()
      val parked   = Effect[Task].guaranteeCase(Task.completable[Int])(exit => Task(exits.add(exit)).unit)
      val timedOut = Try(parked.timeout(50.millis).sync())

      Thread.sleep(200)

      (timedOut.isFailure, exits.asScala.toList) shouldBe (true, Nil)
    }
  }
