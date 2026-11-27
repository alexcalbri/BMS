package com.ideasdeveloper.bms.server.assistant

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.jsonMapper
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.helpers.BetaMessageAccumulator
import com.anthropic.models.beta.AnthropicBeta
import com.anthropic.models.beta.messages.BetaCacheControlEphemeral
import com.anthropic.models.beta.messages.BetaCompact20260112Edit
import com.anthropic.models.beta.messages.BetaContextManagementConfig
import com.anthropic.models.beta.messages.BetaInputTokensTrigger
import com.anthropic.models.beta.messages.BetaMessage
import com.anthropic.models.beta.messages.BetaMessageParam
import com.anthropic.models.beta.messages.BetaOutputConfig
import com.anthropic.models.beta.messages.BetaTextBlockParam
import com.anthropic.models.beta.messages.BetaThinkingBlockBinding
import com.anthropic.models.beta.messages.BetaThinkingConfigAdaptive
import com.anthropic.models.beta.messages.BetaThinkingPrefixMismatchBehavior
import com.anthropic.models.beta.messages.BetaTool
import com.anthropic.models.beta.messages.BetaToolChoiceAuto
import com.anthropic.models.beta.messages.MessageCreateParams
import com.ideasdeveloper.bms.AssistantIds
import com.ideasdeveloper.bms.server.i18n.tr
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.time.Duration

/**
 * The Claude API behind a small interface, so the turn engine and its tests never need the network (GLOBAL_AGENT.md,
 * "Claude API rules"). [AnthropicModelClients] is the real one: the official Java SDK, one client per API key, the key
 * passed explicitly (never read from the process environment), streaming only.
 */
interface AssistantModelClient {
    /** Streams one Messages request; text deltas go to [listener]; returns the complete message. */
    fun send(params: MessageCreateParams, listener: ModelStreamListener): BetaMessage
}

/** One [AssistantModelClient] per API key (the server's, or a company's own). */
fun interface AssistantModelClients {
    fun forKey(apiKey: String): AssistantModelClient
}

interface ModelStreamListener {
    fun text(delta: String)

    /** True once the user closed the stream: the client stops reading and throws [AssistantCancelledException]. */
    val cancelled: Boolean
}

/** A failed model request, already classified; [message] is for people and never carries the key or the request. */
class AssistantModelException(
    val code: String,
    val retryable: Boolean,
    message: String,
    // What the provider already reported for this request (message_start / message_delta), to be billed.
    val usage: ModelUsage? = null,
) : Exception(message) {
    fun withUsage(usage: ModelUsage?) = AssistantModelException(code, retryable, message.orEmpty(), usage)
}

/** The user closed the stream while the model was answering; [usage] is what the provider reported so far (or null). */
class AssistantCancelledException(val usage: ModelUsage? = null) : Exception("cancelled")

/** One tool as the model sees it. */
class ModelTool(val name: String, val description: String, val inputSchema: JsonObject)

/** One stored message as it is sent again: role and content blocks, unchanged. */
class ModelTurn(val role: String, val content: JsonArray)

/** Everything one request needs, independent of the SDK. */
class ModelRequest(
    val model: String,
    val effort: String,
    val maxTokens: Long,
    // The stable, cached prefix (no dates, no ids): who the assistant works for and the rules.
    val system: String,
    // Sorted by name.
    val tools: List<ModelTool>,
    val messages: List<ModelTurn>,
)

/** What the engine needs from a model response. [content] is the assistant message to store and send again. */
class ModelResponse(
    val stopReason: String?,
    val content: JsonArray,
    val usage: ModelUsage,
    val model: String,
    val refusalCategory: String?,
    val fallbackUsed: Boolean,
    val compacted: Boolean,
)

data class ModelUsage(
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val cacheReadInputTokens: Long = 0,
    val cacheCreationInputTokens: Long = 0,
) {
    /** What counts for the monthly limit: input + cache writes + output + cache reads at their ~1/10 price. */
    val countedTokens: Long get() = inputTokens + cacheCreationInputTokens + outputTokens + (cacheReadInputTokens + 9) / 10

    operator fun plus(other: ModelUsage) = ModelUsage(
        inputTokens + other.inputTokens, outputTokens + other.outputTokens,
        cacheReadInputTokens + other.cacheReadInputTokens, cacheCreationInputTokens + other.cacheCreationInputTokens,
    )
}

/** Per model id, what the request may carry (one place, with the allowlist in AssistantIds.Models). */
object AssistantModels {
    const val MaxTokens = 32_000L
    // Server-side compaction trigger (input tokens of a request).
    const val CompactionTrigger = 150_000L

    fun allowed(model: String): Boolean = model in AssistantIds.Models

    // Server-side refusal fallback (beta server-side-fallback-2026-07-01, "default" mode): not for Claude Haiku 5.5,
    // which has no fallback model.
    fun fallbacks(model: String): Boolean = model != "claude-haiku-5-5"

    // Server-side compaction (beta compact-2026-01-12): documented for the three models of the allowlist (the reference
    // lists threshold and on-demand compaction among what is new on Claude Haiku 5.5).
    fun compaction(model: String): Boolean = allowed(model)
}

/** Builds the SDK request: the only place that decides the request shape. */
object AnthropicRequests {
    fun build(request: ModelRequest): MessageCreateParams {
        val mapper = jsonMapper()
        val builder = MessageCreateParams.builder()
            .model(request.model)
            .maxTokens(request.maxTokens)
            // Adaptive thinking (always on for these models); thinking blocks replayed after a change of tools or
            // system prompt (a permission granted or removed) are dropped by the API instead of failing the request.
            .thinking(
                BetaThinkingConfigAdaptive.builder()
                    .blockBinding(BetaThinkingBlockBinding.builder().prefixMismatchBehavior(BetaThinkingPrefixMismatchBehavior.DROP_BLOCK).build())
                    .build(),
            )
            .addBeta(AnthropicBeta.THINKING_BINDING_CONTROLS_2026_08_01)
            .outputConfig(BetaOutputConfig.builder().effort(BetaOutputConfig.Effort.of(request.effort)).build())
            // Explicit breakpoint at the end of the stable prefix (tools render first, then system), plus automatic
            // caching of the growing conversation.
            .systemOfBetaTextBlockParams(
                listOf(BetaTextBlockParam.builder().text(request.system).cacheControl(BetaCacheControlEphemeral.builder().build()).build()),
            )
            .cacheControl(BetaCacheControlEphemeral.builder().build())
        if (request.tools.isNotEmpty()) {
            request.tools.sortedBy { it.name }.forEach { tool ->
                builder.addTool(
                    BetaTool.builder()
                        .name(tool.name)
                        .description(tool.description)
                        .inputSchema(mapper.readValue(tool.inputSchema.toString(), BetaTool.InputSchema::class.java))
                        .build(),
                )
            }
            builder.toolChoice(BetaToolChoiceAuto.builder().build())
        }
        builder.messages(
            request.messages.map { turn ->
                val json = buildJsonObject {
                    put("role", turn.role)
                    put("content", turn.content)
                }
                mapper.readValue(json.toString(), BetaMessageParam::class.java)
            },
        )
        if (AssistantModels.fallbacks(request.model)) {
            builder.fallbacksDefault()
            builder.addBeta(AnthropicBeta.SERVER_SIDE_FALLBACK_2026_07_01)
        }
        if (AssistantModels.compaction(request.model)) {
            builder.contextManagement(
                BetaContextManagementConfig.builder()
                    .addEdit(BetaCompact20260112Edit.builder().trigger(BetaInputTokensTrigger.builder().value(AssistantModels.CompactionTrigger).build()).build())
                    .build(),
            )
            builder.addBeta(AnthropicBeta.COMPACT_2026_01_12)
        }
        return builder.build()
    }

    /** The request body as JSON (tests and diagnostics; never logged). */
    fun bodyJson(params: MessageCreateParams): JsonObject = Json.parseToJsonElement(jsonMapper().writeValueAsString(params._body())).jsonObject
}

/** Reads what the engine needs from a complete SDK message. */
object AnthropicResponses {
    private val dropBeforeFallback = setOf("thinking", "redacted_thinking", "tool_use", "server_tool_use")

    fun from(message: BetaMessage): ModelResponse {
        val param = Json.parseToJsonElement(jsonMapper().writeValueAsString(message.toParam())).jsonObject
        val content = when (val raw = param["content"]) {
            is JsonArray -> raw
            is JsonPrimitive -> JsonArray(listOf(buildJsonObject { put("type", "text"); put("text", raw.content) }))
            else -> JsonArray(emptyList())
        }
        val usage = message.usage()
        val iterations = usage.iterations().orElse(emptyList())
        val total = if (iterations.isNotEmpty()) {
            iterations.fold(ModelUsage()) { sum, it ->
                sum + ModelUsage(it.inputTokens(), it.outputTokens(), it.cacheReadInputTokens(), it.cacheCreationInputTokens())
            }
        } else {
            ModelUsage(
                usage.inputTokens(), usage.outputTokens(),
                usage.cacheReadInputTokens().orElse(0L), usage.cacheCreationInputTokens().orElse(0L),
            )
        }
        val types = content.mapNotNull { ((it as? JsonObject)?.get("type") as? JsonPrimitive)?.contentOrNull }
        return ModelResponse(
            stopReason = message.stopReason().map { it.asString() }.orElse(null),
            content = echoable(content),
            usage = total,
            model = message.model().asString(),
            refusalCategory = message.stopDetails().flatMap { it.category() }.map { it.asString() }.orElse(null),
            fallbackUsed = "fallback" in types || iterations.any { it.isFallbackMessage() },
            compacted = "compaction" in types,
        )
    }

    /**
     * The content as it may be sent again: after a server-side fallback, the thinking, tool_use and server-tool blocks
     * that came before the last `fallback` block are left out (the fallback block itself stays where it was).
     */
    fun echoable(content: JsonArray): JsonArray {
        val boundary = content.indexOfLast { blockType(it) == "fallback" }
        if (boundary < 0) return content
        return JsonArray(content.filterIndexed { index, block -> index >= boundary || blockType(block) !in dropBeforeFallback })
    }

    fun blockType(block: kotlinx.serialization.json.JsonElement): String? = ((block as? JsonObject)?.get("type") as? JsonPrimitive)?.contentOrNull
}

/**
 * A small LRU cache of clients that never closes one in use: a client evicted while a request runs on it is closed when
 * that request ends. [max] keys are kept; past that, the least recently used idle ones are closed.
 */
class ClientCache<T>(private val max: Int, private val closer: (T) -> Unit) {
    private class Entry<T>(val client: T) {
        var inFlight = 0
        var lastUsed = 0L
        var evicted = false
    }

    private val entries = LinkedHashMap<String, Entry<T>>()
    private var tick = 0L

    fun <R> use(key: String, create: () -> T, block: (T) -> R): R {
        val entry = synchronized(this) {
            val found = entries.getOrPut(key) { Entry(create()) }
            found.inFlight++
            found.lastUsed = ++tick
            evictIdle()
            found
        }
        try {
            return block(entry.client)
        } finally {
            val close = synchronized(this) {
                entry.inFlight--
                val evictedWhileBusy = entry.evicted && entry.inFlight == 0
                // Over the limit because every client was busy: the idle ones go now (closed by evictIdle).
                if (!entry.evicted) evictIdle()
                evictedWhileBusy
            }
            if (close) runCatching { closer(entry.client) }
        }
    }

    // Caller holds the lock.
    private fun evictIdle() {
        if (entries.size <= max) return
        entries.entries.sortedBy { it.value.lastUsed }.forEach { (key, entry) ->
            if (entries.size <= max) return
            if (entry.inFlight == 0) {
                entries.remove(key)
                entry.evicted = true
                runCatching { closer(entry.client) }
            }
        }
    }

    val size: Int get() = synchronized(this) { entries.size }

    /** Closes every idle client and marks the busy ones to close when their request ends. */
    fun close() {
        val idle = synchronized(this) {
            entries.values.forEach { it.evicted = true }
            val list = entries.values.filter { it.inFlight == 0 }
            entries.clear()
            list
        }
        idle.forEach { runCatching { closer(it.client) } }
    }
}

/**
 * The SDK clients, one per API key (kept by the key's hash, never by the key itself). The SDK does no retries of its own
 * (maxRetries 0): [AnthropicModelClient] retries once, only before anything was streamed, so one model request lasts at
 * most 2 x [RequestTimeout] plus the pause, well under the turn's lease (AssistantTurnLimits.lease).
 */
class AnthropicModelClients(maxClients: Int = 50) : AssistantModelClients {
    private val cache = ClientCache<AnthropicClient>(maxClients) { it.close() }

    private fun build(apiKey: String): AnthropicClient = AnthropicOkHttpClient.builder()
        .apiKey(apiKey)
        .timeout(com.anthropic.core.Timeout.builder().connect(Duration.ofSeconds(15)).read(ReadTimeout).write(Duration.ofSeconds(30)).request(RequestTimeout).build())
        .maxRetries(0)
        // Never log requests or responses, whatever ANTHROPIC_LOG says in the environment: they carry the company's data.
        .logLevel(com.anthropic.core.LogLevel.OFF)
        .build()

    override fun forKey(apiKey: String): AssistantModelClient {
        val id = sha256(apiKey)
        return object : AssistantModelClient {
            override fun send(params: MessageCreateParams, listener: ModelStreamListener): BetaMessage =
                cache.use(id, { build(apiKey) }) { client -> AnthropicModelClient(client).send(params, listener) }
        }
    }

    fun close() = cache.close()

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

    companion object {
        val RequestTimeout: Duration = Duration.ofMinutes(4)
        val ReadTimeout: Duration = Duration.ofMinutes(2)
    }
}

/** One request on the SDK client: streams, keeps the usage reported so far, retries once before any content. */
class AnthropicModelClient(private val client: AnthropicClient, private val retryPause: Duration = Duration.ofSeconds(2)) : AssistantModelClient {
    private val log = LoggerFactory.getLogger(AnthropicModelClient::class.java)

    override fun send(params: MessageCreateParams, listener: ModelStreamListener): BetaMessage {
        var attempt = 0
        while (true) {
            attempt++
            val progress = StreamProgress()
            try {
                return once(params, listener, progress)
            } catch (failure: AssistantModelException) {
                if (failure.retryable && attempt < 2 && !progress.started && !listener.cancelled) {
                    Thread.sleep(retryPause.toMillis())
                    continue
                }
                throw failure.withUsage(progress.usage)
            }
        }
    }

    /** What one attempt received: the usage of message_start, then of each message_delta. */
    class StreamProgress {
        var started = false
        var usage: ModelUsage? = null

        fun onEvent(event: com.anthropic.models.beta.messages.BetaRawMessageStreamEvent) {
            started = true
            event.messageStart().ifPresent { start ->
                val usage = start.message().usage()
                this.usage = ModelUsage(usage.inputTokens(), usage.outputTokens(), usage.cacheReadInputTokens().orElse(0L), usage.cacheCreationInputTokens().orElse(0L))
            }
            event.messageDelta().ifPresent { delta ->
                val usage = delta.usage()
                val before = this.usage ?: ModelUsage()
                this.usage = ModelUsage(
                    usage.inputTokens().orElse(before.inputTokens), usage.outputTokens(),
                    usage.cacheReadInputTokens().orElse(before.cacheReadInputTokens), usage.cacheCreationInputTokens().orElse(before.cacheCreationInputTokens),
                )
            }
        }
    }

    private fun once(params: MessageCreateParams, listener: ModelStreamListener, progress: StreamProgress): BetaMessage {
        val accumulator = BetaMessageAccumulator.create()
        try {
            client.beta().messages().createStreaming(params).use { stream ->
                for (event in stream) {
                    progress.onEvent(event)
                    if (listener.cancelled) throw AssistantCancelledException(progress.usage)
                    accumulator.accumulate(event)
                    event.contentBlockDelta().flatMap { it.delta().text() }.ifPresent { listener.text(it.text()) }
                }
            }
        } catch (cancelled: AssistantCancelledException) {
            throw cancelled
        } catch (failure: AnthropicServiceException) {
            // Only the status: the exception text may quote the request.
            log.warn("Assistant model request failed with HTTP {}", failure.statusCode())
            throw AnthropicErrors.classify(failure.statusCode())
        } catch (failure: AnthropicIoException) {
            log.warn("Assistant model request failed: {}", failure.javaClass.simpleName)
            if (listener.cancelled) throw AssistantCancelledException(progress.usage)
            throw AnthropicErrors.unavailable()
        }
        return accumulator.message()
    }
}

/** Typed errors of the API, as the user reads them (GLOBAL_AGENT.md, stream error codes). */
object AnthropicErrors {
    fun classify(status: Int): AssistantModelException = when (status) {
        401, 402, 403 -> AssistantModelException(
            "MODEL_KEY_INVALID", false,
            tr("Anthropic rechazo la clave de API configurada. Pide al dueno de la empresa o del servidor que la revise."),
        )
        429 -> AssistantModelException("MODEL_RATE_LIMITED", true, tr("El servicio de IA esta recibiendo demasiadas solicitudes. Intenta de nuevo en un momento."))
        400, 404, 413, 422 -> AssistantModelException("MODEL_BAD_REQUEST", false, tr("El servicio de IA no acepto la solicitud. Si se repite, empieza una conversacion nueva."))
        else -> AssistantModelException("MODEL_OVERLOADED", true, tr("El servicio de IA esta saturado. Intenta de nuevo en un momento."))
    }

    fun unavailable() = AssistantModelException("MODEL_UNAVAILABLE", true, tr("No se pudo contactar al servicio de IA. Intenta de nuevo en un momento."))
}
