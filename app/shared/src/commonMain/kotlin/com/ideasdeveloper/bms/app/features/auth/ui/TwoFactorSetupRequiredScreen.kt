package com.ideasdeveloper.bms.app.features.auth.ui

import com.ideasdeveloper.bms.app.core.i18n.tr
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ideasdeveloper.bms.app.core.layout.AdaptiveLayout
import com.ideasdeveloper.bms.app.core.layout.ContentWidth
import com.ideasdeveloper.bms.app.core.session.SessionStore
import com.ideasdeveloper.bms.app.core.theme.AppTheme
import com.ideasdeveloper.bms.app.features.auth.data.LoginResponse

/**
 * Pantalla bloqueante: el dueno del servidor exige la verificacion en dos pasos a los administradores y esta
 * cuenta aun no la tiene. Cubre toda la app hasta que se activa (el servidor responde 403
 * TWO_FACTOR_SETUP_REQUIRED a todo lo demas); solo permite activarla o cerrar sesion.
 */
@Composable
fun TwoFactorSetupRequiredScreen(
    serverUrl: String?,
    session: LoginResponse,
    onLogout: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AppTheme.colors
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
                Text(tr("Activa la verificacion en dos pasos"), style = MaterialTheme.typography.titleLarge, color = colors.accentText)
                Text(
                    tr("El dueno del servidor exige la verificacion en dos pasos a los administradores. Activala ahora para poder continuar."),
                    color = colors.muted,
                )
                TwoFactorSection(
                    serverUrl = serverUrl,
                    session = session,
                    onEnabled = { SessionStore.update { it.copy(twoFactorSetupRequired = false) } },
                )
                TextButton(onClick = onLogout) { Text(tr("Cerrar sesion")) }
            }
        }
    }
}
