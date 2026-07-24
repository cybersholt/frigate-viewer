package net.triton.frigateviewer.core.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp

/**
 * Line-with-filled-area chart over a series of samples.
 *
 * Extracted from the live-tile stats overlay so the Settings screens can draw the same shape
 * without inheriting that overlay's on-video chrome (hardcoded white ink, fixed 26 dp height).
 * Callers supply colour and height, so this works on a themed surface and over video alike.
 *
 * Scaled to the window's own peak rather than an absolute maximum: a metric sitting at a steady
 * level still shows real variation instead of a flat line pinned near zero. The Y axis is
 * therefore *relative* — the absolute value belongs in an accompanying label.
 */
@Composable
fun LineAreaChart(
    values: List<Double>,
    lineColor: Color,
    height: Dp,
    modifier: Modifier = Modifier,
    showAverage: Boolean = true,
    maxValue: Double? = null,
) {
    Canvas(modifier.height(height)) {
        drawLineArea(values, lineColor, showAverage, maxValue = maxValue)
    }
}

/**
 * The drawing itself, exposed separately so a caller that already owns a [Canvas] — the stats
 * overlay stacks several series into one — doesn't have to nest another.
 */
fun DrawScope.drawLineArea(
    values: List<Double>,
    lineColor: Color,
    showAverage: Boolean = true,
    strokeWidth: Float = 2f,
    maxValue: Double? = null,
) {
    // A metric with a known ceiling (a 0-100% reading) must scale against that ceiling, not the
    // window's own peak: peak-relative, a CPU steady at 44% pins its line to the top of the box and
    // reads as a solid block rather than a chart. Series with no natural maximum (inference time,
    // bandwidth) keep peak-relative scaling so their variation stays visible.
    val peak = maxValue ?: values.maxOrNull() ?: 0.0
    if (values.size < 2 || peak <= 0.0) return

    val stepX = size.width / (values.size - 1).toFloat()

    fun yFor(value: Double): Float = size.height - (value / peak).toFloat() * size.height

    // Two paths, not one: the fill has to include the baseline corners, the stroke must not —
    // stroking a closed path would draw a line along the bottom edge and back up both sides.
    val line = Path()
    val area = Path()
    values.forEachIndexed { i, v ->
        val x = i * stepX
        val y = yFor(v)
        if (i == 0) {
            line.moveTo(x, y)
            area.moveTo(x, size.height)
            area.lineTo(x, y)
        } else {
            line.lineTo(x, y)
            area.lineTo(x, y)
        }
    }
    area.lineTo((values.size - 1) * stepX, size.height)
    area.close()

    drawPath(path = area, color = lineColor.copy(alpha = 0.25f))
    drawPath(path = line, color = lineColor, style = Stroke(width = strokeWidth, cap = StrokeCap.Round))

    // Mean of the window: makes a momentary spike visibly distinguishable from a raised floor.
    val average = values.average()
    if (showAverage && average > 0) {
        val avgY = yFor(average)
        drawLine(
            color = lineColor.copy(alpha = 0.35f),
            start = Offset(0f, avgY),
            end = Offset(size.width, avgY),
            strokeWidth = 1f,
        )
    }
}
