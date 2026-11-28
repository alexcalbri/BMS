package com.ideasdeveloper.bms.app.features.assistant.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.ideasdeveloper.bms.AssistantIds
import com.ideasdeveloper.bms.AssistantServerCompany
import com.ideasdeveloper.bms.AssistantServerSettingsRequest
import com.ideasdeveloper.bms.AssistantServerSettingsResponse
import com.ideasdeveloper.bms.AssistantServerUsageResponse
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
import com.ideasdeveloper.bms.app.features.shell.ui.CollapsibleSection
import com.ideasdeveloper.bms.app.features.shell.ui.ConfirmDialog
import kotlinx.coroutines.launch

@Composable
// Ajustes del asistente en el servidor (permiso server.assistant.manage): activacion, clave, modelo, limites, empresas y uso.
fun AssistantServerScreen(
    serverUrl: String?,
    session: LoginResponse?,
    onSettings: () -> Unit,
    onLogout: () -> Unit,
    onReturnToDashboard: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AppTheme.colors
    val scope = rememberCoroutineScope()
    val token = session?.accessToken

    var settings by remember { mutableStateOf<AssistantServerSettingsResponse?>(null) }
    var companies by remember { mutableStateOf<List<AssistantServerCompany>>(emptyList()) }
    var usage by remember { mutableStateOf<AssistantServerUsageResponse?>(null) }
    var loading by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    // Campos que se guardan juntos con "Guardar".
    var model by remember { mutableStateOf("") }
    var effort by remember { mutableStateOf("") }
    var tokensInput by remember { mutableStateOf("") }
    var retentionInput by remember { mutableStateOf("") }
    var limitOwnKey by remember { mutableStateOf(false) }
    var keyInput by remember { mutableStateOf("") }
    var confirmingEnable by remember { mutableStateOf(false) }
    var confirmingKeyRemoval by remember { mutableStateOf(false) }

    fun show(loaded: AssistantServerSettingsResponse) {
        settings = loaded
        model = loaded.model
        effort = loaded.effort
        tokensInput = loaded.defaultMonthlyTokens.toString()
        retentionInput = loaded.retentionDays.toString()
        limitOwnKey = loaded.limitOwnKeyCompanies
    }

    LaunchedEffect(serverUrl, token) {
        val url = serverUrl ?: return@LaunchedEffect
        val accessToken = token ?: return@LaunchedEffect
        loading = true
        // Un administrador sin alcance sobre todas las empresas recibe 403 en los ajustes y la clave: se muestra el
        // mensaje del servidor y la lista de empresas sigue disponible.
        try {
            show(AssistantApi(url).use { api -> api.serverSettings(accessToken) })
            error = null
        } catch (exception: CompanyException) {
            error = exception.message
        }
        try {
            companies = AssistantApi(url).use { api -> api.serverCompanies(accessToken) }
        } catch (exception: CompanyException) {
            if (error == null) error = exception.message
        }
        try {
            usage = AssistantApi(url).use { api -> api.serverUsage(accessToken, null) }
        } catch (exception: CompanyException) {
            if (error == null) error = exception.message
        } finally {
            loading = false
        }
    }

    // Ejecuta un cambio en el servidor y recarga los ajustes que devuelve.
    fun change(success: String, action: suspend (AssistantApi, String) -> AssistantServerSettingsResponse) {
        val url = serverUrl ?: return
        val accessToken = token ?: return
        busy = true
        error = null
        message = null
        scope.launch {
            try {
                show(AssistantApi(url).use { api -> action(api, accessToken) })
                message = success
            } catch (exception: CompanyException) {
                error = exception.message
            } finally {
                busy = false
            }
        }
    }

    fun saveFields() {
        val defaultTokens = tokensInput.trim().toLongOrNull()
        val retention = retentionInput.trim().toIntOrNull()
        if (defaultTokens == null || defaultTokens < 0) {
            error = tr("Escribe un limite mensual valido (0 = sin limite).")
            message = null
            return
        }
        if (retention == null || retention < 1 || retention > 3650) {
            error = tr("Escribe los dias de retencion entre 1 y 3650.")
            message = null
            return
        }
        val request = AssistantServerSettingsRequest(
            model = model,
            effort = effort,
            defaultMonthlyTokens = defaultTokens,
            limitOwnKeyCompanies = limitOwnKey,
            retentionDays = retention,
        )
        change(tr("Ajustes guardados.")) { api, accessToken -> api.updateServerSettings(accessToken, request) }
    }

    if (confirmingEnable) {
        AssistantDisclosureDialog(
            disclosure = settings?.disclosure.orEmpty(),
            onAccept = {
                confirmingEnable = false
                val request = AssistantServerSettingsRequest(enabled = true, acceptDisclosure = true)
                change(tr("Asistente activado.")) { api, accessToken -> api.updateServerSettings(accessToken, request) }
            },
            onDismiss = { confirmingEnable = false },
        )
    }
    if (confirmingKeyRemoval) {
        ConfirmDialog(
            title = tr("Quitar la clave del servidor"),
            message = tr("Las empresas sin clave propia dejaran de poder usar el asistente."),
            confirmLabel = tr("Quitar clave"),
            onConfirm = {
                confirmingKeyRemoval = false
                change(tr("Clave quitada.")) { api, accessToken -> api.removeServerKey(accessToken) }
            },
            onDismiss = { confirmingKeyRemoval = false },
        )
    }

    AuthenticatedScreen(
        modifier = modifier.background(colors.screenBrush),
        topBar = {
            AuthenticatedTopBar(
                title = tr("Asistente"),
                subtitle = tr("Asistente global del servidor"),
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
                .padding(bottom = 16.dp)
                .contentWidth(ContentWidth.Max, 0.92f),
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                shape = MaterialTheme.shapes.medium,
                color = colors.surface,
            ) {
                Column(
                    modifier = Modifier.padding(AdaptiveLayout.screenPadding - 2.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(
                        modifier = Modifier.widthIn(max = ContentWidth.Form).fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(tr("Asistente global"), style = MaterialTheme.typography.titleLarge, color = colors.accentText)
                        Text(
                            tr("Un asistente dentro de la app que responde y hace tareas con los permisos de cada usuario. Usa Claude, de Anthropic: los datos que consulta se envian a Anthropic para generar la respuesta."),
                            color = colors.muted,
                        )
                        if (loading) Text(tr("Cargando..."), color = colors.muted)
                        error?.let { Text(it, color = colors.danger) }
                        message?.let { Text(it, color = colors.success) }

                        val current = settings
                        if (current != null) {
                            if (!current.secretsAvailable) {
                                Text(
                                    tr("El servidor no puede guardar claves de forma segura porque falta su clave maestra (BMS_MASTER_KEY). Configurala y reinicia el servidor."),
                                    color = colors.warning,
                                )
                            }
                            AssistantSwitchRow(
                                title = tr("Activar el asistente en el servidor"),
                                description = tr("Cada empresa debe ademas activarlo por su cuenta."),
                                checked = current.enabled,
                                enabled = !busy,
                                onChange = { value ->
                                    if (value) {
                                        confirmingEnable = true
                                    } else {
                                        val request = AssistantServerSettingsRequest(enabled = false)
                                        change(tr("Asistente desactivado.")) { api, accessToken -> api.updateServerSettings(accessToken, request) }
                                    }
                                },
                            )

                            Text(tr("Clave de Anthropic del servidor"), style = MaterialTheme.typography.titleSmall, color = colors.accentText)
                            Text(
                                tr("Se usa en las empresas que no tienen clave propia. Se guarda cifrada y no se vuelve a mostrar."),
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.muted,
                            )
                            AssistantKeyEditor(
                                keySet = current.keySet,
                                hint = current.keyHint,
                                keyInput = keyInput,
                                onKeyInputChange = { keyInput = it },
                                busy = busy || !current.secretsAvailable,
                                onSave = {
                                    // La clave se envia una vez y se borra del estado de la pantalla.
                                    val key = keyInput.trim()
                                    keyInput = ""
                                    change(tr("Clave guardada.")) { api, accessToken -> api.setServerKey(accessToken, key) }
                                },
                                onRemove = { confirmingKeyRemoval = true },
                            )

                            Text(tr("Modelo y limites"), style = MaterialTheme.typography.titleSmall, color = colors.accentText)
                            OptionPicker(
                                label = tr("Modelo"),
                                value = model,
                                options = current.models.ifEmpty { AssistantIds.Models },
                                enabled = !busy,
                                onSelect = { model = it },
                            )
                            OptionPicker(
                                label = tr("Esfuerzo de razonamiento"),
                                value = effort,
                                options = current.efforts.ifEmpty { AssistantIds.Efforts },
                                enabled = !busy,
                                onSelect = { effort = it },
                            )
                            OutlinedTextField(
                                value = tokensInput,
                                onValueChange = { tokensInput = it.filter { char -> char.isDigit() }.take(15) },
                                label = { Text(tr("Tokens mensuales por empresa (0 = sin limite)")) },
                                singleLine = true,
                                enabled = !busy,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                colors = appFieldColors(),
                                modifier = Modifier.fillMaxWidth(),
                            )
                            AssistantSwitchRow(
                                title = tr("Las empresas con clave propia no cuentan para el limite"),
                                description = tr("Si lo apagas, el limite mensual tambien se aplica a las empresas que usan su propia clave."),
                                checked = !limitOwnKey,
                                enabled = !busy,
                                onChange = { limitOwnKey = !it },
                            )
                            OutlinedTextField(
                                value = retentionInput,
                                onValueChange = { retentionInput = it.filter { char -> char.isDigit() }.take(4) },
                                label = { Text(tr("Dias que se conservan las conversaciones (1-3650)")) },
                                singleLine = true,
                                enabled = !busy,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                colors = appFieldColors(),
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                Button(onClick = ::saveFields, enabled = !busy, colors = accentButtonColors()) { Text(tr("Guardar")) }
                            }
                        }
                    }

                    if (!loading) {
                        CollapsibleSection(
                            tr("Empresas"),
                            colors.accent,
                            initiallyOpen = true,
                            subtitle = tr("Permitir el asistente y limite propio de cada empresa"),
                        ) {
                            if (companies.isEmpty()) Text(tr("No hay empresas en tu alcance."), color = colors.muted)
                            companies.forEach { company ->
                                CompanyAllowanceRow(
                                    company = company,
                                    enabled = !busy,
                                    onSave = { allowed, monthlyTokens ->
                                        val url = serverUrl
                                        val accessToken = token
                                        if (url != null && accessToken != null) {
                                            busy = true
                                            error = null
                                            message = null
                                            scope.launch {
                                                try {
                                                    val saved = AssistantApi(url).use { api -> api.updateServerCompany(accessToken, company.code, allowed, monthlyTokens) }
                                                    companies = companies.map { if (it.code == saved.code) saved else it }
                                                    message = tr("Empresa guardada.")
                                                } catch (exception: CompanyException) {
                                                    error = exception.message
                                                } finally {
                                                    busy = false
                                                }
                                            }
                                        }
                                    },
                                )
                            }
                        }
                        CollapsibleSection(
                            tr("Uso del mes"),
                            colors.accent,
                            subtitle = usage?.month,
                        ) {
                            val month = usage
                            if (month == null || month.companies.isEmpty()) {
                                Text(tr("Ninguna empresa ha usado el asistente este mes."), color = colors.muted)
                            } else {
                                month.companies.forEach { row ->
                                    Text(
                                        tr(
                                            "{0} ({1}): {2} solicitudes, {3} tokens (servidor {4}, clave propia {5})",
                                            row.name.ifBlank { row.code },
                                            row.code,
                                            row.requests,
                                            tokensText(row.countedTokens),
                                            tokensText(row.serverKeyTokens),
                                            tokensText(row.ownKeyTokens),
                                        ),
                                        color = colors.text,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
// Lista corta de valores (modelos, esfuerzos): un boton con el valor actual que abre el menu.
private fun OptionPicker(label: String, value: String, options: List<String>, enabled: Boolean, onSelect: (String) -> Unit) {
    val colors = AppTheme.colors
    var open by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = colors.muted)
        Box {
            OutlinedButton(onClick = { open = true }, enabled = enabled) { Text(value.ifBlank { "-" }, color = colors.text) }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option) },
                        onClick = {
                            open = false
                            onSelect(option)
                        },
                    )
                }
            }
        }
    }
}

@Composable
// Una empresa: si puede usar el asistente y su limite mensual propio (vacio = el del servidor, 0 = sin limite).
private fun CompanyAllowanceRow(company: AssistantServerCompany, enabled: Boolean, onSave: (Boolean, Long?) -> Unit) {
    val colors = AppTheme.colors
    var allowed by remember(company) { mutableStateOf(company.allowed) }
    var tokens by remember(company) { mutableStateOf(company.monthlyTokens?.toString().orEmpty()) }
    var invalid by remember(company) { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surfaceAlt, MaterialTheme.shapes.small)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("${company.name.ifBlank { company.code }} (${company.code})", style = MaterialTheme.typography.titleSmall, color = colors.accentText)
        Text(
            listOf(
                if (company.enabled) tr("Activado por la empresa") else tr("Sin activar por la empresa"),
                if (company.ownKeySet) tr("clave propia") else tr("clave del servidor"),
                tr("uso del mes: {0} tokens", tokensText(company.monthUsedTokens)),
                company.effectiveMonthlyTokens?.let { tr("limite efectivo: {0} tokens", tokensText(it)) } ?: tr("sin limite"),
            ).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = colors.muted,
        )
        AssistantSwitchRow(
            title = tr("Permitir el asistente en esta empresa"),
            description = null,
            checked = allowed,
            enabled = enabled,
            onChange = { allowed = it },
        )
        OutlinedTextField(
            value = tokens,
            onValueChange = {
                tokens = it.filter { char -> char.isDigit() }.take(15)
                invalid = false
            },
            label = { Text(tr("Limite mensual propio (vacio = el del servidor, 0 = sin limite)")) },
            singleLine = true,
            enabled = enabled,
            isError = invalid,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            colors = appFieldColors(),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Button(
                onClick = {
                    val text = tokens.trim()
                    val parsed = if (text.isEmpty()) null else text.toLongOrNull()
                    if (text.isNotEmpty() && parsed == null) {
                        invalid = true
                    } else {
                        onSave(allowed, parsed)
                    }
                },
                enabled = enabled,
                colors = accentButtonColors(),
            ) { Text(tr("Guardar")) }
        }
    }
}
