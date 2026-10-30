package com.qgx1992.dshmobile.mockharness

import kotlinx.serialization.json.*

/**
 * Fixtures for the two experimental namespaces the desktop client gained before this one did:
 * `speech` (recognition) and `pluginMarket` (the Discover tab), plus the `agentTeam` projection.
 *
 * Everything here mirrors what the real services enforce, because the point of this harness is to
 * be strict enough that a client mistake fails here rather than on a phone: `speech/transcribe`
 * takes its argument inside `request`, `pluginMarket/search` requires `sort`/`offset`/`limit` and
 * refuses a query without them, and a page never silently reorders itself between offsets.
 *
 * It records what it was asked so a test can assert the client sent what it meant to.
 */
class MarketScenario(private val harness: MockHarness) {
    /** Every `speech/transcribe` call's request object, in order. */
    val transcriptions = mutableListOf<JsonObject>()

    /** Every `pluginMarket/search` call: the index URL and the query as it arrived. */
    val searches = mutableListOf<Pair<String, JsonObject>>()

    /** The index sources this host is configured with; `setIndexes` replaces it. */
    var indexes: MutableList<String> = mutableListOf(MARKET_URL)

    /** Recognition preferences the host would persist; `configure` patches them. */
    var selection = buildJsonObject { put("providerId", PROVIDER_LOCAL); put("language", "auto") }

    private fun JsonObject.string(key: String) = getValue(key).jsonPrimitive.content
    /** Reads a plain string member; a localized title is an object, which is not one, so it reads as absent. */
    private fun JsonElement.stringOrNull(key: String) =
        ((this as? JsonObject)?.get(key) as? JsonPrimitive)?.contentOrNull

    /** Every language of a localized member, either form: one plain string, or a map of language to string. */
    private fun JsonObject.localizedStrings(key: String): List<String> = when (val node = get(key)) {
        null, is JsonNull -> emptyList()
        is JsonPrimitive -> listOfNotNull(node.contentOrNull)
        is JsonObject -> node.values.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        else -> emptyList()
    }

    init {
        registerSpeech()
        registerMarket()
    }

    // ---------------------------------------------------------------- speech

    private fun registerSpeech() {
        harness.remote("speech", "catalog", emptySet()) {
            buildJsonObject {
                put("providers", JsonArray(listOf(providerValue(PROVIDER_LOCAL, "ready"), providerValue(PROVIDER_CLOUD, "ready"))))
                put("selection", selection)
                put("maxAudioBytes", 4 * 1024 * 1024)
                put("maxDurationSeconds", 120)
            }
        }
        // The argument is a single `request` object, decoded strictly on the host side.
        harness.requestRemote("speech", "transcribe", setOf("audioBase64"), optional = setOf("providerId", "language")) { request ->
            val encoded = request.string("audioBase64")
            if (encoded.length > MAX_ENCODED_CHARS) {
                throw MockHarness.RemoteFailure(
                    "speech/invalid-audio",
                    "Audio is invalid or exceeds the configured byte limit",
                    buildJsonObject { put("reason", "encoding-or-size") },
                )
            }
            // The host re-encodes and compares: only canonical base64 gets through.
            val bytes = runCatching { java.util.Base64.getDecoder().decode(encoded) }.getOrNull()
                ?: throw MockHarness.RemoteFailure(
                    "speech/transcription-failed",
                    "Audio is not valid base64",
                    buildJsonObject { put("reason", "decode") },
                )
            if (java.util.Base64.getEncoder().encodeToString(bytes) != encoded) {
                throw MockHarness.RemoteFailure(
                    "speech/transcription-failed",
                    "Audio is not canonical base64",
                    buildJsonObject { put("reason", "decode") },
                )
            }
            if (bytes.size < 46) {
                throw MockHarness.RemoteFailure(
                    "speech/invalid-audio",
                    "Audio is invalid or exceeds the configured byte limit",
                    buildJsonObject { put("reason", "encoding-or-size") },
                )
            }
            synchronized(transcriptions) { transcriptions += request }
            buildJsonObject {
                put("text", TRANSCRIPT)
                put("audioSeconds", 1.5)
                put("inferenceSeconds", 0.25)
            }
        }
        harness.remote("speech", "prepare", setOf("providerId")) { args ->
            // A host-local recognizer is prepared on demand; the fixture is always ready already.
            val providerId = args.string("providerId")
            if (providerId !in setOf(PROVIDER_LOCAL, PROVIDER_CLOUD)) {
                throw MockHarness.RemoteFailure("speech/provider-unknown", "No recognizer named $providerId")
            }
            JsonNull
        }
        harness.remote("speech", "cancelPreparation", setOf("providerId")) { JsonNull }
        // `configure` takes its argument as `patch`, not as `request`, so the outer key is matched
        // directly rather than unwrapped.
        harness.remote("speech", "configure", setOf("patch")) { args ->
            val patch = args.getValue("patch").jsonObject
            selection = JsonObject(selection + patch.filterValues { it !is JsonNull })
            JsonNull
        }
    }

    private fun providerValue(id: String, phase: String): JsonObject = buildJsonObject {
        put("id", id)
        put("name", if (id == PROVIDER_LOCAL) "On-device model" else "Cloud recognizer")
        put("location", if (id == PROVIDER_LOCAL) "host-local" else "cloud")
        put("languages", JsonArray(listOf(JsonPrimitive("auto"), JsonPrimitive("en"), JsonPrimitive("zh"))))
        putJsonObject("preparation") { put("phase", phase) }
    }

    // ---------------------------------------------------------------- plugin market

    private fun registerMarket() {
        harness.remote("pluginMarket", "listIndexes", emptySet()) {
            JsonArray(indexes.map { url ->
                buildJsonObject {
                    put("url", url)
                    // A localized title in the object form, which the client must accept as readily
                    // as the plain string one below.
                    putJsonObject("title") { put("en", "Fixture market"); put("zh-cn", "示例市场") }
                }
            })
        }
        harness.remote("pluginMarket", "search", setOf("url", "query")) { args ->
            val url = args.string("url")
            if (url !in indexes) throw MockHarness.RemoteFailure("plugin-market/unknown-index", "No index source is configured for $url")
            val query = args.getValue("query").jsonObject
            // The host's codec is strict and these three are not optional: a client that omits one
            // would be refused with gateway/arguments-invalid before any search ran.
            for (field in listOf("sort", "offset", "limit")) {
                if (field !in query.keys) {
                    throw MockHarness.RemoteFailure(
                        "plugin-market/invalid-query",
                        "query.$field is required",
                        buildJsonObject { put("field", field); put("reason", "missing") },
                    )
                }
            }
            val offset = query.getValue("offset").jsonPrimitive.int
            val limit = query.getValue("limit").jsonPrimitive.int
            if (offset < 0) throw MockHarness.RemoteFailure("plugin-market/invalid-query", "offset out of range",
                buildJsonObject { put("field", "offset"); put("reason", "range") })
            if (limit !in 1..100) throw MockHarness.RemoteFailure("plugin-market/invalid-query", "limit out of range",
                buildJsonObject { put("field", "limit"); put("reason", "range") })
            synchronized(searches) { searches += url to query }
            page(query, offset, limit)
        }
        harness.remote("pluginMarket", "setIndexes", setOf("urls")) { args ->
            val urls = args.getValue("urls").jsonArray.map { it.jsonPrimitive.content }
            for (candidate in urls) {
                if (!candidate.startsWith("http://") && !candidate.startsWith("https://")) {
                    throw MockHarness.RemoteFailure(
                        "plugin-market/invalid-index-url",
                        "Index sources must be absolute http(s) URLs",
                        buildJsonObject { put("reason", "scheme") },
                    )
                }
            }
            if (urls.size != urls.toSet().size) {
                throw MockHarness.RemoteFailure("plugin-market/invalid-index-url", "Index source listed twice")
            }
            indexes = urls.toMutableList()
            JsonNull
        }
    }

    private fun page(query: JsonObject, offset: Int, limit: Int): JsonObject {
        val text = query.stringOrNull("text").orEmpty().lowercase()
        val category = query.stringOrNull("category")
        val labels = query["labels"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
        val filtered = catalog.filter { candidate ->
            val entry = candidate.value
            val metadata = entry.getValue("metadata").jsonObject
            (text.isEmpty() || entry.getValue("name").jsonPrimitive.content.lowercase().contains(text) ||
                entry.localizedStrings("title").any { it.lowercase().contains(text) } ||
                metadata["keywords"]?.jsonArray?.any { it.jsonPrimitive.content.lowercase().contains(text) } == true) &&
                (category == null || metadata.stringOrNull("category") == category) &&
                labels.all { it in metadata["labels"]?.jsonArray?.map { label -> label.jsonPrimitive.content }.orEmpty() }
        }
        val sort = query.getValue("sort").jsonObject
        val sorted = when (sort.stringOrNull("kind")) {
            "published" -> if (sort.stringOrNull("direction") == "asc") {
                filtered.sortedBy { it.published }
            } else {
                filtered.sortedByDescending { it.published }
            }
            "relevance" -> if (text.isEmpty()) filtered else filtered.sortedByDescending { it.relevance(text) }
            else -> filtered
        }
        val window = sorted.drop(offset).take(limit)
        val next = offset + window.size
        return buildJsonObject {
            put("url", MARKET_URL)
            put("title", buildJsonObject { put("en", "Fixture market") })
            put("description", "Two plugins, one incompatible host, and every installation state")
            putJsonObject("metadata") {
                putJsonObject("categories") { putJsonObject("ui") { put("title", buildJsonObject { put("en", "Interface") }) } }
                putJsonObject("labels") { putJsonObject("experimental") { put("title", buildJsonObject { put("en", "Experimental") }) } }
                putJsonObject("sortKeys") { putJsonObject("downloads") { put("title", buildJsonObject { put("en", "Downloads") }); put("order", "desc") } }
            }
            put("fetchedAt", 1_700_000_000_000)
            put("entries", JsonArray(window.map { it.value }))
            put("total", sorted.size)
            put("nextOffset", if (next < sorted.size) JsonPrimitive(next) else JsonNull)
        }
    }

    /** One catalog row: the plugin, its versions with host-computed annotations, and the profile's state. */
    private class Entry(val published: Long, val value: JsonObject) {
        fun relevance(text: String) =
            value.getValue("name").jsonPrimitive.content.lowercase().split('-', '/').count { it.startsWith(text) }
    }

    private val catalog: List<Entry> = listOf(
        Entry(
            published = 1_700_000_100,
            value = entry(
                name = "dsh-cost-meter",
                title = JsonPrimitive("Cost meter"),
                description = JsonPrimitive("Spend per turn, straight from the billing history"),
                version = "1.9.33",
                published = 1_700_000_100,
                installation = "installed",
                installedVersion = "1.9.33",
                category = "ui",
                keywords = listOf("cost", "billing", "tokens"),
                labels = listOf("experimental"),
            ),
        ),
        Entry(
            published = 1_700_000_200,
            value = entry(
                name = "dsh-better-sidebar",
                title = buildJsonObject { put("en", "Better sidebar"); put("zh-cn", "更好的侧边栏") },
                description = buildJsonObject { put("en", "Session tree with groups and pins") },
                version = "0.28.0",
                published = 1_700_000_200,
                installation = "update-available",
                installedVersion = "0.27.1",
                category = "ui",
                keywords = listOf("sidebar", "sessions"),
            ),
        ),
        Entry(
            published = 1_700_000_300,
            value = entry(
                name = "@tt-a1i/archify-dsh",
                title = JsonPrimitive("Archify"),
                description = JsonPrimitive("Architecture diagrams from a description"),
                version = "0.3.0",
                published = 1_700_000_300,
                installation = "not-installed",
                category = "ui",
                keywords = listOf("diagram", "architecture"),
                compatible = false,
            ),
        ),
        Entry(
            published = 1_700_000_400,
            value = entry(
                name = "dsh-relay",
                title = JsonPrimitive("Relay"),
                description = JsonPrimitive("Reach this host from anywhere"),
                version = "0.2.1",
                published = 1_700_000_400,
                installation = "provided",
                installedVersion = "0.2.1",
            ),
        ),
    )

    private fun entry(
        name: String,
        title: JsonElement,
        description: JsonElement,
        version: String,
        published: Long,
        installation: String,
        installedVersion: String? = null,
        category: String? = null,
        keywords: List<String> = emptyList(),
        labels: List<String> = emptyList(),
        compatible: Boolean = true,
    ): JsonObject = buildJsonObject {
        put("name", name)
        put("title", title)
        put("description", description)
        put("author", "fixture")
        put("homepage", "https://example.invalid/$name")
        putJsonObject("metadata") {
            category?.let { put("category", it) }
            putJsonArray("keywords") { keywords.forEach { keyword -> add(keyword) } }
            putJsonArray("labels") { labels.forEach { label -> add(label) } }
            putJsonObject("sortKeys") { put("downloads", 100) }
        }
        putJsonArray("versions") {
            addJsonObject {
                put("version", version)
                put("publishTimestamp", published)
                putJsonObject("install") { put("spec", "$name@$version") }
                putJsonObject("compatibility") {
                    put("compatible", compatible)
                    if (!compatible) {
                        put("runtimeVersion", "0.2.1-alpha.1")
                        putJsonObject("requirements") { put("engines.dsh", ">=0.2.1-rc.5") }
                        put("exempted", false)
                    }
                }
                putJsonObject("engines") { put("dsh", ">=0.2.1-alpha.1") }
            }
        }
        if (compatible) put("defaultVersion", version)
        putJsonObject("installation") {
            put("status", installation)
            installedVersion?.let { put("installedVersion", it) }
        }
    }

    companion object {
        const val PROVIDER_LOCAL = "whisper-host"
        const val PROVIDER_CLOUD = "cloud-stt"
        const val MARKET_URL = "https://market.example.invalid/index.json"
        const val TRANSCRIPT = "Send the failing test output to the desktop session"

        /** The host's own limit, expressed the way it checks it: `ceil(bytes / 3) * 4`. */
        private const val MAX_ENCODED_CHARS = ((4 * 1024 * 1024 + 2) / 3) * 4

        /**
         * The `agentTeam` projection of a lead with two teammates and three tasks, one of them
         * blocked behind another, one owned and running, and one already complete.
         */
        fun agentTeamValue(): JsonObject = Json.parseToJsonElement(
            """
            {"members":[
              {"id":"lead-session","name":"lead","role":"lead","phase":"active"},
              {"id":"teammate-a","name":"sidebar-scout","role":"teammate","phase":"active"},
              {"id":"teammate-b","name":"test-writer","role":"teammate","phase":"provisioning"}
            ],"tasks":[
              {"id":"task-1","revision":3,"subject":"Map the sidebar package","description":"Read dsh-better-sidebar and list its slots","status":"completed","blockedBy":[],"writeScopes":[],"ownerName":"sidebar-scout","ready":true,"writeScopeWarnings":[]},
              {"id":"task-2","revision":1,"subject":"Port the session tree","description":"Move the tree into the mobile drawer","status":"in_progress","blockedBy":["task-1"],"writeScopes":["app/src/main/java/com/qgx1992/dshmobile/ui/screens/main"],"ownerName":"sidebar-scout","ready":true,"writeScopeWarnings":["Overlaps ChatListDrawer.kt"]},
              {"id":"task-3","revision":1,"subject":"Cover the fold with tests","description":"Add a projection decode test","status":"pending","blockedBy":["task-2"],"writeScopes":["app/src/test"],"ready":false,"writeScopeWarnings":[]}
            ]}
            """.trimIndent(),
        ).jsonObject
    }
}
