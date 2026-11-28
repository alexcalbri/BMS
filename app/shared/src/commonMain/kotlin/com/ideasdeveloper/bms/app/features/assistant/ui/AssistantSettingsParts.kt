package com.ideasdeveloper.bms.app.features.assistant.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.ideasdeveloper.bms.app.core.i18n.tr
import com.ideasdeveloper.bms.app.core.theme.AppTheme
import com.ideasdeveloper.bms.app.core.theme.accentButtonColors
import com.ideasdeveloper.bms.app.core.theme.appFieldColors

// Piezas comunes de los ajustes del asistente (empresa y servidor).

// Texto que el servidor manda como aviso de privacidad; si falta, el de la especificacion.
internal fun disclosureText(fromServer: String): String =
    fromServer.ifBlank { tr("Los datos que el asistente consulte se envian a Anthropic para generar la respuesta.") }

@Composable
internal fun AssistantSwitchRow(
    title: String,
    description: String?,
    checked: Boolean,
    enabled: Boolean,
    onChange: (Boolean) -> Unit,
) {
    val colors = AppTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = colors.accentText)
            description?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.muted) }
        }
        Switch(checked = checked, enabled = enabled, onCheckedChange = onChange)
    }
}

// Aviso de privacidad antes de activar el asistente: hay que aceptarlo de forma explicita.
@Composable
internal fun AssistantDisclosureDialog(disclosure: String, onAccept: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Antes de activar el asistente")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(disclosureText(disclosure))
                Text(tr("Al activarlo confirmas que conoces y aceptas este aviso."), color = AppTheme.colors.muted)
            }
        },
        confirmButton = { TextButton(onClick = onAccept) { Text(tr("Aceptar y activar")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Cancelar")) } },
    )
}

// Clave de la API de Anthropic: solo se escribe, nunca se muestra; del servidor solo vuelve una pista (sk-ant-... y los
// ultimos 4 caracteres). Al guardar, el campo se vacia: la clave se envia una vez y no queda en el estado de la pantalla.
@Composable
internal fun AssistantKeyEditor(
    keySet: Boolean,
    hint: String?,
    keyInput: String,
    onKeyInputChange: (String) -> Unit,
    busy: Boolean,
    onSave: () -> Unit,
    onRemove: () -> Unit,
) {
    val colors = AppTheme.colors
    Text(
        if (keySet) tr("Clave guardada: {0}", hint?.takeIf { it.isNotBlank() } ?: "sk-ant-...") else tr("Todavia no hay una clave guardada."),
        color = colors.text,
    )
    OutlinedTextField(
        value = keyInput,
        onValueChange = onKeyInputChange,
        label = { Text(if (keySet) tr("Nueva clave de Anthropic (reemplaza la actual)") else tr("Clave de Anthropic")) },
        singleLine = true,
        enabled = !busy,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        colors = appFieldColors(),
        modifier = Modifier.fillMaxWidth(),
    )
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
        if (keySet) {
            Button(
                onClick = onRemove,
                enabled = !busy,
                colors = ButtonDefaults.buttonColors(containerColor = colors.surfaceAlt, contentColor = colors.danger),
            ) { Text(tr("Quitar clave")) }
        }
        Button(onClick = onSave, enabled = !busy && keyInput.isNotBlank(), colors = accentButtonColors()) { Text(tr("Guardar clave")) }
    }
}

// Etiqueta legible de la clave con la que se contaron los tokens.
internal fun keySourceLabel(source: String): String = when (source) {
    "company" -> tr("clave propia")
    "server" -> tr("clave del servidor")
    else -> source
}
