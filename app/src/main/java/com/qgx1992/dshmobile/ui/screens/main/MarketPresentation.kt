package com.qgx1992.dshmobile.ui.screens.main

import com.qgx1992.dshmobile.R
import com.qgx1992.dshmobile.core.wire.dto.LocalizedText
import com.qgx1992.dshmobile.core.wire.dto.MarketInstallationView
import com.qgx1992.dshmobile.core.wire.dto.MarketSort
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * Everything the Discover page shows that is not a composable: host code → our copy, host status →
 * our copy, a publish stamp → a date.
 *
 * Kept out of [PluginMarketScreen] for the same reason [AutomationPresentation] is kept out of
 * [AutomationScreen]: these mappings are the part worth reading in one list, and the page file then
 * reads as layout only.
 */

/**
 * The string an error code is worth showing, or null when none of ours fits.
 *
 * The code reaches us either bare (`fetch-failed`) or namespaced (`plugin-market/fetch-failed`)
 * depending on which layer refused the call, so only the last segment is matched. The reason an
 * index URL was rejected travels as `reason` in the error details and never as the code, which is
 * why `invalid-index-url` and `invalid-index` share copy: from this page both mean "that source did
 * not work".
 *
 * Null is not a failure to map — it is the answer for codes this build does not know, and the
 * caller shows [message] (the host's own words) instead. Inventing copy for an unknown code would
 * hide a real diagnostic behind a guess.
 */
internal fun marketErrorText(code: String?, message: String?): Int? = when (code?.substringAfterLast('/')) {
    "invalid-index-url", "invalid-index", "fetch-failed", "unknown-index" -> R.string.market_source_invalid
    // The harness has no plugins settings to read: nothing is configured, so "no sources yet" is
    // literally what happened rather than an error to retry.
    "settings-unavailable" -> R.string.market_sources_empty
    else -> null
}

/**
 * The pill copy for an installation status.
 *
 * Unknown statuses (a newer host inventing another one) fall back to "not installed": it is the only
 * reading that does not claim something is present when this build cannot see it, and the install
 * button still offers the action.
 */
internal fun marketStatusText(status: String): Int = when (status) {
    MarketInstallationView.STATUS_INSTALLED -> R.string.market_status_installed
    MarketInstallationView.STATUS_UPDATE_AVAILABLE -> R.string.market_status_update
    MarketInstallationView.STATUS_DIFFERENT_VERSION -> R.string.market_status_different
    MarketInstallationView.STATUS_PROVIDED -> R.string.market_status_provided
    else -> R.string.market_status_absent
}

/**
 * A publish stamp (Unix seconds) as a short date, or "" when there is none.
 *
 * One formatter is cached per locale: this runs once per row, and `ofLocalizedDate` re-resolves its
 * symbols against the locale every time it is called. The cache is keyed on the locale because the
 * user can change the system language under a *running* app, and a stale formatter would keep
 * formatting dates in the previous language.
 */
internal fun marketPublishedLabel(unixSeconds: Long): String {
    if (unixSeconds <= 0) return ""
    // Guarded: a host answering with nonsense (0 or a value past the epoch range) must not take the
    // whole list down for a date.
    return runCatching {
        publishFormatter(Locale.getDefault())
            .format(Instant.ofEpochSecond(unixSeconds).atZone(ZoneId.systemDefault()))
    }.getOrDefault("")
}

private var publishFormatterCache: DateTimeFormatter? = null
private var publishFormatterLocale: Locale? = null

private fun publishFormatter(locale: Locale): DateTimeFormatter {
    publishFormatterCache?.let { cached -> if (publishFormatterLocale == locale) return cached }
    return DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT).withLocale(locale).also {
        publishFormatterCache = it
        publishFormatterLocale = locale
    }
}

/** One selectable sort order: a stable key for saved state, our label, and the wire value. */
internal data class MarketSortChoice(val key: String, val labelRes: Int, val sort: MarketSort)

/**
 * The sort orders, in the order the desktop market's own menu lists them.
 *
 * [MarketSortChoice.key] is a hand-written string rather than the sort's wire form so that the
 * selected segment survives [androidx.compose.runtime.saveable.rememberSaveable] without this file
 * having to spell out how a sealed value is serialized.
 */
internal fun marketSortChoices(): List<MarketSortChoice> = listOf(
    MarketSortChoice("index", R.string.market_sort_index, MarketSort.INDEX),
    MarketSortChoice("relevance", R.string.market_sort_relevance, MarketSort.RELEVANCE),
    MarketSortChoice("newest", R.string.market_sort_newest, MarketSort.NEWEST),
    MarketSortChoice("oldest", R.string.market_sort_oldest, MarketSort.OLDEST),
)

/**
 * A localized value, or null when the host had nothing in a language we can use.
 *
 * Blank counts as missing: a variant that exists but is empty would otherwise render an empty title
 * or an empty description line, and the caller's fallback (package name, or dropping the line) is
 * always better than a gap.
 */
internal fun localizedOrNull(text: LocalizedText?, languageTag: String): String? =
    text?.pick(languageTag)?.trim()?.takeIf { it.isNotEmpty() }
