package metrics4s.grafana

import scala.util.Try
import scala.annotation.{publicInBinary, targetName}

import metrics4s.Labels

sealed trait LabelMatchers:
  extension [A](label: A)
    @targetName("is")
    def ===(value: A): LabelMatch

    @targetName("isNot")
    def =!=(value: A): LabelMatch

    def in(value: A, more: A*): LabelMatch

    def notIn(value: A, more: A*): LabelMatch

final class LabelMatch private (label: Option[String], operator: LabelMatch.Operator, values: Seq[Any]):
  private[grafana] def matcher[L](panel: String, labels: Labels[L]): Either[GrafanaError, String] =
    val names = labels.names.toList

    for
      name     <- label.orElse(Option.when(names.size == 1)(names.head)).toRight(GrafanaError.NotOneLabel(panel, names))
      field    <- Some(names.indexOf(name)).filter(_ >= 0).toRight(GrafanaError.UnknownLabel(panel, name, names))
      rendered <-
        values.distinct.foldLeft[Either[GrafanaError, Vector[String]]](Right(Vector.empty)) { (acc, value) =>
          acc.flatMap(done => Try(labels.renderField(field, value)).toOption.map(done :+ _).toRight(GrafanaError.UnknownLabelValue(panel, name, value.toString)))
        }
    yield LabelMatch.promql(name, operator, rendered)

object LabelMatch:
  enum Operator:
    case Is, IsNot, In, NotIn

  @publicInBinary private[grafana] def apply(label: Option[String], operator: Operator, values: Seq[Any]): LabelMatch = new LabelMatch(label, operator, values)

  private[grafana] def matchers[L](panel: String, labels: Labels[L], matches: List[LabelMatch]): Either[GrafanaError, List[String]] =
    matches.foldLeft[Either[GrafanaError, List[String]]](Right(Nil)) { (acc, matched) =>
      acc.flatMap(done => matched.matcher(panel, labels).map(done :+ _))
    }

  private def promql(name: String, operator: Operator, values: Vector[String]): String =
    operator match
      case Operator.Is                        => s"$name=${quoted(values.head)}"
      case Operator.IsNot                     => s"$name!=${quoted(values.head)}"
      case Operator.In if values.size == 1    => s"$name=${quoted(values.head)}"
      case Operator.NotIn if values.size == 1 => s"$name!=${quoted(values.head)}"
      case Operator.In                        => s"$name=~${quoted(values.map(literal).mkString("|"))}"
      case Operator.NotIn                     => s"$name!~${quoted(values.map(literal).mkString("|"))}"

  private def literal(value: String): String =
    value.flatMap { char =>
      if """\.+*?()|[]{}^$""".contains(char)
      then s"\\$char"
      else char.toString
    }

  private def quoted(value: String): String =
    val escaped = value.flatMap {
      case '\\'  => "\\\\"
      case '"'   => "\\\""
      case '\n'  => "\\n"
      case other => other.toString
    }
    s"\"$escaped\""
