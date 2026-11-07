package metrics4s.it.docs.recipes.dashboard

import java.nio.file.{Files, Paths}

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import metrics4s.MetricDef
import metrics4s.it.docs.quickstart.ProcessorMetrics as Definitions
import metrics4s.grafana.{Dashboard, Panel, Row, ServiceFilter, Stat, Stats}

object ProcessorMetrics:
  val definitions: List[MetricDef] = List(Definitions.processed, Definitions.processTime, Definitions.cacheSize)

  val dashboard: Dashboard = Dashboard("line-processor", "Line / processor", "line", ServiceFilter.regex("([a-z]+-)?processor.*"))(
    Stats(Stat.rate(Definitions.processed)),
    Row("Processing")(Panel.rate(Definitions.processed).byAll, Panel.latency(Definitions.processTime)),
  )

abstract class DashboardFileSpec extends AnyWordSpec, Matchers:
  "the dashboard" should {
    "be the file in the repository, so a metric and its panels change in the same commit" in {
      ProcessorMetrics.dashboard.render shouldBe Right(Files.readString(Paths.get("metrics/line-processor.json")))
    }

    "read only series the service's definitions serve" in {
      ProcessorMetrics.dashboard.missingSeries(ProcessorMetrics.definitions) shouldBe Set.empty
    }
  }
