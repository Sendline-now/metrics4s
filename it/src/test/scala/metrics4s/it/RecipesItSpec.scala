package metrics4s.it

import java.net.ConnectException
import java.util.concurrent.TimeoutException

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import cats.effect.IO
import org.http4s.implicits.*
import org.http4s.{Method, Request, Status}

import metrics4s.Metrics
import metrics4s.testkit.MetricsTestkit
import metrics4s.it.docs.quickstart.ProcessorMetrics
import metrics4s.it.docs.recipes.route.metricsRoutes
import metrics4s.it.docs.recipes.tenants.{Tenant, TenantMetrics}
import metrics4s.it.docs.recipes.errors.{CallResult, Receipt, calls, charged}

import cats.effect.unsafe.implicits.global
import metrics4s.runtime.cats.CatsInstances.given

final class RecipesItSpec extends AnyWordSpec, Matchers:
  "the /metrics route" should {
    "serve the registry's exposition as text" in {
      val testkit       = MetricsTestkit()
      given Metrics[IO] = testkit.metrics[IO]

      val (status, body) =
        (Metrics[IO].register(ProcessorMetrics.processed) >> metricsRoutes(testkit.registry).orNotFound.run(Request[IO](Method.GET, uri"/metrics")).flatMap { response =>
          response.as[String].map(response.status -> _)
        }).unsafeRunSync()

      (status, body.linesIterator.count(_.startsWith("processor_items_total{"))) shouldBe (Status.Ok, 3)
    }
  }

  "labels known only at startup" should {
    "pre-create every configured tenant, and count one nobody configured as other" in {
      val testkit       = MetricsTestkit()
      given Metrics[IO] = testkit.metrics[IO]
      val tenants       = TenantMetrics(List(Tenant("acme"), Tenant("globex")))

      Metrics[IO].register(tenants.invoices).flatMap(_.inc((tenant = tenants.tenantOf("initech")))).unsafeRunSync()

      List("acme", "globex", "other").map(id => testkit.value(tenants.invoices, (tenant = Tenant(id)))) shouldBe List(Some(0.0), Some(0.0), Some(1.0))
    }
  }

  "errors as a label" should {
    "time each call under a result from a closed set, whatever the error says" in {
      val testkit       = MetricsTestkit()
      given Metrics[IO] = testkit.metrics[IO]

      Metrics[IO]
        .register(calls)
        .flatMap { histogram =>
          charged(histogram)(IO.pure(Receipt("r-1"))) >>
            charged(histogram)(IO.raiseError(TimeoutException("no answer in 5s"))).attempt >>
            charged(histogram)(IO.raiseError(ConnectException("refused"))).attempt >>
            charged(histogram)(IO.raiseError(IllegalStateException("card 4242 declined"))).attempt
        }
        .unsafeRunSync()

      CallResult.values.toList.map(result => result -> testkit.histogram(calls, result).map(_.count)) shouldBe List(
        CallResult.Ok      -> Some(1L),
        CallResult.Timeout -> Some(1L),
        CallResult.Refused -> Some(1L),
        CallResult.Failed  -> Some(1L),
      )
    }
  }
