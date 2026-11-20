package metrics4s.it

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets
import java.time.Duration as JavaDuration
import java.util.Base64

import scala.concurrent.duration.FiniteDuration
import scala.util.Try

import org.testcontainers.Testcontainers
import org.testcontainers.containers.{GenericContainer, Network}
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.images.builder.Transferable
import org.testcontainers.utility.DockerImageName

final class PrometheusContainer(network: Network, target: Int) extends GenericContainer[PrometheusContainer](DockerImageName.parse("prom/prometheus:v3.16.0")):
  withNetwork(network)
  withNetworkAliases("prometheus")
  withExposedPorts(9090)
  withCopyToContainer(Transferable.of(PrometheusContainer.config(target)), "/etc/prometheus/prometheus.yml")
  waitingFor(Wait.forHttp("/-/ready").forStatusCode(200))

  def url: String = s"http://$getHost:${getMappedPort(9090)}"

object PrometheusContainer:
  def config(target: Int): String =
    s"""global:
       |  scrape_interval: 1s
       |  evaluation_interval: 1s
       |scrape_configs:
       |  - job_name: processor
       |    static_configs:
       |      - targets: ["host.testcontainers.internal:$target"]
       |        labels:
       |          namespace: it
       |          service: line-processor
       |          pod: processor-0
       |""".stripMargin

final class GrafanaContainer(network: Network) extends GenericContainer[GrafanaContainer](DockerImageName.parse("grafana/grafana:13.3.1")):
  withNetwork(network)
  withExposedPorts(3000)
  withEnv("GF_SECURITY_ADMIN_PASSWORD", "metrics4s")
  waitingFor(Wait.forHttp("/api/health").forStatusCode(200))

  def url: String = s"http://$getHost:${getMappedPort(3000)}"

object Http:
  private val client = HttpClient.newBuilder().connectTimeout(JavaDuration.ofSeconds(5)).build()

  private val grafanaAuth = "Basic " + Base64.getEncoder.encodeToString("admin:metrics4s".getBytes(StandardCharsets.UTF_8))

  def get(url: String, grafana: Boolean = false): (Int, String) =
    send(HttpRequest.newBuilder(URI.create(url)).GET(), grafana)

  def post(url: String, body: String, grafana: Boolean = false): (Int, String) =
    send(HttpRequest.newBuilder(URI.create(url)).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)), grafana)

  def query(base: String, expr: String, grafana: Boolean = false): ujson.Value =
    val (_, body) = get(s"$base/api/v1/query?query=${java.net.URLEncoder.encode(expr, StandardCharsets.UTF_8)}", grafana)
    ujson.read(body)

  private def send(request: HttpRequest.Builder, grafana: Boolean): (Int, String) =
    val authorized = if grafana then request.header("Authorization", grafanaAuth) else request
    val response   = client.send(authorized.timeout(JavaDuration.ofSeconds(10)).build(), HttpResponse.BodyHandlers.ofString())
    (response.statusCode(), response.body())

object Eventually:
  def apply[A](within: FiniteDuration)(attempt: => Option[A]): Option[A] =
    val deadline = System.nanoTime() + within.toNanos

    def loop(): Option[A] =
      Try(attempt).toOption.flatten match
        case found @ Some(_)                      => found
        case None if System.nanoTime() < deadline =>
          Thread.sleep(250)
          loop()
        case None                                 => None

    loop()

object Exposed:
  def port(port: Int): Unit = Testcontainers.exposeHostPorts(port)
