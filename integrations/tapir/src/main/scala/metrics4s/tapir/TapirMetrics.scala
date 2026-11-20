package metrics4s.tapir

import java.util.concurrent.atomic.AtomicBoolean

import sttp.monad.MonadError
import sttp.tapir.AnyEndpoint
import sttp.tapir.server.interceptor.{EndpointInterceptor, RequestHandler, Responder}
import sttp.tapir.server.interceptor.metrics.MetricsRequestInterceptor
import sttp.tapir.server.metrics.{EndpointMetric, Metric}

import metrics4s.{Effect, Metrics}
import metrics4s.http.*

import scala.language.adhocExtensions

final class TapirMetrics[F[*]] private (val recorder: HttpServerRecorder):
  val interceptor: MetricsRequestInterceptor[F] = TapirMetrics.Interceptor[F](recorder)

object TapirMetrics:
  def apply[F[*]](endpoints: Seq[AnyEndpoint])(using metrics: Metrics[F], F: Effect[F]): F[TapirMetrics[F]] =
    F.map(HttpServerMetrics(endpoints.map(template)).register[F])(recorder => new TapirMetrics[F](recorder))

  def template(endpoint: AnyEndpoint): String =
    s"${endpoint.method.fold("ANY")(_.method)} ${endpoint.showPathTemplate(showQueryParam = None)}"

  private final class Interceptor[F[*]](recorder: HttpServerRecorder) extends MetricsRequestInterceptor[F](Nil, Seq.empty):
    override def apply[R, B](responder: Responder[F, B], requestHandler: EndpointInterceptor[F] => RequestHandler[F, R, B]): RequestHandler[F, R, B] =
      RequestHandler.from { (request, endpoints, monad) =>
        monad.flatMap(monad.eval(Request(recorder, recorder.start()))) { started =>
          val measured = MetricsRequestInterceptor[F](List(Metric[F, Unit]((), (_, _, inner) => inner.unit(started.metric(inner)))), Seq.empty)

          monad.ensure2(measured(responder, requestHandler)(request, endpoints)(using monad), monad.eval(started.abandon()))
        }
      }

  private final class Request(recorder: HttpServerRecorder, started: Long):
    private val ended = AtomicBoolean(false)

    def abandon(): Unit = if ended.compareAndSet(false, true) then recorder.abandon()

    def metric[F[*]](monad: MonadError[F]): EndpointMetric[F] =
      def finish(route: Route, status: StatusClass): F[Unit] = monad.eval(if ended.compareAndSet(false, true) then recorder.finish(started, route, status))

      EndpointMetric[F]()
        .onResponseHeaders((endpoint, response) => finish(recorder.metrics.route(Some(template(endpoint))), StatusClass.of(response.code.code)))
        .onException((endpoint, _) => finish(recorder.metrics.route(Some(template(endpoint))), StatusClass.ServerError))
        .onInterceptorResponse(response => finish(Route.Unmatched, StatusClass.of(response.code.code)))
        .onDecodeFailure(() => finish(Route.Unmatched, StatusClass.ClientError))
