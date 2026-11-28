package com.ideasdeveloper.bms.app.features.assistant.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.ideasdeveloper.bms.AssistantCompanySettingsRequest
import com.ideasdeveloper.bms.AssistantCompanySettingsResponse
import com.ideasdeveloper.bms.AssistantCompanyUsageResponse
import com.ideasdeveloper.bms.app.core.i18n.tr
import com.ideasdeveloper.bms.app.core.theme.AppTheme
import com.ideasdeveloper.bms.app.features.assistant.data.AssistantApi
import com.ideasdeveloper.bms.app.features.assistant.data.use
import com.ideasdeveloper.bms.app.features.auth.data.LoginResponse
import com.ideasdeveloper.bms.app.features.company.data.CompanyException
import com.ideasdeveloper.bms.app.features.shell.ui.CollapsibleSection
import com.ideasdeveloper.bms.app.features.shell.ui.ConfirmDialog
import kotlinx.coroutines.launch

/**
 * Seccion del business owner en Empresa: activar el asistente (con el aviso de privacidad), su clave propia de Anthropic,
 * el permiso que necesitan los usuarios y el uso del mes. No se muestra si el servidor no la tiene (Core anterior a 0.14.0)
 * o si la sesion no es la del business owner.
 */
@Composable
fun AssistantCompanySettingsSection(
    serverUrl: String?,
    session: LoginResponse?,
    primaryColor: Color,
    // false: el contenido va suelto (dialogo del boton flotante), sin la seccion plegable y con avisos en lugar de ocultarse.
    framed: Boolean = true,
    // Se llama despues de guardar un cambio (activar, clave...) para que quien la muestra vuelva a pedir el estado.
    onChanged: () -> Unit = {},
) {
    val token = session?.accessToken
    val companyCode = session?.companyCode
    var settings by remember { mutableStateOf<AssistantCompanySettingsResponse?>(null) }
    var usage by remember { mutableStateOf<AssistantCompanyUsageResponse?>(null) }
    var hidden by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(serverUrl, token, companyCode) {
        val url = serverUrl ?: return@LaunchedEffect
        val accessToken = token ?: return@LaunchedEffect
        val company = companyCode ?: return@LaunchedEffect
        try {
            settings = AssistantApi(url).use { api -> api.settings(accessToken, company) }
            hidden = false
            loadError = null
        } catch (exception: CompanyException) {
            // 404: Core anterior; 403: no es el business owner. En ambos casos la seccion no existe para esta sesion.
            if (exception.status == 404 || exception.status == 403) hidden = true else loadError = exception.message
            return@LaunchedEffect
        }
        usage = try {
            AssistantApi(url).use { api -> api.usage(accessToken, company, null) }
        } catch (_: CompanyException) {
            null
        }
    }

    if (!framed) {
        val current = settings
        when {
            hidden -> Text(tr("Tu usuario no puede cambiar los ajustes del asistente en esta empresa."), color = AppTheme.colors.text)
            current != null -> AssistantCompanySettingsContent(
                serverUrl = serverUrl,
                token = token,
                companyCode = companyCode,
                settings = current,
                usage = usage,
                onSettings = { settings = it },
                onChanged = onChanged,
            )
            loadError != null -> Text(loadError.orEmpty(), color = AppTheme.colors.danger)
            else -> Text(tr("Cargando..."), color = AppTheme.colors.muted)
        }
        return
    }
    if (hidden || (settings == null && loadError == null)) return
    CollapsibleSection(tr("Asistente"), primaryColor, subtitle = tr("Activacion, clave propia y uso")) {
        val current = settings
        if (current == null) {
            Text(loadError.orEmpty(), color = AppTheme.colors.danger)
        } else {
            AssistantCompanySettingsContent(
                serverUrl = serverUrl,
                token = token,
                companyCode = companyCode,
                settings = current,
                usage = usage,
                onSettings = { settings = it },
                onChanged = onChanged,
            )
        }
    }
}

@Composable
private fun AssistantCompanySettingsContent(
    serverUrl: String?,
    token: String?,
    companyCode: String?,
    settings: AssistantCompanySettingsResponse,
    usage: AssistantCompanyUsageResponse?,
    onSettings: (AssistantCompanySettingsResponse) -> Unit,
    onChanged: () -> Unit,
) {
    val colors = AppTheme.colors
    val scope = rememberCoroutineScope()
    var keyInput by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmingEnable by remember { mutableStateOf(false) }
    var confirmingKeyRemoval by remember { mutableStateOf(false) }

    fun save(request: AssistantCompanySettingsRequest, success: String) {
        val url = serverUrl ?: return
        val accessToken = token ?: return
        val company = companyCode ?: return
        busy = true
        error = null
        message = null
        scope.launch {
            try {
                onSettings(AssistantApi(url).use { api -> api.updateSettings(accessToken, company, request) })
                message = success
                onChanged()
            } catch (exception: CompanyException) {
                error = exception.message
            } finally {
                busy = false
            }
        }
    }

    if (confirmingEnable) {
        AssistantDisclosureDialog(
            disclosure = settings.disclosure,
            onAccept = {
                confirmingEnable = false
                save(AssistantCompanySettingsRequest(enabled = true, acceptDisclosure = true), tr("Asistente activado."))
            },
            onDismiss = { confirmingEnable = false },
        )
    }
    if (confirmingKeyRemoval) {
        ConfirmDialog(
            title = tr("Quitar la clave propia"),
            message = tr("La empresa dejara de usar su clave propia. Si el servidor tiene una clave, se usara esa; si no, el asistente dejara de funcionar."),
            confirmLabel = tr("Quitar clave"),
            onConfirm = {
                confirmingKeyRemoval = false
                save(AssistantCompanySettingsRequest(removeApiKey = true), tr("Clave quitada."))
            },
            onDismiss = { confirmingKeyRemoval = false },
        )
    }

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            tr("El asistente responde preguntas y hace tareas en la app con los permisos de cada usuario, y pide aprobacion antes de cambiar datos. Para responder envia a Anthropic los datos que consulta."),
            color = colors.muted,
        )
        if (!settings.serverEnabled) {
            Text(tr("El dueno del servidor tiene el asistente desactivado."), color = colors.warning)
        } else if (!settings.companyAllowed) {
            Text(tr("El servidor no permite el asistente en esta empresa."), color = colors.warning)
        }
        AssistantSwitchRow(
            title = tr("Activar el asistente en esta empresa"),
            description = if (settings.enabled) tr("Activado") else tr("Desactivado"),
            checked = settings.enabled,
            enabled = !busy && settings.serverEnabled && settings.companyAllowed,
            onChange = { value ->
                if (value) confirmingEnable = true else save(AssistantCompanySettingsRequest(enabled = false), tr("Asistente desactivado."))
            },
        )

        Text(tr("Clave propia de Anthropic"), style = MaterialTheme.typography.titleSmall, color = colors.accentText)
        Text(
            if (settings.ownKeySet) {
                tr("La empresa usa su clave propia.")
            } else if (settings.serverKeySet) {
                tr("Sin clave propia: se usa la clave del servidor.")
            } else {
                tr("No hay ninguna clave: el asistente no funcionara hasta que haya una.")
            },
            style = MaterialTheme.typography.bodySmall,
            color = colors.muted,
        )
        AssistantKeyEditor(
            keySet = settings.ownKeySet,
            hint = settings.ownKeyHint,
            keyInput = keyInput,
            onKeyInputChange = { keyInput = it },
            busy = busy,
            onSave = {
                // La clave se envia una vez y se borra del estado de la pantalla.
                val key = keyInput.trim()
                keyInput = ""
                save(AssistantCompanySettingsRequest(apiKey = key), tr("Clave guardada."))
            },
            onRemove = { confirmingKeyRemoval = true },
        )

        error?.let { Text(it, color = colors.danger) }
        message?.let { Text(it, color = colors.success) }

        Text(tr("Quien puede usarlo"), style = MaterialTheme.typography.titleSmall, color = colors.accentText)
        Text(
            tr("Los usuarios necesitan el permiso \"Usar el asistente\" ({0}) para ver y usar el asistente; el business owner lo tiene siempre. Concedelo en la seccion de usuarios de esta pantalla (Usuarios y permisos).", settings.permissionId),
            color = colors.text,
        )

        Text(tr("Limites"), style = MaterialTheme.typography.titleSmall, color = colors.accentText)
        Text(
            settings.monthlyLimitTokens?.let { tr("Limite mensual: {0} tokens.", tokensText(it)) } ?: tr("Limite mensual: sin limite."),
            color = colors.text,
        )
        if (settings.ownKeySet && !settings.limitAppliesToOwnKey) {
            Text(tr("El limite no se aplica mientras la empresa use su clave propia."), style = MaterialTheme.typography.bodySmall, color = colors.muted)
        }
        Text(tr("Modelo: {0}", settings.model), color = colors.text)
        Text(tr("Las conversaciones se conservan {0} dias sin actividad y luego se eliminan.", settings.retentionDays), color = colors.text)

        usage?.let { UsageBlock(it) }
    }
}

@Composable
private fun UsageBlock(usage: AssistantCompanyUsageResponse) {
    val colors = AppTheme.colors
    Text(tr("Uso de {0}", usage.month), style = MaterialTheme.typography.titleSmall, color = colors.accentText)
    Text(
        usage.monthlyLimitTokens?.let { tr("{0} de {1} tokens", tokensText(usage.usedTokens), tokensText(it)) }
            ?: tr("{0} tokens (sin limite)", tokensText(usage.usedTokens)),
        color = colors.text,
    )
    if (usage.users.isEmpty()) {
        Text(tr("Nadie ha usado el asistente este mes."), color = colors.muted)
    }
    usage.users.forEach { user ->
        Text(
            tr("{0}: {1} solicitudes, {2} tokens ({3})", user.name, user.requests, tokensText(user.countedTokens), keySourceLabel(user.keySource)),
            style = MaterialTheme.typography.bodySmall,
            color = colors.text,
        )
    }
}
