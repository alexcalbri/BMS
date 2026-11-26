package com.ideasdeveloper.bms.app.features.serveradmin.ui

import com.ideasdeveloper.bms.app.core.i18n.tr
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.ideasdeveloper.bms.app.core.layout.AdaptiveGrid
import com.ideasdeveloper.bms.app.core.layout.AdaptiveLayout
import com.ideasdeveloper.bms.app.core.layout.ContentWidth
import com.ideasdeveloper.bms.app.core.layout.contentWidth
import com.ideasdeveloper.bms.app.core.theme.AppTheme
import com.ideasdeveloper.bms.app.core.theme.accentButtonColors
import com.ideasdeveloper.bms.app.core.ui.DateFieldPicker
import com.ideasdeveloper.bms.app.features.activitylog.data.ActivityLogApi
import com.ideasdeveloper.bms.app.features.activitylog.data.use
import com.ideasdeveloper.bms.app.features.activitylog.ui.ActivityLogPanel
import com.ideasdeveloper.bms.app.features.auth.data.LoginResponse
import com.ideasdeveloper.bms.app.features.auth.data.ServerPermission
import com.ideasdeveloper.bms.app.features.auth.data.can
import com.ideasdeveloper.bms.app.features.company.data.CompanyException
import com.ideasdeveloper.bms.app.features.serveradmin.data.AuditEntry
import com.ideasdeveloper.bms.app.features.serveradmin.data.AuditPage
import com.ideasdeveloper.bms.app.features.serveradmin.data.ServerAdminApi
import com.ideasdeveloper.bms.app.features.serveradmin.data.asDisplayText
import com.ideasdeveloper.bms.app.features.serveradmin.data.use
import com.ideasdeveloper.bms.app.features.shell.ui.AuthenticatedScreen
import com.ideasdeveloper.bms.app.features.shell.ui.AuthenticatedTopBar
import com.ideasdeveloper.bms.app.features.shell.ui.ClosableView
import kotlinx.coroutines.launch

@Composable
// Registro de auditoria del servidor (dueno o permiso server.audit.view): filtros y lista paginada, lo mas reciente primero.
fun AuditLogScreen(
    serverUrl: String?,
    session: LoginResponse?,
    onSettings: () -> Unit,
    onLogout: () -> Unit,
    onReturnToDashboard: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AppTheme.colors
    val scope = rememberCoroutineScope()
    var company by remember { mutableStateOf("") }
    var action by remember { mutableStateOf("") }
    var actor by remember { mutableStateOf("") }
    var from by remember { mutableStateOf("") }
    var to by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<AuditPage?>(null) }
    // Pagina pedida ahora (null = la primera, sin enviar numero) y las anteriores, para volver sin suponer desde que numero cuenta el servidor.
    var requestedPage by remember { mutableStateOf<Int?>(null) }
    var history by remember { mutableStateOf<List<Int?>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val allowed = session.can(ServerPermission.AuditView)
    // Pestana "Actividad en empresas": solo con Core >= 0.12.0.
    var activitySupported by remember { mutableStateOf(false) }
    var activityTab by remember { mutableStateOf(false) }

    fun load(page: Int?, nextHistory: List<Int?>) {
        val url = serverUrl
        val token = session?.accessToken
        if (url == null || token == null) {
            error = tr("No hay una sesion activa.")
            return
        }
        loading = true
        error = null
        scope.launch {
            try {
                val loaded = ServerAdminApi(url).use { api -> api.audit(token, company, action, actor, from, to, page) }
                result = loaded
                requestedPage = page
                history = nextHistory
            } catch (exception: CompanyException) {
                error = exception.message
            } finally {
                loading = false
            }
        }
    }

    LaunchedEffect(serverUrl, session?.accessToken) {
        if (allowed) load(null, emptyList())
    }

    LaunchedEffect(serverUrl, allowed) {
        val url = serverUrl ?: return@LaunchedEffect
        if (!allowed) return@LaunchedEffect
        activitySupported = try {
            ActivityLogApi(url).use { api -> api.supported() }
        } catch (exception: CompanyException) {
            false
        }
    }

    AuthenticatedScreen(
        modifier = modifier.background(colors.screenBrush),
        topBar = {
            AuthenticatedTopBar(
                title = tr("Registro de auditoria"),
                subtitle = tr("Quien hizo que en el servidor"),
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
                .contentWidth(ContentWidth.Max, 0.94f),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(AdaptiveLayout.screenPadding - 2.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (!allowed) {
                    Text(tr("No tienes permiso para ver el registro de auditoria."), color = colors.danger)
                    return@Column
                }
                if (activitySupported) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = !activityTab,
                            onClick = { activityTab = false },
                            label = { Text(tr("Auditoria del servidor")) },
                        )
                        FilterChip(
                            selected = activityTab,
                            onClick = { activityTab = true },
                            label = { Text(tr("Actividad en empresas")) },
                        )
                    }
                }
                if (activitySupported && activityTab) {
                    ActivityLogPanel(serverUrl = serverUrl, session = session, serverLevel = true)
                    return@Column
                }
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = colors.surface,
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp).widthIn(max = ContentWidth.Form),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(tr("Filtros"), style = MaterialTheme.typography.titleMedium, color = colors.accentText)
                        OutlinedTextField(
                            value = company,
                            onValueChange = { company = it.trim().lowercase() },
                            label = { Text(tr("Empresa (codigo)")) },
                            singleLine = true,
                            enabled = !loading,
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = action,
                            onValueChange = { action = it.trim() },
                            label = { Text(tr("Accion")) },
                            singleLine = true,
                            enabled = !loading,
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = actor,
                            onValueChange = { actor = it.trim() },
                            label = { Text(tr("Usuario que actuo")) },
                            singleLine = true,
                            enabled = !loading,
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        DateFieldPicker(
                            value = from,
                            onValueChange = { from = it },
                            label = tr("Desde"),
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !loading,
                        )
                        DateFieldPicker(
                            value = to,
                            onValueChange = { to = it },
                            label = tr("Hasta"),
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !loading,
                        )
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                            Button(
                                enabled = !loading,
                                onClick = {
                                    company = ""
                                    action = ""
                                    actor = ""
                                    from = ""
                                    to = ""
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = colors.surfaceAlt, contentColor = colors.accentText),
                            ) { Text(tr("Limpiar")) }
                            Button(
                                enabled = !loading,
                                onClick = { load(null, emptyList()) },
                                colors = accentButtonColors(),
                            ) { Text(if (loading) tr("Cargando...") else tr("Buscar")) }
                        }
                    }
                }
                error?.let { Text(it, color = colors.danger) }
                val page = result
                if (page != null) {
                    if (page.items.isEmpty() && !loading && error == null) {
                        Text(tr("No hay registros con esos filtros."), color = colors.onScreenMuted)
                    }
                    AdaptiveGrid(modifier = Modifier.fillMaxWidth(), minItemWidth = 480.dp, gap = 12.dp, equalHeight = false) {
                        page.items.forEach { entry -> AuditRow(entry) }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Button(
                            enabled = !loading && history.isNotEmpty(),
                            onClick = { load(history.last(), history.dropLast(1)) },
                            colors = ButtonDefaults.buttonColors(containerColor = colors.surfaceAlt, contentColor = colors.accentText),
                        ) { Text(tr("Anterior")) }
                        Text(tr("Pagina {0}", history.size + 1), color = colors.onScreen)
                        Button(
                            enabled = !loading && page.hasMore,
                            onClick = { load(page.page + 1, history + listOf(requestedPage)) },
                            colors = accentButtonColors(),
                        ) { Text(tr("Siguiente")) }
                    }
                }
            }
        }
    }
}

@Composable
// Una accion registrada: cuando, quien, que, donde y los detalles tal como los guardo el servidor.
private fun AuditRow(entry: AuditEntry) {
    val colors = AppTheme.colors
    val actorName = entry.actorName?.takeIf { it.isNotBlank() } ?: entry.actorRole.orEmpty()
    val actorKind = entry.actorKind?.takeIf { it.isNotBlank() }
    val details = entry.details.asDisplayText()
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = colors.surface,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(entry.createdAt.asDisplayText(), color = colors.muted, style = MaterialTheme.typography.bodySmall)
            Text(entry.action, color = colors.text, fontWeight = FontWeight.SemiBold)
            Text(
                if (actorKind != null) "$actorName ($actorKind)" else actorName,
                color = colors.text,
                style = MaterialTheme.typography.bodySmall,
            )
            val where = listOfNotNull(entry.companyCode?.takeIf { it.isNotBlank() }, entry.moduleId?.takeIf { it.isNotBlank() })
            if (where.isNotEmpty()) {
                Text(where.joinToString(" / "), color = colors.muted, style = MaterialTheme.typography.bodySmall)
            }
            if (details.isNotBlank()) {
                Text(details, color = colors.text, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
