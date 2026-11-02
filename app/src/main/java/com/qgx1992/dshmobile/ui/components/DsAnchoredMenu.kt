package com.qgx1992.dshmobile.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.qgx1992.dshmobile.ui.theme.DsShapes
import com.qgx1992.dshmobile.ui.theme.DsSpacing
import com.qgx1992.dshmobile.ui.theme.DsTheme
import com.qgx1992.dshmobile.ui.theme.DsType

/**
 * A short list of choices attached to the control that opens it.
 *
 * The permission chip sits at the bottom of the composer, so a sheet for three presets meant a
 * full-height scrim, a slide-in from the edge and a tap far from the finger that asked for it —
 * for a menu with three rows. Anything that small belongs next to its trigger. Long, searchable
 * pickers (models, presets, history) keep the sheet: they need the height.
 *
 * Placement is handled by [Popup] relative to this composable's parent layout node, so call this
 * from inside the layout that should act as the anchor — the chip's own `Box`, not the screen.
 * The menu opens upward, which is where the space is for a control on the bottom edge, and falls
 * back to below the anchor when the anchor sits high on the screen.
 *
 * The card is flat: a hairline border and no elevation. A popup window is sized to its content, so
 * an elevation shadow is painted inside those bounds and the part that falls outside is cut off —
 * against a transcript that reads as a grey band hugging two or three edges of the card, darkest at
 * the bottom. The cards underneath are separated by a border and no elevation, so this matches them
 * as well as it avoids the artefact.
 */
@Composable
fun DsAnchoredMenu(
    title: String?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    maxHeightFraction: Float = 0.5f,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = DsTheme.colors
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val maxHeight: Dp = remember(configuration.screenHeightDp, maxHeightFraction) {
        (configuration.screenHeightDp.dp * maxHeightFraction).coerceAtLeast(160.dp)
    }
    val gapPx = with(density) { DsSpacing.small.roundToPx() }
    val marginPx = with(density) { DsSpacing.medium.roundToPx() }
    val positionProvider = remember(gapPx, marginPx) { AnchoredMenuPositionProvider(gapPx, marginPx) }

    // A menu that appears on the frame the tap lands reads as part of the tap; anything longer
    // feels like it waited for the network. Entry only — closing a popup is instant everywhere.
    var appeared by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { appeared = true }
    val progress by animateFloatAsState(
        targetValue = if (appeared) 1f else 0f,
        animationSpec = tween(durationMillis = 110),
        label = "anchoredMenuIn",
    )
    val enterModifier = Modifier.graphicsLayer {
        alpha = progress
        val s = 0.96f + 0.04f * progress
        scaleX = s
        scaleY = s
    }

    Popup(
        popupPositionProvider = positionProvider,
        onDismissRequest = onDismiss,
        properties = PopupProperties(
            focusable = true,
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
            clippingEnabled = false,
        ),
    ) {
        Surface(
            modifier = enterModifier
                .widthIn(min = 216.dp, max = 288.dp)
                .then(modifier),
            shape = DsShapes.menu,
            color = colors.bgLayer2,
            contentColor = colors.labelPrimary,
            border = BorderStroke(1.dp, colors.borderL2),
        ) {
            Column(
                Modifier
                    .heightIn(max = maxHeight)
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = DsSpacing.xsmall),
                verticalArrangement = Arrangement.spacedBy(DsSpacing.tiny),
            ) {
                if (title != null) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(
                                horizontal = DsSpacing.medium,
                                vertical = DsSpacing.xsmall,
                            ),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Text(title, style = DsType.caption11, color = colors.labelTertiary)
                    }
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = DsSpacing.medium)
                            .height(1.dp)
                            .background(colors.borderL1),
                    )
                }
                content()
            }
        }
    }
}

/**
 * Places a menu above its anchor, inside the window.
 *
 * Upward first: the composer chip lives on the bottom edge, and a menu that drops below a control
 * there is a menu that cannot be reached. When the anchor is high on the screen the first branch
 * fails on space and the menu opens below instead. Horizontal edges are clamped rather than
 * clipped, so a chip at the window edge still shows the whole card.
 *
 * The arithmetic is pure — an anchor rectangle in, an offset out, no measurement — so
 * `AnchoredMenuPositionProviderTest` holds the flip and clamp cases down from a JVM test.
 */
internal class AnchoredMenuPositionProvider(
    private val gapPx: Int,
    private val marginPx: Int,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val hasAnchor = anchorBounds.width > 0 && anchorBounds.height > 0
        if (!hasAnchor) {
            // Defensive: without an anchor the menu has nowhere to attach. Centre it low in the
            // window — where the composer is — rather than pinning it to the window corner, so the
            // failure looks like a menu and not like a rendering bug.
            val x = ((windowSize.width - popupContentSize.width) / 2)
                .coerceIn(marginPx, (windowSize.width - popupContentSize.width - marginPx).coerceAtLeast(marginPx))
            val y = (windowSize.height - popupContentSize.height - marginPx).coerceAtLeast(marginPx)
            return IntOffset(x, y)
        }

        val maxX = (windowSize.width - popupContentSize.width - marginPx).coerceAtLeast(marginPx)
        val startEdge = if (layoutDirection == LayoutDirection.Ltr) {
            anchorBounds.left
        } else {
            anchorBounds.right - popupContentSize.width
        }
        val x = startEdge.coerceIn(marginPx, maxX)

        val above = anchorBounds.top - gapPx - popupContentSize.height
        val below = anchorBounds.bottom + gapPx
        val maxY = (windowSize.height - popupContentSize.height - marginPx).coerceAtLeast(marginPx)
        val y = when {
            above >= marginPx -> above
            below <= maxY -> below
            // Neither side fits — a menu taller than the space on either side of the anchor. Keep
            // it below and let its own scroll handle the overflow.
            else -> below.coerceIn(marginPx, maxY)
        }
        return IntOffset(x, y)
    }
}
