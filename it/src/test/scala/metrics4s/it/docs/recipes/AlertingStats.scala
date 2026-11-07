package metrics4s.it.docs.recipes.stats

import metrics4s.grafana.{Color, Stat, Stats}
import metrics4s.http.{HttpServerMetrics, Route, StatusClass}

object AlertingStats:
  val stats: Stats =
    Stats(
      Stat.podsUp,
      Stat
        .share(HttpServerMetrics.requests)(_.status.in(StatusClass.ServerError, StatusClass.Failed))
        .titled("Server errors")
        .withThresholds(Color.Green, 0.01 -> Color.Orange, 0.05 -> Color.Red),
      Stat
        .quantile(HttpServerMetrics.duration, 0.99)
        .where(_.route === Route("POST /payments"))
        .titled("Payments, p99")
        .withThresholds(Color.Green, 0.5  -> Color.Orange, 2.0  -> Color.Red),
    )
