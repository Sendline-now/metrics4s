package metrics4s.sttpclient

import sttp.client4.{Backend, GenericRequest, ResponseException, SyncBackend}
import sttp.client4.listener.{ListenerBackend, RequestListener}
import sttp.model.ResponseMetadata
import sttp.shared.Identity

import metrics4s.{Effect, Metrics}
import metrics4s.http.*

object SttpMetrics:
  def register[F[*]](operations: Seq[String])(using Metrics[F], Effect[F]): F[HttpClientRecorder] = HttpClientMetrics(operations).register[F]

  def wrap[F[*]](backend: Backend[F], recorder: HttpClientRecorder)(classify: GenericRequest[?, ?] => Option[String]): Backend[F] =
    ListenerBackend.lift(backend, listener(recorder, classify))

  def wrap(backend: SyncBackend, recorder: HttpClientRecorder)(classify: GenericRequest[?, ?] => Option[String]): SyncBackend =
    ListenerBackend(backend, listener(recorder, classify))

  private final case class Started(at: Long, operation: Operation)

  private def listener(recorder: HttpClientRecorder, classify: GenericRequest[?, ?] => Option[String]): RequestListener[Identity, Started] =
    new RequestListener[Identity, Started]:
      def before(request: GenericRequest[?, ?]): Started = Started(recorder.start(), recorder.metrics.operation(classify(request)))

      def responseBodyReceived(request: GenericRequest[?, ?], response: ResponseMetadata, tag: Started): Unit = ()

      def responseHandled(request: GenericRequest[?, ?], response: ResponseMetadata, tag: Started, exception: Option[ResponseException[?]]): Unit =
        recorder.finish(tag.at, tag.operation, StatusClass.of(response.code.code))

      def exception(request: GenericRequest[?, ?], tag: Started, exception: Throwable, responseBodyReceivedCalled: Boolean): Unit =
        recorder.finish(tag.at, tag.operation, StatusClass.Failed)
