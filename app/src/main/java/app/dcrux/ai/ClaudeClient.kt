package app.dcrux.ai

import app.dcrux.data.AppJson
import app.dcrux.data.ModelInfo
import app.dcrux.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okio.ByteString.Companion.toByteString
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.coroutines.coroutineContext

class ClaudeException(val kind: Kind, message: String, val status: Int = 0, val retryAfterSec: Int = 0) : Exception(message) {
    enum class Kind {
        NO_KEY, AUTH, BILLING, PERMISSION, NOT_FOUND, RATE_LIMIT, OVERLOADED, BAD_REQUEST, TOO_LARGE,
        SERVER, NETWORK, REFUSAL, TRUNCATED, PARSE
    }

    /** Plain words for the UI. */
    val friendly: String
        get() = when (kind) {
            Kind.NO_KEY -> "No AI key is set."
            Kind.AUTH -> "The AI key was rejected. Check that it is valid and active."
            Kind.BILLING -> "The AI account has a billing problem (credits or payment)."
            Kind.PERMISSION -> "This API key is not allowed to do that."
            Kind.NOT_FOUND -> "That model is not available for this API key."
            // The service explains its own daily limits in plain words; pass those on as they are.
            Kind.RATE_LIMIT -> message?.takeIf { it.contains("tomorrow") } ?: "Rate limit reached. Try again in a little while."
            Kind.OVERLOADED -> "Crux AI is busy right now. Try again shortly."
            Kind.TOO_LARGE -> "This is too long to send in one request."
            Kind.SERVER -> "The AI service had a problem. Try again."
            Kind.NETWORK -> "No connection to the AI service. Check the internet and try again."
            Kind.REFUSAL -> "Crux AI declined to process this content."
            Kind.TRUNCATED -> "The answer was cut off before it finished. Try again."
            Kind.PARSE -> "The answer came back in an unexpected shape. Try again."
            Kind.BAD_REQUEST -> message ?: "The request was not accepted."
        }
}

data class Completion(val text: String, val model: String, val thinking: String = "", val sources: List<Pair<String, String>> = emptyList())

/** How to reach the DCrux service: its address, this install's id and the secret requests are signed with. */
class ServiceAuth(val baseUrl: String, val deviceId: String, val secret: ByteArray)

/**
 * Talks to the Claude Messages API directly over HTTPS from the phone.
 * (The official Java SDK depends on Jackson 2.19, which needs Android 8+; DCrux supports Android 7.)
 */
class ClaudeClient {
    private val json = "application/json".toMediaType()

    /** Set when this build uses the DCrux service; see [VIA_SERVICE]. */
    @Volatile var service: ServiceAuth? = null

    /**
     * Starts a request for [path]. With a real key it goes straight to the API. With [VIA_SERVICE]
     * it goes to the DCrux service, signed with this install's id, the time and a hash of [body],
     * so the service can tell the app from a stranger and count fair use per install.
     */
    private fun base(path: String, apiKey: String, beta: String?, body: ByteArray = ByteArray(0)): Request.Builder {
        val svc = service
        val builder = if (apiKey == VIA_SERVICE && svc != null) {
            val ts = System.currentTimeMillis().toString()
            val bodyHash = MessageDigest.getInstance("SHA-256").digest(body).joinToString("") { "%02x".format(it) }
            val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(svc.secret, "HmacSHA256")) }
            val sig = mac.doFinal("${svc.deviceId}.$ts.$bodyHash".toByteArray()).joinToString("") { "%02x".format(it) }
            Request.Builder().url(svc.baseUrl + path)
                .header("x-dcrux-device", svc.deviceId).header("x-dcrux-ts", ts).header("x-dcrux-sig", sig)
        } else {
            Request.Builder().url(BASE + path).header("x-api-key", apiKey)
        }
        return builder.header("anthropic-version", VERSION).apply { if (beta != null) header("anthropic-beta", beta) }
    }

    /**
     * Opens the connection to the AI service ahead of a question, so the first answer does not also
     * wait for the connection to be set up. It sends nothing about the user and its result is ignored.
     */
    suspend fun warm() = withContext(Dispatchers.IO) {
        runCatching {
            val url = service?.let { it.baseUrl + "/health" } ?: BASE
            Http.streaming.newCall(Request.Builder().url(url).head().build()).execute().close()
        }
        Unit
    }

    /** A signed GET to the DCrux service for something other than the AI (air quality). Null when there is no service or it has no answer. */
    suspend fun serviceGet(path: String): String? = withContext(Dispatchers.IO) {
        if (service == null) return@withContext null
        runCatching {
            Http.client.newCall(base(path, VIA_SERVICE, null).get().build()).execute().use { r -> if (r.isSuccessful) r.body.string() else null }
        }.getOrNull()
    }

    /**
     * A signed call to the DCrux service for something other than the AI (sync). Returns the status
     * and the reply, or null when this build has no service or it could not be reached.
     */
    suspend fun serviceCall(method: String, path: String, body: String? = null): Pair<Int, String>? = withContext(Dispatchers.IO) {
        if (service == null) return@withContext null
        runCatching {
            val bytes = body?.toByteArray(Charsets.UTF_8) ?: ByteArray(0)
            val req = base(path, VIA_SERVICE, null, bytes).method(method, if (body != null) bytes.toRequestBody(json) else null).build()
            val call = Http.streaming.newCall(req)
            // A waiting call (sync's "tell me when something changes") is dropped at once when it is no longer wanted.
            val handle = coroutineContext.job.invokeOnCompletion { if (it != null) call.cancel() }
            try { call.execute().use { r -> r.code to r.body.string() } } finally { handle.dispose() }
        }.getOrNull()
    }

    /** Lists the models this key can use, with the capabilities DCrux adapts to. Doubles as the key test. */
    suspend fun listModels(apiKey: String): List<ModelInfo> = withContext(Dispatchers.IO) {
        val out = mutableListOf<ModelInfo>()
        var after: String? = null
        var beta: String? = FALLBACK_BETA
        while (true) {
            val path = "/v1/models?limit=100" + (after?.let { "&after_id=$it" } ?: "")
            val res = execute(base(path, apiKey, beta).get().build())
            val body = res.use { r ->
                val text = r.body.string()
                if (r.code == 400 && beta != null) null
                else if (!r.isSuccessful) throw httpError(r, text)
                else text
            }
            if (body == null) { beta = null; continue }
            val root = AppJson.parseToJsonElement(body).jsonObject
            for (el in root["data"]?.jsonArray.orEmpty()) {
                val m = el.jsonObject
                val id = m["id"]?.jsonPrimitive?.content ?: continue
                val caps = m["capabilities"] as? JsonObject
                val effort = caps?.get("effort") as? JsonObject
                out += ModelInfo(
                    id = id,
                    name = m["display_name"]?.jsonPrimitive?.content ?: id,
                    maxTokens = m["max_tokens"]?.jsonPrimitive?.intOrNull ?: 16000,
                    structured = (caps?.get("structured_outputs") as? JsonObject)?.get("supported")?.jsonPrimitive?.booleanOrNull ?: false,
                    efforts = EFFORTS.filter { (effort?.get(it) as? JsonObject)?.get("supported")?.jsonPrimitive?.booleanOrNull == true },
                    fallbacks = (m["allowed_fallback_models"] as? kotlinx.serialization.json.JsonArray)?.isNotEmpty() == true,
                )
            }
            after = if (root["has_more"]?.jsonPrimitive?.booleanOrNull == true) root["last_id"]?.jsonPrimitive?.content else null
            if (after == null) break
        }
        out
    }

    /**
     * One streamed request. [schema] constrains the answer to JSON on models that support
     * structured outputs; [onText] receives the text so far as it grows.
     */
    suspend fun complete(
        apiKey: String,
        model: ModelInfo,
        system: String,
        user: String,
        schema: JsonObject? = null,
        effort: String = "low",
        maxTokens: Int = 16000,
        showThinking: Boolean = false,
        /** JPEG pictures to show the model ahead of the text (photos of a handwritten answer). */
        images: List<ByteArray> = emptyList(),
        /**
         * A long piece of source text (captions, an article) sent ahead of [user] and marked for the API's
         * prompt cache, so a second request about the same text starts faster and costs less.
         */
        context: String? = null,
        /** Lets the model look things up on the web (a few searches at most) when the material does not hold the answer. */
        webSearch: Boolean = false,
        onSearching: () -> Unit = {},
        onThinking: (String) -> Unit = {},
        onText: (String) -> Unit = {},
    ): Completion = withContext(Dispatchers.IO) {
        val useSchema = schema != null && model.structured
        val prompt = if (schema != null && !useSchema) "$user\n\nReply with one JSON object only, matching this JSON Schema:\n$schema" else user
        val body = buildJsonObject {
            put("model", model.id)
            put("max_tokens", minOf(maxTokens, model.maxTokens))
            put("stream", true)
            put("system", system)
            put("messages", buildJsonArray {
                add(buildJsonObject {
                    put("role", "user")
                    if (images.isEmpty() && context.isNullOrBlank()) put("content", prompt)
                    else put("content", buildJsonArray {
                        if (!context.isNullOrBlank()) add(buildJsonObject {
                            put("type", "text"); put("text", context)
                            putJsonObject("cache_control") { put("type", "ephemeral") }
                        })
                        images.forEach { jpeg ->
                            add(buildJsonObject {
                                put("type", "image")
                                putJsonObject("source") {
                                    put("type", "base64")
                                    put("media_type", "image/jpeg")
                                    put("data", jpeg.toByteString().base64())
                                }
                            })
                        }
                        add(buildJsonObject { put("type", "text"); put("text", prompt) })
                    })
                })
            })
            if (useSchema || effort in model.efforts) {
                putJsonObject("output_config") {
                    if (effort in model.efforts) put("effort", effort)
                    if (useSchema) putJsonObject("format") {
                        put("type", "json_schema")
                        put("schema", schema!!)
                    }
                }
            }
            // Ask for the model's own summary of its reasoning (it is never the raw reasoning, and only
            // the current models offer it; older ones would reject the setting).
            if (showThinking && ADAPTIVE.containsMatchIn(model.id)) putJsonObject("thinking") {
                put("type", "adaptive")
                put("display", "summarized")
            }
            if (webSearch) put("tools", buildJsonArray {
                add(buildJsonObject { put("type", WEB_SEARCH); put("name", "web_search"); put("max_uses", 3) })
            })
            // If a safety classifier declines, let the API retry on its recommended model.
            if (model.fallbacks) put("fallbacks", "default")
        }
        val bytes = body.toString().toByteArray(Charsets.UTF_8)
        val req = base("/v1/messages", apiKey, if (model.fallbacks) FALLBACK_BETA else null, bytes)
            .post(bytes.toRequestBody(json)).build()
        val call = Http.streaming.newCall(req)
        val handle = coroutineContext.job.invokeOnCompletion { if (it != null) call.cancel() }
        try {
            val res = try { call.execute() } catch (e: IOException) {
                coroutineContext.ensureActive()
                throw ClaudeException(ClaudeException.Kind.NETWORK, e.message ?: "network")
            }
            res.use { r ->
                if (!r.isSuccessful) throw httpError(r, r.body.string())
                val sb = StringBuilder()
                val thought = StringBuilder()
                val sources = LinkedHashMap<String, String>()
                var stop: String? = null
                var served = model.id
                val src = r.body.source()
                try {
                    while (true) {
                        val line = src.readUtf8Line() ?: break
                        if (!line.startsWith("data:")) continue
                        val ev = runCatching { AppJson.parseToJsonElement(line.substring(5).trim()).jsonObject }.getOrNull() ?: continue
                        when (ev["type"]?.jsonPrimitive?.content) {
                            "message_start" -> ev["message"]?.jsonObject?.get("model")?.jsonPrimitive?.content?.let { served = it }
                            "content_block_start" -> {
                                // A fallback block marks a switch of model: drop the declined model's partial text.
                                if (ev["content_block"]?.jsonObject?.get("type")?.jsonPrimitive?.content == "server_tool_use") onSearching()
                                if (ev["content_block"]?.jsonObject?.get("type")?.jsonPrimitive?.content == "fallback") {
                                    sb.setLength(0)
                                    ev["content_block"]?.jsonObject?.get("to")?.jsonObject?.get("model")?.jsonPrimitive?.content?.let { served = it }
                                }
                            }
                            "content_block_delta" -> {
                                val d = ev["delta"]?.jsonObject
                                when (d?.get("type")?.jsonPrimitive?.content) {
                                    "text_delta" -> {
                                        sb.append(d["text"]?.jsonPrimitive?.content.orEmpty())
                                        onText(sb.toString())
                                    }
                                    // A web page the sentence just written rests on.
                                    "citations_delta" -> (d["citation"] as? JsonObject)?.let { c ->
                                        val url = c["url"]?.jsonPrimitive?.content.orEmpty()
                                        if (url.startsWith("http") && sources.size < 6) sources.getOrPut(url) { c["title"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: url }
                                    }
                                    "thinking_delta" -> {
                                        thought.append(d["thinking"]?.jsonPrimitive?.content.orEmpty())
                                        if (thought.isNotBlank()) onThinking(thought.toString())
                                    }
                                }
                            }
                            "message_delta" -> ev["delta"]?.jsonObject?.get("stop_reason")?.jsonPrimitive?.let { if (it.isString) stop = it.content }
                            "error" -> {
                                val err = ev["error"]?.jsonObject
                                throw typeError(err?.get("type")?.jsonPrimitive?.content.orEmpty(), err?.get("message")?.jsonPrimitive?.content ?: "error", 0, 0)
                            }
                        }
                    }
                } catch (e: IOException) {
                    coroutineContext.ensureActive()
                    throw ClaudeException(ClaudeException.Kind.NETWORK, e.message ?: "stream interrupted")
                }
                when (stop) {
                    "refusal" -> throw ClaudeException(ClaudeException.Kind.REFUSAL, "refusal")
                    "max_tokens" -> throw ClaudeException(ClaudeException.Kind.TRUNCATED, "max_tokens")
                    null -> throw ClaudeException(ClaudeException.Kind.NETWORK, "The stream ended early")
                }
                Completion(sb.toString(), served, thought.toString().trim(), sources.map { it.value to it.key })
            }
        } finally {
            handle.dispose()
        }
    }

    private fun execute(req: Request): Response = try {
        Http.client.newCall(req).execute()
    } catch (e: IOException) {
        throw ClaudeException(ClaudeException.Kind.NETWORK, e.message ?: "network")
    }

    private fun httpError(r: Response, body: String): ClaudeException {
        val err = runCatching { AppJson.parseToJsonElement(body).jsonObject["error"]?.jsonObject }.getOrNull()
        val type = err?.get("type")?.jsonPrimitive?.content.orEmpty()
        val msg = err?.get("message")?.jsonPrimitive?.content ?: "HTTP ${r.code}"
        return typeError(type, msg, r.code, r.header("retry-after")?.toIntOrNull() ?: 0)
    }

    private fun typeError(type: String, msg: String, status: Int, retryAfter: Int): ClaudeException {
        val kind = when {
            status == 401 || type == "authentication_error" -> ClaudeException.Kind.AUTH
            status == 402 || type == "billing_error" -> ClaudeException.Kind.BILLING
            status == 403 || type == "permission_error" -> ClaudeException.Kind.PERMISSION
            status == 404 || type == "not_found_error" -> ClaudeException.Kind.NOT_FOUND
            status == 413 || type == "request_too_large" -> ClaudeException.Kind.TOO_LARGE
            status == 429 || type == "rate_limit_error" -> ClaudeException.Kind.RATE_LIMIT
            status == 529 || type == "overloaded_error" -> ClaudeException.Kind.OVERLOADED
            status == 400 || type == "invalid_request_error" -> ClaudeException.Kind.BAD_REQUEST
            else -> ClaudeException.Kind.SERVER
        }
        return ClaudeException(kind, msg, status, retryAfter)
    }

    companion object {
        const val BASE = "https://api.anthropic.com"

        /** Stands in for a key when requests go through the DCrux service. */
        const val VIA_SERVICE = "via-dcrux-service"
        const val VERSION = "2026-10-21"
        const val FALLBACK_BETA = "server-side-fallback-2026-07-01"
        const val WEB_SEARCH = "web_search_20250305"
        val EFFORTS = listOf("low", "medium", "high", "xhigh", "max")

        /** Models that take adaptive thinking with a summary. */
        private val ADAPTIVE = Regex("^claude-(fable|mythos|opus-5|opus-4-[678]|sonnet-5|sonnet-4-6|haiku-5)")
    }
}
