package com.ideasdeveloper.bms.app.features.extensions.ui

import com.ideasdeveloper.bms.app.core.i18n.tr
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.ideasdeveloper.bms.app.core.layout.AdaptiveGrid
import com.ideasdeveloper.bms.app.core.layout.AdaptiveLayout
import com.ideasdeveloper.bms.app.core.layout.ContentWidth
import com.ideasdeveloper.bms.app.core.layout.contentWidth
import com.ideasdeveloper.bms.app.core.theme.AppColors
import com.ideasdeveloper.bms.app.core.theme.AppTheme
import com.ideasdeveloper.bms.app.core.theme.accentButtonColors
import com.ideasdeveloper.bms.app.features.auth.data.LoginResponse
import com.ideasdeveloper.bms.app.features.auth.data.isServerOwner
import com.ideasdeveloper.bms.app.features.company.data.CompanyException
import com.ideasdeveloper.bms.app.features.extensions.data.ExtensionApi
import com.ideasdeveloper.bms.app.features.extensions.data.ExtensionSummary
import com.ideasdeveloper.bms.app.features.extensions.data.ExtensionTarget
import com.ideasdeveloper.bms.app.features.extensions.data.GroupResponse
import com.ideasdeveloper.bms.app.features.shell.ui.AuthenticatedScreen
import com.ideasdeveloper.bms.app.features.shell.ui.AuthenticatedTopBar
import com.ideasdeveloper.bms.app.features.shell.ui.ClosableView
import kotlinx.coroutines.launch

/**
 * Datos de conexion de las operaciones de extensiones: cliente, token y empresa. El [ExtensionApi]
 * es uno por pantalla (lo crea y cierra la pantalla) y se reutiliza en todas las operaciones.
 */
internal class ExtensionContext(val api: ExtensionApi, val token: String, val target: ExtensionTarget) {
    suspend fun <T> run(block: suspend ExtensionApi.(token: String, target: ExtensionTarget) -> T): T =
        api.block(token, target)
}

// Dialogo que esta abierto sobre la lista; solo hay uno a la vez.
private sealed interface ExtensionDialog {
    data class Configure(val extension: ExtensionSummary) : ExtensionDialog
    data class Uninstall(val extension: ExtensionSummary) : ExtensionDialog
    data class ApplyToGroup(val extension: ExtensionSummary) : ExtensionDialog
}

/**
 * Extensiones de una empresa. La misma pantalla sirve al business owner (empresa actual) y al
 * server owner (empresa elegida): lo que cambia es el [target].
 */
@Composable
fun ExtensionsScreen(
    serverUrl: String?,
    session: LoginResponse?,
    target: ExtensionTarget,
    companyName: String?,
    onSettings: () -> Unit,
    onLogout: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    // Si viene, solo se muestran las extensiones de ese modulo (entrada desde su Configuracion); null = todas.
    moduleId: String? = null,
) {
    val colors = AppTheme.colors
    val scope = rememberCoroutineScope()
    val token = session?.accessToken
    // Un solo cliente HTTP por pantalla; se cierra al salir o si cambia el servidor.
    val api = remember(serverUrl) { serverUrl?.let { runCatching { ExtensionApi(it) }.getOrNull() } }
    DisposableEffect(api) { onDispose { api?.close() } }
    val ctx = remember(api, token, target) {
        if (api != null && !token.isNullOrBlank()) ExtensionContext(api, token, target) else null
    }

    var extensions by remember { mutableStateOf<List<ExtensionSummary>>(emptyList()) }
    var group by remember { mutableStateOf<GroupResponse?>(null) }
    var loading by remember { mutableStateOf(false) }
    var busyId by remember { mutableStateOf<String?>(null) }
    var reviewing by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var dialog by remember { mutableStateOf<ExtensionDialog?>(null) }

    // Extensiones agrupadas por modulo, en el orden del servidor; solo se recalcula si cambia la lista.
    val grouped by remember { derivedStateOf { extensions.filter { moduleId == null || it.module == moduleId }.groupBy { it.module }.toList() } }
    // Empresas del grupo distintas de esta: a ellas se puede aplicar la extension.
    val otherMembers by remember { derivedStateOf { group?.members.orEmpty().filter { it.code != target.companyCode } } }
    val thisSuspended by remember { derivedStateOf { group?.members.orEmpty().any { it.code == target.companyCode && it.suspended } } }

    suspend fun load(context: ExtensionContext) {
        loading = true
        try {
            extensions = context.run { token, target -> list(token, target) }
            error = null
        } catch (exception: CompanyException) {
            error = exception.message
        }
        // El grupo es informativo: si falla (empresa sin grupo, permisos) la lista sigue funcionando.
        group = try {
            context.run { token, target -> companyGroup(token, target) }
        } catch (_: CompanyException) {
            null
        }
        loading = false
    }

    LaunchedEffect(ctx) {
        ctx?.let { load(it) }
    }

    // Ejecuta una operacion sobre una extension y recarga la lista al terminar.
    fun runAction(id: String, success: String, block: suspend ExtensionApi.(String, ExtensionTarget) -> Unit) {
        val context = ctx ?: return
        busyId = id
        error = null
        message = null
        scope.launch {
            try {
                context.run(block)
                message = success
                load(context)
            } catch (exception: CompanyException) {
                error = exception.message
            } finally {
                busyId = null
            }
        }
    }

    fun finishDialog(result: String?) {
        dialog = null
        if (result != null) message = result
        ctx?.let { context -> scope.launch { load(context) } }
    }

    AuthenticatedScreen(
        modifier = modifier.background(colors.screenBrush),
        topBar = {
            AuthenticatedTopBar(
                title = tr("Extensiones"),
                subtitle = listOfNotNull(moduleId?.moduleTitle(), companyName?.takeIf { it.isNotBlank() } ?: target.companyCode).joinToString(" · "),
                primaryColor = colors.accent,
                onReturnToDashboard = onClose,
                onSettings = onSettings,
                onLogout = onLogout,
            )
        },
    ) {
        ClosableView(
            onClose = onClose,
            modifier = Modifier
                .align(Alignment.Center)
                .padding(bottom = 16.dp)
                .contentWidth(ContentWidth.Max, 0.92f),
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                color = colors.surface,
            ) {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(AdaptiveLayout.screenPadding - 2.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item(key = "header") {
                        Column(modifier = Modifier.widthIn(max = ContentWidth.Content).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(tr("Extensiones de la empresa"), style = MaterialTheme.typography.titleLarge, color = colors.accentText)
                            Text(
                                tr("Instala, configura y activa integraciones de los modulos de esta empresa. Desinstalar nunca borra clientes ni contactos."),
                                color = colors.muted,
                            )
                            error?.let { Text(it, color = colors.danger) }
                            message?.let { Text(it, color = colors.success) }
                            if (loading) Text(tr("Cargando extensiones..."), color = colors.muted)
                        }
                    }
                    if (group != null && group?.members.orEmpty().size > 1) {
                        item(key = "group") {
                            GroupPanel(
                                group = group,
                                companyCode = target.companyCode,
                                suspended = thisSuspended,
                                // Revisar el grupo (vinculos entre empresas) es del dueno del servidor.
                                canReview = target.asServerOwner && session.isServerOwner,
                                reviewing = reviewing,
                                onReview = {
                                    ctx?.let { context ->
                                        reviewing = true
                                        error = null
                                        scope.launch {
                                            try {
                                                group = context.run { token, _ -> reviewGroup(token, target.companyCode) }
                                                message = tr("Grupo revisado.")
                                            } catch (exception: CompanyException) {
                                                error = exception.message
                                            } finally {
                                                reviewing = false
                                            }
                                        }
                                    }
                                },
                            )
                        }
                    }
                    if (!loading && grouped.isEmpty() && error == null) {
                        item(key = "empty") {
                            Text(tr("Los modulos activos de esta empresa no tienen extensiones disponibles."), color = colors.muted)
                        }
                    }
                    items(grouped, key = { it.first }) { (module, list) ->
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(module.moduleTitle(), style = MaterialTheme.typography.titleMedium, color = colors.accentText)
                            // equalHeight desactivado: cada tarjeta lleva filas de botones que se ajustan al ancho.
                            AdaptiveGrid(modifier = Modifier.fillMaxWidth(), minItemWidth = 420.dp, gap = 12.dp, equalHeight = false) {
                                list.forEach { extension ->
                                    androidx.compose.runtime.key(extension.id) {
                                        ExtensionCard(
                                            extension = extension,
                                            busy = busyId == extension.id || loading,
                                            canApplyToGroup = otherMembers.any { !it.suspended } && !thisSuspended,
                                            onInstall = { runAction(extension.id, tr("{0} instalada.", extension.displayName)) { token, target -> install(token, target, extension.id) } },
                                            onConfigure = { dialog = ExtensionDialog.Configure(extension) },
                                            onToggle = { enable ->
                                                runAction(extension.id, if (enable) tr("{0} activada.", extension.displayName) else tr("{0} desactivada.", extension.displayName)) { token, target ->
                                                    setEnabled(token, target, extension.id, enable)
                                                }
                                            },
                                            onUninstall = { dialog = ExtensionDialog.Uninstall(extension) },
                                            onApplyToGroup = { dialog = ExtensionDialog.ApplyToGroup(extension) },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        val context = ctx
        when (val current = dialog) {
            is ExtensionDialog.Configure -> if (context != null) {
                ConfigureExtensionDialog(current.extension, context, onDone = ::finishDialog, onDismiss = { dialog = null })
            }
            is ExtensionDialog.Uninstall -> if (context != null) {
                UninstallExtensionDialog(current.extension, context, onDone = ::finishDialog, onDismiss = { dialog = null })
            }
            is ExtensionDialog.ApplyToGroup -> if (context != null) {
                ApplyToGroupDialog(current.extension, context, members = otherMembers, onDone = ::finishDialog, onDismiss = { dialog = null })
            }
            null -> Unit
        }
    }
}

// Titulo del modulo a partir de su id ("crm" -> "CRM", "hotspot" -> "Hotspot").
private fun String.moduleTitle(): String =
    if (length <= 3) uppercase() else replaceFirstChar { it.uppercase() }

// Etiqueta del estado y el color de su borde; la etiqueta lleva siempre el texto, no solo el color.
private data class StatusChip(val label: String, val tone: Color)

private fun statusChip(extension: ExtensionSummary, colors: AppColors): StatusChip = when {
    !extension.compatible || extension.status == "incompatible" -> StatusChip(tr("Incompatible"), colors.danger)
    extension.status == "enabled" -> StatusChip(tr("Activa"), colors.success)
    extension.status == "disabled" -> StatusChip(tr("Desactivada"), colors.warning)
    extension.status == "installed" -> StatusChip(tr("Instalada"), colors.accentText)
    else -> StatusChip(tr("No instalada"), colors.muted)
}

@Composable
private fun StatusChipView(chip: StatusChip) {
    Surface(
        shape = RoundedCornerShape(50),
        color = AppTheme.colors.surface,
        border = BorderStroke(1.5.dp, chip.tone),
    ) {
        Text(
            chip.label,
            style = MaterialTheme.typography.labelMedium,
            color = AppTheme.colors.text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
// Tarjeta de una extension con su estado y las acciones que admite en ese estado.
private fun ExtensionCard(
    extension: ExtensionSummary,
    busy: Boolean,
    canApplyToGroup: Boolean,
    onInstall: () -> Unit,
    onConfigure: () -> Unit,
    onToggle: (enable: Boolean) -> Unit,
    onUninstall: () -> Unit,
    onApplyToGroup: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AppTheme.colors
    val chip = remember(extension, colors) { statusChip(extension, colors) }
    val installed = extension.status != null && extension.status != "removed"
    val incompatible = !extension.compatible || extension.status == "incompatible"
    val secondary = ButtonDefaults.buttonColors(containerColor = colors.surfaceAlt, contentColor = colors.accentText)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.surfaceAlt, MaterialTheme.shapes.small)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(extension.displayName, style = MaterialTheme.typography.titleSmall, color = colors.accentText, modifier = Modifier.weight(1f))
            StatusChipView(chip)
        }
        Text(extension.description, color = colors.muted)
        Text(
            buildString {
                append(tr("Version {0}", extension.version))
                append(if (extension.sourceType == "bundled") tr(" · Incluida en el modulo") else tr(" · Paquete externo"))
                extension.minModuleVersion?.let { append(tr(" · Requiere modulo {0} o superior", it)) }
            },
            style = MaterialTheme.typography.bodySmall,
            color = colors.muted,
        )
        when {
            incompatible -> Text(
                tr("Esta extension no es compatible con la version del modulo instalada. Puedes desinstalarla."),
                style = MaterialTheme.typography.bodySmall,
                color = colors.danger,
            )
            !extension.moduleInstalled || !extension.moduleEnabled -> Text(
                tr("Activa el modulo {0} en esta empresa para usar esta extension.", extension.module.moduleTitle()),
                style = MaterialTheme.typography.bodySmall,
                color = colors.warning,
            )
            installed && extension.missingRequired.isNotEmpty() -> Text(
                tr("Faltan ajustes obligatorios: {0}.", extension.missingRequired.joinToString()),
                style = MaterialTheme.typography.bodySmall,
                color = colors.warning,
            )
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            if (!installed) {
                Button(
                    enabled = !busy && !incompatible && extension.moduleInstalled && extension.moduleEnabled,
                    onClick = onInstall,
                    colors = accentButtonColors(),
                ) { Text(tr("Instalar")) }
            } else {
                if (!incompatible && extension.settings.isNotEmpty()) {
                    Button(enabled = !busy, onClick = onConfigure, colors = secondary) { Text(tr("Configurar")) }
                }
                if (!incompatible) {
                    val enabled = extension.status == "enabled"
                    Button(
                        enabled = !busy && (enabled || extension.moduleEnabled),
                        onClick = { onToggle(!enabled) },
                        colors = if (enabled) secondary else accentButtonColors(),
                    ) { Text(if (enabled) tr("Desactivar") else tr("Activar")) }
                    if (canApplyToGroup) {
                        Button(enabled = !busy, onClick = onApplyToGroup, colors = secondary) { Text(tr("Aplicar a empresas del grupo")) }
                    }
                }
                Button(
                    enabled = !busy,
                    onClick = onUninstall,
                    colors = ButtonDefaults.buttonColors(containerColor = colors.surfaceAlt, contentColor = colors.danger),
                ) { Text(tr("Desinstalar")) }
            }
        }
    }
}

@Composable
// Empresas vinculadas (mismo business owner) y, si la empresa quedo suspendida, el boton de revision.
private fun GroupPanel(
    group: GroupResponse?,
    companyCode: String,
    suspended: Boolean,
    canReview: Boolean,
    reviewing: Boolean,
    onReview: () -> Unit,
) {
    val colors = AppTheme.colors
    Column(
        modifier = Modifier
            .widthIn(max = ContentWidth.Content)
            .fillMaxWidth()
            .background(colors.surfaceAlt, MaterialTheme.shapes.small)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(tr("Grupo de empresas"), style = MaterialTheme.typography.titleSmall, color = colors.accentText)
        group?.members.orEmpty().forEach { member ->
            val flags = buildList {
                if (member.code == companyCode) add(tr("esta empresa"))
                if (member.suspended) add(tr("suspendida"))
                if (!member.ownerEmailMatches) add(tr("el correo del business owner ya no coincide"))
            }
            Text(
                "${member.name.ifBlank { member.code }} (${member.code})" + if (flags.isEmpty()) "" else " · ${flags.joinToString(", ")}",
                style = MaterialTheme.typography.bodySmall,
                color = if (member.suspended) colors.warning else colors.text,
            )
        }
        if (suspended) {
            Text(
                tr("El grupo esta suspendido: no se puede aplicar ni desinstalar en varias empresas hasta que el dueno del servidor lo revise."),
                color = colors.warning,
            )
            if (canReview) {
                Button(enabled = !reviewing, onClick = onReview, colors = accentButtonColors()) {
                    Text(if (reviewing) tr("Revisando...") else tr("Revisar y reactivar"))
                }
            }
        }
    }
}
