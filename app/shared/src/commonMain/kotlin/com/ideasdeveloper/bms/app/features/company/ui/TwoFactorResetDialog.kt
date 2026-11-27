package com.ideasdeveloper.bms.app.features.company.ui

import com.ideasdeveloper.bms.app.core.i18n.tr
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.ideasdeveloper.bms.app.core.theme.AppTheme
import com.ideasdeveloper.bms.app.features.company.data.CompanyException
import com.ideasdeveloper.bms.app.features.company.data.CompanySwitch
import com.ideasdeveloper.bms.app.features.company.data.TwoFactorResetResponse
import kotlinx.coroutines.launch

/**
 * Desactivar la verificacion en dos pasos de otra persona (TWO_FACTOR.md 8): explica el efecto y pide la contrasena de
 * quien lo hace. [submit] llama a la ruta que corresponda; [onDone] recibe el mensaje del servidor (y el llamador
 * actualiza su lista). Si la sesion debe volver a iniciarse con su codigo (403 TWO_FACTOR_REAUTH_REQUIRED) se abre el
 * dialogo comun de volver a iniciar sesion; si la persona ya no la tenia activa (409) se actualiza la lista.
 */
@Composable
fun TwoFactorResetDialog(
    targetName: String,
    submit: suspend (currentPassword: String) -> TwoFactorResetResponse,
    onDone: (message: String) -> Unit,
    onDismiss: () -> Unit,
    // Aviso extra bajo la explicacion (p. ej. de que contrasena se trata).
    hint: String? = null,
) {
    val scope = rememberCoroutineScope()
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(tr("Desactivar verificacion en dos pasos")) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(tr("Vas a desactivar la verificacion en dos pasos de {0}.", targetName), color = AppTheme.colors.text)
                Text(
                    tr("Se cerraran todas sus sesiones, se desconectaran sus agentes de IA y recibira un aviso. Despues podra entrar solo con su contrasena y debera activar la verificacion de nuevo."),
                    color = AppTheme.colors.warning,
                )
                Text(tr("Para confirmar escribe tu contrasena actual."), color = AppTheme.colors.muted)
                hint?.let { Text(it, color = AppTheme.colors.muted) }
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(tr("Contrasena actual")) },
                    singleLine = true,
                    enabled = !busy,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let { Text(it, color = AppTheme.colors.danger) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && password.isNotEmpty(),
                onClick = {
                    busy = true
                    error = null
                    scope.launch {
                        try {
                            val result = submit(password)
                            password = ""
                            onDone(result.message.ifBlank { tr("Se desactivo la verificacion en dos pasos de {0}.", targetName) })
                        } catch (exception: CompanyException) {
                            when (exception.code) {
                                "TWO_FACTOR_REAUTH_REQUIRED" -> {
                                    CompanySwitch.requestReauth(exception.message ?: tr("Vuelve a iniciar sesion con tu codigo de dos pasos."))
                                    onDismiss()
                                }
                                // Ya no la tenia activa: se cierra y el llamador actualiza la lista.
                                "TWO_FACTOR_NOT_ENABLED" -> onDone(exception.message ?: tr("Esa persona ya no tiene la verificacion en dos pasos activa."))
                                else -> error = exception.message
                            }
                        } finally {
                            busy = false
                        }
                    }
                },
            ) { Text(tr("Desactivar"), color = AppTheme.colors.danger) }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(tr("Cancelar")) } },
    )
}
