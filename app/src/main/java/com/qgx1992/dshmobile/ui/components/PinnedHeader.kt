package com.qgx1992.dshmobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.qgx1992.dshmobile.ui.theme.DsShapes
import com.qgx1992.dshmobile.ui.theme.DsTheme
import com.qgx1992.dshmobile.ui.theme.DsType

/**
 * Keeps a scrolling panel's header in reach while its content is on screen.
 *
 * A thought panel opened in the transcript is capped and scrolls inside itself, so its header stays
 * put while that text moves — but the transcript scrolls too, and once the whole panel has gone up
 * past the top edge the header goes with it. Collapsing it then means scrolling back to find it,
 * which is exactly what a reader working their way down a long thought does not want to do.
 *
 * So the scrolling surface draws the header a second time at its own top edge, for as long as the
 * panel it belongs to is under that edge, and taps on the copy run the panel's own toggle. Nothing
 * is remembered: the panel reports its bounds on every placement, and the copy appears and leaves
 * with them.
 *
 * Only one panel can straddle the top edge at a time — panels in a list are stacked, not
 * overlapping — so the state holds one header rather than a map of them.
 */
internal class PinnedHeaderState {
    private var viewportTop = Float.NaN

    /** The panel whose header should be drawn at the top edge, or null. */
    var pinned: PinnedHeader? by mutableStateOf(null)
        private set

    /** Where the scrolling surface's own top edge is, in window coordinates. */
    fun placeViewport(top: Float) {
        viewportTop = top
    }

    /**
     * Called by a panel as it is placed. [top] and [bottom] are the panel's bounds in window
     * coordinates with the header included, so a panel whose header is off screen still reaches
     * here with something left to scroll back to.
     */
    fun report(owner: Any, top: Float, bottom: Float, title: String, onCollapse: () -> Unit) {
        if (!pinsHeader(viewportTop, top, bottom)) {
            clear(owner)
            return
        }
        val current = pinned
        if (current != null && current.owner === owner) {
            // The same panel is still under the edge. Refresh what the copy says and does without
            // writing observable state: this runs on every placement of every scroll frame.
            current.title = title
            current.onCollapse = onCollapse
            return
        }
        pinned = PinnedHeader(owner, title, onCollapse)
    }

    /** Forgets the copy if it belongs to [owner] — the panel collapsed, left, or scrolled away. */
    fun clear(owner: Any) {
        if (pinned?.owner === owner) pinned = null
    }
}

/** What the scrolling surface draws in place of a panel's own header. */
internal class PinnedHeader(
    val owner: Any,
    var title: String,
    var onCollapse: () -> Unit,
)

/**
 * True while the top edge of the viewport is inside the panel: its header is out of sight, and some
 * of the panel is still there to scroll back into. False until the viewport has been measured.
 */
internal fun pinsHeader(viewportTop: Float, top: Float, bottom: Float): Boolean =
    !viewportTop.isNaN() && top < viewportTop && bottom > viewportTop

/** Supplied by the scrolling surface; null anywhere else, which switches the reporting off. */
internal val LocalPinnedHeader = staticCompositionLocalOf<PinnedHeaderState?> { null }

/** The copy of a panel's header that a scrolling surface draws over its own top edge. */
@Composable
internal fun PinnedHeaderRow(
    title: String,
    onCollapse: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = DsTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(DsShapes.block)
            // Opaque: the panel's own text scrolls under this copy, not behind a scrim.
            .background(colors.bgLayer1)
            .border(1.dp, colors.borderL2, DsShapes.block)
            .clickable(role = Role.Button, onClick = onCollapse)
            .heightIn(min = 36.dp)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(FeatherIcons.Layers, null, Modifier.size(14.dp), tint = colors.labelCaption)
        Text(title, style = DsType.small13, color = colors.labelTertiary, modifier = Modifier.weight(1f))
        // Pointing down, as it does in the expanded panel: this copy is what folds the panel up.
        Icon(
            FeatherIcons.ChevronRight,
            null,
            Modifier.size(14.dp).graphicsLayer { rotationZ = 90f },
            tint = colors.labelCaption,
        )
    }
}
