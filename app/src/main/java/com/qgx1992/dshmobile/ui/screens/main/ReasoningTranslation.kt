package com.qgx1992.dshmobile.ui.screens.main

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.qgx1992.dshmobile.R
import com.qgx1992.dshmobile.core.wire.*
import com.qgx1992.dshmobile.ui.media.LocalAttachmentScope
import com.qgx1992.dshmobile.ui.rememberSessionStore
import com.qgx1992.dshmobile.ui.theme.DsShapes
import com.qgx1992.dshmobile.ui.theme.DsTheme
import com.qgx1992.dshmobile.ui.theme.DsType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * One reasoning block's translation, shared by the panel header (which carries the control) and the
 * text itself. The source stays visible until an explicit request completes, and a host without a
 * translation provider simply reports [available] = false, which renders no control at all.
 */
internal data class ReasoningTranslation(
    val available: Boolean = false,
    val busy: Boolean = false,
    val showTranslation: Boolean = false,
    val failed: Boolean = false,
    val provider: String = "",
    val translated: String? = null,
    val onToggle: () -> Unit = {},
) {
    fun display(original: String): String = if (showTranslation) translated ?: original else original
}

@Composable
internal fun rememberReasoningTranslation(text: String, streaming: Boolean): ReasoningTranslation {
    val store = rememberSessionStore()
    val owner = LocalAttachmentScope.current
    val api = remember(owner) { store.apiForHost(owner?.first) }
    val scope = rememberCoroutineScope()
    val language = LocalConfiguration.current.locales[0].toLanguageTag()
    var limits by remember(owner) { mutableStateOf<TranslationLimits?>(null) }
    var translated by remember(owner, text) { mutableStateOf<String?>(null) }
    var showTranslation by remember(owner, text) { mutableStateOf(false) }
    var busy by remember(owner, text) { mutableStateOf(false) }
    var failed by remember(owner, text) { mutableStateOf(false) }
    LaunchedEffect(api) {
        try { limits = (api?.translationLimits() as? RpcResult.Ok)?.value }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { limits = null }
    }
    val metadata = limits
    val available = !streaming && api != null && owner != null && metadata != null &&
        metadata.maxTextChars >= 2 && metadata.preferences.provider in metadata.availableProviders && text.isNotBlank()
    return ReasoningTranslation(
        available = available,
        busy = busy,
        showTranslation = showTranslation,
        failed = failed,
        provider = metadata?.preferences?.provider.orEmpty(),
        translated = translated,
        onToggle = {
            if (translated != null) showTranslation = !showTranslation
            else if (api != null && owner != null && metadata != null) scope.launch {
                busy = true
                failed = false
                try {
                    val target = metadata.preferences.targetLanguage.takeUnless { it == "auto" } ?: language
                    val parts = mutableListOf<String>()
                    for (fragment in translationFragments(text, metadata.maxTextChars)) {
                        parts += api.translateReasoning(owner.second, fragment, target, metadata.preferences.provider).featureValue()
                    }
                    translated = parts.joinToString("")
                    showTranslation = true
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failed = true
                } finally {
                    busy = false
                }
            }
        },
    )
}

/** The control lives in the thinking panel's header, so a long thought never hides it. */
@Composable
internal fun ReasoningTranslationAction(translation: ReasoningTranslation, modifier: Modifier = Modifier) {
    if (!translation.available) return
    val label = when {
        translation.busy -> stringResource(R.string.common_loading)
        translation.showTranslation -> stringResource(R.string.alpha2_original)
        else -> stringResource(R.string.alpha2_translate, translation.provider)
    }
    Text(
        text = label,
        style = DsType.caption11,
        color = DsTheme.colors.labelSecondary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .widthIn(max = 168.dp)
            .clip(DsShapes.pill)
            .clickable(enabled = !translation.busy, role = Role.Button, onClick = translation.onToggle)
            .padding(horizontal = 6.dp, vertical = 3.dp),
    )
}

/** Selectable text rather than full-size reply markdown: this is reasoning, not the answer. */
@Composable
internal fun ReasoningContent(translation: ReasoningTranslation, text: String) {
    Column {
        if (translation.failed) {
            Text(stringResource(R.string.common_error), style = DsType.caption11, color = DsTheme.colors.error,
                modifier = Modifier.padding(bottom = 4.dp))
        }
        SelectionContainer {
            Text(translation.display(text).trimEnd(), style = DsType.small13,
                color = DsTheme.colors.labelTertiary, softWrap = true)
        }
    }
}
