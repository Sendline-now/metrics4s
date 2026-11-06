package metrics4s

import scala.NamedTuple.NamedTuple
import scala.annotation.publicInBinary
import scala.compiletime.{constValueTuple, summonAll}
import scala.deriving.Mirror

import metrics4s.internal.{Checked, Naming}

final class Labels[L] private (val names: IArray[String], fields: IArray[LabelValue[Any]], select: (L, Int) => Any):
  val domains: IArray[Option[IArray[String]]] = fields.map(_.domain)

  val size: Option[Long] =
    domains.foldLeft(Option(1L)) { (product, domain) =>
      for
        acc    <- product
        values <- domain
      yield Labels.multiply(acc, values.length.toLong)
    }

  private val radixes: Array[Int] = Array.tabulate(domains.length)(field => domains(field).fold(0)(_.length))

  def render(labels: L): IArray[String] =
    val out   = new Array[String](fields.length)
    var field = 0
    while field < out.length do
      out(field) = fields(field).render(select(labels, field))
      field += 1
    IArray.unsafeFromArray(out)

  def index(labels: L): Int =
    var acc   = 0
    var field = 0
    while field < radixes.length do
      acc = acc * radixes(field) + fields(field).index(select(labels, field))
      field += 1
    acc

  def renderAt(index: Int): IArray[String] =
    val out       = new Array[String](radixes.length)
    var remainder = index
    var field     = radixes.length - 1
    while field >= 0 do
      val radix = radixes(field)
      out(field) = domains(field).fold("")(_(remainder % radix))
      remainder = remainder / radix
      field -= 1
    IArray.unsafeFromArray(out)

  override def toString: String = names.mkString("Labels(", ", ", ")")

object Labels:
  given none: Labels[NoLabels] = Labels(IArray.empty, IArray.empty, (_, _) => NoLabels)

  inline def single[A](inline name: String)(using value: LabelValue[A]): Labels[A] =
    fromFields[A](List(Checked.labelName(name)), List(value.asInstanceOf[LabelValue[Any]]), (labels, _) => labels)

  inline def derived[L](using mirror: Mirror.ProductOf[L]): Labels[L] =
    fromProduct[L](
      constValueTuple[mirror.MirroredElemLabels].toList.map(_.toString),
      summonAll[Tuple.Map[mirror.MirroredElemTypes, LabelValue]].toList.map(_.asInstanceOf[LabelValue[Any]]),
    )

  inline given namedTuple: [N <: Tuple, V <: Tuple] => Labels[NamedTuple[N, V]] =
    fromProduct[NamedTuple[N, V]](
      constValueTuple[N].toList.map(_.toString),
      summonAll[Tuple.Map[V, LabelValue]].toList.map(_.asInstanceOf[LabelValue[Any]]),
    )

  @publicInBinary private[metrics4s] def fromFields[L](names: List[String], fields: List[LabelValue[Any]], select: (L, Int) => Any): Labels[L] =
    Labels(IArray.from(names), IArray.from(fields), select)

  @publicInBinary private[metrics4s] def fromProduct[L](fieldNames: List[String], fields: List[LabelValue[Any]]): Labels[L] =
    Labels(IArray.from(fieldNames.map(Naming.snakeCase)), IArray.from(fields), (labels, field) => labels.asInstanceOf[Product].productElement(field))

  private def multiply(left: Long, right: Long): Long =
    if right != 0 && left > Long.MaxValue / right
    then Long.MaxValue
    else left * right
