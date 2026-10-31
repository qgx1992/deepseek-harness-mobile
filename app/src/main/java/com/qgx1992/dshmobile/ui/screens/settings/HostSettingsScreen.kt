package com.qgx1992.dshmobile.ui.screens.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qgx1992.dshmobile.R
import com.qgx1992.dshmobile.core.wire.DshApiClient
import com.qgx1992.dshmobile.core.wire.RpcError
import com.qgx1992.dshmobile.core.wire.RpcResult
import com.qgx1992.dshmobile.core.wire.classifyCapability
import com.qgx1992.dshmobile.core.wire.dto.SettingsDescribeValue
import com.qgx1992.dshmobile.core.wire.dto.SettingsNamespaceView
import com.qgx1992.dshmobile.core.wire.dto.SettingsPathOpView
import com.qgx1992.dshmobile.data.HarnessFeature
import com.qgx1992.dshmobile.ui.components.DsButton
import com.qgx1992.dshmobile.ui.components.DsButtonSize
import com.qgx1992.dshmobile.ui.components.DsButtonVariant
import com.qgx1992.dshmobile.ui.components.DsCard
import com.qgx1992.dshmobile.ui.components.DsDialog
import com.qgx1992.dshmobile.ui.components.DsPill
import com.qgx1992.dshmobile.ui.components.DsToastHost
import com.qgx1992.dshmobile.ui.components.EmptyHero
import com.qgx1992.dshmobile.ui.components.ToggleRow
import com.qgx1992.dshmobile.ui.components.rememberDsToast
import com.qgx1992.dshmobile.ui.rememberSessionStore
import com.qgx1992.dshmobile.ui.screens.main.FeatureAction
import com.qgx1992.dshmobile.ui.screens.main.FeatureDiagnostics
import com.qgx1992.dshmobile.ui.screens.main.FeaturePage
import com.qgx1992.dshmobile.ui.screens.main.FeatureProgress
import com.qgx1992.dshmobile.ui.screens.main.dialogTextFieldColors
import com.qgx1992.dshmobile.ui.screens.main.technicalDisplay
import com.qgx1992.dshmobile.ui.theme.DsShapes
import com.qgx1992.dshmobile.ui.theme.DsSpacing
import com.qgx1992.dshmobile.ui.theme.DsTheme
import com.qgx1992.dshmobile.ui.theme.DsType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement

/**
 * The harness's own runtime settings, edited over the wire.
 *
 * This is the desktop's configuration plane, not the phone's preferences: the form is generated
 * from the `settings/describe` schema, so a host that grows a new namespace or a new field shows up
 * here without an app release. Everything a user sees is derived from that document, which means
 * the screen has to survive shapes it was not written for — an unknown node kind is drawn as one
 * read-only line rather than dropped or thrown over.
 *
 * Reads go through [HarnessFeature.HOST_SETTINGS] so a host that mounts no settings controller is
 * recorded as missing the capability instead of as a broken connection. Writes deliberately do not:
 * a `settings/conflict` or `settings/rejected` answer is about this one request, and folding it into
 * the connection state would put an error banner over an otherwise healthy link.
 *
 * Secret values never come back from the host — only the set/unset flag — so the editor shows that
 * flag plus an empty field for a replacement, and writes it through `credentials/set`. The typed
 * value lives in this screen's draft state only: it is never logged, never persisted, and is
 * dropped as soon as the write answers.
 *
 * Two desktop pages are not here yet, because the schema form is the part that has to be correct
 * first:
 * TODO(settings) a model picker fed by the `llm` discovery calls (`llm/listProviders`,
 *  `llm/discoverModels`), so a model-shaped field opens a real list instead of a plain text row;
 * TODO(settings) a credentials page that runs `credentials/describe` over every reference the
 *  document declares, so a secret can be cleared without scrolling to its namespace.
 */
@Composable
internal fun HostSettingsScreen(onClose: () -> Unit) {
    val store = rememberSessionStore()
    val available by store.hostSettingsAvailable.collectAsStateWithLifecycle()
    val connection by store.connectionState.collectAsStateWithLifecycle()
    val host = store.activeHostKey
    val scope = rememberCoroutineScope()
    val toast = rememberDsToast()
    val colors = DsTheme.colors

    var describe by remember(host) { mutableStateOf<SettingsDescribeValue?>(null) }
    var loading by remember(host) { mutableStateOf(false) }
    var error by remember(host) { mutableStateOf<String?>(null) }
    var conflict by remember(host) { mutableStateOf(false) }
    var revision by remember { mutableIntStateOf(0) }
    var draft by remember(host) { mutableStateOf<HostSettingDraft?>(null) }
    var writing by remember(host) { mutableStateOf(false) }
    var writeError by remember(host) { mutableStateOf<String?>(null) }

    val offline = stringResource(R.string.common_offline)
    val saved = stringResource(R.string.host_settings_saved)
    val conflictMessage = stringResource(R.string.host_settings_conflict)
    val invalidNumber = stringResource(R.string.host_settings_invalid_number)
    val refused = stringResource(R.string.host_settings_error)

    // The probe already decided the row's visibility; if the answer arrives after the page opened
    // -- or a later probe clears it -- leave rather than draw a page that cannot load.
    LaunchedEffect(available) { if (available == false) onClose() }
    if (available == false) return

    fun run(block: suspend (DshApiClient) -> Unit) {
        scope.launch {
            loading = true
            error = null
            try {
                block(store.apiForHost(host) ?: error(offline))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: offline
            } finally {
                loading = false
            }
        }
    }

    LaunchedEffect(host, connection.phase, revision) {
        run { api ->
            val value = store.featureCall(HarnessFeature.HOST_SETTINGS, api) { api.settingsDescribe() }.let {
                when (it) {
                    is RpcResult.Ok -> it.value
                    is RpcResult.Err -> throw SettingsRefused(it.error)
                }
            }
            if (store.activeHostKey == host) describe = value
        }
    }

    /** Splice a fresh namespace view in place, so a write does not cost a second full describe. */
    fun applyView(view: SettingsNamespaceView) {
        val current = describe ?: return
        describe = current.copy(namespaces = current.namespaces.map { if (it.ns == view.ns) view else it })
    }

    /**
     * A refusal is about this request, so it is reported where the request started: a revision
     * clash reloads the page and says so at the top, everything else shows the host's own words.
     */
    fun applyFailure(failure: RpcError, report: (String) -> Unit) {
        when (failure.classifyCapability().code) {
            "settings/conflict" -> {
                conflict = true
                revision++
            }
            else -> report(failure.message.ifBlank { refused })
        }
    }

    fun save(ns: String, field: HostSettingField, value: JsonElement, done: () -> Unit = {}) {
        val view = describe?.namespaces?.firstOrNull { it.ns == ns }
        val api = store.apiForHost(host)
        if (view == null || api == null) {
            writeError = offline
            return
        }
        writeError = null
        writing = true
        scope.launch {
            try {
                // The revision travels with the patch: a save that raced another client must not
                // quietly overwrite it, and the host answers `settings/conflict` when it would.
                when (val result = api.settingsMutate(ns, listOf(SettingsPathOpView.Set(field.path, value)), view.revision.toInt())) {
                    is RpcResult.Ok -> {
                        applyView(result.value)
                        conflict = false
                        toast.second(saved)
                        done()
                    }
                    is RpcResult.Err -> applyFailure(result.error) { writeError = it }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                writeError = e.message ?: offline
            } finally {
                writing = false
            }
        }
    }

    fun writeSecret(field: HostSettingField, value: String, clear: Boolean) {
        val ref = field.secretRef ?: return
        val api = store.apiForHost(host)
        if (api == null) {
            writeError = offline
            return
        }
        writeError = null
        writing = true
        scope.launch {
            try {
                val result = if (clear) api.credentialsUnset(ref) else api.credentialsSet(ref, value)
                when (result) {
                    is RpcResult.Ok -> {
                        draft = null
                        conflict = false
                        // The host redacts secrets, so the only honest way to learn the new flag is
                        // to ask again; the typed value is dropped here and never kept anywhere.
                        revision++
                        toast.second(saved)
                    }
                    is RpcResult.Err -> applyFailure(result.error) { writeError = it }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                writeError = e.message ?: offline
            } finally {
                writing = false
            }
        }
    }

    @Composable
    fun appliesLabel(applies: String): String = when (applies) {
        "live" -> stringResource(R.string.host_settings_applies_live)
        "restart", "restart-required" -> stringResource(R.string.host_settings_applies_restart)
        else -> stringResource(R.string.host_settings_applies_other, applies.ifBlank { "-" })
    }

    @Composable
    fun displayValue(field: HostSettingField): String = when (field.kind) {
        HostSettingKind.Secret -> stringResource(
            if (field.secretSet) R.string.host_settings_secret_set else R.string.host_settings_secret_unset,
        )
        HostSettingKind.Choice -> field.options.firstOrNull { it.value == field.value }?.label ?: field.value
        else -> field.value.ifEmpty { stringResource(R.string.host_settings_value_missing) }
    }

    @Composable
    fun FieldRow(view: SettingsNamespaceView, field: HostSettingField) {
        val editable = describe?.writable != false && field.editable && !writing
        when (field.kind) {
            HostSettingKind.Toggle -> ToggleRow(
                label = field.label,
                checked = field.value == "true",
                hint = field.description,
                enabled = editable,
            ) {
                // A switch has no natural cancel, so it saves on flip; a refusal arrives in the
                // error slot above and the reload puts the row back where the host left it.
                save(view.ns, field, HostSettingsPresentation.toggleValue(field.value != "true"))
            }

            HostSettingKind.ReadOnly -> Row(Modifier.fillMaxWidth().heightIn(min = DsSpacing.touchTarget), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(field.label, style = DsType.std14, color = colors.labelSecondary)
                    Text(
                        field.typeLabel?.let { stringResource(R.string.host_settings_read_only_type, it) }
                            ?: stringResource(R.string.host_settings_read_only),
                        style = DsType.caption11,
                        color = colors.labelTertiary,
                    )
                }
                Text(
                    technicalDisplay(field.value.ifEmpty { stringResource(R.string.host_settings_value_missing) }),
                    style = DsType.small13,
                    color = colors.labelTertiary,
                    maxLines = 2,
                )
            }

            else -> Row(
                Modifier.fillMaxWidth().heightIn(min = DsSpacing.touchTarget)
                    .clickable(enabled = editable) {
                        writeError = null
                        draft = HostSettingDraft(view.ns, field, if (field.kind == HostSettingKind.Secret) "" else field.value)
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(field.label, style = DsType.std14, color = if (editable) colors.labelSecondary else colors.labelTertiary)
                    field.description?.let { Text(it, style = DsType.caption11, color = colors.labelTertiary) }
                }
                Text(displayValue(field), style = DsType.small13, color = colors.labelSecondary, maxLines = 1)
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = colors.labelTertiary)
            }
        }
    }

    @Composable
    fun NamespaceCard(view: SettingsNamespaceView) {
        val fields = remember(view) { HostSettingsPresentation.fields(view) }
        DsCard {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(technicalDisplay(view.ns), Modifier.weight(1f), style = DsType.std14Strong, color = colors.labelPrimary)
                Text(
                    stringResource(R.string.host_settings_revision, view.revision),
                    style = DsType.caption11,
                    color = colors.labelTertiary,
                )
            }
            Text(appliesLabel(view.applies), style = DsType.caption11, color = colors.labelTertiary)
            if (fields.isEmpty()) {
                Text(stringResource(R.string.host_settings_namespace_empty), style = DsType.small13, color = colors.labelTertiary)
            }
            fields.forEach { field -> FieldRow(view, field) }
        }
    }

    FeaturePage(
        title = stringResource(R.string.host_settings_title),
        onClose = onClose,
        onRefresh = { revision++ },
    ) {
        Text(stringResource(R.string.host_settings_subtitle), style = DsType.small13, color = colors.labelTertiary)

        if (conflict) {
            DsCard {
                Text(conflictMessage, style = DsType.std14, color = colors.warnText)
            }
        }

        if (loading && describe == null) FeatureProgress()

        error?.let { message ->
            DsCard {
                Text(refused, style = DsType.std14, color = colors.error)
                FeatureAction(stringResource(R.string.common_retry), !loading) { revision++ }
                FeatureDiagnostics(message)
            }
        }

        val document = describe
        if (document != null) {
            if (!document.writable) {
                DsCard { Text(stringResource(R.string.host_settings_writable_off), style = DsType.small13, color = colors.warnText) }
            }
            if (document.namespaces.isEmpty()) {
                EmptyHero(stringResource(R.string.host_settings_empty), null, showPreview = false)
            }
            document.namespaces.forEach { view -> NamespaceCard(view) }
        }
        // A little air so the last card is not flush against the sheet's bottom inset.
        Spacer(Modifier.height(DsSpacing.small))
    }

    DsToastHost(toast, modifier = Modifier.fillMaxWidth())

    draft?.let { current ->
        val field = current.field
        DsDialog(title = field.label, onDismiss = { if (!writing) draft = null }) {
            if (field.description != null) {
                Text(field.description, style = DsType.small13, color = colors.labelSecondary)
            }
            when (field.kind) {
                HostSettingKind.Secret -> {
                    Text(stringResource(R.string.host_settings_secret_hint), style = DsType.caption11, color = colors.labelTertiary)
                    Text(
                        stringResource(if (field.secretSet) R.string.host_settings_secret_set else R.string.host_settings_secret_unset),
                        style = DsType.small13,
                        color = if (field.secretSet) colors.labelSecondary else colors.warnText,
                    )
                    TextField(
                        value = current.text,
                        onValueChange = { draft = current.copy(text = it) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.host_settings_secret_label)) },
                        enabled = !writing,
                        singleLine = true,
                        textStyle = DsType.std14,
                        colors = dialogTextFieldColors(),
                        shape = DsShapes.block,
                    )
                }

                HostSettingKind.Choice -> Column(verticalArrangement = Arrangement.spacedBy(DsSpacing.tiny)) {
                    if (field.options.isEmpty()) {
                        Text(stringResource(R.string.host_settings_read_only), style = DsType.small13, color = colors.labelTertiary)
                    }
                    field.options.forEach { option ->
                        DsPill(
                            text = option.label ?: option.value,
                            selected = option.value == current.text,
                            onClick = { draft = current.copy(text = option.value) },
                        )
                    }
                }

                else -> TextField(
                    value = current.text,
                    onValueChange = { draft = current.copy(text = it) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(field.label) },
                    enabled = !writing,
                    singleLine = true,
                    keyboardOptions = if (field.kind == HostSettingKind.Number) {
                        KeyboardOptions(keyboardType = KeyboardType.Number)
                    } else {
                        KeyboardOptions.Default
                    },
                    textStyle = DsType.std14,
                    colors = dialogTextFieldColors(),
                    shape = DsShapes.block,
                )
            }

            writeError?.let { Text(it, style = DsType.small13, color = colors.error) }

            Row(Modifier.fillMaxWidth().padding(top = DsSpacing.tiny), verticalAlignment = Alignment.CenterVertically) {
                if (field.kind == HostSettingKind.Secret && field.secretSet) {
                    DsButton(
                        text = stringResource(R.string.host_settings_secret_clear),
                        onClick = { writeSecret(field, "", clear = true) },
                        enabled = !writing,
                        variant = DsButtonVariant.Danger,
                        size = DsButtonSize.Small,
                    )
                }
                Spacer(Modifier.weight(1f))
                DsButton(
                    text = stringResource(R.string.common_cancel),
                    onClick = { draft = null },
                    enabled = !writing,
                    variant = DsButtonVariant.Ghost,
                    size = DsButtonSize.Small,
                )
                DsButton(
                    text = stringResource(R.string.common_save),
                    onClick = {
                        if (field.kind == HostSettingKind.Secret) {
                            writeSecret(field, current.text.trim(), clear = false)
                        } else {
                            when (val encoded = HostSettingsPresentation.encode(field.kind, current.text.trim())) {
                                null -> writeError = invalidNumber
                                else -> save(current.ns, field, encoded) { draft = null }
                            }
                        }
                    },
                    enabled = !writing && (field.kind != HostSettingKind.Secret || current.text.isNotBlank()),
                    variant = DsButtonVariant.Primary,
                    size = DsButtonSize.Small,
                )
            }
        }
    }
}

/** One open editor: which field, in which namespace, and the text on its way to the host. */
private data class HostSettingDraft(
    val ns: String,
    val field: HostSettingField,
    val text: String,
)

/**
 * Carries a refusal out of a `run { }` block as an exception the caller can catch, so the load path
 * and the write path share one error slot instead of each spelling out the same `when`.
 */
private class SettingsRefused(val failure: RpcError) : Exception(failure.message)
