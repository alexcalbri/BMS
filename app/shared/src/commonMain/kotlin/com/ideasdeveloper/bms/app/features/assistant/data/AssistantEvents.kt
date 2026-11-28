package com.ideasdeveloper.bms.app.features.assistant.data

import com.ideasdeveloper.bms.AssistantConfirmation
import com.ideasdeveloper.bms.AssistantIds
import com.ideasdeveloper.bms.AssistantSummaryRow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

// Eventos del flujo SSE del asistente (GLOBAL_AGENT.md, "SSE stream"). Un evento desconocido o mal formado se ignora.
sealed interface AssistantEvent {
    data class TurnStarted(val conversationId: String) : AssistantEvent
    data class TextDelta(val text: String) : AssistantEvent
    data class ToolStarted(val toolUseId: String, val name: String, val label: String, val readOnly: Boolean) : AssistantEvent
    data class ToolFinished(val toolUseId: String, val name: String, val label: String, val status: String, val message: String?) : AssistantEvent
    data class ConfirmationRequired(val confirmation: AssistantConfirmation) : AssistantEvent
    data class Usage(val countedTokens: Long, val monthUsedTokens: Long?, val monthlyLimitTokens: Long?) : AssistantEvent
    data class Notice(val code: String, val message: String) : AssistantEvent
    data class Error(val code: String, val message: String, val retryable: Boolean) : AssistantEvent
    data class Done(val reason: String) : AssistantEvent
}

// Lector incremental de Server-Sent Events: se le pasan las lineas una a una (sin salto de linea) y devuelve el evento
// cuando una linea en blanco cierra el cuadro. Los comentarios (": ping"), id: y retry: se ignoran.
class SseParser {
    private var eventName: String? = null
    private val data = StringBuilder()
    private var hasData = false

    fun feed(line: String): AssistantEvent? {
        if (line.isEmpty()) return dispatch()
        if (line.startsWith(":")) return null
        val separator = line.indexOf(':')
        val field = if (separator < 0) line else line.substring(0, separator)
        var value = if (separator < 0) "" else line.substring(separator + 1)
        if (value.startsWith(" ")) value = value.substring(1)
        when (field) {
            "event" -> eventName = value
            "data" -> {
                if (hasData) data.append('\n')
                data.append(value)
                hasData = true
            }
        }
        return null
    }

    private fun dispatch(): AssistantEvent? {
        val name = eventName
        val payload = data.toString()
        val present = hasData
        eventName = null
        data.clear()
        hasData = false
        if (name == null || !present) return null
        return parseAssistantEvent(name, payload)
    }
}

private val EventJson = Json { ignoreUnknownKeys = true; isLenient = true }

// Convierte un cuadro en su evento; null si el tipo no se conoce o el JSON no se puede leer.
fun parseAssistantEvent(name: String, payload: String): AssistantEvent? {
    val parsed: JsonObject? = try {
        EventJson.parseToJsonElement(payload) as? JsonObject
    } catch (_: Exception) {
        null
    }
    val obj = parsed ?: return null
    return when (name) {
        AssistantIds.EventTurnStarted -> AssistantEvent.TurnStarted(obj.text("conversationId").orEmpty())
        AssistantIds.EventTextDelta -> obj.text("text")?.let { AssistantEvent.TextDelta(it) }
        AssistantIds.EventToolStarted -> {
            val id = obj.text("toolUseId") ?: return null
            val toolName = obj.text("name").orEmpty()
            AssistantEvent.ToolStarted(id, toolName, obj.text("label")?.takeIf { it.isNotBlank() } ?: toolName, obj.flag("readOnly"))
        }
        AssistantIds.EventToolFinished -> {
            val id = obj.text("toolUseId") ?: return null
            val toolName = obj.text("name").orEmpty()
            AssistantEvent.ToolFinished(
                id,
                toolName,
                obj.text("label")?.takeIf { it.isNotBlank() } ?: toolName,
                obj.text("status").orEmpty(),
                obj.text("message")?.takeIf { it.isNotBlank() },
            )
        }
        AssistantIds.EventConfirmationRequired -> {
            val confirmationId = obj.text("confirmationId") ?: return null
            AssistantEvent.ConfirmationRequired(
                AssistantConfirmation(
                    confirmationId = confirmationId,
                    toolUseId = obj.text("toolUseId").orEmpty(),
                    name = obj.text("name").orEmpty(),
                    label = obj.text("label").orEmpty(),
                    module = obj.text("module").orEmpty(),
                    destructive = obj.flag("destructive"),
                    summary = obj.rows("summary"),
                    expiresAt = obj.text("expiresAt").orEmpty(),
                    outbound = obj.flag("outbound"),
                    preview = obj.rows("preview"),
                    previewError = obj.text("previewError")?.takeIf { it.isNotBlank() },
                ),
            )
        }
        AssistantIds.EventUsage -> AssistantEvent.Usage(
            countedTokens = obj.number("countedTokens") ?: 0L,
            monthUsedTokens = obj.number("monthUsedTokens"),
            monthlyLimitTokens = obj.number("monthlyLimitTokens"),
        )
        AssistantIds.EventNotice -> AssistantEvent.Notice(obj.text("code").orEmpty(), obj.text("message").orEmpty())
        AssistantIds.EventError -> AssistantEvent.Error(obj.text("code").orEmpty(), obj.text("message").orEmpty(), obj.flag("retryable"))
        AssistantIds.EventDone -> AssistantEvent.Done(obj.text("reason").orEmpty())
        else -> null
    }
}

private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

private fun JsonObject.flag(key: String): Boolean = (this[key] as? JsonPrimitive)?.booleanOrNull == true

private fun JsonObject.number(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull

// Filas de resumen o de vista previa; lo que no sea una fila valida se ignora.
private fun JsonObject.rows(key: String): List<AssistantSummaryRow> = (this[key] as? JsonArray).orEmpty().mapNotNull { row ->
    val rowObject = row as? JsonObject ?: return@mapNotNull null
    AssistantSummaryRow(rowObject.text("label").orEmpty(), rowObject.text("value").orEmpty(), rowObject.flag("hiddenCharacters"))
}
