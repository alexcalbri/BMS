package com.ideasdeveloper.bms.app.features.assistant.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ideasdeveloper.bms.AssistantStatusResponse
import com.ideasdeveloper.bms.app.core.i18n.tr
import com.ideasdeveloper.bms.app.core.theme.AppTheme
import com.ideasdeveloper.bms.app.core.theme.accentButtonColors
import com.ideasdeveloper.bms.app.core.theme.appFieldColors

// Panel del chat: mensajes (con la respuesta llegando en directo), avisos, uso del mes y campo de texto.
@Composable
internal fun AssistantChatPanel(
    chat: AssistantChatState,
    status: AssistantStatusResponse,
    draft: String,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
    // En pantallas estrechas el panel reemplaza a la lista y trae un boton para volver a ella.
    onBack: (() -> Unit)? = null,
) {
    val colors = AppTheme.colors
    Column(modifier = modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (onBack != null) {
            TextButton(onClick = onBack) { Text("‹ " + tr("Conversaciones"), color = colors.accentText) }
        }
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            AssistantMessageList(chat)
        }

        chat.banner?.let { banner ->
            Row(
                modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(banner.text, color = if (banner.error) colors.danger else colors.muted, modifier = Modifier.weight(1f))
                TextButton(onClick = chat::clearBanner) { Text(tr("Cerrar"), color = colors.accentText) }
            }
        }

        usageLine(chat, status)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.muted) }

        val running = !chat.streaming && chat.conversation?.state == "running"
        when {
            !status.available -> {
                // El historial sigue legible; en lugar del campo de texto se explica por que no se puede escribir.
                Text(
                    status.message?.takeIf { it.isNotBlank() } ?: tr("El asistente no esta disponible ahora."),
                    color = colors.text,
                )
                if (status.canManage && status.reason == "COMPANY_DISABLED") {
                    Text(tr("Puedes activarlo en Empresa, en la seccion Asistente."), style = MaterialTheme.typography.bodySmall, color = colors.muted)
                }
            }
            running -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(tr("Hay una respuesta en curso en esta conversacion."), color = colors.muted, modifier = Modifier.weight(1f, fill = false))
                TextButton(onClick = chat::reload) { Text(tr("Actualizar"), color = colors.accentText) }
            }
            else -> Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { onDraftChange(it.take(status.maxMessageChars)) },
                    placeholder = { Text(tr("Escribe tu mensaje")) },
                    minLines = 1,
                    maxLines = 5,
                    enabled = !chat.streaming,
                    colors = appFieldColors(),
                    modifier = Modifier.weight(1f),
                )
                if (chat.streaming) {
                    Button(
                        onClick = chat::stop,
                        colors = ButtonDefaults.buttonColors(containerColor = colors.surfaceAlt, contentColor = colors.danger),
                    ) { Text(tr("Detener")) }
                } else {
                    Button(
                        onClick = onSend,
                        enabled = draft.isNotBlank() && !chat.loading,
                        colors = accentButtonColors(),
                    ) { Text(tr("Enviar")) }
                }
            }
        }
    }
}

// "Uso del mes: 12.345 de 2.000.000 tokens"; sin cifras todavia no hay linea.
private fun usageLine(chat: AssistantChatState, status: AssistantStatusResponse): String? {
    val used = chat.monthUsed ?: return null
    val limit = chat.monthLimit
    return if (limit != null && status.limitApplies) {
        tr("Uso del mes: {0} de {1} tokens", tokensText(used), tokensText(limit))
    } else {
        tr("Uso del mes: {0} tokens (sin limite)", tokensText(used))
    }
}

@Composable
private fun AssistantMessageList(chat: AssistantChatState) {
    val colors = AppTheme.colors
    val listState = rememberLazyListState()
    // Mientras sigue el final, cada texto nuevo baja la lista; si la persona sube, deja de seguirla hasta que vuelva al final.
    var follow by remember { mutableStateOf(true) }
    var programmatic by remember { mutableStateOf(false) }
    val last = chat.entries.lastOrNull()
    val thinking = chat.streaming && last !is AssistantText
    val total = chat.entries.size + if (thinking) 1 else 0
    val lastLength = (last as? AssistantText)?.text?.length ?: 0

    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collect { scrolling ->
            if (!scrolling && !programmatic) follow = !listState.canScrollForward
        }
    }
    LaunchedEffect(chat.conversation?.id) { follow = true }
    LaunchedEffect(total, lastLength) {
        if (follow && total > 0) {
            programmatic = true
            try {
                listState.scrollToItem(total - 1)
                // Una respuesta larga ocupa mas que la ventana: baja hasta su final.
                listState.scrollBy(1_000_000f)
            } finally {
                programmatic = false
            }
        }
    }

    if (chat.entries.isEmpty() && !chat.streaming) {
        Text(
            if (chat.loading) tr("Cargando...") else tr("Pregunta lo que necesites sobre tu empresa. El asistente solo ve y hace lo que tus permisos permiten y te pide aprobacion antes de cambiar algo."),
            color = colors.muted,
            modifier = Modifier.padding(8.dp),
        )
        return
    }

    LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items(chat.entries, key = { it.key }) { entry ->
            ChatEntryView(entry, chat)
        }
        if (thinking) {
            item(key = "thinking") {
                Text(tr("Pensando..."), color = colors.muted, fontStyle = FontStyle.Italic)
            }
        }
    }
}

@Composable
private fun ChatEntryView(entry: ChatEntry, chat: AssistantChatState) {
    val colors = AppTheme.colors
    when (entry) {
        is UserText -> Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            Box(
                modifier = Modifier
                    .widthIn(max = 560.dp)
                    .background(colors.accent.copy(alpha = 0.16f), MaterialTheme.shapes.medium)
                    .padding(10.dp),
            ) {
                SelectionContainer { Text(entry.text, color = colors.text) }
            }
        }
        is AssistantText -> AssistantMarkdown(entry.text, modifier = Modifier.fillMaxWidth())
        is ToolLine -> {
            val failed = entry.status == "error"
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    "• " + toolLineText(entry),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (failed) colors.danger else colors.muted,
                )
                entry.message?.takeIf { failed }?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = colors.danger, modifier = Modifier.padding(start = 12.dp))
                }
            }
        }
        is NoticeLine -> Text(
            entry.text,
            style = MaterialTheme.typography.bodySmall,
            fontStyle = FontStyle.Italic,
            color = if (entry.code == "REFUSAL") colors.warning else colors.muted,
            modifier = Modifier.fillMaxWidth().background(colors.surfaceAlt, MaterialTheme.shapes.small).padding(8.dp),
        )
        is ConfirmationCard -> ConfirmationCardView(entry, chat)
    }
}

private fun toolLineText(entry: ToolLine): String = when (entry.status) {
    "running" -> tr("Consultando: {0}...", entry.label)
    "ok" -> tr("{0}: listo", entry.label)
    "error" -> tr("{0}: fallo", entry.label)
    "rejected" -> tr("{0}: rechazado", entry.label)
    "expired" -> tr("{0}: vencio sin respuesta", entry.label)
    "pending" -> tr("{0}: espera tu aprobacion", entry.label)
    "not_run" -> tr("{0}: no se ejecuto", entry.label)
    else -> entry.label
}

@Composable
// Accion que el asistente quiere hacer y espera aprobacion: que herramienta, de que modulo, con que datos y hasta cuando.
private fun ConfirmationCardView(card: ConfirmationCard, chat: AssistantChatState) {
    val colors = AppTheme.colors
    val confirmation = card.confirmation
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = 640.dp)
            .border(1.dp, if (confirmation.destructive) colors.danger else colors.border, MaterialTheme.shapes.medium)
            .background(colors.surfaceAlt, MaterialTheme.shapes.medium)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(tr("El asistente quiere hacer esto"), style = MaterialTheme.typography.labelMedium, color = colors.muted)
        Text(confirmation.label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = colors.text)
        if (confirmation.module.isNotBlank()) {
            Text(tr("Modulo: {0}", confirmation.module), style = MaterialTheme.typography.bodySmall, color = colors.muted)
        }
        if (confirmation.destructive) {
            Text(tr("Esta accion elimina o cambia datos y no se puede deshacer facilmente."), style = MaterialTheme.typography.bodySmall, color = colors.danger)
        }
        SelectionContainer {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                confirmation.summary.forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(row.label, style = MaterialTheme.typography.bodySmall, color = colors.muted, modifier = Modifier.weight(0.4f))
                        Text(row.value, style = MaterialTheme.typography.bodyMedium, color = colors.text, modifier = Modifier.weight(0.6f))
                    }
                }
            }
        }
        if (confirmation.expiresAt.isNotBlank()) {
            Text(tr("Vence: {0}", dateTimeText(confirmation.expiresAt)), style = MaterialTheme.typography.bodySmall, color = colors.muted)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val enabled = !chat.streaming && !card.deciding
            Button(onClick = { chat.decide(card, approve = true) }, enabled = enabled, colors = accentButtonColors()) { Text(tr("Aprobar")) }
            Button(
                onClick = { chat.decide(card, approve = false) },
                enabled = enabled,
                colors = ButtonDefaults.buttonColors(containerColor = colors.surface, contentColor = colors.text),
            ) { Text(tr("Rechazar")) }
            Spacer(Modifier.weight(1f))
        }
    }
}

// "2026-11-27T14:03:22.123Z" -> "2026-11-27 14:03 UTC".
internal fun dateTimeText(iso: String): String {
    if (iso.length < 16) return iso
    return iso.substring(0, 16).replace('T', ' ') + if (iso.endsWith("Z")) " UTC" else ""
}
