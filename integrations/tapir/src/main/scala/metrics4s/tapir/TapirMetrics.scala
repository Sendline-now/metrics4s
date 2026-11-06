package metrics4s.tapir

import sttp.monad.MonadError
import sttp.tapir.AnyEndpoint
import sttp.tapir.server.interceptor.metrics.MetricsRequestInterceptor
import sttp.tapir.server.metrics.{EndpointMetric, Metric}

import metrics4s.{Effect, Metrics}
import metrics4s.http.*

final class TapirMetrics[F[*]] private (val recorder: HttpServerRecorder):
  val metric: Metric[F, HttpServerRecorder] =
    Metric(
      recorder,
      (_, recorder, monad) =>
        monad.eval {
          val started = recorder.start()
          TapirMetrics.endpointMetric(recorder, started, monad)
        },
    )

  def interceptor: MetricsRequestInterceptor[F] = MetricsRequestInterceptor(List(metric), Seq.empty)

object TapirMetrics:
  def apply[F[*]](endpoints: Seq[AnyEndpoint])(using metrics: Metrics[F], F: Effect[F]): F[TapirMetrics[F]] =
    F.map(HttpServerMetrics(endpoints.map(template)).register[F])(recorder => new TapirMetrics[F](recorder))

  def template(endpoint: AnyEndpoint): String =
    s"${endpoint.method.fold("ANY")(_.method)} ${endpoint.showPathTemplate(showQueryParam = None)}"

  private def endpointMetric[F[*]](recorder: HttpServerRecorder, started: Long, monad: MonadError[F]): EndpointMetric[F] =
    def finish(route: Route, status: StatusClass): F[Unit] = monad.eval(recorder.finish(started, route, status))

    EndpointMetric[F]()
      .onResponseHeaders((endpoint, response) => finish(recorder.metrics.route(Some(template(endpoint))), StatusClass.of(response.code.code)))
      .onException((endpoint, _) => finish(recorder.metrics.route(Some(template(endpoint))), StatusClass.ServerError))
      .onInterceptorResponse(response => finish(Route.Unmatched, StatusClass.of(response.code.code)))
      .onDecodeFailure(() => finish(Route.Unmatched, StatusClass.ClientError))
