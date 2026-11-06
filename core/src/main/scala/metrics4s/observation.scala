package metrics4s

trait Observer[V, L]:
  def report(labels: L, value: V): Unit

  final def report(value: V)(using labels: NoLabels =:= L): Unit = report(labels(NoLabels), value)

final class Observation[S] private (val definition: ObservableDef[?, ?], private[metrics4s] val collect: (S, ObservedValues, Int) => Unit)

object Observation:
  def apply[S, V, L](definition: ObservableDef[V, L])(report: (S, Observer[V, L]) => Unit): Observation[S] =
    new Observation[S](
      definition,
      (state: S, sink: ObservedValues, index: Int) =>
        report(
          state,
          new Observer[V, L]:
            def report(labels: L, value: V): Unit = sink.report(index, definition.labels.render(labels), definition.recorded.toDouble(value)),
        ),
    )
