package metrics4s.it.docs.recipes.errors

import java.net.ConnectException
import java.util.concurrent.TimeoutException

import scala.concurrent.duration.FiniteDuration

import cats.effect.IO

import metrics4s.*

final case class Receipt(id: String)

enum CallResult derives LabelValue:
  case Ok, Timeout, Refused, Failed

object CallResult:
  given Labels[CallResult] = Labels.single("result")

  def of(result: Either[Throwable, ?]): CallResult =
    result match
      case Right(_)                  => CallResult.Ok
      case Left(_: TimeoutException) => CallResult.Timeout
      case Left(_: ConnectException) => CallResult.Refused
      case Left(_)                   => CallResult.Failed

val calls = Histogram.seconds("payments_call_seconds", "Calls to the payment provider by result", Buckets.Normal).labelled[CallResult]

def charged(histogram: HistogramOf[IO, FiniteDuration, CallResult])(charge: IO[Receipt]): IO[Receipt] = histogram.timedBy(charge)(CallResult.of)
