package metrics4s.grafana

import scala.quoted.*

import metrics4s.internal.Naming

object LabelSelector:
  inline def name[L](inline selector: L => Any): String = ${ nameImpl('selector) }

  def nameImpl[L](selector: Expr[L => Any])(using Quotes): Expr[String] =
    import quotes.reflect.*

    def fieldOf(term: Term): Option[String] =
      term match
        case Inlined(_, _, inner)               => fieldOf(inner)
        case Block(Nil, inner)                  => fieldOf(inner)
        case Typed(inner, _)                    => fieldOf(inner)
        case Lambda(_, Select(Ident(_), field)) => Some(field)
        case _                                  => None

    fieldOf(selector.asTerm) match
      case Some(field) => Expr(Naming.snakeCase(field))
      case None        => report.errorAndAbort("Select one label field of the metric, as in _.outcome", selector)
