package com.ideasdeveloper.bms.server.assistant

import com.anthropic.core.jsonMapper
import com.anthropic.models.beta.messages.BetaMessage
import com.anthropic.models.beta.messages.MessageCreateParams
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID

/** Builders of model responses as the API would stream them, and a scripted model client (no network, no key). */
object Scripted {
    fun text(text: String) = buildJsonObject { put("type", "text"); put("text", text) }

    fun thinking(signature: String) = buildJsonObject { put("type", "thinking"); put("thinking", ""); put("signature", signature) }

    fun toolUse(id: String, name: String, input: JsonObject = JsonObject(emptyMap())) = buildJsonObject {
        put("type", "tool_use"); put("id", id); put("name", name); put("input", input)
    }

    fun message(
        stopReason: String,
        vararg content: JsonObject,
        input: Long = 100,
        output: Long = 20,
        cacheRead: Long = 0,
        cacheWrite: Long = 0,
        model: String = "claude-opus-5-5",
        stopDetails: JsonObject? = null,
    ): BetaMessage {
        val json = buildJsonObject {
            put("id", "msg_" + UUID.randomUUID().toString().take(8))
            put("type", "message")
            put("role", "assistant")
            put("model", model)
            put("content", JsonArray(content.toList()))
            put("stop_reason", stopReason)
            put("stop_sequence", kotlinx.serialization.json.JsonNull)
            stopDetails?.let { put("stop_details", it) }
            put("usage", buildJsonObject {
                put("input_tokens", input)
                put("output_tokens", output)
                put("cache_read_input_tokens", cacheRead)
                put("cache_creation_input_tokens", cacheWrite)
            })
        }
        return jsonMapper().readValue(json.toString(), BetaMessage::class.java)
    }
}

/** Answers each request with the next scripted step (a message or an exception) and keeps every request it got. */
class FakeModelClients(vararg steps: Any) : AssistantModelClients {
    private val script = ArrayDeque(steps.toList())
    val requests = mutableListOf<MessageCreateParams>()
    val keys = mutableListOf<String>()

    fun add(vararg steps: Any) = script.addAll(steps)

    override fun forKey(apiKey: String): AssistantModelClient {
        keys += apiKey
        return object : AssistantModelClient {
            override fun send(params: MessageCreateParams, listener: ModelStreamListener): BetaMessage {
                requests += params
                val step = script.removeFirstOrNull() ?: error("the model was called more times than scripted")
                if (step is Exception) throw step
                val message = step as BetaMessage
                message.content().forEach { block -> block.text().ifPresent { listener.text(it.text()) } }
                if (listener.cancelled) throw AssistantCancelledException()
                return message
            }
        }
    }

    /** The JSON body of request [index]. */
    fun body(index: Int): JsonObject = AnthropicRequests.bodyJson(requests[index])
}

class FakeToolbox(vararg tools: AssistantTool) : AssistantToolbox {
    var available = tools.toMutableList()
    val handlers = mutableMapOf<String, (JsonObject) -> AssistantToolResult>()
    val executed = mutableListOf<Pair<String, JsonObject>>()

    override fun tools(): List<AssistantTool> = available.sortedBy { it.name }

    override fun instructions(): List<Pair<String, String>> = available.map { it.moduleName }.distinct().map { it to "Guia de $it." }

    override fun execute(name: String, arguments: JsonObject): AssistantToolResult {
        // Same rule as the real toolbox: only what is offered now.
        if (available.none { it.name == name }) return AssistantToolResult(false, AssistantTools.UnknownTool)
        executed += name to arguments
        return handlers[name]?.invoke(arguments) ?: AssistantToolResult(true, "{\"ok\":true}")
    }

    companion object {
        fun tool(name: String, readOnly: Boolean, destructive: Boolean = false) = AssistantTool(
            name = name, moduleId = name.substringBefore('_'), moduleName = name.substringBefore('_').replaceFirstChar { it.uppercase() },
            title = name.substringAfter('_'), description = "Hace $name.",
            inputSchema = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject { put("nombre", buildJsonObject { put("type", "string"); put("description", "Nombre del cliente.") }) })
                put("additionalProperties", false)
            },
            readOnly = readOnly, destructive = destructive,
        )
    }
}

class RecordingSink(private val cancelAfter: Int = Int.MAX_VALUE) : AssistantSink {
    val events = mutableListOf<Pair<String, JsonObject>>()

    override fun event(name: String, data: JsonObject) {
        events += name to data
    }

    override val cancelled: Boolean get() = events.size >= cancelAfter

    fun names() = events.map { it.first }
    fun last(name: String) = events.last { it.first == name }.second
    fun done() = (last("done")["reason"] as JsonPrimitive).content
    fun all(name: String) = events.filter { it.first == name }.map { it.second }
}

fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

/** The store in memory, with the same owner rule as the JDBC one. */
class MemoryAssistantStore : AssistantStore {
    val conversations = linkedMapOf<UUID, AssistantConversation>()
    val messages = mutableListOf<Pair<UUID, AssistantStoredMessage>>()
    val pendingCalls = linkedMapOf<UUID, AssistantPendingCall>()
    val usageRows = mutableMapOf<Triple<LocalDate, UUID, String>, AssistantUsageRow>()
    private var nextId = 1L

    override fun createConversation(userId: UUID, ownerKey: String, title: String, now: Instant) =
        AssistantConversation(UUID.randomUUID(), userId, ownerKey, title, AssistantConversation.Idle, null, now, now, null).also { conversations[it.id] = it }

    override fun conversation(id: UUID, userId: UUID, ownerKey: String) = conversations[id]?.takeIf { it.userId == userId && it.ownerKey == ownerKey }

    override fun conversations(userId: UUID, ownerKey: String, archived: Boolean, limit: Int, before: Instant?) =
        conversations.values.filter { it.userId == userId && it.ownerKey == ownerKey && (it.archivedAt != null) == archived }.take(limit)

    override fun updateConversation(id: UUID, userId: UUID, ownerKey: String, title: String?, archived: Boolean?, now: Instant): AssistantConversation? {
        val current = conversation(id, userId, ownerKey) ?: return null
        val updated = current.copy(title = title ?: current.title, archivedAt = if (archived == null) current.archivedAt else if (archived) now else null, updatedAt = now)
        conversations[id] = updated
        return updated
    }

    override fun deleteConversation(id: UUID, userId: UUID, ownerKey: String): Boolean {
        conversation(id, userId, ownerKey) ?: return false
        conversations.remove(id)
        messages.removeIf { it.first == id }
        pendingCalls.values.removeIf { it.conversationId == id }
        return true
    }

    override fun startTurn(id: UUID, fromState: String, leaseUntil: Instant, now: Instant): Boolean {
        val current = conversations[id] ?: return false
        val free = current.state == fromState || (current.state == AssistantConversation.Running && current.leaseUntil?.isBefore(now) == true)
        if (!free) return false
        conversations[id] = current.copy(state = AssistantConversation.Running, leaseUntil = leaseUntil)
        return true
    }

    override fun setState(id: UUID, state: String, now: Instant) {
        conversations[id]?.let { conversations[id] = it.copy(state = state, leaseUntil = null, updatedAt = now) }
    }

    override fun messages(conversationId: UUID) = messages.filter { it.first == conversationId }.map { it.second }

    override fun messageCount(conversationId: UUID) = messages(conversationId).count { it.role != Roles.Notice }

    override fun appendMessage(conversationId: UUID, role: String, content: JsonArray, model: String?, stopReason: String?, usage: ModelUsage?, now: Instant) =
        AssistantStoredMessage(nextId++, role, content, now).also { messages += conversationId to it }

    override fun savePending(calls: List<AssistantPendingCall>) = calls.forEach { pendingCalls[it.id] = it }

    override fun pending(conversationId: UUID) = pendingCalls.values.filter { it.conversationId == conversationId }.sortedBy { it.position }

    override fun pendingCall(id: UUID, userId: UUID, ownerKey: String) =
        pendingCalls[id]?.takeIf { conversations[it.conversationId]?.let { c -> c.userId == userId && c.ownerKey == ownerKey } == true }

    override fun decide(id: UUID, status: String, now: Instant): Boolean {
        val call = pendingCalls[id]?.takeIf { it.status == AssistantPendingCall.Pending } ?: return false
        pendingCalls[id] = call.copy(status = status)
        return true
    }

    override fun clearPending(conversationId: UUID) {
        pendingCalls.values.removeIf { it.conversationId == conversationId }
    }

    override fun expiredConversations(now: Instant) =
        pendingCalls.values.filter { it.status == AssistantPendingCall.Pending && !it.expiresAt.isAfter(now) }.map { it.conversationId }.distinct()

    override fun recordUsage(day: LocalDate, userId: UUID, keySource: String, usage: ModelUsage, requests: Long) {
        val key = Triple(day, userId, keySource)
        val previous = usageRows[key]
        usageRows[key] = AssistantUsageRow(
            day, userId, keySource, (previous?.requests ?: 0) + requests, (previous?.usage ?: ModelUsage()) + usage, (previous?.countedTokens ?: 0) + usage.countedTokens,
        )
    }

    override fun monthUsage(month: YearMonth, keySources: Set<String>) =
        usageRows.values.filter { YearMonth.from(it.day) == month && it.keySource in keySources }.sumOf { it.countedTokens }

    override fun usage(month: YearMonth) = usageRows.values.filter { YearMonth.from(it.day) == month }

    override fun purge(before: Instant, usageBefore: LocalDate): Int {
        val old = conversations.values.filter { it.updatedAt.isBefore(before) }.map { it.id }
        old.forEach { id -> conversations.remove(id); messages.removeIf { it.first == id } }
        return old.size
    }
}

fun jsonArrayOf(vararg elements: JsonElement) = buildJsonArray { elements.forEach { add(it) } }
