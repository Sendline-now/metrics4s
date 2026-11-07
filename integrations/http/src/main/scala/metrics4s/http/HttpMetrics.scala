package metrics4s.http

import scala.concurrent.duration.{FiniteDuration, NANOSECONDS}

import metrics4s.*

final case class Route(template: String)

object Route:
  val Unmatched: Route = Route("unmatched")

final case class Operation(name: String)

object Operation:
  val Other: Operation = Operation("other")

enum StatusClass(val value: String):
  case Informational extends StatusClass("1xx")
  case Success       extends StatusClass("2xx")
  case Redirection   extends StatusClass("3xx")
  case ClientError   extends StatusClass("4xx")
  case ServerError   extends StatusClass("5xx")
  case Failed        extends StatusClass("failed")

object StatusClass:
  given LabelValue[StatusClass] = LabelValue.enumerated(StatusClass.values)(_.value)

  def of(code: Int): StatusClass =
    code / 100 match
      case 1 => Informational
      case 2 => Success
      case 3 => Redirection
      case 4 => ClientError
      case _ => ServerError

final class HttpServerMetrics private (val routes: List[Route]):
  private val known = routes.toSet

  private given LabelValue[Route] = LabelValue.enumerated(routes :+ Route.Unmatched)(_.template)

  val requests: CounterDef[Long, (route: Route, status: StatusClass)] = HttpServerMetrics.requests.labelled[(route: Route, status: StatusClass)]

  val duration: HistogramDef[FiniteDuration, (route: Route)] = HttpServerMetrics.duration.labelled[(route: Route)]

  val inFlight: GaugeDef[Double, NoLabels] = HttpServerMetrics.inFlight

  val definitions: List[MetricDef] = List(requests, duration, inFlight)

  def route(template: Option[String]): Route = template.map(Route(_)).filter(known).getOrElse(Route.Unmatched)

  def register[F[*]](using metrics: Metrics[F], F: Effect[F]): F[HttpServerRecorder] =
    F.map(metrics.registerAll((requests, duration, inFlight)))((requests, duration, inFlight) =>
      HttpServerRecorder(this, requests.unsafe, duration.unsafe, inFlight.unsafe)
    )

object HttpServerMetrics:
  private given LabelValue[Route] = LabelValue.bounded(_.template)

  val requests: CounterDef[Long, (route: Route, status: StatusClass)] =
    Counter("http_server_requests", "Handled HTTP requests by route template and status class").labelled[(route: Route, status: StatusClass)]

  val duration: HistogramDef[FiniteDuration, (route: Route)] =
    Histogram.seconds("http_server_request_seconds", "Time from the request to the response headers", Buckets.Normal).labelled[(route: Route)]

  val inFlight: GaugeDef[Double, NoLabels] = Gauge("http_server_in_flight", "HTTP requests being handled")

  val definitions: List[MetricDef] = List(requests, duration, inFlight)

  def apply(routes: Seq[String]): HttpServerMetrics = new HttpServerMetrics(routes.distinct.filterNot(_ == Route.Unmatched.template).map(Route(_)).toList)

final class HttpServerRecorder private[http] (
    val metrics: HttpServerMetrics,
    requests: CounterOf.Unsafe[Long, (route: Route, status: StatusClass)],
    duration: HistogramOf.Unsafe[FiniteDuration, (route: Route)],
    inFlight: GaugeOf.Unsafe[Double, NoLabels],
):
  def start(): Long =
    inFlight.inc(NoLabels)
    System.nanoTime()

  def finish(started: Long, route: Route, status: StatusClass): Unit =
    inFlight.dec(NoLabels)
    requests.inc((route = route, status = status))
    duration.observe((route = route), FiniteDuration(System.nanoTime() - started, NANOSECONDS))

  def abandon(): Unit = inFlight.dec(NoLabels)

final class HttpClientMetrics private (val operations: List[Operation]):
  private val known = operations.toSet

  private given LabelValue[Operation] = LabelValue.enumerated(operations :+ Operation.Other)(_.name)

  val requests: CounterDef[Long, (operation: Operation, status: StatusClass)] =
    HttpClientMetrics.requests.labelled[(operation: Operation, status: StatusClass)]

  val duration: HistogramDef[FiniteDuration, (operation: Operation)] = HttpClientMetrics.duration.labelled[(operation: Operation)]

  val inFlight: GaugeDef[Double, NoLabels] = HttpClientMetrics.inFlight

  val definitions: List[MetricDef] = List(requests, duration, inFlight)

  def operation(name: Option[String]): Operation = name.map(Operation(_)).filter(known).getOrElse(Operation.Other)

  def register[F[*]](using metrics: Metrics[F], F: Effect[F]): F[HttpClientRecorder] =
    F.map(metrics.registerAll((requests, duration, inFlight)))((requests, duration, inFlight) =>
      HttpClientRecorder(this, requests.unsafe, duration.unsafe, inFlight.unsafe)
    )

object HttpClientMetrics:
  private given LabelValue[Operation] = LabelValue.bounded(_.name)

  val requests: CounterDef[Long, (operation: Operation, status: StatusClass)] =
    Counter("http_client_requests", "HTTP requests sent by operation and status class").labelled[(operation: Operation, status: StatusClass)]

  val duration: HistogramDef[FiniteDuration, (operation: Operation)] =
    Histogram.seconds("http_client_request_seconds", "Time from sending a request to its response headers", Buckets.Normal).labelled[(operation: Operation)]

  val inFlight: GaugeDef[Double, NoLabels] = Gauge("http_client_in_flight", "HTTP requests waiting for a response")

  val definitions: List[MetricDef] = List(requests, duration, inFlight)

  def apply(operations: Seq[String]): HttpClientMetrics =
    new HttpClientMetrics(operations.distinct.filterNot(_ == Operation.Other.name).map(Operation(_)).toList)

final class HttpClientRecorder private[http] (
    val metrics: HttpClientMetrics,
    requests: CounterOf.Unsafe[Long, (operation: Operation, status: StatusClass)],
    duration: HistogramOf.Unsafe[FiniteDuration, (operation: Operation)],
    inFlight: GaugeOf.Unsafe[Double, NoLabels],
):
  def start(): Long =
    inFlight.inc(NoLabels)
    System.nanoTime()

  def finish(started: Long, operation: Operation, status: StatusClass): Unit =
    inFlight.dec(NoLabels)
    requests.inc((operation = operation, status = status))
    duration.observe((operation = operation), FiniteDuration(System.nanoTime() - started, NANOSECONDS))

  def abandon(): Unit = inFlight.dec(NoLabels)
