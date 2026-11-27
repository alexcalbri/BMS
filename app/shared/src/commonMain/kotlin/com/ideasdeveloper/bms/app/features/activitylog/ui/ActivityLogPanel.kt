package com.ideasdeveloper.bms.app.features.activitylog.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.ideasdeveloper.bms.app.core.i18n.tr
import com.ideasdeveloper.bms.app.core.layout.ContentWidth
import com.ideasdeveloper.bms.app.core.platform.saveTextFile
import com.ideasdeveloper.bms.app.core.theme.AppTheme
import com.ideasdeveloper.bms.app.core.theme.accentButtonColors
import com.ideasdeveloper.bms.app.core.ui.DateFieldPicker
import com.ideasdeveloper.bms.app.features.activitylog.data.ActivityActionIds
import com.ideasdeveloper.bms.app.features.activitylog.data.ActivityFilters
import com.ideasdeveloper.bms.app.features.activitylog.data.ActivityLogApi
import com.ideasdeveloper.bms.app.features.activitylog.data.ActivityPage
import com.ideasdeveloper.bms.app.features.activitylog.data.ActivityRow
import com.ideasdeveloper.bms.app.features.activitylog.data.use
import com.ideasdeveloper.bms.app.features.auth.data.LoginResponse
import com.ideasdeveloper.bms.app.features.auth.data.canAccessCompany
import com.ideasdeveloper.bms.app.features.company.data.CompanyApi
import com.ideasdeveloper.bms.app.features.company.data.CompanyException
import com.ideasdeveloper.bms.app.features.serveradmin.data.asDisplayText
import kotlinx.coroutines.launch

@Composable
// Filtros, lista paginada (lo mas reciente primero) y exportacion CSV del registro de actividad.
// [serverLevel] = false: registro de la empresa de la sesion; true: registro de las empresas a alcance del servidor.
fun ActivityLogPanel(
    serverUrl: String?,
    session: LoginResponse?,
    serverLevel: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = AppTheme.colors
    val scope = rememberCoroutineScope()
    var filters by remember { mutableStateOf(ActivityFilters()) }
    var result by remember { mutableStateOf<ActivityPage?>(null) }
    // Pagina pedida ahora (null = la primera) y las anteriores, para volver sin suponer desde que numero cuenta el servidor.
    var requestedPage by remember { mutableStateOf<Int?>(null) }
    var history by remember { mutableStateOf<List<Int?>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    // Opciones de los selectores: (id, texto). Vacias = campo de texto libre.
    var userOptions by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var companyOptions by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    val companyCode = session?.companyCode

    fun load(page: Int?, nextHistory: List<Int?>) {
        val url = serverUrl
        val token = session?.accessToken
        if (url == null || token == null || (!serverLevel && companyCode == null)) {
            error = tr("No hay una sesion activa.")
            return
        }
        loading = true
        error = null
        message = null
        scope.launch {
            try {
                val loaded = ActivityLogApi(url).use { api ->
                    if (serverLevel) api.serverActivity(token, filters, page)
                    else api.companyActivity(token, companyCode.orEmpty(), filters, page)
                }
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

    fun export() {
        val url = serverUrl
        val token = session?.accessToken
        if (url == null || token == null || (!serverLevel && companyCode == null)) return
        loading = true
        error = null
        message = null
        scope.launch {
            try {
                val csv = ActivityLogApi(url).use { api ->
                    if (serverLevel) api.exportServer(token, filters)
                    else api.exportCompany(token, companyCode.orEmpty(), filters)
                }
                message = saveTextFile("registro-actividad.csv", csv, "text/csv;charset=utf-8")
            } catch (exception: CompanyException) {
                error = exception.message
            } finally {
                loading = false
            }
        }
    }

    LaunchedEffect(serverUrl, session?.accessToken, serverLevel) {
        val url = serverUrl ?: return@LaunchedEffect
        val token = session?.accessToken ?: return@LaunchedEffect
        // Listas de usuarios o empresas solo si el usuario puede pedirlas; si no, queda el campo de texto.
        if (serverLevel) {
            userOptions = emptyList()
            companyOptions = try {
                CompanyApi(url).use { api -> api.listCompanies(token) }
                    .filter { session.canAccessCompany(it.code) }
                    .map { it.code to it.name }
            } catch (exception: CompanyException) {
                emptyList()
            }
        } else if (companyCode != null) {
            companyOptions = emptyList()
            userOptions = try {
                CompanyApi(url).use { api -> api.companyUsers(token, companyCode) }
                    .users
                    .map { it.userId to (it.displayName?.takeIf { name -> name.isNotBlank() } ?: it.username) }
            } catch (exception: CompanyException) {
                emptyList()
            }
        }
        load(null, emptyList())
    }

    val moduleOptions = remember(session?.enabledModules) {
        (listOf("core") + session?.enabledModules.orEmpty()).distinct().map { it to it }
    }
    val actionOptions = ActivityActionIds.map { (id, label) -> id to tr(label) }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Surface(modifier = Modifier.fillMaxWidth(), color = colors.surface, shape = MaterialTheme.shapes.medium) {
            Column(
                modifier = Modifier.padding(14.dp).widthIn(max = ContentWidth.Form),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(tr("Filtros"), style = MaterialTheme.typography.titleMedium, color = colors.accentText)
                if (serverLevel) {
                    if (companyOptions.isNotEmpty()) {
                        ChoiceField(tr("Empresa"), tr("Todas"), companyOptions, filters.company, !loading) { filters = filters.copy(company = it) }
                    } else {
                        FilterText(tr("Empresa (codigo)"), filters.company, !loading) { filters = filters.copy(company = it.trim().lowercase()) }
                    }
                }
                if (userOptions.isNotEmpty()) {
                    ChoiceField(tr("Usuario"), tr("Todos"), userOptions, filters.user, !loading) { filters = filters.copy(user = it) }
                } else {
                    FilterText(tr("Usuario"), filters.user, !loading) { filters = filters.copy(user = it) }
                }
                if (serverLevel) {
                    FilterText(tr("Modulo"), filters.module, !loading) { filters = filters.copy(module = it.trim()) }
                } else {
                    ChoiceField(tr("Modulo"), tr("Todos"), moduleOptions, filters.module, !loading) { filters = filters.copy(module = it) }
                }
                ChoiceField(tr("Accion"), tr("Todas"), actionOptions, filters.action, !loading) { filters = filters.copy(action = it) }
                FilterText(tr("Texto"), filters.query, !loading) { filters = filters.copy(query = it) }
                DateFieldPicker(
                    value = filters.from,
                    onValueChange = { filters = filters.copy(from = it) },
                    label = tr("Desde"),
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !loading,
                )
                DateFieldPicker(
                    value = filters.to,
                    onValueChange = { filters = filters.copy(to = it) },
                    label = tr("Hasta"),
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !loading,
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                    Button(
                        enabled = !loading,
                        onClick = { filters = ActivityFilters() },
                        colors = ButtonDefaults.buttonColors(containerColor = colors.surfaceAlt, contentColor = colors.accentText),
                    ) { Text(tr("Limpiar")) }
                    Button(
                        enabled = !loading,
                        onClick = { export() },
                        colors = ButtonDefaults.buttonColors(containerColor = colors.surfaceAlt, contentColor = colors.accentText),
                    ) { Text(tr("Exportar CSV")) }
                    Button(
                        enabled = !loading,
                        onClick = { load(null, emptyList()) },
                        colors = accentButtonColors(),
                    ) { Text(if (loading) tr("Cargando...") else tr("Buscar")) }
                }
            }
        }
        error?.let { Text(it, color = colors.danger) }
        message?.let { Text(it, color = colors.onScreen) }
        val page = result
        if (page != null) {
            if (page.items.isEmpty() && !loading && error == null) {
                Text(tr("No hay registros con esos filtros."), color = colors.onScreenMuted)
            }
            page.items.forEachIndexed { index, row -> ActivityRowCard(row, serverLevel, key = "${history.size}-$index") }
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

@Composable
private fun FilterText(label: String, value: String, enabled: Boolean, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        enabled = enabled,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
// Selector de una lista (id, texto) con una opcion "todos" que deja el filtro vacio.
private fun ChoiceField(
    label: String,
    allLabel: String,
    options: List<Pair<String, String>>,
    value: String,
    enabled: Boolean,
    onChange: (String) -> Unit,
) {
    val colors = AppTheme.colors
    var open by remember { mutableStateOf(false) }
    val shown = options.firstOrNull { it.first == value }?.second ?: if (value.isBlank()) allLabel else value
    Box(modifier = Modifier.fillMaxWidth()) {
        Button(
            enabled = enabled,
            onClick = { open = true },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = colors.surfaceAlt, contentColor = colors.accentText),
        ) { Text("$label: $shown") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Column(modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                TextButton(onClick = { onChange(""); open = false }) { Text(allLabel) }
                options.forEach { (id, text) ->
                    TextButton(onClick = { onChange(id); open = false }) { Text(text) }
                }
            }
        }
    }
}

@Composable
// Una fila: hora, quien, modulo, registro y resumen; al tocarla muestra los cambios y los detalles.
// Los textos vienen del servidor ya en espanol y se muestran tal cual.
private fun ActivityRowCard(row: ActivityRow, serverLevel: Boolean, key: String) {
    val colors = AppTheme.colors
    var expanded by remember(key) { mutableStateOf(false) }
    val where = listOfNotNull(
        row.companyCode?.takeIf { serverLevel && it.isNotBlank() },
        (row.moduleName ?: row.moduleId)?.takeIf { it.isNotBlank() },
    ).joinToString(" / ")
    Surface(modifier = Modifier.fillMaxWidth(), color = colors.surface, shape = MaterialTheme.shapes.medium) {
        Column(
            modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(formatActivityTime(row.occurredAt), color = colors.muted, style = MaterialTheme.typography.bodySmall)
            Text(row.actorDisplay ?: row.actorName.orEmpty(), color = colors.text, fontWeight = FontWeight.SemiBold)
            // Que clase de actor fue, desde los campos estructurados: un nombre escrito por alguien no puede fingirlo.
            val badges = actorBadges(row)
            if (badges.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    badges.forEach { badge ->
                        Surface(color = colors.surfaceAlt, shape = MaterialTheme.shapes.small) {
                            Text(badge, color = colors.accentText, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                        }
                    }
                }
            }
            if (where.isNotEmpty()) Text(where, color = colors.muted, style = MaterialTheme.typography.bodySmall)
            row.entityLabel?.takeIf { it.isNotBlank() }?.let { Text(it, color = colors.text) }
            row.summary?.takeIf { it.isNotBlank() }?.let { Text(it, color = colors.text, style = MaterialTheme.typography.bodySmall) }
            if (expanded) {
                row.realActorName?.takeIf { it.isNotBlank() }?.let {
                    val text = if (row.fromServer) tr("Persona real: {0} (desde el servidor)", it) else tr("Persona: {0}", it)
                    Text(text, color = colors.text, style = MaterialTheme.typography.bodySmall)
                }
                row.agentName?.takeIf { it.isNotBlank() }?.let {
                    Text(tr("Agente: {0}", it), color = colors.text, style = MaterialTheme.typography.bodySmall)
                }
                row.actionLabel?.takeIf { it.isNotBlank() }?.let {
                    Text(tr("Accion: {0}", it), color = colors.text, style = MaterialTheme.typography.bodySmall)
                }
                row.origin?.takeIf { it.isNotBlank() }?.let {
                    Text(tr("Origen: {0}", it), color = colors.text, style = MaterialTheme.typography.bodySmall)
                }
                if (row.changes.isEmpty()) {
                    Text(tr("Sin cambios de campos."), color = colors.muted, style = MaterialTheme.typography.bodySmall)
                }
                row.changes.forEach { change ->
                    val name = change.label.ifBlank { change.field }
                    val before = change.old.asDisplayText().ifBlank { "-" }
                    val after = change.new.asDisplayText().ifBlank { "-" }
                    Text("$name: $before -> $after", color = colors.text, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

// Etiquetas de la fila segun actorKind, viaAgent y fromServer (los campos que el servidor arma al leer).
private fun actorBadges(row: ActivityRow): List<String> = buildList {
    when (row.actorKind) {
        "import" -> add(tr("Importación"))
        "automation" -> add(tr("Automatización"))
        "job" -> add(tr("Tarea programada"))
        "integration" -> add(tr("Integración"))
        "system" -> add(tr("Sistema"))
    }
    if (row.viaAgent) add(tr("Agente IA"))
    if (row.fromServer) add(tr("Desde el servidor"))
}

// "2026-11-26T14:03:09Z" -> "2026-11-26 14:03"; si el formato es otro se muestra tal cual.
private fun formatActivityTime(value: String?): String {
    val text = value.orEmpty()
    return if (text.length >= 16 && text[10] == 'T') text.substring(0, 10) + " " + text.substring(11, 16) else text
}

private suspend inline fun <T> CompanyApi.use(block: suspend (CompanyApi) -> T): T = try {
    block(this)
} finally {
    close()
}
