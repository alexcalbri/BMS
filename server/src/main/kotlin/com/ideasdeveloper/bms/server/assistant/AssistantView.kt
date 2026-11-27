package com.ideasdeveloper.bms.server.assistant

import com.ideasdeveloper.bms.AssistantConfirmation
import com.ideasdeveloper.bms.AssistantConversationResponse
import com.ideasdeveloper.bms.AssistantItem
import com.ideasdeveloper.bms.AssistantSummaryRow
import com.ideasdeveloper.bms.AssistantToolView
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * What the app shows of a conversation: the user's and the assistant's text, one line per tool with its outcome and the
 * notices. Never the thinking, the tool arguments or the tool results (only the confirmation summary).
 */
object AssistantView {
    fun conversation(conversation: AssistantConversation, pending: Int) = AssistantConversationResponse(
        id = conversation.id.toString(),
        title = conversation.title,
        createdAt = conversation.createdAt.toString(),
        updatedAt = conversation.updatedAt.toString(),
        archived = conversation.archivedAt != null,
        state = conversation.state,
        pendingConfirmations = pending,
    )

    fun confirmation(call: AssistantPendingCall) = AssistantConfirmation(
        confirmationId = call.id.toString(),
        toolUseId = call.toolUseId,
        name = call.toolName,
        label = call.label,
        module = call.moduleName,
        destructive = call.destructive,
        summary = rows(call.summary),
        expiresAt = call.expiresAt.toString(),
        outbound = call.outbound,
        preview = rows(call.preview),
        previewError = call.previewError,
    )

    private fun rows(array: kotlinx.serialization.json.JsonArray) = array.mapNotNull { row ->
        val obj = row as? JsonObject ?: return@mapNotNull null
        AssistantSummaryRow(text(obj, "label").orEmpty(), text(obj, "value").orEmpty(), (obj["hiddenCharacters"] as? JsonPrimitive)?.booleanOrNull == true)
    }

    fun items(messages: List<AssistantStoredMessage>, pending: List<AssistantPendingCall>, labels: Map<String, String>): List<AssistantItem> {
        // Outcome of each tool call, from the tool_result blocks that answered it.
        val outcomes = mutableMapOf<String, String>()
        messages.filter { it.role == Roles.User }.forEach { message ->
            message.content.forEach { block ->
                val obj = block as? JsonObject ?: return@forEach
                if (text(obj, "type") != "tool_result") return@forEach
                val id = text(obj, "tool_use_id") ?: return@forEach
                val error = (obj["is_error"] as? JsonPrimitive)?.booleanOrNull == true
                val content = text(obj, "content")
                outcomes[id] = when {
                    !error -> "ok"
                    content == AssistantEngine.Rejected -> "rejected"
                    content == AssistantEngine.Expired -> "expired"
                    content == AssistantEngine.UnknownOutcome -> "unknown"
                    content == AssistantEngine.NotRun || content == AssistantEngine.TooManyTools -> "not_run"
                    else -> "error"
                }
            }
        }
        val waiting = pending.filter { it.status != AssistantPendingCall.Done && it.status != AssistantPendingCall.Rejected }.map { it.toolUseId }.toSet()
        return messages.flatMap { message ->
            val at = message.createdAt.toString()
            message.content.mapIndexedNotNull { index, block ->
                val obj = block as? JsonObject ?: return@mapIndexedNotNull null
                val id = "m${message.id}-$index"
                when (message.role) {
                    Roles.User -> text(obj, "text")?.takeIf { text(obj, "type") == "text" && !it.startsWith(AssistantEngine.ContextPrefix) }
                        ?.let { AssistantItem(id, "user", "text", text = it, createdAt = at) }
                    Roles.Assistant -> when (text(obj, "type")) {
                        "text" -> text(obj, "text")?.takeIf { it.isNotBlank() }?.let { AssistantItem(id, "assistant", "text", text = it, createdAt = at) }
                        "tool_use" -> {
                            val toolUseId = text(obj, "id").orEmpty()
                            val name = text(obj, "name").orEmpty()
                            val status = outcomes[toolUseId] ?: if (toolUseId in waiting) "pending" else "not_run"
                            AssistantItem(id, "assistant", "tool", tool = AssistantToolView(toolUseId, name, labels[name] ?: name, status), createdAt = at)
                        }
                        else -> null
                    }
                    Roles.Notice -> AssistantItem(id, "notice", "notice", text = text(obj, "text"), code = text(obj, "code"), createdAt = at)
                    else -> null
                }
            }
        }
    }

    private fun text(obj: JsonObject, key: String): String? = (obj[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
}
