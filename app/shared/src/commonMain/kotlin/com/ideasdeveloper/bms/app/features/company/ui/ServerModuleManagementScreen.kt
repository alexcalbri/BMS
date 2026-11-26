package com.ideasdeveloper.bms.app.features.company.ui

import com.ideasdeveloper.bms.app.core.i18n.tr
import com.ideasdeveloper.bms.app.core.theme.accentButtonColors
import com.ideasdeveloper.bms.app.core.layout.AdaptiveLayout
import com.ideasdeveloper.bms.app.core.layout.ContentWidth
import com.ideasdeveloper.bms.app.core.layout.AdaptiveGrid
import com.ideasdeveloper.bms.app.core.layout.contentWidth
import com.ideasdeveloper.bms.app.core.theme.AppTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.ideasdeveloper.bms.app.core.company.CompanyIdentityStore
import com.ideasdeveloper.bms.app.core.session.SessionStore
import com.ideasdeveloper.bms.app.core.version.ClientVersion
import com.ideasdeveloper.bms.app.core.version.isAppOlderThan
import com.ideasdeveloper.bms.app.features.auth.data.LoginResponse
import com.ideasdeveloper.bms.app.features.auth.data.ServerPermission
import com.ideasdeveloper.bms.app.features.auth.data.can
import com.ideasdeveloper.bms.app.features.auth.data.isServerOwner
import com.ideasdeveloper.bms.app.features.company.data.CompanyApi
import com.ideasdeveloper.bms.app.features.company.data.CompanyException
import com.ideasdeveloper.bms.app.features.company.data.ServerModuleResponse
import com.ideasdeveloper.bms.app.features.extensions.ui.ExtensionPackagesSection
import com.ideasdeveloper.bms.app.features.shell.ui.AuthenticatedScreen
import com.ideasdeveloper.bms.app.features.shell.ui.AuthenticatedTopBar
import com.ideasdeveloper.bms.app.features.shell.ui.ClosableView
import com.ideasdeveloper.bms.app.features.shell.ui.toComposeColor
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val DefaultModuleCatalogUrl = "https://github.com/alexcalbri/BMS/tree/master/modules"

@Composable
// Renderiza la administracion del catalogo de modulos instalados en el servidor.
fun ServerModuleManagementScreen(
    serverUrl: String?,
    session: LoginResponse?,
    onSettings: () -> Unit,
    onLogout: () -> Unit,
    onReturnToDashboard: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val companyIdentity = remember { CompanyIdentityStore.current() }
    val colors = AppTheme.colors
    val primaryColor = colors.accent
    val scope = rememberCoroutineScope()
    // Que se muestra y que se puede hacer depende de los permisos de la sesion; lo que no se permite se oculta.
    val canViewModules = session.can(ServerPermission.ModulesView)
    val canInstallModules = session.can(ServerPermission.ModulesInstall)
    val canRemoveModules = session.can(ServerPermission.ModulesRemove)
    val canUseInServer = session.can(ServerPermission.Workspace)
    val canSeePackages = session.can(ServerPermission.ExtensionsInstall)
    // El repositorio (catalogo) de modulos solo lo cambia el dueno del servidor.
    val isOwner = session.isServerOwner
    var modules by remember { mutableStateOf<List<ServerModuleResponse>>(emptyList()) }
    var catalogUrl by remember { mutableStateOf(DefaultModuleCatalogUrl) }
    var pendingDeletion by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    fun refreshModules() {
        val activeServerUrl = serverUrl ?: return
        val activeSession = session ?: return
        isLoading = true
        scope.launch {
            try {
                modules = CompanyApi(activeServerUrl).use { api ->
                    api.listServerModules(activeSession.accessToken)
                }
                error = null
            } catch (exception: CompanyException) {
                error = exception.message
            } finally {
                isLoading = false
            }
        }
    }

    fun refreshCatalog() {
        val activeServerUrl = serverUrl ?: return
        val activeSession = session ?: return
        scope.launch {
            try {
                catalogUrl = CompanyApi(activeServerUrl).use { api ->
                    api.serverModuleCatalog(activeSession.accessToken).catalogUrl
                }.ifBlank { DefaultModuleCatalogUrl }
            } catch (_: CompanyException) {
                // La lista de modulos mostrara el error principal si el servidor no responde.
            }
        }
    }

    LaunchedEffect(serverUrl, session?.accessToken, canViewModules) {
        if (!canViewModules) return@LaunchedEffect
        if (isOwner) refreshCatalog()
        refreshModules()
    }

    // Mientras un modulo se compila o el servidor se reinicia para cargarlo, la lista se consulta
    // cada pocos segundos; durante el reinicio el servidor no responde y se reintenta.
    val busyModules = modules.any { it.status == "installing" || it.status == "restarting" }
    LaunchedEffect(busyModules, serverUrl, session?.accessToken) {
        val activeServerUrl = serverUrl ?: return@LaunchedEffect
        val activeSession = session ?: return@LaunchedEffect
        if (!busyModules) return@LaunchedEffect
        repeat(60) {
            delay(5_000)
            try {
                val latest = CompanyApi(activeServerUrl).use { api -> api.listServerModules(activeSession.accessToken) }
                val finished = modules.filter { it.status == "installing" || it.status == "restarting" }
                    .mapNotNull { previous -> latest.firstOrNull { it.moduleId == previous.moduleId && it.installed && it.status == null } }
                modules = latest
                finished.forEach { message = tr("Modulo {0} instalado y cargado.", it.displayName) }
                if (latest.none { it.status == "installing" || it.status == "restarting" }) return@LaunchedEffect
            } catch (_: CompanyException) {
                // El servidor se esta reiniciando para cargar el modulo.
            }
        }
    }

    AuthenticatedScreen(
        modifier = modifier.background(colors.screenBrush),
        topBar = {
            AuthenticatedTopBar(
                title = tr("Modulos del servidor"),
                subtitle = tr("Catalogo activo"),
                primaryColor = primaryColor,
                onReturnToDashboard = onReturnToDashboard,
                onSettings = onSettings,
                onLogout = onLogout,
            )
        },
    ) {
        // La vista empieza debajo de la barra superior y lleva la X en su esquina superior derecha.
        ClosableView(
            onClose = onReturnToDashboard,
            modifier = Modifier
                .align(Alignment.Center)
                .padding(bottom = 16.dp)
                .contentWidth(ContentWidth.Max, 0.92f),
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                shape = MaterialTheme.shapes.medium,
                color = AppTheme.colors.surface,
            ) {
                Column(
                    modifier = Modifier.padding(AdaptiveLayout.screenPadding - 2.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    // El formulario del repositorio se queda en un ancho legible; las tarjetas usan todo el ancho.
                    Column(
                        modifier = Modifier.widthIn(max = ContentWidth.Form).fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                    if (canViewModules) Text(tr("Modulos instalados"), style = MaterialTheme.typography.titleLarge, color = AppTheme.colors.accentText)
                    if (isOwner) Text(tr("Repositorio de modulos"), style = MaterialTheme.typography.titleMedium, color = AppTheme.colors.accentText)
                    if (isOwner) OutlinedTextField(
                        value = catalogUrl,
                        onValueChange = { catalogUrl = it },
                        label = { Text(tr("URL del repositorio de modulos")) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (canViewModules) Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    ) {
                        if (isOwner) Button(
                            enabled = !isLoading && serverUrl != null && session?.accessToken?.isNotBlank() == true,
                            onClick = {
                                val activeServerUrl = serverUrl
                                val activeSession = session
                                if (activeServerUrl != null && activeSession != null) {
                                    isLoading = true
                                    error = null
                                    message = null
                                    scope.launch {
                                        try {
                                            catalogUrl = CompanyApi(activeServerUrl).use { api ->
                                                api.updateServerModuleCatalog(activeSession.accessToken, catalogUrl).catalogUrl
                                            }
                                            message = tr("Catalogo actualizado.")
                                            refreshModules()
                                        } catch (exception: CompanyException) {
                                            error = exception.message
                                        } finally {
                                            isLoading = false
                                        }
                                    }
                                }
                            },
                            colors = accentButtonColors(),
                        ) {
                            Text(tr("Guardar catalogo"))
                        }
                        Button(
                            enabled = !isLoading,
                            onClick = {
                                if (isOwner) refreshCatalog()
                                refreshModules()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = AppTheme.colors.surfaceAlt, contentColor = AppTheme.colors.accentText),
                        ) {
                            Text(tr("Actualizar lista"))
                        }
                    }
                    error?.let { Text(it, color = AppTheme.colors.danger) }
                    message?.let { Text(it, color = AppTheme.colors.success) }
                    if (isLoading) {
                        Text(tr("Cargando modulos..."), color = AppTheme.colors.muted)
                    }
                    }

                    if (canViewModules) AdaptiveGrid(modifier = Modifier.fillMaxWidth(), minItemWidth = 360.dp, gap = 12.dp) {
                    modules.forEach { module ->
                        ServerModuleRow(
                            module = module,
                            primaryColor = primaryColor,
                            canInstallModule = canInstallModules,
                            canRemoveModule = canRemoveModules,
                            canUseInServer = canUseInServer,
                            enabled = !isLoading && serverUrl != null && session?.accessToken?.isNotBlank() == true,
                            pendingDeletion = pendingDeletion == module.moduleId,
                            onInstall = {
                                val activeServerUrl = serverUrl
                                val activeSession = session
                                if (activeServerUrl != null && activeSession != null) {
                                    isLoading = true
                                    error = null
                                    message = null
                                    scope.launch {
                                        try {
                                            val installed = CompanyApi(activeServerUrl).use { api ->
                                                api.installServerModule(activeSession.accessToken, module.moduleId)
                                            }
                                            modules = modules.map {
                                                if (it.moduleId == installed.moduleId) installed else it
                                            }
                                            message = when (installed.status) {
                                                "installing", "restarting" ->
                                                    tr("Instalando {0}: el servidor descarga y compila el modulo y luego se reinicia unos segundos.", installed.displayName)
                                                "failed" -> null
                                                else -> tr("Modulo {0} instalado.", installed.displayName)
                                            }?.let { text ->
                                                installed.warning?.takeIf { it.isNotBlank() }?.let { "$text $it" } ?: text
                                            }
                                            if (installed.status == "failed") error = installed.statusMessage
                                        } catch (exception: CompanyException) {
                                            error = exception.message
                                        } finally {
                                            isLoading = false
                                        }
                                    }
                                }
                            },
                            onRequestDelete = { pendingDeletion = module.moduleId },
                            onCancelDelete = { pendingDeletion = null },
                            onToggleServerUsage = { enabled ->
                                val activeServerUrl = serverUrl
                                val activeSession = session
                                if (activeServerUrl != null && activeSession != null) {
                                    isLoading = true
                                    error = null
                                    message = null
                                    scope.launch {
                                        try {
                                            val latest = CompanyApi(activeServerUrl).use { api ->
                                                api.setServerModuleUsage(activeSession.accessToken, module.moduleId, enabled)
                                                api.listServerModules(activeSession.accessToken)
                                            }
                                            modules = latest
                                            // El panel del server_owner muestra al momento los modulos del espacio del servidor.
                                            SessionStore.update { current -> current.withServerWorkspace(latest) }
                                            message = if (enabled) {
                                                tr("{0} activo en el espacio del servidor.", module.displayName)
                                            } else {
                                                tr("{0} desactivado en el espacio del servidor.", module.displayName)
                                            }
                                        } catch (exception: CompanyException) {
                                            error = exception.message
                                        } finally {
                                            isLoading = false
                                        }
                                    }
                                }
                            },
                            onConfirmDelete = {
                                val activeServerUrl = serverUrl
                                val activeSession = session
                                if (activeServerUrl != null && activeSession != null) {
                                    isLoading = true
                                    error = null
                                    message = null
                                    scope.launch {
                                        try {
                                            CompanyApi(activeServerUrl).use { api ->
                                                api.deleteServerModule(activeSession.accessToken, module.moduleId)
                                            }
                                            modules = modules.map {
                                                if (it.moduleId == module.moduleId) {
                                                    it.copy(installed = false, activeCompanyCount = 0)
                                                } else {
                                                    it
                                                }
                                            }
                                            pendingDeletion = null
                                            message = tr("Modulo {0} eliminado del servidor.", module.displayName)
                                        } catch (exception: CompanyException) {
                                            error = exception.message
                                        } finally {
                                            isLoading = false
                                        }
                                    }
                                }
                            },
                        )
                    }
                    }

                    // Paquetes externos de extensiones del servidor: catalogo, instalar, quitar y autorizar sin firma.
                    if (canSeePackages) ExtensionPackagesSection(serverUrl = serverUrl, session = session)

                    Spacer(modifier = Modifier.height(2.dp))
                }
            }
        }
    }
}

// Minimos de Core y app de la version a instalar y, si aplica, el motivo por el que no se puede instalar.
private data class ModuleRequirement(
    val minCore: String?,
    val minApp: String?,
    val blockedReason: String?,
)

@Composable
// Renderiza un modulo instalado en el servidor y permite eliminarlo si ninguna empresa lo usa.
private fun ServerModuleRow(
    module: ServerModuleResponse,
    primaryColor: Color,
    canInstallModule: Boolean,
    canRemoveModule: Boolean,
    canUseInServer: Boolean,
    enabled: Boolean,
    pendingDeletion: Boolean,
    onInstall: () -> Unit,
    onRequestDelete: () -> Unit,
    onCancelDelete: () -> Unit,
    onToggleServerUsage: (Boolean) -> Unit,
    onConfirmDelete: () -> Unit,
) {
    val inProgress = module.status == "installing" || module.status == "restarting"
    val updateAvailable = module.updateAvailable
    // Minimos de la version que se va a instalar (la disponible al instalar o actualizar; si no, la instalada).
    val requirement = remember(module) {
        val installing = !module.installed || module.updateAvailable
        val minCore = if (installing) module.latestMinCoreVersion ?: module.minCoreVersion else module.minCoreVersion
        val minApp = if (installing) module.latestMinAppVersion ?: module.minAppVersion else module.minAppVersion
        val blockedReason = module.installBlockedReason?.takeIf { it.isNotBlank() }
            ?: if (installing && isAppOlderThan(minApp)) {
                tr("Este modulo necesita la app {0} o superior; tu app es {1}. Actualizala antes de instalar.", minApp, ClientVersion)
            } else {
                null
            }
        ModuleRequirement(minCore, minApp, blockedReason)
    }
    val canInstall = enabled && !inProgress && (!module.installed || updateAvailable) && requirement.blockedReason == null
    val canDelete = enabled && module.installed && !module.locked && module.activeCompanyCount == 0
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(AppTheme.colors.surfaceAlt, MaterialTheme.shapes.small)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(module.displayName, style = MaterialTheme.typography.titleSmall, color = AppTheme.colors.accentText)
        Text(module.description, color = AppTheme.colors.muted)
        Text(
            text = buildString {
                append(tr("Version instalada: {0}", module.version))
                when {
                    module.updateAvailable -> append(tr(" · Disponible: {0}", module.latestVersion))
                    // Cambiar a un catalogo con una version anterior nunca degrada el modulo.
                    module.newerThanCatalog -> append(tr(" · Mas nueva que la del catalogo ({0})", module.latestVersion))
                }
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (module.updateAvailable) {
                AppTheme.colors.warning
            } else {
                AppTheme.colors.muted
            },
        )
        if (requirement.minCore != null || requirement.minApp != null) {
            Text(
                text = tr("Requiere Core ≥ {0} · App ≥ {1}", requirement.minCore ?: "-", requirement.minApp ?: "-"),
                style = MaterialTheme.typography.bodySmall,
                color = AppTheme.colors.muted,
            )
        }
        Text(
            text = when {
                module.locked -> tr("Modulo base del sistema")
                !module.installed -> tr("Disponible para instalar")
                module.activeCompanyCount > 0 -> tr("Activo en {0} empresa(s)", module.activeCompanyCount)
                else -> tr("Instalado sin empresas activas")
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (module.activeCompanyCount > 0 || module.locked) AppTheme.colors.warning else AppTheme.colors.success,
        )
        if (canUseInServer && module.installed && !inProgress) {
            // Los modulos base quedan siempre activos una vez creado el espacio del servidor.
            val fixed = module.locked && module.usedByServer
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = module.usedByServer,
                    onCheckedChange = { onToggleServerUsage(it) },
                    enabled = enabled && !fixed,
                )
                Text(
                    text = if (fixed) tr("Usar en el servidor (modulo base, siempre activo)") else tr("Usar en el servidor"),
                    style = MaterialTheme.typography.bodySmall,
                    color = AppTheme.colors.text,
                )
            }
        }
        module.statusMessage?.takeIf { module.status != null }?.let { status ->
            Text(
                text = when (module.status) {
                    "installing" -> tr("Instalando: {0}...", status)
                    "restarting" -> "$status..."
                    "attention" -> tr("Atencion: {0}", status)
                    else -> tr("No se pudo instalar: {0}", status)
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (module.status == "failed" || module.status == "attention") AppTheme.colors.danger else AppTheme.colors.accent,
            )
        }
        if (!module.installed || updateAvailable) {
            requirement.blockedReason?.let { reason ->
                Text(reason, style = MaterialTheme.typography.bodySmall, color = AppTheme.colors.danger)
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (canInstallModule && (!module.installed || updateAvailable)) {
                Button(
                    enabled = canInstall,
                    onClick = onInstall,
                    colors = accentButtonColors(),
                ) {
                    Text(
                        when {
                            inProgress -> tr("Instalando...")
                            updateAvailable -> tr("Actualizar a {0}", module.latestVersion)
                            module.status == "failed" -> tr("Reintentar")
                            else -> tr("Instalar")
                        }
                    )
                }
            }
            if (canRemoveModule) Button(
                enabled = canDelete,
                onClick = if (pendingDeletion) onConfirmDelete else onRequestDelete,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (pendingDeletion) AppTheme.colors.danger else AppTheme.colors.surfaceAlt,
                    contentColor = if (pendingDeletion) Color.White else AppTheme.colors.danger,
                ),
            ) {
                Text(if (pendingDeletion) tr("Confirmar eliminar") else tr("Eliminar del servidor"))
            }
            if (canRemoveModule && pendingDeletion) {
                Button(
                    enabled = enabled,
                    onClick = onCancelDelete,
                    colors = ButtonDefaults.buttonColors(containerColor = AppTheme.colors.surfaceAlt, contentColor = AppTheme.colors.accentText),
                ) {
                    Text(tr("Cancelar"))
                }
            }
        }
    }
}

private suspend inline fun <T> CompanyApi.use(block: suspend (CompanyApi) -> T): T {
    return try {
        block(this)
    } finally {
        close()
    }
}

// Codigo del espacio del servidor (igual que ServerWorkspace.Code en el servidor).
private const val ServerWorkspaceCode = "servidor"

// Sesion con los modulos activos en el espacio del servidor segun la lista recibida.
private fun LoginResponse.withServerWorkspace(modules: List<ServerModuleResponse>): LoginResponse {
    val serverModules = modules.filter { it.usedByServer }.map { it.moduleId }
    return copy(
        enabledModules = serverModules,
        workspaceCompanyCode = if (serverModules.isEmpty()) workspaceCompanyCode else ServerWorkspaceCode,
    )
}
