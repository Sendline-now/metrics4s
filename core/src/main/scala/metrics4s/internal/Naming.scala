package metrics4s.internal

import metrics4s.{MetricKind, MetricUnit}

object Naming:
  private val MetricNamePattern = "[a-z][a-z0-9_]*".r
  private val LabelNamePattern  = "[a-zA-Z_][a-zA-Z0-9_]*".r
  private val GeneratedSuffixes = List("_bucket", "_count", "_sum", "_created", "_info")

  val ReservedLabels: Set[String] = Set("le", "quantile")

  def checkMetricName(name: String, kind: MetricKind, unit: Option[MetricUnit]): Either[String, Unit] =
    if !MetricNamePattern.matches(name) then Left("must be snake case: a lowercase letter, then lowercase letters, digits and underscores")
    else if name.contains("__") then Left("must not contain a double underscore")
    else if name.endsWith("_") then Left("must not end with an underscore")
    else if name.endsWith("_total") then Left(totalSuffix(kind))
    else
      GeneratedSuffixes.find(name.endsWith) match
        case Some(suffix) => Left(s"must not end with $suffix, which the exposition format adds itself")
        case None         => checkUnit(name, kind, unit)

  def checkHelp(help: String): Either[String, Unit] =
    if help.trim.isEmpty
    then Left("help must not be empty")
    else Right(())

  def checkLabelName(name: String): Either[String, Unit] =
    if !LabelNamePattern.matches(name) then Left("must be letters, digits and underscores, not starting with a digit")
    else if name.startsWith("__") then Left("must not start with __, which Prometheus reserves for itself")
    else if ReservedLabels(name) then Left("is written by the exposition format itself")
    else Right(())

  def snakeCase(name: String): String =
    val out = StringBuilder()

    name.indices.foreach { index =>
      val char = name(index)

      if char.isUpper
      then
        val previous = Option.when(index > 0)(name(index - 1))
        val next     = Option.when(index + 1 < name.length)(name(index + 1))
        val boundary = previous.exists(p => p.isLower || p.isDigit) || (previous.exists(_.isUpper) && next.exists(_.isLower))

        if boundary && !out.lastOption.contains('_') then out += '_'
        out += char.toLower
      else out += char
    }

    out.result()
  end snakeCase

  private def totalSuffix(kind: MetricKind): String =
    kind match
      case MetricKind.Counter => "must not end with _total: every counter is exposed with _total added"
      case _                  => "must not end with _total, which marks a counter"

  private def checkUnit(name: String, kind: MetricKind, unit: Option[MetricUnit]): Either[String, Unit] =
    unit match
      case Some(expected) =>
        if name.endsWith(s"_${expected.value}")
        then Right(())
        else Left(s"must end with _${expected.value}")
      case None           =>
        MetricUnit.values.find(candidate => name.endsWith(s"_${candidate.value}")) match
          case Some(candidate) if kind == MetricKind.Info => Left(s"must not end with _${candidate.value}: an info metric carries no unit")
          case Some(candidate)                            => Left(s"must not end with _${candidate.value}: use ${constructorOf(kind)}.${candidate.value}, which records the unit")
          case None                                       => Right(())

  private def constructorOf(kind: MetricKind): String =
    kind match
      case MetricKind.Counter   => "Counter"
      case MetricKind.Gauge     => "Gauge"
      case MetricKind.Histogram => "Histogram"
      case MetricKind.Info      => "Info"
