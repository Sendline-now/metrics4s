package metrics4s.internal

import scala.quoted.*

import metrics4s.{Buckets, MetricKind, MetricName, MetricUnit}

object Macros:

  def metricName(raw: Expr[String], kindName: String, unitName: String)(using Quotes): Expr[MetricName] =
    import quotes.reflect.report

    val kind = MetricKind.values.find(_.value == kindName).getOrElse(report.errorAndAbort(s"Unknown metric kind $kindName"))
    val unit = MetricUnit.values.find(_.value == unitName)

    raw.value match
      case Some(name) =>
        Naming.checkMetricName(name, kind, unit) match
          case Right(())    => '{ MetricName.unsafe(${ Expr(name) }) }
          case Left(reason) => report.errorAndAbort(s"Invalid metric name \"$name\": it $reason", raw)
      case None       => report.errorAndAbort("A metric name must be a string literal, so that it is checked where it is written", raw)

  def help(raw: Expr[String])(using Quotes): Expr[String] =
    import quotes.reflect.report

    raw.value match
      case Some(help) =>
        Naming.checkHelp(help) match
          case Right(())    => Expr(help)
          case Left(reason) => report.errorAndAbort(s"Invalid metric help: $reason", raw)
      case None       => report.errorAndAbort("A metric help must be a string literal", raw)

  def labelName(raw: Expr[String])(using Quotes): Expr[String] =
    import quotes.reflect.report

    raw.value match
      case Some(name) =>
        Naming.checkLabelName(name) match
          case Right(())    => Expr(name)
          case Left(reason) => report.errorAndAbort(s"Invalid label name \"$name\": it $reason", raw)
      case None       => report.errorAndAbort("A label name must be a string literal", raw)

  def buckets(first: Expr[Double], rest: Expr[Seq[Double]])(using Quotes): Expr[Buckets] =
    import quotes.reflect.report

    val bounds = rest match
      case Varargs(others) =>
        (first +: others).map(bound =>
          bound.value.getOrElse(report.errorAndAbort("A bucket bound must be a literal; build buckets from computed bounds with Buckets.from", bound))
        )
      case _               => report.errorAndAbort("Buckets takes literal bounds; build buckets from a sequence with Buckets.from", rest)

    Buckets.check(bounds) match
      case Right(())    => '{ Buckets.unsafe(${ Expr(bounds.toList) }) }
      case Left(reason) => report.errorAndAbort(s"Invalid buckets: $reason", first)
