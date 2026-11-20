package metrics4s

import scala.compiletime.testing.typeCheckErrors

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

final class LabelsSpec extends AnyWordSpec, Matchers:
  private val outcomeValue = summon[LabelValue[Outcome]]

  "a derived label value" should {
    "render each case in snake case and index it by its ordinal" in {
      val errors = summon[LabelValue[HttpError]]

      (
        errors.domain.map(_.toList),
        HttpError.values.toList.map(errors.render),
        HttpError.values.toList.map(errors.index),
        outcomeValue.domain.map(_.toList),
      ) shouldBe (
        Some(List("not_found", "http_version_not_supported", "timeout2_late")),
        List("not_found", "http_version_not_supported", "timeout2_late"),
        List(0, 1, 2),
        Some(List("ok", "skipped", "error")),
      )
    }

    "not compile for an enum with a case that takes parameters" in {
      typeCheckErrors("""
        enum Shape derives LabelValue:
          case Circle
          case Square(side: Int)
      """).map(_.message) shouldBe List("derives LabelValue needs an enum or a sealed trait whose cases take no parameters")
    }
  }

  "an enumerated label value given by hand" should {
    "render what it is told and refuse a value outside the domain" in {
      enum Level(val value: String):
        case Info  extends Level("INFO")
        case Error extends Level("ERROR")

      val levels = LabelValue.enumerated(List(Level.Info))(_.value)

      (levels.render(Level.Info), levels.index(Level.Info), intercept[NoSuchElementException](levels.index(Level.Error)).getMessage) shouldBe (
        "INFO",
        0,
        "Error is not one of the values this label was given",
      )
    }

    "index the cases of an enum by their ordinal and anything else by its place in the list, refusing what the list leaves out either way" in {
      enum Level(val value: String):
        case Debug extends Level("DEBUG")
        case Info  extends Level("INFO")
        case Error extends Level("ERROR")

      enum Other:
        case First, Second

      val all     = LabelValue.enumerated(Level.values)(_.value)
      val leading = LabelValue.enumerated(List(Level.Debug, Level.Info))(_.value)
      val picked  = LabelValue.enumerated(List(Level.Error, Level.Debug))(_.value)
      val mixed   = LabelValue.enumerated[Any](List[Any](Level.Debug, Other.Second))(_.toString)
      val named   = LabelValue.enumerated(List("b", "a", "c"))(identity)

      (
        Level.values.toList.map(all.index),
        intercept[NoSuchElementException](leading.index(Level.Error)).getMessage,
        (picked.index(Level.Error), picked.index(Level.Debug), intercept[NoSuchElementException](picked.index(Level.Info)).getMessage),
        (mixed.index(Level.Debug), mixed.index(Other.Second), intercept[NoSuchElementException](mixed.index(Other.First)).getMessage),
        (named.index("a"), named.index("c"), intercept[NoSuchElementException](named.index("d")).getMessage),
      ) shouldBe (
        List(0, 1, 2),
        "Error is not one of the values this label was given",
        (0, 1, "Info is not one of the values this label was given"),
        (0, 1, "First is not one of the values this label was given"),
        (1, 2, "d is not one of the values this label was given"),
      )
    }
  }

  "derived labels" should {
    "name the labels after the fields in snake case and enumerate every combination" in {
      final case class ByKind(errorKind: HttpError, outcome: Outcome) derives Labels
      val labels = summon[Labels[ByKind]]

      val all     = for kind <- HttpError.values.toList; outcome <- Outcome.values.toList yield ByKind(kind, outcome)
      val indexes = all.map(labels.index)

      (
        labels.names.toList,
        labels.size,
        labels.render(ByKind(HttpError.NotFound, Outcome.Error)).toList,
        indexes,
        indexes.map(labels.renderAt(_).toList) == all.map(labels.render(_).toList),
      ) shouldBe (
        List("error_kind", "outcome"),
        Some(9L),
        List("not_found", "error"),
        (0 until 9).toList,
        true,
      )
    }

    "work for named tuples" in {
      val labels = summon[Labels[(outcome: Outcome, source: Source)]]

      (labels.names.toList, labels.size, labels.render((outcome = Outcome.Skipped, source = Source.Rabbit)).toList) shouldBe (
        List("outcome", "source"),
        Some(6L),
        List("skipped", "rabbit"),
      )
    }

    "have no size when a field is bounded" in {
      val labels = summon[Labels[ByProvider]]

      (labels.size, labels.domains.toList.map(_.map(_.toList)), labels.render(ByProvider(Provider("cns"), Outcome.Ok)).toList) shouldBe (
        None,
        List(None, Some(List("ok", "skipped", "error"))),
        List("cns", "ok"),
      )
    }

    "not compile for a field without a label value" in {
      typeCheckErrors("""
        final case class ByPath(path: String) derives Labels
      """).nonEmpty shouldBe true
    }
  }

  "the names of derived labels" should {
    "not compile when a field is named as the exposition format names its own labels, nor when two fields come to the same name" in {
      (
        typeCheckErrors("""{ final case class ByBucket(le: Outcome) derives Labels }""").map(_.message),
        typeCheckErrors("""summon[Labels[(quantile: Outcome)]]""").map(_.message),
        typeCheckErrors("""summon[Labels[(__name: Outcome)]]""").map(_.message),
        typeCheckErrors("""summon[Labels[(sourceQueue: Outcome, source_queue: Source)]]""").map(_.message),
        summon[Labels[(sourceQueue: Outcome, source: Source)]].names.toList,
      ) shouldBe (
        List("Invalid label name \"le\": it is written by the exposition format itself"),
        List("Invalid label name \"quantile\": it is written by the exposition format itself"),
        List("Invalid label name \"__name\": it must not start with __, which Prometheus reserves for itself"),
        List("Invalid label name \"source_queue\": two fields are both named so in snake case"),
        List("source_queue", "source"),
      )
    }
  }

  "a single label" should {
    "take its name as written and refuse a reserved one" in {
      val labels = summon[Labels[Outcome]]

      (labels.names.toList, labels.render(Outcome.Skipped).toList, labels.index(Outcome.Error), labels.renderAt(1).toList) shouldBe (
        List("outcome"),
        List("skipped"),
        2,
        List("skipped"),
      )
      typeCheckErrors("""Labels.single[Outcome]("le")""").map(_.message) shouldBe List(
        "Invalid label name \"le\": it is written by the exposition format itself"
      )
    }
  }

  "the label of a boolean" should {
    "be false or true" in {
      val flag = summon[LabelValue[Boolean]]

      (flag.domain.map(_.toList), flag.render(true), flag.index(true)) shouldBe (Some(List("false", "true")), "true", 1)
    }
  }

  "no labels" should {
    "be one series with no label values" in {
      val labels = summon[Labels[NoLabels]]

      (labels.names.toList, labels.size, labels.index(NoLabels), labels.renderAt(0).toList) shouldBe (Nil, Some(1L), 0, Nil)
    }
  }
