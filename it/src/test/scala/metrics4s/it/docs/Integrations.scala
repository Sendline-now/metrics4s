package metrics4s.it.docs.integrations

import cats.effect.IO
import org.http4s.Request
import sttp.tapir.AnyEndpoint
import org.http4s.client.Client
import com.mongodb.MongoClientSettings
import sttp.client4.{Backend, GenericRequest}
import sttp.tapir.server.http4s.Http4sServerOptions

import metrics4s.Metrics
import metrics4s.mongo.MongoMetrics
import metrics4s.tapir.TapirMetrics
import metrics4s.sttpclient.SttpMetrics
import metrics4s.http4s.{Http4sClientMetrics, Http4sServerMetrics}

import metrics4s.runtime.cats.CatsInstances.given

abstract class Integrations(httpClient: Client[IO], endpoints: List[AnyEndpoint], backend: Backend[IO])(using Metrics[IO]):
  def classify(request: Request[IO]): Option[String]

  def operationOf(request: Request[IO]): Option[String]

  def operationOf(request: GenericRequest[?, ?]): Option[String]

  // http4s: the route templates and a classifier into them; anything else is "unmatched"
  val middleware = Http4sServerMetrics[IO](List("GET /events/{id}", "POST /events"))(classify)
  val client     = Http4sClientMetrics[IO](httpClient, List("get-event"))(operationOf)

  // tapir: the templates come from the endpoints
  TapirMetrics[IO](endpoints).map(metrics => Http4sServerOptions.customiseInterceptors[IO].metricsInterceptor(metrics.interceptor))

  // sttp client 4
  SttpMetrics.register[IO](List("get-user")).map(recorder => SttpMetrics.wrap(backend, recorder)(operationOf))

  // MongoDB driver, and mongo4s through MongoClientResource.fromSettings
  MongoMetrics(List("users", "events")).register[IO].map(listeners => listeners.applyTo(MongoClientSettings.builder()).build())
