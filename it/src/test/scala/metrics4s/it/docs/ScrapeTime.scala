package metrics4s.it.docs.scrapetime

import cats.effect.IO
import cats.effect.std.Queue

import metrics4s.*

import scala.concurrent.duration.given
import metrics4s.runtime.cats.CatsInstances.given

final case class Status(workers: Int, load: Double)

trait Scheduler:
  def status(): Status

object ScrapeTime:
  val cacheSize = Gauge("processor_cache_entries", "Entries in the cache")
  val workers   = Gauge("processor_workers", "Workers of the scheduler")
  val load      = Gauge("processor_load", "Load of the scheduler")
  val queueSize = Gauge("processor_queued_items", "Items waiting in the queue")

  def observed(cache: Map[String, String], scheduler: Scheduler, queue: Queue[IO, String])(using Metrics[IO]): Allocated[IO, Unit] =
    Metrics[IO].observe(cacheSize)(_.report(cache.size.toDouble))

    Metrics[IO].observeAll(scheduler.status())(              // one read per scrape, several metrics
      Observation(workers)((status, out) => out.report(status.workers.toDouble)),
      Observation(load)((status, out) => out.report(status.load)),
    )

    Metrics[IO].observeAllF(queue.size, timeout = 1.second)( // a value only available in F
      Observation(queueSize)((size, out) => out.report(size.toDouble))
    )
