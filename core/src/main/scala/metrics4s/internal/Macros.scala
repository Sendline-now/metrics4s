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

  def labelNames[N <: Tuple: Type](using Quotes): Expr[List[String]] =
    import quotes.reflect.*

    def unknown: Nothing =
      report.errorAndAbort("The fields that name the labels must be known where the labels are derived")

    def constant(field: TypeRepr): String =
      field.dealias match
        case ConstantType(StringConstant(name)) => name
        case _                                  => unknown

    def fields(tuple: TypeRepr): List[String] =
      tuple.dealias match
        case AppliedType(cons, List(head, tail)) if cons.typeSymbol.fullName == "scala.*:"      => constant(head) :: fields(tail)
        case AppliedType(cons, arguments) if cons.typeSymbol.fullName.startsWith("scala.Tuple") => arguments.map(constant)
        case empty if empty =:= TypeRepr.of[EmptyTuple]                                         => Nil
        case _                                                                                  => unknown

    val names = fields(TypeRepr.of[N]).map(Naming.snakeCase)

    names.foreach { name =>
      Naming.checkLabelName(name) match
        case Right(())    => ()
        case Left(reason) => report.errorAndAbort(s"Invalid label name \"$name\": it $reason")
    }

    names.diff(names.distinct).headOption.foreach(name => report.errorAndAbort(s"Invalid label name \"$name\": two fields are both named so in snake case"))

    Expr(names)
  end labelNames

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

  def linearBuckets(start: Expr[Double], width: Expr[Double], count: Expr[Int])(using Quotes): Expr[Buckets] =
    generated(start, Buckets.linearBounds(literal(start), literal(width), literal(count)))

  def exponentialBuckets(start: Expr[Double], factor: Expr[Double], count: Expr[Int])(using Quotes): Expr[Buckets] =
    generated(start, Buckets.exponentialBounds(literal(start), literal(factor), literal(count)))

  private def literal[A: FromExpr](value: Expr[A])(using Quotes): A =
    import quotes.reflect.report

    value.value.getOrElse(
      report.errorAndAbort("Generated buckets take literals; build them from computed values with Buckets.linearFrom or Buckets.exponentialFrom", value)
    )

  private def generated(position: Expr[Double], bounds: Either[String, List[Double]])(using Quotes): Expr[Buckets] =
    import quotes.reflect.report

    bounds.flatMap(all => Buckets.check(all).map(_ => all)) match
      case Right(all)   => '{ Buckets.unsafe(${ Expr(all) }) }
      case Left(reason) => report.errorAndAbort(s"Invalid buckets: $reason", position)
