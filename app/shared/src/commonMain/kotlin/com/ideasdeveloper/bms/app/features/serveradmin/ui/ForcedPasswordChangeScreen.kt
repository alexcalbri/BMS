package com.ideasdeveloper.bms.app.features.serveradmin.ui

import com.ideasdeveloper.bms.app.core.i18n.tr
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.ideasdeveloper.bms.app.core.layout.AdaptiveLayout
import com.ideasdeveloper.bms.app.core.layout.ContentWidth
import com.ideasdeveloper.bms.app.core.session.SessionStore
import com.ideasdeveloper.bms.app.core.theme.AppTheme
import com.ideasdeveloper.bms.app.core.theme.accentButtonColors
import com.ideasdeveloper.bms.app.features.company.data.CompanyException
import com.ideasdeveloper.bms.app.features.serveradmin.data.ServerAdminApi
import com.ideasdeveloper.bms.app.features.serveradmin.data.use
import kotlinx.coroutines.launch

// Longitud minima de la contrasena de un administrador del servidor (igual que la del dueno).
const val ServerPasswordMinimum = 16

/**
 * Pantalla bloqueante: el administrador debe cambiar la contrasena inicial antes de usar nada mas.
 * Cubre toda la app hasta que el servidor acepta el cambio; solo permite cerrar sesion.
 */
@Composable
fun ForcedPasswordChangeScreen(
    serverUrl: String?,
    accessToken: String,
    onLogout: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AppTheme.colors
    val scope = rememberCoroutineScope()
    var current by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val problem = when {
        newPassword.isNotEmpty() && newPassword.length < ServerPasswordMinimum -> tr("Minimo {0} caracteres.", ServerPasswordMinimum)
        confirmation.isNotEmpty() && confirmation != newPassword -> tr("Las contrasenas no coinciden.")
        newPassword.isNotEmpty() && newPassword == current -> tr("La contrasena nueva debe ser distinta de la actual.")
        else -> null
    }

    Box(modifier = modifier.fillMaxSize().background(colors.screenBrush), contentAlignment = Alignment.Center) {
        Surface(
            modifier = Modifier
                .widthIn(max = ContentWidth.Form)
                .fillMaxWidth()
                .padding(AdaptiveLayout.screenPadding),
            color = colors.surface,
            shape = MaterialTheme.shapes.medium,
        ) {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()).padding(AdaptiveLayout.screenPadding),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(tr("Cambia tu contrasena"), style = MaterialTheme.typography.titleLarge, color = colors.accentText)
                Text(
                    tr("El dueno del servidor te dio una contrasena inicial. Debes cambiarla ahora para poder continuar."),
                    color = colors.muted,
                )
                OutlinedTextField(
                    value = current,
                    onValueChange = { current = it },
                    label = { Text(tr("Contrasena actual")) },
                    singleLine = true,
                    enabled = !saving,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = newPassword,
                    onValueChange = { newPassword = it },
                    label = { Text(tr("Nueva contrasena (minimo {0})", ServerPasswordMinimum)) },
                    singleLine = true,
                    enabled = !saving,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = confirmation,
                    onValueChange = { confirmation = it },
                    label = { Text(tr("Confirmar contrasena")) },
                    singleLine = true,
                    enabled = !saving,
                    visualTransformation = PasswordVisualTransformation(),
                    isError = problem != null,
                    supportingText = problem?.let { message -> { Text(message) } },
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let { Text(it, color = colors.danger) }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                    TextButton(onClick = onLogout, enabled = !saving) { Text(tr("Cerrar sesion")) }
                    Button(
                        enabled = !saving && serverUrl != null && current.isNotEmpty() && problem == null &&
                            newPassword.length >= ServerPasswordMinimum && newPassword == confirmation,
                        onClick = {
                            val url = serverUrl ?: return@Button
                            saving = true
                            error = null
                            scope.launch {
                                try {
                                    ServerAdminApi(url).use { api -> api.changeOwnPassword(accessToken, current, newPassword) }
                                    current = ""
                                    newPassword = ""
                                    confirmation = ""
                                    SessionStore.update { it.copy(mustChangePassword = false) }
                                } catch (exception: CompanyException) {
                                    error = exception.message
                                } finally {
                                    saving = false
                                }
                            }
                        },
                        colors = accentButtonColors(),
                    ) { Text(tr("Cambiar contrasena")) }
                }
            }
        }
    }
}
