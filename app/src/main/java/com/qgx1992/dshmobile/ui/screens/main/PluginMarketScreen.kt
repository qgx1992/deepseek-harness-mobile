package com.qgx1992.dshmobile.ui.screens.main

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qgx1992.dshmobile.R
import com.qgx1992.dshmobile.core.wire.*
import com.qgx1992.dshmobile.core.wire.dto.*
import com.qgx1992.dshmobile.data.HarnessFeature
import com.qgx1992.dshmobile.ui.components.*
import com.qgx1992.dshmobile.ui.rememberSessionStore
import com.qgx1992.dshmobile.ui.theme.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The Discover page: the market indexes this harness can reach, and the plugins each one sells.
 *
 * Split in two on purpose. [PluginMarketScreen] is the standalone entry point and owns a sheet;
 * [PluginMarketContent] is just the column of market widgets and owns nothing. The Plugins page
 * needs the second half inside the sheet it already has (Installed / Discover segments), and a
 * `FeaturePage` nested there would stack a second `ModalBottomSheet` on the first — two scrims, two
 * drag handles, and a back gesture that pops the inner sheet while the outer one stays. One sheet per
 * page is the rule the rest of the app follows: the standalone page may open a detail sheet of its
 * own, the Plugins page may not.
 */

/**
 * The host's error, kept as the two fields it arrived in: [code] picks our copy,
 * [message] is the diagnostic shown verbatim when it does not.
 */
private class MarketFailure(val code: String?, val message: String?)

/** What an install button needs, held by the confirmation sheet until the user agrees. */
private data class MarketInstallRequest(val spec: String, val registry: String?, val replaces: String?)

/**
 * Discover as its own page.
 *
 * The capability check lives here rather than in the content because this is the page that opens the
 * sheet: an unsupported host must not see it flash. [onClose] is the caller's own close, so the
 * reset happens in one place — MainScreen renders the single "unavailable" toast, the same division
 * [AutomationScreen] uses.
 */
@Composable
internal fun PluginMarketScreen(onClose: () -> Unit) {
    val store = rememberSessionStore()
    val available by store.pluginMarketAvailable.collectAsStateWithLifecycle()
    LaunchedEffect(available) { if (available == false) onClose() }
    // Nothing to draw while the answer is "no": emitting the sheet first and closing it in an effect
    // would show an empty panel sliding in and out.
    if (available == false) return

    var refreshSignal by remember { mutableIntStateOf(0) }
    FeaturePage(stringResource(R.string.market_tab_discover), onClose, onRefresh = { refreshSignal++ }) {
        PluginMarketContent(host = store.activeHostKey, onInstalled = onClose, refreshSignal = refreshSignal)
    }
}

/**
 * The Discover column, without a sheet of its own.
 *
 * @param host the harness the page belongs to; every write-back re-checks it (see [searchOnce]).
 * @param onInstalled called once an install has been handed to the shared operation tracker, so the
 *   caller can close its sheet and let the existing progress panel be the only thing reporting it.
 * @param refreshSignal bumped by whichever refresh control the caller owns — the sheet's icon when
 *   standalone, the plugins sheet's icon when embedded. A counter is the only channel, because the
 *   icon lives outside this function.
 * @param embedded true when an enclosing sheet already exists; the entry detail then renders inline
 *   instead of opening a second sheet.
 */
@Composable
internal fun PluginMarketContent(
    host: String?,
    onInstalled: () -> Unit,
    modifier: Modifier = Modifier,
    refreshSignal: Int = 0,
    embedded: Boolean = false,
) {
    val store = rememberSessionStore()
    val scope = rememberCoroutineScope()
    // Resolved here, in composition: the message is ours, so it must be in the language the page is
    // drawn in, not the one current when some request started.
    val offline = stringResource(R.string.common_offline)
    val languageTag = LocalConfiguration.current.locales[0].toLanguageTag()
    val connection by store.connectionState.collectAsStateWithLifecycle()
    val choices = remember { marketSortChoices() }

    // All page state is keyed on the host. Switching harnesses must not leave the previous one's
    // sources, query or results on screen, and re-keying the lot is both cheaper and harder to get
    // wrong than resetting a dozen fields by hand.
    var indexes by remember(host) { mutableStateOf<List<MarketIndexSourceView>>(emptyList()) }
    var indexesLoaded by remember(host) { mutableStateOf(false) }
    var sourceInput by remember(host) { mutableStateOf("") }
    var selectedUrl by remember(host) { mutableStateOf<String?>(null) }
    var query by remember(host) { mutableStateOf("") }
    var settledQuery by remember(host) { mutableStateOf("") }
    var sortKey by remember(host) { mutableStateOf(choices.first().key) }
    var result by remember(host) { mutableStateOf<MarketSearchResultView?>(null) }
    var entries by remember(host) { mutableStateOf<List<MarketEntryView>>(emptyList()) }
    var detail by remember(host) { mutableStateOf<MarketEntryView?>(null) }
    var confirm by remember(host) { mutableStateOf<MarketInstallRequest?>(null) }
    var busy by remember(host) { mutableStateOf(false) }
    var failure by remember(host) { mutableStateOf<MarketFailure?>(null) }
    var indexesBusy by remember(host) { mutableStateOf(false) }
    var indexesFailure by remember(host) { mutableStateOf<MarketFailure?>(null) }
    var revision by remember { mutableIntStateOf(0) }
    var indexRevision by remember { mutableIntStateOf(0) }
    // One-shot freshness. The first load asks the host to re-read the index documents, and so does a
    // refresh or a retry; every other search reuses the copy the host already returned for us.
    var freshNext by remember(host) { mutableStateOf(true) }
    // The counter above starts at whatever the caller had when this page was created, so comparing
    // against a remembered copy is what makes "the user pressed refresh" visible.
    var seenSignal by remember(host) { mutableIntStateOf(refreshSignal) }

    /** Turns a thrown call into the failure card's two fields, without losing our own exception. */
    fun asFailure(error: Exception) = MarketFailure((error as? HarnessFeatureException)?.code, error.message)

    // Typing is debounced: the field drives `settledQuery` after a pause, and only that drives the
    // request. Without it every keystroke would be one RPC to the desktop harness, each re-ranking
    // the whole index.
    LaunchedEffect(query) {
        if (query == settledQuery) return@LaunchedEffect
        delay(300)
        settledQuery = query
    }

    // The configured sources, on entry and on retry.
    LaunchedEffect(host, connection.phase, indexRevision) {
        indexesBusy = true
        indexesFailure = null
        try {
            val api = store.apiForHost(host) ?: error(offline)
            val list = store.featureCall(HarnessFeature.PLUGIN_MARKET, api) { api.marketIndexes() }.featureValue()
            // The reply belongs to the host we asked. A reconnect may have landed on another harness
            // in the meantime; painting its sources here would attach this page to the wrong desktop.
            if (store.activeHostKey != host) return@LaunchedEffect
            indexes = list
            indexesLoaded = true
            // Keep the current pick if it survived; otherwise fall to the first usable source, and to
            // null when there is none — which is what makes the add-a-source UI appear.
            if (selectedUrl == null || list.none { it.url == selectedUrl && it.usable }) {
                selectedUrl = list.firstOrNull { it.usable }?.url
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (store.activeHostKey == host) indexesFailure = asFailure(error)
        } finally {
            if (store.activeHostKey == host) indexesBusy = false
        }
    }

    // The results for the selected source, query and sort order.
    LaunchedEffect(host, connection.phase, selectedUrl, settledQuery, sortKey, revision, refreshSignal) {
        val url = selectedUrl ?: return@LaunchedEffect
        val refresh = freshNext || refreshSignal != seenSignal
        freshNext = false
        seenSignal = refreshSignal
        busy = true
        failure = null
        try {
            val api = store.apiForHost(host) ?: error(offline)
            val sort = choices.firstOrNull { it.key == sortKey }?.sort ?: MarketSort.INDEX
            val page = store.featureCall(HarnessFeature.PLUGIN_MARKET, api) {
                api.marketSearch(
                    url,
                    MarketQuery(
                        text = settledQuery.trim().takeIf { it.isNotEmpty() },
                        sort = sort,
                        offset = 0,
                        limit = MarketQuery.DEFAULT_LIMIT,
                        refresh = refresh,
                    ),
                )
            }.featureValue()
            if (store.activeHostKey != host) return@LaunchedEffect
            result = page
            entries = page.entries
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (store.activeHostKey == host) failure = asFailure(error)
        } finally {
            if (store.activeHostKey == host) busy = false
        }
    }

    /**
     * Adds the typed URL to the harness's source list and selects it.
     *
     * `setIndexes` replaces the whole list and this page has no remove control, so the new URL is
     * merged *in front of* the sources that already work rather than sent alone: sending it alone
     * would silently drop every source the user (or their desktop) had set up. Putting it first is
     * what makes it the default pick once the list comes back.
     */
    fun addSource() {
        val url = sourceInput.trim()
        if (url.isEmpty()) return
        scope.launch {
            indexesBusy = true
            indexesFailure = null
            try {
                val api = store.apiForHost(host) ?: error(offline)
                // Sources the host could not read are left out on purpose: `setIndexes` validates the
                // whole list, so resending a URL it already refused could fail the call and leave the
                // user unable to add anything.
                val merged = (listOf(url) + indexes.filter { it.usable }.map { it.url }).distinct()
                store.featureCall(HarnessFeature.PLUGIN_MARKET, api) { api.marketSetIndexes(merged) }.featureValue()
                if (store.activeHostKey != host) return@launch
                val list = store.featureCall(HarnessFeature.PLUGIN_MARKET, api) { api.marketIndexes() }.featureValue()
                if (store.activeHostKey != host) return@launch
                indexes = list
                indexesLoaded = true
                sourceInput = ""
                selectedUrl = list.firstOrNull { it.url == url && it.usable }?.url
                    ?: list.firstOrNull { it.usable }?.url
                // A source that was just configured has never been read, so the next search must not
                // trust anything the host may have cached for it.
                freshNext = true
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (store.activeHostKey == host) indexesFailure = asFailure(error)
            } finally {
                if (store.activeHostKey == host) indexesBusy = false
            }
        }
    }

    /**
     * Appends the next page.
     *
     * [MarketSearchResultView.fetchedAt] goes back with the request: an offset only means something
     * inside the document it was counted in, so handing the stamp back lets the host page the same
     * snapshot. Without it a host that re-read the index between the two calls would answer page two
     * of a different list, and rows would silently repeat or vanish.
     */
    fun loadMore() {
        val url = selectedUrl ?: return
        val page = result ?: return
        val next = page.nextOffset ?: return
        scope.launch {
            busy = true
            failure = null
            try {
                val api = store.apiForHost(host) ?: error(offline)
                val sort = choices.firstOrNull { it.key == sortKey }?.sort ?: MarketSort.INDEX
                val more = store.featureCall(HarnessFeature.PLUGIN_MARKET, api) {
                    api.marketSearch(
                        url,
                        MarketQuery(
                            text = settledQuery.trim().takeIf { it.isNotEmpty() },
                            sort = sort,
                            offset = next,
                            limit = MarketQuery.DEFAULT_LIMIT,
                            fetchedAt = page.fetchedAt,
                        ),
                    )
                }.featureValue()
                if (store.activeHostKey != host) return@launch
                // A shifting index can return a window we already show; drop the repeats rather than
                // hand the list a duplicate key.
                val shown = entries.mapTo(HashSet()) { it.name }
                entries = entries + more.entries.filter { shown.add(it.name) }
                result = more
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (store.activeHostKey == host) failure = asFailure(error)
            } finally {
                if (store.activeHostKey == host) busy = false
            }
        }
    }

    /** Opens the confirmation sheet for one version of one entry. */
    fun requestInstall(entry: MarketEntryView, version: MarketVersionView) {
        val status = entry.installation.status
        // Only a plugin this harness already knows about can be replaced by name; for a fresh name
        // the host would have nothing to replace. `different-version` counts because it is the same
        // name installed at a version we did not expect.
        val replaces = entry.name.takeIf {
            status == MarketInstallationView.STATUS_INSTALLED ||
                status == MarketInstallationView.STATUS_UPDATE_AVAILABLE ||
                status == MarketInstallationView.STATUS_DIFFERENT_VERSION
        }
        confirm = MarketInstallRequest(version.install.spec, version.install.registry, replaces)
    }

    val pickedSort = choices.firstOrNull { it.key == sortKey } ?: choices.first()
    // No usable source is a state the phone must be able to leave on its own: the desktop can
    // configure indexes, but requiring a trip to the PC is not a mobile answer.
    val needsSource = indexesLoaded && indexes.none { it.usable }
    val showSources = indexesLoaded && indexes.isNotEmpty() && (indexes.size > 1 || indexes.any { !it.usable })
    val detailEntry = detail

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(DsSpacing.medium)) {
        if (embedded && detailEntry != null) {
            // Inline detail: the enclosing sheet is already there, so this is a view swap inside the
            // same column, with a back row instead of a modal on top of a modal.
            FeatureAction(stringResource(R.string.common_back), variant = DsButtonVariant.Ghost) { detail = null }
            Text(
                localizedOrNull(detailEntry.title, languageTag) ?: technicalDisplay(detailEntry.name),
                style = DsType.large20,
                color = DsTheme.colors.labelPrimary,
            )
            MarketDetailBody(detailEntry, languageTag, !busy) { version -> requestInstall(detailEntry, version) }
        } else {
            if (indexesBusy) FeatureProgress()
            indexesFailure?.let { problem ->
                DsCard {
                    Text(stringResource(R.string.ux_a_load_error), style = DsType.std14, color = DsTheme.colors.error)
                    marketErrorText(problem.code, problem.message)?.let {
                        Text(stringResource(it), style = DsType.small13, color = DsTheme.colors.labelSecondary)
                    }
                    FeatureAction(stringResource(R.string.common_retry), !indexesBusy) { indexRevision++ }
                    FeatureDiagnostics(problem.message ?: problem.code)
                }
            }

            if (showSources) {
                SectionHeader(stringResource(R.string.market_sources))
                indexes.forEach { source ->
                    val title = localizedOrNull(source.title, languageTag)
                    // A source that failed to load keeps its row (so the user can tell which one is
                    // broken) but loses the tap target: selecting it would only search a document the
                    // host could not parse.
                    val onSelect: (() -> Unit)? = if (source.usable && !busy) {
                        { selectedUrl = source.url }
                    } else {
                        null
                    }
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(DsSpacing.tiny)) {
                        DsPill(
                            title ?: technicalDisplay(source.url),
                            selected = source.url == selectedUrl,
                            warn = !source.usable,
                            onClick = onSelect,
                        )
                        if (!source.usable) {
                            Text(stringResource(R.string.market_source_invalid), style = DsType.small13, color = DsTheme.colors.error)
                        }
                    }
                }
            }

            if (needsSource) {
                EmptyHero(stringResource(R.string.market_sources_empty), null, showPreview = false)
                FeatureField(sourceInput, { sourceInput = it }, R.string.market_source_url, !indexesBusy, singleLine = true, technical = true)
                FeatureAction(
                    stringResource(R.string.market_source_add),
                    !indexesBusy && sourceInput.isNotBlank(),
                    DsButtonVariant.Primary,
                ) { addSource() }
            }

            if (selectedUrl != null) {
                ChatSearchField(query, { query = it })
                DsSegmented(
                    segments = choices.map { DsSegment(it.key, stringResource(it.labelRes)) },
                    selectedKey = pickedSort.key,
                    onSelect = { sortKey = it },
                    stretch = true,
                    minSegmentHeight = DsSpacing.touchTarget,
                    maxLabelLines = Int.MAX_VALUE,
                )

                if (busy && result == null) {
                    Text(stringResource(R.string.market_loading), style = DsType.small13, color = DsTheme.colors.labelTertiary)
                }
                if (busy && result != null) FeatureProgress()
                failure?.let { problem ->
                    DsCard {
                        Text(stringResource(R.string.ux_a_load_error), style = DsType.std14, color = DsTheme.colors.error)
                        marketErrorText(problem.code, problem.message)?.let {
                            Text(stringResource(it), style = DsType.small13, color = DsTheme.colors.labelSecondary)
                        }
                        FeatureAction(stringResource(R.string.common_retry), !busy) { revision++ }
                        FeatureDiagnostics(problem.message ?: problem.code)
                    }
                }
                result?.let {
                    Text(stringResource(R.string.market_total, it.total), style = DsType.small13, color = DsTheme.colors.labelTertiary)
                }
                if (!busy && result != null && entries.isEmpty()) {
                    EmptyHero(stringResource(R.string.market_empty), null, showPreview = false)
                }
                entries.forEach { entry ->
                    MarketEntryCard(
                        entry = entry,
                        languageTag = languageTag,
                        enabled = !busy,
                        onOpen = { detail = entry },
                        onInstall = { version -> requestInstall(entry, version) },
                    )
                }
                if (result?.nextOffset != null) {
                    FeatureAction(stringResource(R.string.market_load_more), !busy) { loadMore() }
                }
            }
        }
    }

    // The detail sheet is only for the standalone page; embedded pages rendered it inline above.
    if (!embedded) {
        detailEntry?.let { entry ->
            FeaturePage(
                localizedOrNull(entry.title, languageTag) ?: technicalDisplay(entry.name),
                onClose = { detail = null },
            ) {
                MarketDetailBody(entry, languageTag, !busy) { version -> requestInstall(entry, version) }
            }
        }
    }

    confirm?.let { request ->
        DsDialog(stringResource(R.string.market_confirm_title), { confirm = null }) {
            Text(
                stringResource(R.string.market_confirm_body, technicalDisplay(request.spec)),
                style = DsType.std14,
                color = DsTheme.colors.labelPrimary,
            )
            request.registry?.takeIf { it.isNotBlank() }?.let {
                Text(technicalDisplay(it), style = DsType.small13, color = DsTheme.colors.labelSecondary)
            }
            FeatureAction(stringResource(R.string.market_install), variant = DsButtonVariant.Primary) {
                confirm = null
                if (host == null) {
                    // No host means nothing can be installed; saying so beats a button that looks
                    // like it worked.
                    failure = MarketFailure(null, offline)
                    return@FeatureAction
                }
                store.pluginOperations.install(
                    host = host,
                    spec = request.spec,
                    registry = request.registry,
                    approvedBuilds = null,
                    // Market entries are ordinary plugins: enabled and unpinned is what the desktop's
                    // own install button does, and the confirm sheet is where the user agreed.
                    enabled = true,
                    saveExact = null,
                    replaces = request.replaces,
                )
                // The operation tracker owns the progress UI from here. Leaving this page open would
                // show two views of one install, so the caller closes its sheet.
                onInstalled()
            }
            FeatureAction(stringResource(R.string.common_cancel)) { confirm = null }
        }
    }
}

/** One market entry, as a card that opens its details and can install its best version. */
@Composable
private fun MarketEntryCard(
    entry: MarketEntryView,
    languageTag: String,
    enabled: Boolean,
    onOpen: () -> Unit,
    onInstall: (MarketVersionView) -> Unit,
) {
    val status = entry.installation.status
    val installable = entry.installable
    // The host already decided which version it would install (its default, else a compatible one).
    // Re-deriving that here would be a second opinion nothing agrees with.
    val incompatible = !entry.installableHere
    val needed = installable?.compatibility?.requirements?.get("engines.dsh")
    val author = entry.author?.takeIf { it.isNotBlank() }
    val meta = listOfNotNull(
        author?.let { stringResource(R.string.market_author, technicalDisplay(it)) },
        installable?.version?.let { technicalDisplay(it) },
        marketPublishedLabel(installable?.publishTimestamp ?: entry.lastPublished).takeIf { it.isNotEmpty() },
    ).joinToString(" · ")

    DsCard(onClick = onOpen) {
        Text(
            localizedOrNull(entry.title, languageTag) ?: technicalDisplay(entry.name),
            style = DsType.std14Strong,
            color = DsTheme.colors.labelPrimary,
        )
        // The pill sits on its own line: a status like "Bundled with the harness" next to a long
        // plugin title leaves neither enough width on a phone.
        DsPill(stringResource(marketStatusText(status)), warn = status == MarketInstallationView.STATUS_UPDATE_AVAILABLE || incompatible)
        localizedOrNull(entry.description, languageTag)?.let {
            Text(it, style = DsType.std14, color = DsTheme.colors.labelSecondary, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
        if (meta.isNotEmpty()) {
            Text(meta, style = DsType.small13, color = DsTheme.colors.labelTertiary)
        }
        if (incompatible) {
            // Prefer the concrete version requirement when the host sent one; it is the only form of
            // this message that tells the user what to upgrade to.
            Text(
                if (needed != null) {
                    stringResource(R.string.market_incompatible, technicalDisplay(needed))
                } else {
                    stringResource(R.string.market_incompatible_short)
                },
                style = DsType.small13,
                color = DsTheme.colors.warnText,
            )
        }
        // A plugin that ships inside the harness cannot be installed over itself; the pill already
        // says it is provided, and a disabled button would only invite a question the host would
        // answer by refusing.
        if (entry.installation.canInstall) {
            // Both "there is something here to replace" states read as an update; everything else —
            // including a host that invented a new status — reads as a first install.
            val replacing = status == MarketInstallationView.STATUS_UPDATE_AVAILABLE ||
                status == MarketInstallationView.STATUS_DIFFERENT_VERSION
            val label = if (replacing) R.string.market_update else R.string.market_install
            FeatureAction(stringResource(label), enabled = enabled && installable != null && !incompatible) {
                installable?.let(onInstall)
            }
        }
    }
}

/**
 * One entry's full story: description, where it comes from, and every published version with its own
 * install button.
 *
 * A [ColumnScope] extension so both call sites — the standalone sheet and the inline view inside the
 * plugins sheet — lay it out in their own column instead of nesting another one.
 */
@Composable
private fun ColumnScope.MarketDetailBody(
    entry: MarketEntryView,
    languageTag: String,
    enabled: Boolean,
    onInstall: (MarketVersionView) -> Unit,
) {
    val status = entry.installation.status
    localizedOrNull(entry.description, languageTag)?.let {
        Text(it, style = DsType.std14, color = DsTheme.colors.labelSecondary)
    }
    DsPill(stringResource(marketStatusText(status)), warn = status == MarketInstallationView.STATUS_UPDATE_AVAILABLE)
    entry.author?.takeIf { it.isNotBlank() }?.let {
        Text(stringResource(R.string.market_author, technicalDisplay(it)), style = DsType.small13, color = DsTheme.colors.labelSecondary)
    }
    entry.homepage?.takeIf { it.isNotBlank() }?.let {
        Text(technicalDisplay(it), style = DsType.small13, color = DsTheme.colors.labelTertiary)
    }
    entry.repository?.url?.takeIf { it.isNotBlank() }?.let {
        Text(technicalDisplay(it), style = DsType.small13, color = DsTheme.colors.labelTertiary)
    }
    entry.installable?.let {
        Text(stringResource(R.string.market_installs, technicalDisplay(it.install.spec)), style = DsType.small13, color = DsTheme.colors.labelSecondary)
    }
    SectionHeader(stringResource(R.string.market_versions))
    entry.versions.forEach { version ->
        DsCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    technicalDisplay(version.version),
                    modifier = Modifier.weight(1f),
                    style = DsType.std14Strong,
                    color = DsTheme.colors.labelPrimary,
                )
                marketPublishedLabel(version.publishTimestamp).takeIf { it.isNotEmpty() }?.let {
                    Text(it, style = DsType.small13, color = DsTheme.colors.labelTertiary)
                }
            }
            if (!version.compatibility.compatible) {
                val needed = version.compatibility.requirements["engines.dsh"]
                Text(
                    if (needed != null) {
                        stringResource(R.string.market_incompatible, technicalDisplay(needed))
                    } else {
                        stringResource(R.string.market_incompatible_short)
                    },
                    style = DsType.small13,
                    color = DsTheme.colors.warnText,
                )
            }
            // Version-level installs are gated on that version's own compatibility, not the entry's:
            // an older release may run on this harness while the newest one does not.
            if (entry.installation.canInstall) {
                FeatureAction(stringResource(R.string.market_install), enabled && version.compatibility.compatible) { onInstall(version) }
            }
        }
    }
}
