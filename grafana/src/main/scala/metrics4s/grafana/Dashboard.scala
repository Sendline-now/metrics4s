package metrics4s.grafana

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import scala.util.Try

final class ServiceFilter private (val regex: String)

object ServiceFilter:
  def regex(pattern: String): ServiceFilter = ServiceFilter(pattern)

final class Dashboard private (
    val uid: String,
    val title: String,
    val group: String,
    val services: ServiceFilter,
    val description: String,
    val sections: List[Section],
):
  def withDescription(next: String): Dashboard = new Dashboard(uid, title, group, services, next, sections)

  def json: Either[GrafanaError, Json] = Render.dashboard(this)

  def render: Either[GrafanaError, String] = json.map(_.render + "\n")

  def reads: Set[String] = Dashboard.readsOf(sections)

  def writeTo(directory: Path): Either[Throwable, Path] =
    for
      content <- render.left.map(error => IllegalArgumentException(error.message))
      file    <- Try(Files.writeString(Files.createDirectories(directory).resolve(s"$uid.json"), content, StandardCharsets.UTF_8)).toEither
    yield file

object Dashboard:
  def apply(uid: String, title: String, group: String, services: ServiceFilter)(sections: Section*): Dashboard =
    new Dashboard(uid, title, group, services, s"$uid: its own metrics, the runtime and the JVM.", sections.toList)

  private def readsOf(sections: List[Section]): Set[String] =
    sections.flatMap {
      case stats: Stats => stats.stats.map(_.target.map(List(_)))
      case row: Row     => row.panels.map(_.targets)
    }.flatMap(_.toOption.toList.flatten).flatMap(_.reads).toSet
