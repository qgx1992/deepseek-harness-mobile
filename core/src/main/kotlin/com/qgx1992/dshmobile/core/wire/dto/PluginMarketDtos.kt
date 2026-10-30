package com.qgx1992.dshmobile.core.wire.dto

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Views over the `pluginMarket` namespace: a read-only window on the plugin indexes the host is
 * configured to browse.
 *
 * The host does the validating, fetching, searching, and annotating; a client only renders what it
 * returns. Installation is not part of this namespace — it goes through `pluginManager/installBundle`
 * with the `spec` each version carries.
 */

/**
 * A text that the index may write once (plain string) or per language (`{en, "zh-cn", …}`).
 *
 * Both shapes are legal on the wire, so the serializer accepts either and [pick] resolves the best
 * variant for the reader's language.
 */
@Serializable(with = LocalizedTextSerializer::class)
data class LocalizedText(val variants: Map<String, String> = emptyMap()) {
    val isEmpty: Boolean get() = variants.isEmpty()

    /**
     * The best variant for [languageTag] (a BCP-47 tag such as `zh-CN`), falling back to the same
     * base language, then to English, then to whatever the index offered.
     */
    fun pick(languageTag: String? = null): String? {
        if (variants.isEmpty()) return null
        val tag = languageTag?.lowercase()?.replace('_', '-')
        if (tag != null) {
            variants[tag]?.let { return it }
            val base = tag.substringBefore('-')
            variants[base]?.let { return it }
            variants.entries.firstOrNull { it.key.substringBefore('-') == base }?.let { return it.value }
        }
        return variants["en"] ?: variants.values.first()
    }
}

/** Reads [LocalizedText] from either the plain-string or the per-language object form. */
object LocalizedTextSerializer : KSerializer<LocalizedText> {
    override val descriptor: SerialDescriptor = JsonElement.serializer().descriptor

    override fun deserialize(decoder: Decoder): LocalizedText {
        val input = decoder as? JsonDecoder ?: return LocalizedText(mapOf("en" to decoder.decodeString()))
        return when (val element = input.decodeJsonElement()) {
            is JsonPrimitive -> LocalizedText(
                if (element.isString) mapOf("en" to element.content) else emptyMap(),
            )
            is JsonObject -> LocalizedText(
                element.mapNotNull { (language, value) ->
                    (value as? JsonPrimitive)?.takeIf { it.isString }?.let { language to it.content }
                }.toMap(),
            )
            else -> LocalizedText()
        }
    }

    override fun serialize(encoder: Encoder, value: LocalizedText) {
        val output = encoder as? JsonEncoder
            ?: throw IllegalArgumentException("LocalizedText is JSON only")
        output.encodeJsonElement(
            JsonObject(value.variants.mapValues { (_, text) -> JsonPrimitive(text) }),
        )
    }
}

/** One configured index source, as `pluginMarket/listIndexes` reports it. */
@Serializable
data class MarketIndexSourceView(
    @SerialName("url") val url: String = "",
    /** Market title from the last successful read; absent before the first one. */
    @SerialName("title") val title: LocalizedText? = null,
    @SerialName("description") val description: LocalizedText? = null,
    /** Why the last read failed; a source with an error is listed but never fetched again. */
    @SerialName("error") val error: String? = null,
) {
    val usable: Boolean get() = error == null
}

/** A declared category or label: an id and its title. */
@Serializable
data class MarketDeclarationView(
    @SerialName("title") val title: LocalizedText = LocalizedText(),
)

/** A declared sort key, with the direction the index wants it read in. */
@Serializable
data class MarketSortKeyView(
    @SerialName("title") val title: LocalizedText = LocalizedText(),
    @SerialName("order") val order: String = "asc",
)

/** The index header's declarations, used to turn category and label ids into titles. */
@Serializable
data class MarketMetadataView(
    @SerialName("categories") val categories: Map<String, MarketDeclarationView> = emptyMap(),
    @SerialName("labels") val labels: Map<String, MarketDeclarationView> = emptyMap(),
    @SerialName("sortKeys") val sortKeys: Map<String, MarketSortKeyView> = emptyMap(),
) {
    fun categoryTitle(id: String?): LocalizedText? = id?.let { categories[it]?.title }
    fun labelTitle(id: String): LocalizedText? = labels[id]?.title
}

/** What `pnpm add` needs for one exact version. */
@Serializable
data class MarketInstallTargetView(
    @SerialName("spec") val spec: String = "",
    @SerialName("registry") val registry: String? = null,
)

/** Whether the running host can load one version, computed host-side on every call. */
@Serializable
data class MarketCompatibilityView(
    @SerialName("compatible") val compatible: Boolean = true,
    @SerialName("runtimeVersion") val runtimeVersion: String? = null,
    /** The unmet requirement, keyed by engine name (`dsh`). */
    @SerialName("requirements") val requirements: Map<String, String> = emptyMap(),
    @SerialName("exempted") val exempted: Boolean = false,
)

/** The `engines` declaration of one version. */
@Serializable
data class MarketEnginesView(
    @SerialName("dsh") val dsh: String? = null,
)

/** One published version of a plugin, in descending SemVer precedence. */
@Serializable
data class MarketVersionView(
    @SerialName("version") val version: String = "",
    @SerialName("publishTimestamp") val publishTimestamp: Long = 0,
    @SerialName("install") val install: MarketInstallTargetView = MarketInstallTargetView(),
    @SerialName("compatibility") val compatibility: MarketCompatibilityView = MarketCompatibilityView(),
    @SerialName("engines") val engines: MarketEnginesView = MarketEnginesView(),
)

/**
 * The profile's relation to one plugin: `not-installed`, `installed`, `update-available`,
 * `different-version` (installed, but not the version the index would pick), or `provided` (the
 * running host ships it, so it cannot be installed over).
 */
@Serializable
data class MarketInstallationView(
    @SerialName("status") val status: String = STATUS_NOT_INSTALLED,
    @SerialName("installedVersion") val installedVersion: String? = null,
) {
    /** The profile already has this plugin, whatever the version. */
    val present: Boolean get() = status != STATUS_NOT_INSTALLED

    val canInstall: Boolean get() = status != STATUS_PROVIDED

    companion object {
        const val STATUS_NOT_INSTALLED = "not-installed"
        const val STATUS_INSTALLED = "installed"
        const val STATUS_UPDATE_AVAILABLE = "update-available"
        const val STATUS_DIFFERENT_VERSION = "different-version"
        const val STATUS_PROVIDED = "provided"
    }
}

/** The plugin's own metadata: declared category, keywords, label ids, and sort-key values. */
@Serializable
data class MarketEntryMetadataView(
    @SerialName("category") val category: String? = null,
    @SerialName("keywords") val keywords: List<String> = emptyList(),
    @SerialName("labels") val labels: List<String> = emptyList(),
    @SerialName("sortKeys") val sortKeys: Map<String, Double> = emptyMap(),
)

/** The plugin's source repository, for display only. */
@Serializable
data class MarketRepositoryView(
    @SerialName("url") val url: String = "",
    @SerialName("directory") val directory: String? = null,
)

/** One plugin in a market page. */
@Serializable
data class MarketEntryView(
    @SerialName("name") val name: String = "",
    @SerialName("title") val title: LocalizedText? = null,
    @SerialName("description") val description: LocalizedText? = null,
    @SerialName("author") val author: String? = null,
    @SerialName("homepage") val homepage: String? = null,
    @SerialName("icon") val icon: String? = null,
    @SerialName("repository") val repository: MarketRepositoryView? = null,
    @SerialName("versions") val versions: List<MarketVersionView> = emptyList(),
    /** The version an install would pick; absent when none is compatible with this host. */
    @SerialName("defaultVersion") val defaultVersion: String? = null,
    @SerialName("installation") val installation: MarketInstallationView = MarketInstallationView(),
    @SerialName("metadata") val metadata: MarketEntryMetadataView = MarketEntryMetadataView(),
) {
    /** The version to offer: the host's default, else the newest compatible one, else the newest. */
    val installable: MarketVersionView?
        get() = versions.firstOrNull { it.version == defaultVersion }
            ?: versions.firstOrNull { it.compatibility.compatible }
            ?: versions.firstOrNull()

    /** Whether this host can run the version it would install. */
    val installableHere: Boolean get() = installable?.compatibility?.compatible == true

    /** Newest publish time across every version, for "recently updated" ordering in the list. */
    val lastPublished: Long get() = versions.maxOfOrNull { it.publishTimestamp } ?: 0
}

/** One page of a market: the index header, the page itself, and where the next page starts. */
@Serializable
data class MarketSearchResultView(
    @SerialName("url") val url: String = "",
    @SerialName("title") val title: LocalizedText = LocalizedText(),
    @SerialName("description") val description: LocalizedText? = null,
    @SerialName("metadata") val metadata: MarketMetadataView = MarketMetadataView(),
    /** Fetch time of the document that answered, in Unix milliseconds. */
    @SerialName("fetchedAt") val fetchedAt: Long = 0,
    @SerialName("entries") val entries: List<MarketEntryView> = emptyList(),
    @SerialName("total") val total: Int = 0,
    /** Offset of the next page, or null after the last one. */
    @SerialName("nextOffset") val nextOffset: Int? = null,
)

/**
 * One `pluginMarket/search` query.
 *
 * The host's schema requires `sort`, `offset` and `limit` on every call, and rejects unknown
 * members, so [toJson] writes exactly the fields that are set — a null left in would be a
 * `plugin-market/invalid-query`.
 */
data class MarketQuery(
    val text: String? = null,
    val category: String? = null,
    val labels: List<String> = emptyList(),
    val sort: MarketSort = MarketSort.INDEX,
    val offset: Int = 0,
    val limit: Int = DEFAULT_LIMIT,
    val refresh: Boolean = false,
    val fetchedAt: Long? = null,
) {
    fun toJson(): JsonObject = JsonObject(
        buildMap {
            text?.takeIf { it.isNotBlank() }?.let { put("text", JsonPrimitive(it)) }
            category?.let { put("category", JsonPrimitive(it)) }
            if (labels.isNotEmpty()) put("labels", kotlinx.serialization.json.JsonArray(labels.map(::JsonPrimitive)))
            put("sort", sort.toJson())
            put("offset", JsonPrimitive(offset))
            put("limit", JsonPrimitive(limit))
            if (refresh) put("refresh", JsonPrimitive(true))
            fetchedAt?.let { put("fetchedAt", JsonPrimitive(it)) }
        },
    )

    companion object {
        /** The host refuses a larger page; 100 is its ceiling, not a suggestion. */
        const val MAX_LIMIT = 100
        const val DEFAULT_LIMIT = 30
    }
}

/**
 * Result ordering: the index's own order, text relevance, publish time, or one declared sort key.
 *
 * A kind that takes a direction writes one; the host's schema is a closed union and would reject a
 * direction on the kinds that do not.
 */
data class MarketSort(
    val kind: String,
    val direction: String? = null,
    val id: String? = null,
) {
    fun toJson(): JsonObject = JsonObject(
        buildMap {
            put("kind", JsonPrimitive(kind))
            if (kind == KIND_PUBLISHED || kind == KIND_KEY) direction?.let { put("direction", JsonPrimitive(it)) }
            if (kind == KIND_KEY) id?.let { put("id", JsonPrimitive(it)) }
        },
    )

    companion object {
        const val KIND_INDEX = "index"
        const val KIND_RELEVANCE = "relevance"
        const val KIND_PUBLISHED = "published"
        const val KIND_KEY = "key"

        val INDEX = MarketSort(KIND_INDEX)
        val RELEVANCE = MarketSort(KIND_RELEVANCE)
        val NEWEST = MarketSort(KIND_PUBLISHED, "desc")
        val OLDEST = MarketSort(KIND_PUBLISHED, "asc")

        fun key(id: String, ascending: Boolean = true) =
            MarketSort(KIND_KEY, if (ascending) "asc" else "desc", id)
    }
}
