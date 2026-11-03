package com.qgx1992.dshmobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.qgx1992.dshmobile.R
import com.qgx1992.dshmobile.ui.theme.DsShapes
import com.qgx1992.dshmobile.ui.theme.DsTheme
import com.qgx1992.dshmobile.ui.theme.DsType

/** Reasoning stays quiet and compact, independently of how many lines it contains. */
@Composable
fun ThinkingPanel(text: String, streaming: Boolean, expanded: Boolean, onToggle: () -> Unit,
                  action: (@Composable () -> Unit)? = null,
                  content: (@Composable () -> Unit)? = null) {
    if (text.isBlank() && !streaming) return
    val colors = DsTheme.colors
    val title = stringResource(if (streaming) R.string.chat_thinking else R.string.chat_thought_process)
    // In a transcript this panel reports where it sits, so the list can keep a copy of this header
    // pinned to its own top edge once the real one has scrolled off. Everywhere else the local is
    // null and none of this runs.
    val pin = LocalPinnedHeader.current
    val owner = remember { Any() }
    if (pin != null) {
        // A panel that leaves the composition mid-scroll must take its pinned copy with it.
        DisposableEffect(pin, owner) { onDispose { pin.clear(owner) } }
    }
    Column(Modifier.fillMaxWidth()
        .then(
            if (pin == null) {
                Modifier
            } else {
                Modifier.onGloballyPositioned { coords ->
                    if (expanded) {
                        val bounds = coords.boundsInWindow()
                        pin.report(owner, bounds.top, bounds.bottom, title, onToggle)
                    } else {
                        pin.clear(owner)
                    }
                }
            },
        )
        .clip(DsShapes.block).background(colors.bgLayer1)
        .border(1.dp, colors.borderL2, DsShapes.block)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 36.dp).clickable(role = Role.Button, onClick = onToggle)
            .padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(FeatherIcons.Layers, null, Modifier.size(14.dp), tint = colors.labelCaption)
            Text(title, style = DsType.small13, color = colors.labelTertiary, modifier = Modifier.weight(1f))
            // The translate control rides in the header: a long thought scrolls under it instead of
            // hiding it at the end of the content.
            if (action != null && expanded) action()
            if (streaming) StateDot(StateDotState.Running, size = 6.dp)
            Icon(FeatherIcons.ChevronRight, null,
                Modifier.size(14.dp).graphicsLayer { rotationZ = if (expanded) 90f else 0f }, tint = colors.labelCaption)
        }
        if (expanded) {
            Box(Modifier.fillMaxWidth().heightIn(max = 240.dp).verticalScroll(rememberScrollState())
                .padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 4.dp)) {
                // Selectable text rather than full-size reply markdown: this is reasoning, not the answer.
                if (content != null) content() else androidx.compose.foundation.text.selection.SelectionContainer {
                    Text(text.trimEnd(), style = DsType.small13, color = colors.labelTertiary, softWrap = true)
                }
            }
        }
    }
}
