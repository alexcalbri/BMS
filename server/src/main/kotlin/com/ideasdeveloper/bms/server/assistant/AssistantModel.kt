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
import java.util.concurrent.ConcurrentHashMap

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
class AssistantModelException(val code: String, val retryable: Boolean, message: String) : Exception(message)

/** The user closed the stream while the model was answering. */
class AssistantCancelledException : Exception("cancelled")

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

    // Server-side compaction (beta compact-2026-01-12): documented for the Opus and Sonnet lines.
    fun compaction(model: String): Boolean = model != "claude-haiku-5-5"
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

/** The SDK clients, one per API key (kept by the key's hash, never by the key itself). */
class AnthropicModelClients(
    private val timeout: Duration = Duration.ofMinutes(10),
    private val maxRetries: Int = 2,
) : AssistantModelClients {
    private val clients = ConcurrentHashMap<String, AnthropicClient>()

    override fun forKey(apiKey: String): AssistantModelClient {
        val id = sha256(apiKey)
        // Keys are few (the server's and the companies' own): past that, start again rather than grow.
        if (clients.size > 200) close()
        val client = clients.computeIfAbsent(id) {
            AnthropicOkHttpClient.builder()
                .apiKey(apiKey)
                .timeout(timeout)
                .maxRetries(maxRetries)
                .build()
        }
        return AnthropicModelClient(client)
    }

    /** Closes every client (the server stops, or the cache starts again). */
    fun close() {
        val all = clients.values.toList()
        clients.clear()
        all.forEach { runCatching { it.close() } }
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
}

class AnthropicModelClient(private val client: AnthropicClient) : AssistantModelClient {
    private val log = LoggerFactory.getLogger(AnthropicModelClient::class.java)

    override fun send(params: MessageCreateParams, listener: ModelStreamListener): BetaMessage {
        val accumulator = BetaMessageAccumulator.create()
        try {
            client.beta().messages().createStreaming(params).use { stream ->
                for (event in stream) {
                    if (listener.cancelled) throw AssistantCancelledException()
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
