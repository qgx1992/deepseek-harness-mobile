package com.qgx1992.dshmobile.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.qgx1992.dshmobile.ui.theme.DsTheme

/**
 * One entry on a vertical rail: a hairline down the left edge, a dot where the entry starts, and the
 * entry's own content beside it.
 *
 * This is how a turn's work reads in the transcript — the rail is the turn, the dots are the steps,
 * and the answer below it comes off the same rail. It replaces a card per step: a bordered box per
 * tool call, a second box around a run of them, and a third around the whole lot added up to a
 * screen of nested frames around what is really one sequence.
 *
 * The geometry is drawn in [drawBehind], not with layout: a node's height comes from its content,
 * which is a text block of unknown length, so the rail has to be painted after measurement rather
 * than reserved up front. [first] and [last] cut the line at the rail's two ends, which is what
 * makes consecutive nodes read as one rail without any of them knowing their neighbours.
 *
 * [dotCenter] is where the dot sits from the node's top. The default is the middle of a 24dp
 * disclosure row, which is what most nodes are; a node that leads with a taller header overrides it.
 *
 * @param first the top node: the rail starts at its dot instead of above it.
 * @param last the bottom node: the rail stops at its dot.
 * @param dotColor fill of the dot; null for no dot.
 */
@Composable
internal fun TimelineNode(
    modifier: Modifier = Modifier,
    first: Boolean = false,
    last: Boolean = false,
    dotColor: Color? = null,
    railWidth: Dp = 20.dp,
    dotCenter: Dp = 13.dp,
    dotRadius: Dp = 3.dp,
    content: @Composable () -> Unit,
) {
    val line = DsTheme.colors.borderL2
    Box(
        modifier = modifier
            .fillMaxWidth()
            .drawBehind {
                val cx = railWidth.toPx() / 2f
                val cy = dotCenter.toPx()
                val startY = if (first) cy else 0f
                val endY = if (last) cy else size.height
                if (endY - startY > 0.5f) {
                    drawLine(
                        color = line,
                        start = Offset(cx, startY),
                        end = Offset(cx, endY),
                        strokeWidth = 1.dp.toPx(),
                    )
                }
                if (dotColor != null) {
                    drawCircle(color = dotColor, radius = dotRadius.toPx(), center = Offset(cx, cy))
                }
            }
            .padding(start = railWidth),
        contentAlignment = Alignment.TopStart,
    ) {
        content()
    }
}
