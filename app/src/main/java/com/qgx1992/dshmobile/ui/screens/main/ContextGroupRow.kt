package com.qgx1992.dshmobile.ui.screens.main

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.qgx1992.dshmobile.R
import com.qgx1992.dshmobile.core.session.ChatNode
import com.qgx1992.dshmobile.ui.components.FeatherIcons
import com.qgx1992.dshmobile.ui.theme.DsShapes
import com.qgx1992.dshmobile.ui.theme.DsTheme
import com.qgx1992.dshmobile.ui.theme.DsType

/**
 * A run of injected context behind one row — the ledger's only fold.
 *
 * The transcript lists context inline, one node per row; this row is what the ledger shows when it
 * has already decided a turn's context is background rather than a step of its own.
 */
@Composable
internal fun ContextGroupRow(nodes: List<ChatNode>, context: ChatNodeContext, initiallyExpanded: Boolean = false) {
    var expanded by remember(nodes.first().seq) { mutableStateOf(initiallyExpanded) }
    val colors = DsTheme.colors
    Column(Modifier.fillMaxWidth().clip(DsShapes.block).background(colors.bgLayer1)
        .border(1.dp, colors.borderL2, DsShapes.block)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 36.dp).clickable(role = Role.Button) { expanded = !expanded }
            .padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(FeatherIcons.Layers, null, Modifier.size(14.dp), tint = colors.labelCaption)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.chat_context_title) + " · " + nodes.size, style = DsType.small13, color = colors.labelTertiary)
            Spacer(Modifier.weight(1f))
            Icon(FeatherIcons.ChevronRight, null, Modifier.size(14.dp).graphicsLayer { rotationZ = if (expanded) 90f else 0f }, tint = colors.labelCaption)
        }
        if (expanded) Column(Modifier.padding(8.dp)) { nodes.forEach { key(it.seq) { ChatNodeItem(it, context) } } }
    }
}
