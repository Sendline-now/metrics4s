package metrics4s.it

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import metrics4s.Metrics
import metrics4s.grafana.Dashboard
import metrics4s.mongo.MongoMetrics
import metrics4s.tapir.TapirMetrics
import metrics4s.sttpclient.SttpMetrics
import metrics4s.http.HttpServerMetrics
import metrics4s.testkit.MetricsTestkit
import metrics4s.runtime.zio.ZioInstances
import metrics4s.runtime.kyo.KyoInstances
import metrics4s.runtime.cats.CatsInstances
import metrics4s.http4s.Http4sServerMetrics
import metrics4s.runtime.rapid.RapidInstances
import metrics4s.prometheus.PrometheusExporter

final class JavaVersionItSpec extends AnyWordSpec, Matchers:
  private val Java17 = 61

  private val published: List[(String, Class[?])] = List(
    "metrics4s-core"       -> classOf[Metrics[?]],
    "metrics4s-prometheus" -> classOf[PrometheusExporter],
    "metrics4s-cats"       -> classOf[CatsInstances],
    "metrics4s-zio"        -> classOf[ZioInstances],
    "metrics4s-kyo"        -> classOf[KyoInstances],
    "metrics4s-rapid"      -> classOf[RapidInstances],
    "metrics4s-testkit"    -> classOf[MetricsTestkit],
    "metrics4s-grafana"    -> classOf[Dashboard],
    "metrics4s-http"       -> classOf[HttpServerMetrics],
    "metrics4s-http4s"     -> Http4sServerMetrics.getClass,
    "metrics4s-tapir"      -> classOf[TapirMetrics[?]],
    "metrics4s-sttp"       -> SttpMetrics.getClass,
    "metrics4s-mongo"      -> classOf[MongoMetrics],
  )

  private def majorVersionOf(compiled: Class[?]): Int =
    val in = compiled.getResourceAsStream(s"/${compiled.getName.replace('.', '/')}.class")
    try
      val header = in.readNBytes(8)
      (header(6) & 0xff) << 8 | (header(7) & 0xff)
    finally in.close()

  "every published module" should {
    "be compiled to Java 17 class files, so a service on JDK 17 can load what it depends on" in {
      published.map((module, compiled) => module -> majorVersionOf(compiled)) shouldBe published.map((module, _) => module -> Java17)
    }
  }
