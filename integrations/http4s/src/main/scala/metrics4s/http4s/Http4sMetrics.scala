package metrics4s.http4s

import cats.data.{Kleisli, OptionT}
import cats.effect.kernel.{Async, Resource}
import org.http4s.{HttpRoutes, Request, Response}
import org.http4s.client.Client

import metrics4s.Metrics
import metrics4s.http.*

import cats.syntax.flatMap.given
import cats.syntax.functor.given
import metrics4s.runtime.cats.CatsInstances.given

object Http4sServerMetrics:
  def apply[F[*]](routes: Seq[String])(classify: Request[F] => Option[String])(using Metrics[F], Async[F]): F[HttpRoutes[F] => HttpRoutes[F]] =
    HttpServerMetrics(routes).register[F].map(recorder => middleware(recorder)(classify))

  def middleware[F[*]](recorder: HttpServerRecorder)(classify: Request[F] => Option[String])(using F: Async[F]): HttpRoutes[F] => HttpRoutes[F] =
    routes =>
      Kleisli { request =>
        OptionT {
          F.delay(recorder.start()).flatMap { started =>
            F.onCancel(F.attempt(routes(request).value), F.delay(recorder.abandon())).flatMap { result =>
              val route  = recorder.metrics.route(classify(request))
              val status = result.fold(_ => StatusClass.ServerError, _.fold(StatusClass.ClientError)(response => StatusClass.of(response.status.code)))
              F.delay(recorder.finish(started, route, status)) >> F.fromEither(result)
            }
          }
        }
      }

object Http4sClientMetrics:
  def apply[F[*]](client: Client[F], operations: Seq[String])(classify: Request[F] => Option[String])(using Metrics[F], Async[F]): F[Client[F]] =
    HttpClientMetrics(operations).register[F].map(recorder => wrap(client, recorder)(classify))

  def wrap[F[*]](client: Client[F], recorder: HttpClientRecorder)(classify: Request[F] => Option[String])(using F: Async[F]): Client[F] =
    Client[F] { request =>
      val operation = recorder.metrics.operation(classify(request))

      Resource.eval(F.delay(recorder.start())).flatMap { started =>
        client
          .run(request)
          .attempt
          .onCancel(Resource.eval(F.delay(recorder.abandon())))
          .evalTap(result => F.delay(recorder.finish(started, operation, statusOf(result))))
          .flatMap(result => Resource.eval(F.fromEither(result)))
      }
    }

  private def statusOf[F[*]](result: Either[Throwable, Response[F]]): StatusClass =
    result.fold(_ => StatusClass.Failed, response => StatusClass.of(response.status.code))
