package metrics4s.internal

import metrics4s.{Labels, Series}

private[metrics4s] sealed trait Cells[C, L]:
  def series: Series[C]
  def labels: Labels[L]
  def apply(labels: L): C
  def precreated: Option[Int]

private[metrics4s] object Cells:
  final class Precreated[C, L](val series: Series[C], val labels: Labels[L], cells: Array[Any]) extends Cells[C, L]:
    def apply(values: L): C     = cells(labels.index(values)).asInstanceOf[C]
    def at(index: Int): C       = cells(index).asInstanceOf[C]
    def precreated: Option[Int] = Some(cells.length)

  final class Lookup[C, L](val series: Series[C], val labels: Labels[L]) extends Cells[C, L]:
    def apply(values: L): C     = series.cell(labels.render(values))
    def precreated: Option[Int] = None

  def precreate[C, L](series: Series[C], labels: Labels[L], size: Int): Precreated[C, L] =
    Precreated(series, labels, Array.tabulate[Any](size)(index => series.cell(labels.renderAt(index))))
