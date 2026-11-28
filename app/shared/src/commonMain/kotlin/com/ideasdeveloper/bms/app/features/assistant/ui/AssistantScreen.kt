package com.ideasdeveloper.bms.app.features.assistant.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ideasdeveloper.bms.AssistantConversationResponse
import com.ideasdeveloper.bms.AssistantIds
import com.ideasdeveloper.bms.AssistantStatusResponse
import com.ideasdeveloper.bms.app.core.i18n.tr
import com.ideasdeveloper.bms.app.core.layout.AdaptiveLayout
import com.ideasdeveloper.bms.app.core.theme.AppTheme
import com.ideasdeveloper.bms.app.core.theme.accentButtonColors
import com.ideasdeveloper.bms.app.core.theme.appFieldColors
import com.ideasdeveloper.bms.app.features.shell.ui.ConfirmDialog

@Composable
// Contenido del panel del asistente (lista de conversaciones y conversacion abierta). Todo el estado vive en [controller],
// que esta por encima de las pantallas; aqui solo se dibuja y se abren los dialogos de renombrar y eliminar.
// [twoPane] muestra la lista y el chat a la vez; si no, se turnan. [onActivate] abre los ajustes del asistente cuando el
// servidor dice que esta sesion puede gestionarlo (null = no hay accion).
internal fun AssistantPanelBody(
    controller: AssistantController,
    twoPane: Boolean,
    onActivate: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val colors = AppTheme.colors
    var renaming by remember { mutableStateOf<AssistantConversationResponse?>(null) }
    var deleting by remember { mutableStateOf<AssistantConversationResponse?>(null) }
    val chat = controller.chat

    renaming?.let { target ->
        RenameDialog(
            current = target.title,
            onSave = { title ->
                renaming = null
                controller.rename(target, title)
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
                controller.delete(target)
            },
            onDismiss = { deleting = null },
        )
    }

    val current = controller.status
    val error = controller.statusError
    if (!controller.statusLoaded) {
        Text(tr("Cargando..."), color = colors.muted, modifier = modifier.padding(AdaptiveLayout.screenPadding))
    } else if (current == null || !current.visible) {
        Column(modifier = modifier.padding(AdaptiveLayout.screenPadding), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                error ?: current?.message?.takeIf { it.isNotBlank() } ?: tr("El asistente no esta disponible para tu usuario."),
                color = if (error != null) colors.danger else colors.text,
            )
            if (error != null) {
                Button(onClick = { controller.loadStatus() }, colors = accentButtonColors()) { Text(tr("Reintentar")) }
            }
        }
    } else {
        Column(modifier = modifier) {
            // Visible pero no disponible: el servidor dice por que y, si la sesion puede gestionarlo, se ofrece activarlo.
            if (!current.available) {
                UnavailableNotice(status = current, onActivate = onActivate)
            }
            val listPanel: @Composable (Modifier) -> Unit = { panelModifier ->
                ConversationListPanel(
                    conversations = controller.conversations,
                    selectedId = chat.conversation?.id,
                    showArchived = controller.showArchived,
                    canLoadMore = controller.nextBefore != null,
                    error = controller.listError,
                    canCreate = current.available,
                    onShowArchived = controller::setShowArchived,
                    onNew = controller::newConversation,
                    onSelect = controller::openConversation,
                    onRename = { renaming = it },
                    onArchive = { target -> controller.archive(target, !target.archived) },
                    onDelete = { deleting = it },
                    onLoadMore = { controller.loadList(false) },
                    modifier = panelModifier,
                )
            }
            val chatPanel: @Composable (Modifier, Boolean) -> Unit = { panelModifier, withBack ->
                AssistantChatPanel(
                    chat = chat,
                    status = current,
                    draft = controller.draft,
                    onDraftChange = { controller.draft = it },
                    onSend = controller::sendDraft,
                    onNewConversation = controller::newConversation,
                    modifier = panelModifier,
                    onBack = if (withBack) ({ controller.chatOpen = false }) else null,
                )
            }
            val contentModifier = Modifier.weight(1f).fillMaxWidth()
            if (twoPane) {
                Row(modifier = contentModifier) {
                    listPanel(Modifier.width(300.dp).fillMaxHeight())
                    Box(modifier = Modifier.width(1.dp).fillMaxHeight().background(colors.border))
                    chatPanel(Modifier.weight(1f).fillMaxHeight(), false)
                }
            } else if (controller.chatOpen) {
                chatPanel(contentModifier, true)
            } else {
                listPanel(contentModifier)
            }
        }
    }
}

// Aviso de que el asistente no se puede usar ahora (mensaje del servidor) con la accion para activarlo si la sesion puede.
@Composable
private fun UnavailableNotice(status: AssistantStatusResponse, onActivate: (() -> Unit)?) {
    val colors = AppTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, top = 12.dp)
            .background(colors.surfaceAlt, MaterialTheme.shapes.small)
            .padding(12.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            status.message?.takeIf { it.isNotBlank() } ?: tr("El asistente no esta disponible ahora."),
            color = colors.text,
        )
        if (status.canManage && onActivate != null) {
            val settingsOnly = status.reason == AssistantIds.ReasonNoApiKey ||
                status.reason == AssistantIds.ReasonLimitReached ||
                status.reason == AssistantIds.ReasonSecretsUnavailable
            Button(onClick = onActivate, colors = accentButtonColors()) {
                Text(if (settingsOnly) tr("Ajustes del asistente") else tr("Activar asistente"))
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
                conversation.state == "closed" -> tr("Cerrada")
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
