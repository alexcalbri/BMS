package com.ideasdeveloper.bms.app.features.assistant.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.ideasdeveloper.bms.AssistantConversationResponse
import com.ideasdeveloper.bms.AssistantStatusResponse
import com.ideasdeveloper.bms.app.features.assistant.data.AssistantAttention
import com.ideasdeveloper.bms.app.features.assistant.data.AssistantApi
import com.ideasdeveloper.bms.app.features.assistant.data.use
import com.ideasdeveloper.bms.app.features.company.data.CompanyException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// Estado del asistente que vive por encima de las pantallas (lo crea el anfitrion del boton flotante, montado en App):
// el estado del servidor, la lista de conversaciones, el borrador y el chat en curso. Como no depende de la pantalla
// abierta, cerrar el panel o navegar no corta la respuesta que va llegando ni pierde lo escrito. Se descarta al cerrar
// la sesion o cambiar de empresa (el anfitrion lo recrea con otra clave). Se usa desde la corrutina principal de Compose.
internal class AssistantController(
    private val serverUrl: String,
    private val token: String,
    // Valor de X-Company-Code: la empresa de la sesion o el espacio del servidor.
    private val companyCode: String,
    private val scope: CoroutineScope,
) {
    var status by mutableStateOf<AssistantStatusResponse?>(null)
        private set
    var statusLoaded by mutableStateOf(false)
        private set
    var statusError by mutableStateOf<String?>(null)
        private set
    var conversations by mutableStateOf<List<AssistantConversationResponse>>(emptyList())
        private set
    var nextBefore by mutableStateOf<String?>(null)
        private set
    var showArchived by mutableStateOf(false)
        private set
    var listError by mutableStateOf<String?>(null)
        private set

    // Pantallas estrechas: la lista y el chat se turnan; en las anchas se ven a la vez.
    var chatOpen by mutableStateOf(false)
    var draft by mutableStateOf("")
    var panelOpen by mutableStateOf(false)
        private set

    // Una respuesta termino mientras el panel estaba cerrado y todavia no se vio.
    private var unread by mutableStateOf(false)

    val chat: AssistantChatState = AssistantChatState(
        serverUrl = serverUrl,
        token = token,
        companyCode = companyCode,
        scope = scope,
        onConversationsChanged = { loadList(true) },
        onStatusChanged = { loadStatus() },
        onTurnFinished = { if (!panelOpen) unread = true },
        onConversationLoaded = { id -> markRead(id) },
    )

    // Lo que dice el servidor que espera a la persona (tambien lo que paso en otros dispositivos); se consulta cada 20 s.
    private var remote by mutableStateOf(AssistantAttention())
    private var pollJob: Job? = null

    // Conversaciones con una confirmacion pendiente o una respuesta sin ver segun el servidor.
    val attentionIds: Set<String>
        get() = remote.conversationIds.toSet()

    // El boton pide atencion: hay una confirmacion esperando o una respuesta terminada que no se ha visto.
    val attention: Boolean
        get() = !panelOpen && (
            unread ||
                remote.pendingConfirmations + remote.unseenFinished > 0 ||
                chat.entries.any { it is ConfirmationCard } ||
                conversations.any { !it.archived && it.pendingConfirmations > 0 }
            )

    // El servidor dice que el asistente se ve (y el boton existe).
    val visible: Boolean
        get() = status?.visible == true

    // Primera carga al montarse el boton: estado y lista (la lista da las confirmaciones pendientes de otras sesiones).
    fun start() {
        loadStatus()
        loadList(true)
        startPolling()
    }

    // Consulta periodica de la atencion pendiente mientras el estado dice que el asistente se ve. 20 s; tras fallos seguidos
    // 60 s; un 404 (Core anterior) la detiene sin avisar y queda solo lo local. No hay gancho de segundo plano en el proyecto
    // (la campana de notificaciones tampoco lo usa): sigue mientras la sesion esta abierta.
    private fun startPolling() {
        if (pollJob != null) return
        pollJob = scope.launch {
            var failures = 0
            while (true) {
                if (visible) {
                    when (refreshAttention()) {
                        null -> return@launch
                        true -> failures = 0
                        false -> failures++
                    }
                }
                delay(if (failures >= 2) 60_000L else 20_000L)
            }
        }
    }

    // true = consultado; false = fallo pasajero; null = el servidor no tiene el endpoint (404).
    private suspend fun refreshAttention(): Boolean? = try {
        remote = AssistantApi(serverUrl).use { api -> api.attention(token, companyCode) }
        true
    } catch (exception: CancellationException) {
        throw exception
    } catch (exception: CompanyException) {
        if (exception.status == 404) null else false
    }

    private fun markRead(id: String) {
        scope.launch {
            try {
                AssistantApi(serverUrl).use { api -> api.markRead(token, companyCode, id) }
                refreshAttention()
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: CompanyException) {
                // 404 u otro fallo: no es un error que mostrar.
            }
        }
    }

    fun open() {
        panelOpen = true
        unread = false
        loadStatus()
        loadList(true)
        scope.launch { refreshAttention() }
    }

    // Abre el panel en una conversacion concreta (por ejemplo desde una notificacion).
    fun openConversationById(id: String) {
        open()
        chat.open(id)
        chatOpen = true
    }

    fun close() {
        panelOpen = false
    }

    // Corta lo que este en curso (sesion cerrada o empresa cambiada).
    fun dispose() {
        pollJob?.cancel()
        pollJob = null
        chat.startNew()
    }

    fun loadStatus() {
        if (serverUrl.isBlank() || token.isBlank() || companyCode.isBlank()) return
        scope.launch {
            try {
                val loaded = AssistantApi(serverUrl).use { api -> api.status(token, companyCode) }
                status = loaded
                statusError = null
                chat.setUsage(loaded.monthUsedTokens, if (loaded.limitApplies) loaded.monthlyLimitTokens else null)
            } catch (exception: CompanyException) {
                // 404: este servidor no tiene el asistente (Core anterior a 0.14.0); 403: no hay asistente para esta sesion.
                // Ninguno es un error que mostrar: el boton no aparece. Otro fallo conserva lo que ya se sabia.
                if (exception.status == 404 || exception.status == 403) {
                    status = null
                    statusError = null
                } else {
                    statusError = exception.message
                }
            } finally {
                statusLoaded = true
            }
        }
    }

    fun loadList(reset: Boolean) {
        if (serverUrl.isBlank() || token.isBlank() || companyCode.isBlank()) return
        val before = if (reset) null else nextBefore
        val archived = showArchived
        scope.launch {
            try {
                val page = AssistantApi(serverUrl).use { api -> api.conversations(token, companyCode, archived, before) }
                conversations = if (reset) page.conversations else conversations + page.conversations
                nextBefore = page.nextBefore
                listError = null
            } catch (exception: CompanyException) {
                // Sin asistente para esta sesion (404/403) la lista no es un error que mostrar.
                listError = if (exception.status == 404 || exception.status == 403) null else exception.message
            }
        }
    }

    fun selectArchived(value: Boolean) {
        showArchived = value
        loadList(true)
    }

    fun newConversation() {
        chat.startNew()
        draft = ""
        chatOpen = true
    }

    fun openConversation(conversation: AssistantConversationResponse) {
        chat.open(conversation.id)
        chatOpen = true
    }

    fun archive(conversation: AssistantConversationResponse, archived: Boolean) {
        scope.launch {
            try {
                AssistantApi(serverUrl).use { api -> api.updateConversation(token, companyCode, conversation.id, null, archived) }
                if (chat.conversation?.id == conversation.id) chat.startNew()
                loadList(true)
            } catch (exception: CompanyException) {
                listError = exception.message
            }
        }
    }

    fun rename(conversation: AssistantConversationResponse, title: String) {
        scope.launch {
            try {
                AssistantApi(serverUrl).use { api -> api.updateConversation(token, companyCode, conversation.id, title, null) }
                loadList(true)
            } catch (exception: CompanyException) {
                listError = exception.message
            }
        }
    }

    fun delete(conversation: AssistantConversationResponse) {
        scope.launch {
            try {
                AssistantApi(serverUrl).use { api -> api.deleteConversation(token, companyCode, conversation.id) }
                if (chat.conversation?.id == conversation.id) chat.startNew()
                loadList(true)
            } catch (exception: CompanyException) {
                listError = exception.message
            }
        }
    }

    // Envia el borrador; si el servidor lo rechaza antes de empezar, el texto vuelve al campo.
    fun sendDraft() {
        val text = draft.trim()
        draft = ""
        chat.send(text) { rejected -> draft = rejected }
    }
}
