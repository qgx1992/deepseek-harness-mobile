package com.qgx1992.dshmobile.mockharness

import kotlinx.serialization.json.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Fixtures for the configuration plane: `settings/describe|update|mutate` beside
 * `credentials/describe|set|unset`.
 *
 * The schemas below are schemastery `toJSON()` trees — the shape the host really sends: a root
 * `object` whose `dict` maps each key to its own node, every node carrying a `type` and a `meta`
 * object with `label`/`description`/`role`/`options`. Three fields are deliberate awkward cases a
 * client must survive rather than assume away:
 *
 *  - `webSearch.apiKey` is `role: "secret"`, so its value never crosses the wire; the namespace
 *    view carries only `{path, set}` in `secrets[]`, and writing it is `credentials/set`'s job;
 *  - `webSearch.hostAllowlist` is an `array`, which has no scalar editor — a form has to degrade
 *    it to a read-only line instead of guessing at rows;
 *  - `llm.effort` is a `union` of two `const` branches, which is an enum wearing a union's clothes.
 *
 * Revisions are per namespace and bumped by every accepted write, which is what makes
 * `settings/conflict` reachable: [bumpRevision] plays the second writer, and a client that sends a
 * stale `expectedRevision` is refused the way the host refuses it.
 */
class SettingsScenario(private val harness: MockHarness) {
    /** Namespaces this fixture serves, in `describe` order. */
    val namespaces = listOf(WEB_SEARCH, AGENT, LLM)

    /**
     * Every reference a write named, in call order.
     *
     * The values themselves are deliberately not recorded: a fixture that kept them would tempt a
     * test into asserting on the secret instead of on the fact that only its reference crossed.
     */
    val credentialRefsWritten = mutableListOf<String>()

    /** Set to a message and the next `settings/mutate` answers `settings/rejected` with it. */
    @Volatile
    var rejectNextMutate: String? = null

    /** What `describe` reports as `writable`; false is the read-only desktop. */
    @Volatile
    var writable: Boolean = true

    private val credentials = ConcurrentHashMap<String, String>()
    private val values = ConcurrentHashMap<String, JsonObject>()
    private val revisions = ConcurrentHashMap<String, Long>()

    private data class SecretSlot(val path: List<String>, val ref: String)

    private val secretSlots: Map<String, List<SecretSlot>> = namespaces.associateWith { ns ->
        val out = mutableListOf<SecretSlot>()
        collectSecrets(schemas.getValue(ns), emptyList(), out)
        out
    }

    init {
        for (ns in namespaces) {
            values[ns] = DEFAULT_VALUES.getValue(ns)
            revisions[ns] = 1L
        }
        registerSettings()
        registerCredentials()
    }

    // ------------------------------------------------------------------ inspection helpers

    fun revision(ns: String): Long = revisions.getValue(ns)

    fun value(ns: String): JsonObject = values.getValue(ns)

    /** Plays the other writer: the namespace's revision moves without its value changing. */
    fun bumpRevision(ns: String) {
        revisions[ns] = revisions.getValue(ns) + 1
    }

    fun seedCredential(ref: String, value: String) {
        credentials[ref] = value
    }

    /** The stored credential, for a test that wants to prove the write landed. */
    fun credentialValue(ref: String): String? = credentials[ref]

    /** The reference the schema declares for one secret slot. */
    fun declaredSecretRef(ns: String, path: List<String>): String? =
        secretSlots[ns]?.firstOrNull { it.path == path }?.ref

    // ------------------------------------------------------------------ registration

    private fun registerSettings() {
        harness.remote("settings", "describe", emptySet()) { describe() }

        harness.remote("settings", "update", setOf("ns", "patch", "expectedRevision")) { args ->
            val ns = knownNamespace(args.string("ns"))
            val patch = args["patch"]?.jsonObject ?: JsonObject(emptyMap())
            synchronized(this) {
                checkRevision(ns, args)
                values[ns] = JsonObject(values.getValue(ns).toMutableMap().apply { putAll(patch) })
                revisions[ns] = revisions.getValue(ns) + 1
                view(ns)
            }
        }

        harness.remote("settings", "mutate", setOf("ns", "ops", "expectedRevision")) { args ->
            val ns = knownNamespace(args.string("ns"))
            val ops = args["ops"]?.jsonArray ?: JsonArray(emptyList())
            synchronized(this) {
                rejectNextMutate?.let { message ->
                    rejectNextMutate = null
                    throw MockHarness.RemoteFailure("settings/rejected", message, buildJsonObject { put("ns", ns) })
                }
                checkRevision(ns, args)
                var next = values.getValue(ns)
                for (element in ops) {
                    val op = element.jsonObject
                    val path = (op["path"] as? JsonArray)?.map { it.jsonPrimitive.content } ?: emptyList()
                    if (path.isEmpty()) throw MockHarness.RemoteFailure("settings/rejected", "an op needs a path", buildJsonObject { put("ns", ns) })
                    next = when (op.string("op")) {
                        "set" -> write(next, path, op["value"] ?: JsonNull)
                        "unset" -> remove(next, path)
                        else -> throw MockHarness.RemoteFailure("settings/rejected", "unsupported op \"${op.string("op")}\"", buildJsonObject { put("ns", ns) })
                    }
                }
                values[ns] = next
                revisions[ns] = revisions.getValue(ns) + 1
                view(ns)
            }
        }
    }

    private fun registerCredentials() {
        harness.remote("credentials", "describe", setOf("refs")) { args ->
            val refs = (args["refs"] as? JsonArray)?.map { it.jsonPrimitive.content } ?: emptyList()
            buildJsonObject {
                for (ref in refs) putJsonObject(ref) {
                    put("configured", credentials.containsKey(ref))
                    put("writable", true)
                }
            }
        }

        harness.remote("credentials", "set", setOf("ref", "value")) { args ->
            val ref = args.string("ref")
            val value = args.string("value")
            if (!REF_PATTERN.matches(ref)) {
                // The message names the reference and never the value: a refusal travels through
                // logs and banners, and the one thing it may not carry is what was being written.
                throw MockHarness.RemoteFailure("credential/rejected", "\"$ref\" is not a credential reference", buildJsonObject { put("ref", ref) })
            }
            if (value.isEmpty()) {
                throw MockHarness.RemoteFailure("credential/rejected", "a credential value cannot be empty", buildJsonObject { put("ref", ref) })
            }
            synchronized(this) {
                credentials[ref] = value
                credentialRefsWritten += ref
            }
            JsonObject(emptyMap())
        }

        harness.remote("credentials", "unset", setOf("ref")) { args ->
            val ref = args.string("ref")
            if (!REF_PATTERN.matches(ref)) {
                throw MockHarness.RemoteFailure("credential/rejected", "\"$ref\" is not a credential reference", buildJsonObject { put("ref", ref) })
            }
            synchronized(this) { credentials.remove(ref) }
            JsonObject(emptyMap())
        }
    }

    // ------------------------------------------------------------------ wire shapes

    private fun describe(): JsonObject = synchronized(this) {
        buildJsonObject {
            put("writable", writable)
            put("hasDocument", true)
            putJsonArray("namespaces") { for (ns in namespaces) add(view(ns)) }
        }
    }

    /** One `namespaceView`: the redacted effective value plus the secret sidecar. */
    private fun view(ns: String): JsonObject = buildJsonObject {
        put("ns", ns)
        put("schema", schemas.getValue(ns))
        put("value", values.getValue(ns))
        put("applies", "live")
        putJsonArray("secrets") {
            for (slot in secretSlots.getValue(ns)) add(buildJsonObject {
                putJsonArray("path") { for (segment in slot.path) add(JsonPrimitive(segment)) }
                put("set", credentials.containsKey(slot.ref))
            })
        }
        put("revision", revisions.getValue(ns))
    }

    // ------------------------------------------------------------------ helpers

    private fun knownNamespace(ns: String): String =
        if (values.containsKey(ns)) ns else throw MockHarness.RemoteFailure("settings/rejected", "no settings namespace \"$ns\"", JsonObject(emptyMap()))

    private fun checkRevision(ns: String, args: JsonObject) {
        val expected = args["expectedRevision"]?.jsonPrimitive?.longOrNull
            ?: throw MockHarness.RemoteFailure("settings/rejected", "settings/$ns requires an expected revision", buildJsonObject { put("ns", ns) })
        val actual = revisions.getValue(ns)
        if (expected != actual) throw MockHarness.RemoteFailure(
            "settings/conflict",
            "settings namespace \"$ns\" moved from revision $expected to $actual",
            buildJsonObject { put("ns", ns); put("expected", expected); put("actual", actual) },
        )
    }

    private fun write(node: JsonObject, path: List<String>, value: JsonElement): JsonObject {
        val head = path.first()
        return if (path.size == 1) JsonObject(node + (head to value))
        else JsonObject(node + (head to write(node[head] as? JsonObject ?: JsonObject(emptyMap()), path.drop(1), value)))
    }

    private fun remove(node: JsonObject, path: List<String>): JsonObject {
        val head = path.first()
        return if (path.size == 1) JsonObject(node - head)
        else JsonObject(node + (head to remove(node[head] as? JsonObject ?: JsonObject(emptyMap()), path.drop(1))))
    }

    private fun collectSecrets(node: JsonElement, path: List<String>, out: MutableList<SecretSlot>) {
        val obj = node as? JsonObject ?: return
        val meta = obj["meta"] as? JsonObject
        if (meta?.get("role")?.jsonPrimitive?.contentOrNull == "secret") {
            out += SecretSlot(path, secretRefOf(path, meta))
            return
        }
        when (obj["type"]?.jsonPrimitive?.contentOrNull) {
            "object" -> (obj["dict"] as? JsonObject)?.forEach { (key, child) -> collectSecrets(child, path + key, out) }
            "transform" -> obj["inner"]?.let { collectSecrets(it, path, out) }
        }
    }

    /** The reference a secret slot resolves through: declared by the schema, else named after its path. */
    private fun secretRefOf(path: List<String>, meta: JsonObject): String {
        val declared = REF_META_KEYS.firstNotNullOfOrNull { key -> meta[key]?.jsonPrimitive?.contentOrNull?.takeIf { REF_PATTERN.matches(it) } }
        return declared ?: credentialNameOf(path)
    }

    private fun credentialNameOf(path: List<String>): String {
        val joined = path.joinToString("_").map { if (it.isLetterOrDigit() || it == '_') it else '_' }.joinToString("")
        val trimmed = joined.trim('_')
        val prefixed = if (trimmed.isEmpty() || trimmed.first().isDigit()) "SETTINGS_$trimmed" else trimmed
        return prefixed.uppercase()
    }

    private fun JsonObject.string(key: String) = getValue(key).jsonPrimitive.content

    companion object {
        const val WEB_SEARCH = "webSearch"
        const val AGENT = "agent"
        const val LLM = "llm"

        /** The secret slot's declared reference, which a client must send to `credentials/set`. */
        const val API_KEY_REF = "WEB_SEARCH_API_KEY"

        /** What a write to the secret slot must leave behind, for a test that checks the store. */
        const val API_KEY_VALUE = "sk-mock-not-a-real-key"

        private val REF_PATTERN = Regex("^[A-Za-z_][A-Za-z0-9_]*$")

        /** Meta keys through which a schema may name its own credential reference. */
        private val REF_META_KEYS = listOf("credentialsRef", "credentialRef", "ref", "env")

        private fun json(text: String): JsonElement = Json.parseToJsonElement(text)

        private val schemas: Map<String, JsonObject> = mapOf(
            WEB_SEARCH to json(
                """
                {"type":"object","meta":{"description":"Web search"},"dict":{
                  "enabled":{"type":"boolean","meta":{"label":"Enabled","default":true}},
                  "provider":{"type":"string","meta":{"label":"Provider","options":[
                    {"value":"duckduckgo","label":"DuckDuckGo"},
                    {"value":"brave","label":"Brave"}]}},
                  "apiKey":{"type":"string","meta":{"label":"API key","role":"secret","credentialsRef":"$API_KEY_REF"}},
                  "maxResults":{"type":"number","meta":{"label":"Max results","default":5}},
                  "userAgent":{"type":"string","meta":{"label":"User agent","description":"Sent with every request"}},
                  "retry":{"type":"object","meta":{"label":"Retry"},"dict":{
                    "attempts":{"type":"natural","meta":{"label":"Attempts"}},
                    "backoffMs":{"type":"number","meta":{"label":"Backoff (ms)"}}}},
                  "hostAllowlist":{"type":"array","inner":{"type":"string"},"meta":{"label":"Host allowlist"}}
                }}
                """.trimIndent(),
            ).jsonObject,
            AGENT to json(
                """
                {"type":"object","meta":{"description":"Agent runtime"},"dict":{
                  "maxParallelSubagents":{"type":"natural","meta":{"label":"Max parallel subagents","default":4}},
                  "shellTimeoutMs":{"type":"natural","meta":{"label":"Shell timeout (ms)","default":120000}},
                  "autoApprove":{"type":"boolean","meta":{"label":"Auto approve"}}
                }}
                """.trimIndent(),
            ).jsonObject,
            LLM to json(
                """
                {"type":"object","meta":{"description":"Model routes"},"dict":{
                  "model":{"type":"string","meta":{"label":"Model","options":[
                    {"value":"deepseek-chat"},{"value":"deepseek-reasoner"}]}},
                  "temperature":{"type":"number","meta":{"label":"Temperature","default":0.7}},
                  "effort":{"type":"union","meta":{"label":"Effort"},"list":[
                    {"type":"const","value":"standard"},{"type":"const","value":"high"}]}
                }}
                """.trimIndent(),
            ).jsonObject,
        )

        private val DEFAULT_VALUES: Map<String, JsonObject> = mapOf(
            // No `apiKey` member: a redacted namespace view omits the secret entirely.
            WEB_SEARCH to buildJsonObject {
                put("enabled", true)
                put("provider", "duckduckgo")
                put("maxResults", 5)
                put("userAgent", "")
                putJsonObject("retry") { put("attempts", 2); put("backoffMs", 250) }
                putJsonArray("hostAllowlist") { add(JsonPrimitive("example.com")) }
            },
            AGENT to buildJsonObject {
                put("maxParallelSubagents", 4)
                put("shellTimeoutMs", 120000)
                put("autoApprove", false)
            },
            LLM to buildJsonObject {
                put("model", "deepseek-chat")
                put("temperature", 0.7)
                put("effort", "standard")
            },
        )
    }
}
