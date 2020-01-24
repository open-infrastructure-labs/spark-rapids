package ai.rapids.spark

import ai.rapids.cudf.{Table, WindowAggregate, WindowOptions}
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.expressions.{Alias, Attribute, Expression, FrameType, NamedExpression, RangeFrame, RowFrame, SortOrder}
import org.apache.spark.sql.catalyst.plans.physical.Partitioning
import org.apache.spark.sql.execution.window.WindowExec
import org.apache.spark.sql.execution.{SparkPlan, UnaryExecNode}
import org.apache.spark.sql.rapids.{GpuAggregateExpression, GpuAggregateFunction, GpuCount, GpuMax, GpuMin, GpuSum}
import org.apache.spark.sql.types.{CalendarIntervalType, IntegerType}
import org.apache.spark.sql.vectorized.{ColumnVector, ColumnarBatch}
import org.apache.spark.unsafe.types.CalendarInterval

class GpuWindowExecMeta(windowExec: WindowExec,
                        conf: RapidsConf,
                        parent: Option[RapidsMeta[_, _, _]],
                        rule: ConfKeysAndIncompat)
  extends SparkPlanMeta[WindowExec](windowExec, conf, parent, rule) {

  val windowExpressions: Seq[ExprMeta[NamedExpression]] =
    windowExec.windowExpression.map(GpuOverrides.wrapExpr(_, conf, Some(this)))
  val partitionSpec: Seq[ExprMeta[Expression]] =
    windowExec.partitionSpec.map(GpuOverrides.wrapExpr(_, conf, Some(this)))
  val orderSpec: Seq[ExprMeta[SortOrder]] =
    windowExec.orderSpec.map(GpuOverrides.wrapExpr(_, conf, Some(this)))

  override def tagPlanForGpu(): Unit = {

    // Implementation depends on receiving an `Alias` wrapped WindowExpression.
    windowExpressions.map(meta => meta.wrapped)
      .filter(expr => !expr.isInstanceOf[Alias])
      .foreach(_ => willNotWorkOnGpu(because = "Unexpected query plan with Windowing functions; " +
        "cannot convert for GPU execution. " +
        "(Detail: WindowExpression not wrapped in `Alias`.)"))

    // TODO: Detect RANGE window-frames that have `GpuSortOrder` with more than one column.
  }

  override def convertToGpu(): GpuExec = {
    GpuWindowExec(
      windowExpressions.map(_.convertToGpu().asInstanceOf[GpuAlias]),
      partitionSpec.map(_.convertToGpu()),                        // TODO: Verify that this parameter can be removed.
      orderSpec.map(_.convertToGpu().asInstanceOf[GpuSortOrder]), // TODO: Verify that this parameter can be removed.
      childPlans.head.convertIfNeeded()
    )
  }
}

case class GpuWindowExec(windowExpressionAliases: Seq[GpuAlias],
                         partitionSpec: Seq[GpuExpression],
                         orderSpec: Seq[GpuSortOrder],
                         child: SparkPlan
                        ) extends UnaryExecNode with GpuExec {

  override def output: Seq[Attribute] = {
    child.output ++ windowExpressionAliases.map {
      _.toAttribute
    }
  }

  override def outputOrdering: Seq[SortOrder] = child.outputOrdering

  override def outputPartitioning: Partitioning = child.outputPartitioning

  val windowExpressions: Seq[GpuWindowExpression] = windowExpressionAliases.map(_.child.asInstanceOf[GpuWindowExpression])
  val windowFunctions: Seq[GpuAggregateExpression] = windowExpressions.map(_.windowFunction.asInstanceOf[GpuAggregateExpression])     // One per window expression.
  val unboundWindowFunctionExpressions: Seq[GpuExpression] = windowFunctions.map(_.aggregateFunction.inputProjection.head)            // One per window expression.
  val windowFrameTypes: Seq[FrameType] = windowExpressions.map(_.windowSpec.frameSpecification.asInstanceOf[GpuSpecifiedWindowFrame].frameType)
  val boundWindowAggregations: Seq[GpuExpression] = GpuBindReferences.bindReferences(unboundWindowFunctionExpressions, child.output)  // One per window expression.
  val boundWindowPartKeys : Seq[Seq[GpuExpression]] = windowExpressions.map(_.windowSpec.partitionSpec).map(GpuBindReferences.bindReferences(_, child.output)) // 1 set of part-keys per window-expression.
  val boundWindowSortKeys : Seq[Seq[GpuExpression]] = windowExpressions.map(_.windowSpec.orderSpec).map(_.map(_.child)).map(GpuBindReferences.bindReferences(_, child.output))

  val deleteme_breakpoint_placeholder_sorry_not_sorry : Int = 0

  override protected def doExecute(): RDD[InternalRow]
  = throw new IllegalStateException(s"Row-based execution should not happen, in $this.")

  override protected def doExecuteColumnar(): RDD[ColumnarBatch] = {

    val input = child.executeColumnar()
    input.map {
      cb => {

        var originalCols: Array[GpuColumnVector] = null
        var aggCols     : Array[GpuColumnVector] = null

        try {
          originalCols = GpuColumnVector.extractColumns(cb)
          originalCols.foreach(_.incRefCount())
          aggCols      = windowExpressions.indices.toArray.map(evaluateWindowExpression(cb, _))

          new ColumnarBatch( originalCols ++ aggCols, cb.numRows() )
        }
        finally {
          cb.close()
        }

      }
    }
  }

  private def evaluateWindowExpression(cb : ColumnarBatch, i : Int) : GpuColumnVector = {
    if (windowFrameTypes(i).equals(RangeFrame)) {
      evaluateRangeBasedWindowExpression(cb, i)
    }
    else {
      evaluateRowBasedWindowExpression(cb, i)
    }
  }

  private def evaluateRowBasedWindowExpression(cb : ColumnarBatch, i : Int) : GpuColumnVector = {

    var groupingColsCB : ColumnarBatch = null
    var aggregationColsCB : ColumnarBatch = null
    var groupingCols : Array[GpuColumnVector] = null
    var aggregationCols : Array[GpuColumnVector] = null
    var inputTable : Table = null
    var aggResultTable : Table = null

    try {
      // Project required column batches.
      groupingColsCB    = GpuProjectExec.project(cb, boundWindowPartKeys(i))
      aggregationColsCB = GpuProjectExec.project(cb, Seq(boundWindowAggregations(i)))
      // Extract required columns columns.
      groupingCols = GpuColumnVector.extractColumns(groupingColsCB)
      aggregationCols = GpuColumnVector.extractColumns(aggregationColsCB)

      inputTable = new Table( ( groupingCols ++ aggregationCols ).map(_.getBase) : _* )

      aggResultTable = inputTable.groupBy(0 until groupingColsCB.numCols(): _*)
        .aggregateWindows(
          GpuWindowExec.getRowBasedWindowFrame(
            groupingColsCB.numCols(),
            windowFunctions(i).aggregateFunction,
            windowExpressions(i).windowSpec.frameSpecification.asInstanceOf[GpuSpecifiedWindowFrame]
          )
        )

      // Aggregation column is at index `0`
      val aggColumn = aggResultTable.getColumn(0)
      aggColumn.incRefCount()
      GpuColumnVector.from(aggColumn)
    }
    finally {
      if (groupingColsCB != null) groupingColsCB.close()
      if (aggregationColsCB != null) aggregationColsCB.close()
      if (inputTable != null) inputTable.close()
      if (aggResultTable != null) aggResultTable.close()
    }
  }

  private def evaluateRangeBasedWindowExpression(cb : ColumnarBatch, i : Int) : GpuColumnVector = {

    var groupingColsCB : ColumnarBatch = null
    var sortColsCB : ColumnarBatch = null
    var aggregationColsCB : ColumnarBatch = null
    var groupingCols : Array[GpuColumnVector] = null
    var sortCols : Array[GpuColumnVector] = null
    var aggregationCols : Array[GpuColumnVector] = null
    var inputTable : Table = null
    var aggResultTable : Table = null

    try {
      // Project required column batches.
      groupingColsCB    = GpuProjectExec.project(cb, boundWindowPartKeys(i))
      assert(boundWindowSortKeys(i).size == 1, "Expected a single sort column.")
      sortColsCB        = GpuProjectExec.project(cb, boundWindowSortKeys(i))
      aggregationColsCB = GpuProjectExec.project(cb, Seq(boundWindowAggregations(i)))

      // Extract required columns columns.
      groupingCols = GpuColumnVector.extractColumns(groupingColsCB)
      sortCols        = GpuColumnVector.extractColumns(sortColsCB)
      aggregationCols = GpuColumnVector.extractColumns(aggregationColsCB)

      inputTable = new Table( ( groupingCols ++ sortCols ++ aggregationCols ).map(_.getBase) : _* )

      aggResultTable = inputTable.groupBy(0 until groupingColsCB.numCols(): _*)
        .aggregateWindowsOverTimeRanges(
          GpuWindowExec.getRangeBasedWindowFrame(
            groupingColsCB.numCols() + sortColsCB.numCols(),
            groupingColsCB.numCols(),
            windowFunctions(i).aggregateFunction,
            windowExpressions(i).windowSpec.frameSpecification.asInstanceOf[GpuSpecifiedWindowFrame]
          )
        )

      // Aggregation column is at index `0`
      val aggColumn = aggResultTable.getColumn(0)
      aggColumn.incRefCount()
      GpuColumnVector.from(aggColumn)
    }
    finally {
      if (groupingColsCB != null) groupingColsCB.close()
      if (sortColsCB != null) sortColsCB.close()
      if (aggregationColsCB != null) aggregationColsCB.close()
      if (inputTable != null) inputTable.close()
      if (aggResultTable != null) aggResultTable.close()
    }
  }

}

object GpuWindowExec {

  def getRowBasedWindowFrame(columnIndex : Int,
                             aggFunction : GpuAggregateFunction,
                             windowSpec : GpuSpecifiedWindowFrame)
  : WindowAggregate = {

    // FIXME: Assumes `lower` is negative.
    var lower = getBoundaryValue(windowSpec.lower)
    assert(lower <= 0, "Lower-bounds ahead of current row is not supported.")
    lower -= 1 // CUDF counts current row against the lower-bound of the window.

    val upper = getBoundaryValue(windowSpec.upper)
    assert(upper >= 0, "Upper-bounds behind of current row is not supported.")

    val windowOption = WindowOptions.builder().minPeriods(1)
      .window(-lower,upper).build()

    aggFunction match {
      case GpuCount(_) => WindowAggregate.count(columnIndex, windowOption)
      case GpuSum(_) => WindowAggregate.sum(columnIndex, windowOption)
      case GpuMin(_) => WindowAggregate.min(columnIndex, windowOption)
      case GpuMax(_)=> WindowAggregate.max(columnIndex, windowOption)
      case _ => throw new IllegalStateException("Unsupported aggregation!")
    }
  }

  def getRangeBasedWindowFrame(aggColumnIndex : Int,
                               timeColumnIndex : Int,
                               aggFunction : GpuAggregateFunction,
                               windowSpec : GpuSpecifiedWindowFrame)
  : WindowAggregate = {

    // FIXME: Assumes `lower` is negative.
    val lower = getBoundaryValue(windowSpec.lower)
    assert(lower <= 0, "Lower-bounds ahead of current row is not supported.")

    val upper = getBoundaryValue(windowSpec.upper)
    assert(upper >= 0, "Upper-bounds behind of current row is not supported.")

    val windowOption = WindowOptions.builder().minPeriods(1)
      .window(-lower,upper).timestampColumnIndex(timeColumnIndex).build()

    aggFunction match {
      case GpuCount(_) => WindowAggregate.count(aggColumnIndex, windowOption)
      case GpuSum(_) => WindowAggregate.sum(aggColumnIndex, windowOption)
      case GpuMin(_) => WindowAggregate.min(aggColumnIndex, windowOption)
      case GpuMax(_)=> WindowAggregate.max(aggColumnIndex, windowOption)
      case _ => throw new IllegalStateException("Unsupported aggregation!")
    }
  }

  def getBoundaryValue(boundary : GpuExpression) : Int = boundary match {
    case literal: GpuLiteral if literal.dataType.equals(IntegerType) => literal.value.asInstanceOf[Int]
    case literal: GpuLiteral if literal.dataType.equals(CalendarIntervalType) => literal.value.asInstanceOf[CalendarInterval].days
    case special: GpuSpecialFrameBoundary => special.value
    case anythingElse => throw new UnsupportedOperationException(s"Unsupported window frame expression $anythingElse")
  }
}
