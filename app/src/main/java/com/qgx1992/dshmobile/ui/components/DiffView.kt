package com.qgx1992.dshmobile.ui.components

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.qgx1992.dshmobile.R
import com.qgx1992.dshmobile.core.text.DiffFile
import com.qgx1992.dshmobile.core.text.DiffHunk
import com.qgx1992.dshmobile.core.text.DiffLine
import com.qgx1992.dshmobile.ui.theme.DsSpacing
import com.qgx1992.dshmobile.ui.theme.DsType
import com.qgx1992.dshmobile.ui.theme.LocalDsColors

/**
 * A parsed diff, drawn the one way a phone can read it: unified, never side by side.
 *
 * Each file gets its name and, for a rename, the name it came from; each hunk gets its `@@` header
 * so a reader can place the change in the file; each line gets both gutters, so a removed line
 * shows the old number and an added one the new number. Code runs sideways inside its hunk rather
 * than wrapping, because a wrapped diff line no longer reads as one line of code — and the gutter
 * stays put while it scrolls, so the numbers never leave the screen.
 *
 * Colours come from the theme tokens ([LocalDsColors]): added lines on `successTertiary`, removed
 * ones on `errorTertiary`, with the marker in `success`/`error`. A position of 0 means the patch
 * text did not state it, and the gutter stays blank rather than inventing a number.
 */
@Composable
fun DiffView(
    files: List<DiffFile>,
    modifier: Modifier = Modifier,
    empty: (@Composable () -> Unit)? = null,
) {
    if (files.isEmpty()) {
        empty?.invoke()
        return
    }
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(DsSpacing.large),
    ) {
        files.forEach { file -> FileDiffBlock(file) }
    }
}

@Composable
private fun FileDiffBlock(file: DiffFile) {
    val colors = LocalDsColors.current
    Column(Modifier.fillMaxWidth()) {
        Text(
            file.path,
            style = DsType.small13Strong.copy(fontFamily = DsType.codeFont),
            color = colors.labelPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        val old = file.oldPath
        if (old != null) {
            Text(
                stringResource(R.string.diff_renamed_from, old),
                style = DsType.caption11,
                color = colors.labelTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        when {
            file.binary -> Caption(stringResource(R.string.diff_binary_file))
            file.hunks.isEmpty() -> Caption(stringResource(R.string.diff_empty_file))
            else -> file.hunks.forEach { hunk -> HunkBlock(hunk) }
        }
    }
}

@Composable
private fun Caption(text: String) {
    Text(
        text,
        style = DsType.caption11,
        color = LocalDsColors.current.labelTertiary,
        modifier = Modifier.padding(top = 6.dp),
    )
}

@Composable
private fun HunkBlock(hunk: DiffHunk) {
    val colors = LocalDsColors.current
    // One state per hunk, shared by every line in it, so all lines slide together and the change
    // reads as a block instead of as a dozen rows drifting apart.
    val hscroll = rememberScrollState()
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 22.dp)
                .background(colors.codeBlockBg)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                hunkHeader(hunk),
                style = DsType.caption11.copy(fontFamily = DsType.codeFont),
                color = colors.labelTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.horizontalScroll(hscroll),
            )
        }
        hunk.lines.forEach { line -> DiffLineRow(line, hscroll) }
    }
}

/** `@@ -a,b +c,d @@ heading`, with the range left out when the patch never stated one. */
private fun hunkHeader(hunk: DiffHunk): String = buildString {
    append("@@")
    if (hunk.oldStart > 0 || hunk.newStart > 0) {
        append(" -").append(hunk.oldStart).append(',').append(hunk.oldLines)
        append(" +").append(hunk.newStart).append(',').append(hunk.newLines)
    }
    append(" @@")
    val heading = hunk.heading?.trim().orEmpty()
    if (heading.isNotEmpty()) append(' ').append(heading)
}

private class LineStyle(
    val oldNo: Int,
    val newNo: Int,
    val sign: String,
    val tint: Color,
    val background: Color,
)

@Composable
private fun DiffLineRow(line: DiffLine, hscroll: ScrollState) {
    val colors = LocalDsColors.current
    val style = when (line) {
        is DiffLine.Context -> LineStyle(line.oldNo, line.newNo, " ", colors.labelTertiary, Color.Transparent)
        is DiffLine.Add -> LineStyle(0, line.newNo, "+", colors.success, colors.successTertiary)
        is DiffLine.Remove -> LineStyle(line.oldNo, 0, "-", colors.error, colors.errorTertiary)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 20.dp)
            .background(style.background),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Gutter(style.oldNo)
        Gutter(style.newNo)
        Row(Modifier.weight(1f).horizontalScroll(hscroll)) {
            Text(style.sign, style = DsType.mdCode, color = style.tint, softWrap = false)
            Text(line.text, style = DsType.mdCode, color = colors.labelPrimary, softWrap = false)
        }
    }
}

@Composable
private fun Gutter(number: Int) {
    Text(
        text = if (number > 0) number.toString() else "",
        style = DsType.caption11.copy(fontFamily = DsType.codeFont),
        color = LocalDsColors.current.labelTertiary,
        textAlign = TextAlign.End,
        maxLines = 1,
        softWrap = false,
        modifier = Modifier.width(32.dp).padding(end = 6.dp),
    )
}
