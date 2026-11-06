package metrics4s

import java.util.NoSuchElementException

import scala.annotation.publicInBinary
import scala.compiletime.{constValueTuple, erasedValue, error, summonFrom}
import scala.deriving.Mirror

import metrics4s.internal.Naming

sealed abstract class LabelValue[A]:
  def render(value: A): String
  def domain: Option[IArray[String]]
  def index(value: A): Int

object LabelValue:
  final class Enumerated[A] private[LabelValue] (val rendered: IArray[String], indexOf: A => Int) extends LabelValue[A]:
    def size: Int                      = rendered.length
    def domain: Option[IArray[String]] = Some(rendered)
    def index(value: A): Int           = indexOf(value)
    def render(value: A): String       = rendered(indexOf(value))
    override def toString: String      = rendered.mkString("LabelValue.Enumerated(", ", ", ")")

  final class Bounded[A] private[LabelValue] (renderer: A => String) extends LabelValue[A]:
    def domain: Option[IArray[String]] = None
    def render(value: A): String       = renderer(value)
    def index(value: A): Int           = throw UnsupportedOperationException("a bounded label value has no index")
    override def toString: String      = "LabelValue.Bounded"

  inline def derived[A](using mirror: Mirror.SumOf[A]): Enumerated[A] =
    requireSingletons[mirror.MirroredElemTypes]
    fromCaseNames[A](constValueTuple[mirror.MirroredElemLabels].toList.map(_.toString), mirror.ordinal)

  def enumerated[A](values: Iterable[A])(render: A => String): Enumerated[A] =
    val all       = values.toVector
    val positions = all.zipWithIndex.toMap

    Enumerated(
      IArray.from(all.map(render)),
      value => positions.getOrElse(value, throw NoSuchElementException(s"$value is not one of the values this label was given")),
    )

  def bounded[A](render: A => String): Bounded[A] = Bounded(render)

  given Enumerated[Boolean] = enumerated(List(false, true))(_.toString)

  @publicInBinary private[metrics4s] def fromCaseNames[A](names: List[String], ordinal: A => Int): Enumerated[A] =
    Enumerated(IArray.from(names.map(Naming.snakeCase)), ordinal)

  private inline def requireSingletons[T <: Tuple]: Unit =
    inline erasedValue[T] match
      case _: EmptyTuple     => ()
      case _: (head *: tail) =>
        requireSingleton[head]
        requireSingletons[tail]

  private inline def requireSingleton[C]: Unit =
    summonFrom {
      case _: ValueOf[C] => ()
      case _             => error("derives LabelValue needs an enum or a sealed trait whose cases take no parameters")
    }
