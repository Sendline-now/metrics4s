package metrics4s.grafana

enum Color(val value: String):
  case Green  extends Color("green")
  case Yellow extends Color("yellow")
  case Orange extends Color("orange")
  case Red    extends Color("red")
  case Blue   extends Color("blue")
  case Purple extends Color("purple")

final class Thresholds private (val base: Color, val steps: List[(Double, Color)])

object Thresholds:
  def apply(base: Color, steps: (Double, Color)*): Thresholds = new Thresholds(base, steps.toList)
