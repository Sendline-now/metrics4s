package metrics4s.grafana

import scala.quoted.*

import metrics4s.internal.Naming

object LabelSelector:
  inline def name[L](inline selector: L => Any): String = ${ nameImpl('selector) }

  inline def matching[L](inline condition: LabelMatchers ?=> L => LabelMatch): LabelMatch = ${ matchingImpl('condition) }

  def nameImpl[L](selector: Expr[L => Any])(using Quotes): Expr[String] =
    import quotes.reflect.*

    lambda(selector.asTerm).flatMap(field(_, _)).flatten match
      case Some(name) => Expr(name)
      case None       => report.errorAndAbort("Select one label field of the metric, as in _.outcome", selector)

  def matchingImpl[L](condition: Expr[LabelMatchers ?=> L => LabelMatch])(using Quotes): Expr[LabelMatch] =
    import quotes.reflect.*

    def refuse: Nothing =
      report.errorAndAbort("Compare one label field of the metric with values of its type, as in _.outcome === Outcome.Ok", condition)

    def strip(term: Term): Term =
      term match
        case Inlined(_, Nil, inner) => strip(inner)
        case Block(Nil, inner)      => strip(inner)
        case Typed(inner, _)        => strip(inner)
        case other                  => other

    val (param, body) = lambda(condition.asTerm).flatMap((_, inner) => lambda(inner)).getOrElse(refuse)

    strip(body) match
      case Apply(Apply(TypeApply(operator: Select, _), List(receiver)), arguments) if operator.symbol.owner == TypeRepr.of[LabelMatchers].typeSymbol =>
        val label     = field(param, receiver).getOrElse(refuse)
        val fieldType = receiver.tpe.widen

        def typed(term: Term): Expr[Any] =
          if term.tpe <:< fieldType
          then term.asExprOf[Any]
          else refuse

        val values = arguments.map {
          case Typed(Repeated(elements, _), _)                                     => Expr.ofSeq(elements.map(typed))
          case Repeated(elements, _)                                               => Expr.ofSeq(elements.map(typed))
          case Typed(spread, tpt) if tpt.tpe.typeSymbol == defn.RepeatedParamClass =>
            if spread.tpe.baseType(TypeRepr.of[Seq[Any]].typeSymbol).typeArgs.forall(_ <:< fieldType)
            then spread.asExprOf[Seq[Any]]
            else refuse
          case single                                                              => '{ Seq(${ typed(single) }) }
        }

        val matched = operator.symbol.name match
          case "===" | "is"    => '{ LabelMatch.Operator.Is }
          case "=!=" | "isNot" => '{ LabelMatch.Operator.IsNot }
          case "in"            => '{ LabelMatch.Operator.In }
          case _               => '{ LabelMatch.Operator.NotIn }

        '{ LabelMatch(${ Expr(label) }, $matched, ${ values.reduce((left, right) => '{ $left ++ $right }) }) }
      case _                                                                                                                                         => refuse
  end matchingImpl

  private def lambda(using Quotes)(term: quotes.reflect.Term): Option[(quotes.reflect.ValDef, quotes.reflect.Term)] =
    import quotes.reflect.*

    term match
      case Inlined(_, _, inner)      => lambda(inner)
      case Block(Nil, inner)         => lambda(inner)
      case Typed(inner, _)           => lambda(inner)
      case Lambda(List(param), body) => Some((param, body))
      case _                         => None

  private def field(using Quotes)(param: quotes.reflect.ValDef, term: quotes.reflect.Term): Option[Option[String]] =
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

    term match
      case Inlined(Some(Apply(Apply(TypeApply(Select(_, "apply"), _), List(Ident(_))), List(Literal(IntConstant(index))))), _, _) =>
        namesOf(param.tpt.tpe).lift(index).map(name => Some(Naming.snakeCase(name)))
      case Inlined(_, _, inner)                                                                                                   => field(param, inner)
      case Block(Nil, inner)                                                                                                      => field(param, inner)
      case Typed(inner, _)                                                                                                        => field(param, inner)
      case Select(Ident(_), name)                                                                                                 => Some(Some(Naming.snakeCase(name)))
      case Ident(name) if name == param.name                                                                                      => Some(None)
      case _                                                                                                                      => None
