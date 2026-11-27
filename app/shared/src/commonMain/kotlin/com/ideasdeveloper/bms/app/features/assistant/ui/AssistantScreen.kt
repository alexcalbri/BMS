package com.ideasdeveloper.bms.app.features.assistant.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ideasdeveloper.bms.AssistantConversationResponse
import com.ideasdeveloper.bms.AssistantStatusResponse
import com.ideasdeveloper.bms.app.core.i18n.tr
import com.ideasdeveloper.bms.app.core.layout.AdaptiveLayout
import com.ideasdeveloper.bms.app.core.layout.ContentWidth
import com.ideasdeveloper.bms.app.core.layout.contentWidth
import com.ideasdeveloper.bms.app.core.theme.AppTheme
import com.ideasdeveloper.bms.app.core.theme.accentButtonColors
import com.ideasdeveloper.bms.app.core.theme.appFieldColors
import com.ideasdeveloper.bms.app.features.assistant.data.AssistantApi
import com.ideasdeveloper.bms.app.features.assistant.data.use
import com.ideasdeveloper.bms.app.features.auth.data.LoginResponse
import com.ideasdeveloper.bms.app.features.company.data.CompanyException
import com.ideasdeveloper.bms.app.features.shell.ui.AuthenticatedScreen
import com.ideasdeveloper.bms.app.features.shell.ui.AuthenticatedTopBar
import com.ideasdeveloper.bms.app.features.shell.ui.ClosableView
import com.ideasdeveloper.bms.app.features.shell.ui.ConfirmDialog
import kotlinx.coroutines.launch

@Composable
// Chat con el asistente global de la empresa: lista de conversaciones y conversacion abierta.
fun AssistantScreen(
    serverUrl: String?,
    session: LoginResponse?,
    onSettings: () -> Unit,
    onLogout: () -> Unit,
    onReturnToDashboard: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AppTheme.colors
    val scope = rememberCoroutineScope()
    val url = serverUrl.orEmpty()
    val token = session?.accessToken.orEmpty()
    val companyCode = session?.companyCode.orEmpty()

    var status by remember { mutableStateOf<AssistantStatusResponse?>(null) }
    var statusLoaded by remember { mutableStateOf(false) }
    var statusError by remember { mutableStateOf<String?>(null) }
    var conversations by remember { mutableStateOf<List<AssistantConversationResponse>>(emptyList()) }
    var nextBefore by remember { mutableStateOf<String?>(null) }
    var showArchived by remember { mutableStateOf(false) }
    var listError by remember { mutableStateOf<String?>(null) }
    // Pantallas estrechas: la lista y el chat se turnan; en las anchas se ven a la vez.
    var chatOpen by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }
    var renaming by remember { mutableStateOf<AssistantConversationResponse?>(null) }
    var deleting by remember { mutableStateOf<AssistantConversationResponse?>(null) }

    fun loadStatus() {
        if (url.isBlank() || token.isBlank() || companyCode.isBlank()) return
        scope.launch {
            try {
                status = AssistantApi(url).use { api -> api.status(token, companyCode) }
                statusError = null
            } catch (exception: CompanyException) {
                // 404: este servidor no tiene el asistente (Core anterior a 0.14.0); no es un error que mostrar.
                if (exception.status == 404) {
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
        if (url.isBlank() || token.isBlank() || companyCode.isBlank()) return
        val before = if (reset) null else nextBefore
        val archived = showArchived
        scope.launch {
            try {
                val page = AssistantApi(url).use { api -> api.conversations(token, companyCode, archived, before) }
                conversations = if (reset) page.conversations else conversations + page.conversations
                nextBefore = page.nextBefore
                listError = null
            } catch (exception: CompanyException) {
                listError = exception.message
            }
        }
    }

    val chat = remember(url, token, companyCode) {
        AssistantChatState(
            serverUrl = url,
            token = token,
            companyCode = companyCode,
            scope = scope,
            onConversationsChanged = { loadList(true) },
            onStatusChanged = { loadStatus() },
        )
    }

    LaunchedEffect(url, token, companyCode) { loadStatus() }
    LaunchedEffect(url, token, companyCode, showArchived) { loadList(true) }
    LaunchedEffect(status) {
        status?.let { chat.setUsage(it.monthUsedTokens, if (it.limitApplies) it.monthlyLimitTokens else null) }
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
                AssistantApi(url).use { api -> api.updateConversation(token, companyCode, conversation.id, null, archived) }
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
                AssistantApi(url).use { api -> api.updateConversation(token, companyCode, conversation.id, title, null) }
                loadList(true)
            } catch (exception: CompanyException) {
                listError = exception.message
            }
        }
    }

    fun delete(conversation: AssistantConversationResponse) {
        scope.launch {
            try {
                AssistantApi(url).use { api -> api.deleteConversation(token, companyCode, conversation.id) }
                if (chat.conversation?.id == conversation.id) chat.startNew()
                loadList(true)
            } catch (exception: CompanyException) {
                listError = exception.message
            }
        }
    }

    renaming?.let { target ->
        RenameDialog(
            current = target.title,
            onSave = { title ->
                renaming = null
                rename(target, title)
            },
            onDismiss = { renaming = null },
        )
    }
    deleting?.let { target ->
        ConfirmDialog(
            title = tr("Eliminar conversacion"),
            message = tr("Se eliminara la conversacion \"{0}\" con sus mensajes y confirmaciones pendientes. No se puede deshacer.", target.title),
            confirmLabel = tr("Eliminar"),
            onConfirm = {
                deleting = null
                delete(target)
            },
            onDismiss = { deleting = null },
        )
    }

    AuthenticatedScreen(
        modifier = modifier.background(colors.screenBrush),
        topBar = {
            AuthenticatedTopBar(
                title = tr("Asistente"),
                subtitle = tr("Pregunta y pide cambios; trabaja con tus permisos"),
                primaryColor = colors.accent,
                onReturnToDashboard = onReturnToDashboard,
                onSettings = onSettings,
                onLogout = onLogout,
            )
        },
    ) {
        ClosableView(
            onClose = onReturnToDashboard,
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxHeight()
                .padding(bottom = 16.dp)
                .contentWidth(ContentWidth.Max, 0.94f),
        ) {
            Surface(modifier = Modifier.fillMaxSize(), shape = MaterialTheme.shapes.medium, color = colors.surface) {
                val current = status
                if (!statusLoaded) {
                    Text(tr("Cargando..."), color = colors.muted, modifier = Modifier.padding(AdaptiveLayout.screenPadding))
                } else if (current == null || !current.visible) {
                    Column(modifier = Modifier.padding(AdaptiveLayout.screenPadding), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            statusError ?: current?.message?.takeIf { it.isNotBlank() } ?: tr("El asistente no esta disponible para tu usuario."),
                            color = if (statusError != null) colors.danger else colors.text,
                        )
                        if (statusError != null) {
                            Button(onClick = { loadStatus() }, colors = accentButtonColors()) { Text(tr("Reintentar")) }
                        }
                    }
                } else {
                    val listPanel: @Composable (Modifier) -> Unit = { panelModifier ->
                        ConversationListPanel(
                            conversations = conversations,
                            selectedId = chat.conversation?.id,
                            showArchived = showArchived,
                            canLoadMore = nextBefore != null,
                            error = listError,
                            canCreate = current.available,
                            onShowArchived = { showArchived = it },
                            onNew = ::newConversation,
                            onSelect = ::openConversation,
                            onRename = { renaming = it },
                            onArchive = { target -> archive(target, !target.archived) },
                            onDelete = { deleting = it },
                            onLoadMore = { loadList(false) },
                            modifier = panelModifier,
                        )
                    }
                    val chatPanel: @Composable (Modifier, Boolean) -> Unit = { panelModifier, withBack ->
                        AssistantChatPanel(
                            chat = chat,
                            status = current,
                            draft = draft,
                            onDraftChange = { draft = it },
                            onSend = {
                                val text = draft.trim()
                                draft = ""
                                // Si el servidor rechaza el mensaje antes de empezar, el texto vuelve al campo.
                                chat.send(text) { rejected -> draft = rejected }
                            },
                            modifier = panelModifier,
                            onBack = if (withBack) ({ chatOpen = false }) else null,
                        )
                    }
                    if (AdaptiveLayout.roomy) {
                        Row(modifier = Modifier.fillMaxSize()) {
                            listPanel(Modifier.width(300.dp).fillMaxHeight())
                            Box(modifier = Modifier.width(1.dp).fillMaxHeight().background(colors.border))
                            chatPanel(Modifier.weight(1f).fillMaxHeight(), false)
                        }
                    } else if (chatOpen) {
                        chatPanel(Modifier.fillMaxSize(), true)
                    } else {
                        listPanel(Modifier.fillMaxSize())
                    }
                }
            }
        }
    }
}

@Composable
private fun ConversationListPanel(
    conversations: List<AssistantConversationResponse>,
    selectedId: String?,
    showArchived: Boolean,
    canLoadMore: Boolean,
    error: String?,
    canCreate: Boolean,
    onShowArchived: (Boolean) -> Unit,
    onNew: () -> Unit,
    onSelect: (AssistantConversationResponse) -> Unit,
    onRename: (AssistantConversationResponse) -> Unit,
    onArchive: (AssistantConversationResponse) -> Unit,
    onDelete: (AssistantConversationResponse) -> Unit,
    onLoadMore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AppTheme.colors
    Column(modifier = modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(tr("Conversaciones"), style = MaterialTheme.typography.titleMedium, color = colors.accentText, modifier = Modifier.weight(1f))
            Button(onClick = onNew, enabled = canCreate, colors = accentButtonColors()) { Text(tr("Nueva")) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = !showArchived, onClick = { onShowArchived(false) }, label = { Text(tr("Activas")) })
            FilterChip(selected = showArchived, onClick = { onShowArchived(true) }, label = { Text(tr("Archivadas")) })
        }
        error?.let { Text(it, color = colors.danger) }
        if (conversations.isEmpty() && error == null) {
            Text(
                if (showArchived) tr("No hay conversaciones archivadas.") else tr("Todavia no hay conversaciones. Empieza una nueva."),
                color = colors.muted,
            )
        }
        LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(conversations, key = { it.id }) { conversation ->
                ConversationRow(
                    conversation = conversation,
                    selected = conversation.id == selectedId,
                    onSelect = { onSelect(conversation) },
                    onRename = { onRename(conversation) },
                    onArchive = { onArchive(conversation) },
                    onDelete = { onDelete(conversation) },
                )
            }
            if (canLoadMore) {
                item(key = "more") {
                    TextButton(onClick = onLoadMore) { Text(tr("Cargar mas"), color = colors.accentText) }
                }
            }
        }
    }
}

@Composable
private fun ConversationRow(
    conversation: AssistantConversationResponse,
    selected: Boolean,
    onSelect: () -> Unit,
    onRename: () -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = AppTheme.colors
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (selected) colors.surfaceAlt else colors.surface, MaterialTheme.shapes.small)
            .clickable(onClick = onSelect)
            .padding(start = 10.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                conversation.title.ifBlank { tr("Conversacion nueva") },
                color = colors.text,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val caption = when {
                conversation.state == "awaiting_confirmation" || conversation.pendingConfirmations > 0 -> tr("Espera tu aprobacion")
                conversation.state == "running" -> tr("Respondiendo...")
                else -> dateTimeText(conversation.updatedAt)
            }
            Text(
                caption,
                style = MaterialTheme.typography.bodySmall,
                color = if (conversation.pendingConfirmations > 0) colors.warning else colors.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box {
            TextButton(onClick = { menuOpen = true }) { Text("⋮", color = colors.accentText) }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text(tr("Renombrar")) },
                    onClick = {
                        menuOpen = false
                        onRename()
                    },
                )
                DropdownMenuItem(
                    text = { Text(if (conversation.archived) tr("Desarchivar") else tr("Archivar")) },
                    onClick = {
                        menuOpen = false
                        onArchive()
                    },
                )
                DropdownMenuItem(
                    text = { Text(tr("Eliminar"), color = colors.danger) },
                    onClick = {
                        menuOpen = false
                        onDelete()
                    },
                )
            }
        }
    }
}

@Composable
private fun RenameDialog(current: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var title by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Renombrar conversacion")) },
        text = {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it.take(80) },
                label = { Text(tr("Titulo")) },
                singleLine = true,
                colors = appFieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { TextButton(onClick = { onSave(title.trim()) }, enabled = title.isNotBlank()) { Text(tr("Guardar")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Cancelar")) } },
    )
}
