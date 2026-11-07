package metrics4s.grafana

import scala.quoted.*

import metrics4s.internal.Naming

object LabelSelector:
  inline def name[L](inline selector: L => Any): String = ${ nameImpl('selector) }

  def nameImpl[L](selector: Expr[L => Any])(using Quotes): Expr[String] =
    import quotes.reflect.*

    def elements(tuple: TypeRepr): List[TypeRepr] =
      tuple.dealias match
        case AppliedType(cons, List(head, tail)) if cons.typeSymbol.name == "*:" => head :: elements(tail)
        case AppliedType(_, arguments)                                           => arguments
        case _                                                                   => Nil

    def namesOf(named: TypeRepr): List[String] =
      named.dealias.widen match
        case AppliedType(_, List(names, _)) => elements(names).collect { case ConstantType(StringConstant(name)) => name }
        case _                              => Nil

    def namedField(param: ValDef, body: Term): Option[String] =
      body match
        case Inlined(Some(Apply(Apply(TypeApply(Select(_, "apply"), _), List(Ident(_))), List(Literal(IntConstant(index))))), _, _) =>
          namesOf(param.tpt.tpe).lift(index)
        case _                                                                                                                      => None

    def fieldOf(term: Term): Option[String] =
      term match
        case Inlined(_, _, inner)               => fieldOf(inner)
        case Block(Nil, inner)                  => fieldOf(inner)
        case Typed(inner, _)                    => fieldOf(inner)
        case Lambda(_, Select(Ident(_), field)) => Some(field)
        case Lambda(List(param), body)          => namedField(param, body)
        case _                                  => None

    fieldOf(selector.asTerm) match
      case Some(field) => Expr(Naming.snakeCase(field))
      case None        => report.errorAndAbort("Select one label field of the metric, as in _.outcome", selector)
