package metrics4s.sttpclient

import sttp.client4.{Backend, GenericRequest, ResponseException, StreamBackend, SyncBackend, WebSocketBackend, WebSocketStreamBackend, WebSocketSyncBackend}
import sttp.client4.listener.{ListenerBackend, RequestListener}
import sttp.model.ResponseMetadata
import sttp.shared.Identity

import metrics4s.{Effect, Metrics}
import metrics4s.http.*

object SttpMetrics:
  type Classify = GenericRequest[?, ?] => Option[String]

  def register[F[*]](operations: Seq[String])(using Metrics[F], Effect[F]): F[HttpClientRecorder] = HttpClientMetrics(operations).register[F]

  def wrap(backend: SyncBackend, recorder: HttpClientRecorder)(classify: Classify): SyncBackend =
    ListenerBackend(backend, listener(recorder, classify))

  def wrap(backend: WebSocketSyncBackend, recorder: HttpClientRecorder)(classify: Classify): WebSocketSyncBackend =
    ListenerBackend(backend, listener(recorder, classify))

  def wrap[F[*]](backend: Backend[F], recorder: HttpClientRecorder)(classify: Classify): Backend[F] =
    ListenerBackend(backend, RequestListener.lift(listener(recorder, classify), backend.monad))

  def wrap[F[*]](backend: WebSocketBackend[F], recorder: HttpClientRecorder)(classify: Classify): WebSocketBackend[F] =
    ListenerBackend(backend, RequestListener.lift(listener(recorder, classify), backend.monad))

  def wrap[F[*], S](backend: StreamBackend[F, S], recorder: HttpClientRecorder)(classify: Classify): StreamBackend[F, S] =
    ListenerBackend(backend, RequestListener.lift(listener(recorder, classify), backend.monad))

  def wrap[F[*], S](backend: WebSocketStreamBackend[F, S], recorder: HttpClientRecorder)(classify: Classify): WebSocketStreamBackend[F, S] =
    ListenerBackend(backend, RequestListener.lift(listener(recorder, classify), backend.monad))

  private final case class Started(at: Long, operation: Operation)

  private def listener(recorder: HttpClientRecorder, classify: Classify): RequestListener[Identity, Started] =
    new RequestListener[Identity, Started]:
      def before(request: GenericRequest[?, ?]): Started =
        val operation = recorder.metrics.operation(classify(request))
        Started(recorder.start(), operation)

      def responseBodyReceived(request: GenericRequest[?, ?], response: ResponseMetadata, tag: Started): Unit = ()

      def responseHandled(request: GenericRequest[?, ?], response: ResponseMetadata, tag: Started, exception: Option[ResponseException[?]]): Unit =
        recorder.finish(tag.at, tag.operation, ClientStatus.of(response.code.code))

      def exception(request: GenericRequest[?, ?], tag: Started, exception: Throwable, responseBodyReceivedCalled: Boolean): Unit =
        exception match
          case _: InterruptedException => recorder.abandon()
          case _                       => recorder.finish(tag.at, tag.operation, ClientStatus.Failed)
