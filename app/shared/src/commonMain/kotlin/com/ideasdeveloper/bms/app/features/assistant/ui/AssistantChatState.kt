package com.ideasdeveloper.bms.app.features.assistant.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.ideasdeveloper.bms.AssistantConfirmation
import com.ideasdeveloper.bms.AssistantConversationDetailResponse
import com.ideasdeveloper.bms.AssistantConversationResponse
import com.ideasdeveloper.bms.AssistantItem
import com.ideasdeveloper.bms.app.core.i18n.tr
import com.ideasdeveloper.bms.app.features.assistant.data.AssistantApi
import com.ideasdeveloper.bms.app.features.assistant.data.AssistantEvent
import com.ideasdeveloper.bms.app.features.assistant.data.use
import com.ideasdeveloper.bms.app.features.company.data.CompanyException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

// Lo que muestra la lista de mensajes de una conversacion. El texto de la respuesta que va llegando es un
// AssistantText que se reemplaza por una copia mas larga con cada text_delta.
internal sealed interface ChatEntry {
    val key: String
}

internal data class UserText(override val key: String, val text: String) : ChatEntry

internal data class AssistantText(override val key: String, val text: String) : ChatEntry

// status: running | ok | error | rejected | expired | pending | not_run
internal data class ToolLine(
    override val key: String,
    val toolUseId: String,
    val label: String,
    val status: String,
    val message: String? = null,
) : ChatEntry

internal data class NoticeLine(override val key: String, val code: String?, val text: String) : ChatEntry

internal data class ConfirmationCard(
    override val key: String,
    val confirmation: AssistantConfirmation,
    val deciding: Boolean = false,
) : ChatEntry

// Aviso de la ultima operacion: un error del flujo o del servidor (rojo) o una nota informativa.
internal data class ChatBanner(val text: String, val error: Boolean)

// Estado de la conversacion abierta y del turno en curso. Solo guarda en memoria lo que el servidor devuelve para
// mostrarlo; nada del chat se escribe en almacenamiento local. Se usa desde la corrutina principal de Compose.
internal class AssistantChatState(
    private val serverUrl: String,
    private val token: String,
    private val companyCode: String,
    private val scope: CoroutineScope,
    // La lista de conversaciones cambio (nueva, titulo, estado).
    private val onConversationsChanged: () -> Unit,
    // El estado del asistente cambio (limite alcanzado, desactivado...): hay que volver a pedirlo.
    private val onStatusChanged: () -> Unit,
) {
    val entries = mutableStateListOf<ChatEntry>()
    var conversation by mutableStateOf<AssistantConversationResponse?>(null)
        private set
    var streaming by mutableStateOf(false)
        private set
    var loading by mutableStateOf(false)
        private set
    var banner by mutableStateOf<ChatBanner?>(null)
        private set
    var monthUsed by mutableStateOf<Long?>(null)
        private set
    var monthLimit by mutableStateOf<Long?>(null)
        private set

    private var job: Job? = null
    private var nextKey = 0L
    // El ultimo AssistantText esta abierto: el siguiente text_delta se agrega a el.
    private var textOpen = false

    private fun key(): String = "e${nextKey++}"

    // Cifras de uso del mes que ya conoce la pantalla (el estado del asistente); los eventos usage las actualizan.
    fun setUsage(used: Long, limit: Long?) {
        monthUsed = used
        monthLimit = limit
    }

    fun clearBanner() {
        banner = null
    }

    // Conversacion nueva y vacia: se crea en el servidor al enviar el primer mensaje.
    fun startNew() {
        cancelStream()
        conversation = null
        entries.clear()
        banner = null
        textOpen = false
    }

    // Abre una conversacion existente: historial y confirmaciones pendientes salen del servidor.
    fun open(id: String) {
        cancelStream()
        entries.clear()
        banner = null
        textOpen = false
        conversation = null
        scope.launch { load(id) }
    }

    // Vuelve a leer la conversacion abierta (despues de un turno interrumpido o para ver otra sesion).
    fun reload() {
        val id = conversation?.id ?: return
        scope.launch { load(id) }
    }

    private suspend fun load(id: String) {
        loading = true
        try {
            val detail = AssistantApi(serverUrl).use { api -> api.conversation(token, companyCode, id) }
            // Si mientras tanto empezo otro turno, lo que se ve ya es lo mas reciente.
            if (!streaming) applyDetail(detail)
        } catch (exception: CompanyException) {
            banner = ChatBanner(exception.message.orEmpty(), error = true)
        } finally {
            loading = false
        }
    }

    private fun applyDetail(detail: AssistantConversationDetailResponse) {
        conversation = detail.conversation
        entries.clear()
        textOpen = false
        detail.items.forEach { item -> entryFor(item)?.let { entries.add(it) } }
        detail.pending.forEach { entries.add(ConfirmationCard(key(), it)) }
    }

    private fun entryFor(item: AssistantItem): ChatEntry? = when (item.kind) {
        "text" -> {
            val text = item.text.orEmpty()
            when {
                text.isBlank() -> null
                item.role == "user" -> UserText(key(), text)
                else -> AssistantText(key(), text)
            }
        }
        "tool" -> item.tool?.let { ToolLine(key(), it.toolUseId, it.label.ifBlank { it.name }, it.status) }
        "notice" -> NoticeLine(key(), item.code, item.text?.takeIf { it.isNotBlank() } ?: noticeText(item.code.orEmpty()))
        else -> null
    }

    // Envia un mensaje. Si el servidor lo rechaza antes de empezar, [onRejected] devuelve el texto para que no se pierda.
    fun send(text: String, onRejected: (String) -> Unit) {
        if (streaming || text.isBlank()) return
        banner = null
        streaming = true
        val sent = UserText(key(), text)
        job = scope.launch {
            var started = false
            try {
                val existing = conversation
                val target = if (existing != null) {
                    existing
                } else {
                    val created = AssistantApi(serverUrl).use { api -> api.createConversation(token, companyCode, null) }
                    conversation = created
                    onConversationsChanged()
                    created
                }
                val targetId = target.id
                entries.add(sent)
                textOpen = false
                started = true
                val finished = AssistantApi(serverUrl).use { api ->
                    api.sendMessage(token, companyCode, targetId, text) { event -> handle(event) }
                }
                finishTurn(finished)
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: CompanyException) {
                if (started) {
                    // Rechazado antes de que empezara el flujo: el mensaje no se guardo.
                    entries.remove(sent)
                }
                streaming = false
                banner = ChatBanner(exception.message.orEmpty(), error = true)
                onRejected(text)
                if (exception.status == 403) onStatusChanged()
                // Otra pantalla o dispositivo dejo un turno o una confirmacion abiertos: se ve tal como esta en el servidor.
                if (exception.code == "CONVERSATION_BUSY" || exception.code == "CONFIRMATION_PENDING" || exception.code == "CONVERSATION_CLOSED") reload()
            }
        }
    }

    // Aprueba o rechaza una accion pendiente y sigue leyendo el flujo que devuelve el servidor.
    fun decide(card: ConfirmationCard, approve: Boolean) {
        if (streaming) return
        val index = entries.indexOfFirst { it.key == card.key }
        if (index < 0) return
        banner = null
        streaming = true
        entries[index] = card.copy(deciding = true)
        job = scope.launch {
            try {
                val finished = AssistantApi(serverUrl).use { api ->
                    api.decide(token, companyCode, card.confirmation.confirmationId, approve) { event ->
                        // La decision se acepto: la tarjeta cede su lugar a la linea de la herramienta.
                        if (event is AssistantEvent.TurnStarted || event is AssistantEvent.ToolStarted || event is AssistantEvent.ToolFinished || event is AssistantEvent.Done) {
                            settleCard(card, approve)
                        }
                        handle(event)
                    }
                }
                settleCard(card, approve)
                finishTurn(finished)
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: CompanyException) {
                streaming = false
                val at = entries.indexOfFirst { it.key == card.key }
                if (at >= 0) entries[at] = card.copy(deciding = false)
                banner = ChatBanner(exception.message.orEmpty(), error = true)
                // Ya decidida, vencida o inexistente: el servidor sabe mejor que la lista que se ve.
                if (exception.code == "CONFIRMATION_NOT_FOUND" || exception.code == "CONFIRMATION_DECIDED" || exception.code == "CONFIRMATION_EXPIRED") reload()
                if (exception.status == 403) onStatusChanged()
            }
        }
    }

    // Cambia la tarjeta (si sigue ahi) por la linea de su herramienta: en curso si se aprobo, rechazada si no.
    private fun settleCard(card: ConfirmationCard, approve: Boolean) {
        val at = entries.indexOfFirst { it.key == card.key }
        if (at < 0) return
        val toolUseId = card.confirmation.toolUseId
        val existing = entries.indexOfFirst { it is ToolLine && it.toolUseId == toolUseId }
        if (existing >= 0) {
            entries.removeAt(at)
        } else {
            entries[at] = ToolLine(key(), toolUseId, card.confirmation.label, if (approve) "running" else "rejected")
        }
    }

    // Boton de parar: cierra el flujo. El servidor descarta la respuesta parcial, asi que aqui tambien se descarta.
    fun stop() {
        if (!streaming) return
        cancelStream()
        dropOpenText()
        entries.indices.forEach { index ->
            val entry = entries[index]
            if (entry is ToolLine && entry.status == "running") entries[index] = entry.copy(status = "not_run")
            if (entry is ConfirmationCard && entry.deciding) entries[index] = entry.copy(deciding = false)
        }
        onConversationsChanged()
        reload()
    }

    private fun cancelStream() {
        job?.cancel()
        job = null
        streaming = false
    }

    // Quita el texto que se estaba recibiendo (el ultimo AssistantText, si sigue abierto).
    private fun dropOpenText() {
        if (textOpen) {
            val last = entries.lastOrNull()
            if (last is AssistantText) entries.removeAt(entries.lastIndex)
        }
        textOpen = false
    }

    // Termina un turno: [finished] es falso si la conexion se cerro sin "done".
    private suspend fun finishTurn(finished: Boolean) {
        streaming = false
        textOpen = false
        job = null
        val reason = doneReason
        doneReason = null
        entries.indices.forEach { index ->
            val entry = entries[index]
            if (entry is ToolLine && entry.status == "running") entries[index] = entry.copy(status = "not_run")
        }
        if (!finished) {
            banner = ChatBanner(tr("La respuesta se interrumpio. Revisa la conversacion y vuelve a intentarlo."), error = true)
        }
        onConversationsChanged()
        // Con una respuesta normal o una pausa por confirmacion lo que se ve ya es lo guardado; en los demas casos se
        // vuelve a leer del servidor (la respuesta parcial no se guarda, un aviso si).
        if (!finished || (reason != "end_turn" && reason != "awaiting_confirmation")) {
            val id = conversation?.id ?: return
            load(id)
        }
    }

    private var doneReason: String? = null

    private fun handle(event: AssistantEvent) {
        when (event) {
            is AssistantEvent.TurnStarted -> Unit
            is AssistantEvent.TextDelta -> appendText(event.text)
            is AssistantEvent.ToolStarted -> {
                textOpen = false
                upsertTool(event.toolUseId, event.label, "running", null)
            }
            is AssistantEvent.ToolFinished -> {
                textOpen = false
                upsertTool(event.toolUseId, event.label, event.status.ifBlank { "ok" }, event.message)
            }
            is AssistantEvent.ConfirmationRequired -> {
                textOpen = false
                val known = entries.any { it is ConfirmationCard && it.confirmation.confirmationId == event.confirmation.confirmationId }
                if (!known) entries.add(ConfirmationCard(key(), event.confirmation))
            }
            is AssistantEvent.Usage -> {
                event.monthUsedTokens?.let { monthUsed = it }
                monthLimit = event.monthlyLimitTokens
            }
            is AssistantEvent.Notice -> notice(event)
            is AssistantEvent.Error -> {
                textOpen = false
                banner = ChatBanner(event.message.ifBlank { errorText(event.code) }, error = true)
                if (event.code == "LIMIT_REACHED" || event.code == "MODEL_KEY_INVALID") onStatusChanged()
            }
            is AssistantEvent.Done -> doneReason = event.reason
        }
    }

    private fun appendText(delta: String) {
        val last = entries.lastOrNull()
        if (textOpen && last is AssistantText) {
            entries[entries.lastIndex] = last.copy(text = last.text + delta)
        } else {
            entries.add(AssistantText(key(), delta))
            textOpen = true
        }
    }

    private fun upsertTool(toolUseId: String, label: String, status: String, message: String?) {
        val at = entries.indexOfFirst { it is ToolLine && it.toolUseId == toolUseId }
        if (at >= 0) {
            val existing = entries[at] as ToolLine
            entries[at] = existing.copy(label = label.ifBlank { existing.label }, status = status, message = message)
        } else {
            entries.add(ToolLine(key(), toolUseId, label, status, message))
        }
    }

    private fun notice(event: AssistantEvent.Notice) {
        if (event.code == "REFUSAL") {
            // Una respuesta rechazada no se guarda: se quita el texto recibido desde la ultima linea de herramienta.
            val lastBoundary = entries.indexOfLast { it is ToolLine || it is UserText }
            var index = entries.lastIndex
            while (index > lastBoundary) {
                if (entries[index] is AssistantText) entries.removeAt(index)
                index--
            }
        }
        textOpen = false
        val text = event.message.ifBlank { noticeText(event.code) }
        if (text.isNotBlank()) entries.add(NoticeLine(key(), event.code, text))
    }
}

// Texto por defecto de un aviso cuando el servidor no manda uno.
internal fun noticeText(code: String): String = when (code) {
    "REFUSAL" -> tr("El asistente no puede ayudar con esto.")
    "MAX_TOKENS" -> tr("La respuesta se corto porque alcanzo su longitud maxima.")
    "FALLBACK" -> tr("Se uso un modelo alternativo para responder.")
    "COMPACTED" -> tr("La conversacion se resumio para seguir.")
    "CLOSED" -> tr("Esta conversacion no puede continuar. Empieza una conversacion nueva.")
    else -> ""
}

// Texto por defecto de un error del flujo cuando el servidor no manda mensaje.
internal fun errorText(code: String): String = when (code) {
    "MODEL_RATE_LIMITED" -> tr("El modelo recibio demasiadas solicitudes. Intenta de nuevo en un momento.")
    "MODEL_OVERLOADED" -> tr("El modelo esta saturado. Intenta de nuevo en un momento.")
    "MODEL_KEY_INVALID" -> tr("El proveedor rechazo la clave del asistente. Avisa a quien lo administra.")
    "TURN_TOO_LONG" -> tr("La respuesta tardo demasiado y se detuvo.")
    "TOO_MANY_TOOL_CALLS" -> tr("El asistente hizo demasiadas consultas en una respuesta y se detuvo.")
    "LIMIT_REACHED" -> tr("Se alcanzo el limite mensual del asistente.")
    "ASSISTANT_BUSY" -> tr("El asistente esta ocupado, intentalo en un momento.")
    "ASSISTANT_RATE_LIMITED" -> tr("Demasiadas solicitudes al asistente; espera un minuto.")
    "CONVERSATION_BUSY" -> tr("Esta conversacion ya tiene una respuesta en curso.")
    "TOO_MANY_CONVERSATIONS" -> tr("Tienes demasiadas conversaciones; elimina o archiva alguna.")
    else -> tr("El asistente no pudo completar la respuesta.")
}
