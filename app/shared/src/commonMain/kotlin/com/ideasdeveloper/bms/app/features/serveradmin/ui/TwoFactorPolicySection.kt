package com.ideasdeveloper.bms.app.features.serveradmin.ui

import com.ideasdeveloper.bms.app.core.i18n.tr
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ideasdeveloper.bms.app.core.theme.AppTheme
import com.ideasdeveloper.bms.app.features.auth.data.AccountApi
import com.ideasdeveloper.bms.app.features.auth.data.LoginException
import com.ideasdeveloper.bms.app.features.auth.data.use
import com.ideasdeveloper.bms.app.features.shell.ui.ConfirmDialog
import kotlinx.coroutines.launch

/**
 * Interruptor del dueno del servidor: exigir la verificacion en dos pasos a los administradores de servidor
 * (TWO_FACTOR.md seccion 5). Mientras este activo, un administrador que no la tenga solo puede activarla.
 */
@Composable
fun TwoFactorPolicySection(serverUrl: String?, accessToken: String?) {
    val colors = AppTheme.colors
    val scope = rememberCoroutineScope()
    var required by remember { mutableStateOf<Boolean?>(null) }
    var unsupported by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var confirmingEnable by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(serverUrl, accessToken) {
        val url = serverUrl ?: return@LaunchedEffect
        val token = accessToken ?: return@LaunchedEffect
        busy = true
        try {
            val loaded = AccountApi(url).use { it.twoFactorPolicy(token) }
            // Sin la ruta (404) el servidor aun no tiene la verificacion en dos pasos.
            unsupported = loaded == null
            required = loaded?.requireForAdmins
            error = null
        } catch (exception: LoginException) {
            error = exception.message
        } finally {
            busy = false
        }
    }

    fun change(value: Boolean) {
        val url = serverUrl ?: return
        val token = accessToken ?: return
        busy = true
        error = null
        scope.launch {
            try {
                required = AccountApi(url).use { it.setTwoFactorPolicy(token, value) }.requireForAdmins
            } catch (exception: LoginException) {
                error = exception.message
            } finally {
                busy = false
            }
        }
    }

    if (confirmingEnable) {
        ConfirmDialog(
            title = tr("Exigir verificacion en dos pasos"),
            message = tr("Los administradores del servidor que aun no la tengan solo podran activarla hasta que lo hagan: no podran usar nada mas, y las empresas que abrieron dejaran de funcionarles."),
            confirmLabel = tr("Exigirla"),
            onConfirm = {
                confirmingEnable = false
                change(true)
            },
            onDismiss = { confirmingEnable = false },
        )
    }

    if (unsupported) {
        Text(tr("Este servidor todavia no admite la verificacion en dos pasos."), color = colors.muted)
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    tr("Exigir verificacion en dos pasos a los administradores de servidor"),
                    style = MaterialTheme.typography.titleSmall,
                    color = colors.accentText,
                )
                Text(
                    tr("Cada administrador activa la verificacion con una aplicacion de autenticacion; hasta entonces solo puede activarla."),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.muted,
                )
            }
            Switch(
                checked = required == true,
                enabled = required != null && !busy,
                onCheckedChange = { value -> if (value) confirmingEnable = true else change(false) },
            )
        }
        error?.let { Text(it, color = colors.danger) }
    }
}
