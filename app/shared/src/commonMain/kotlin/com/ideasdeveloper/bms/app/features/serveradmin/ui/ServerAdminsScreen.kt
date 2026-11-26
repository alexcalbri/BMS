package com.ideasdeveloper.bms.app.features.serveradmin.ui

import com.ideasdeveloper.bms.app.core.i18n.tr
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import com.ideasdeveloper.bms.app.core.layout.AdaptiveGrid
import com.ideasdeveloper.bms.app.core.layout.AdaptiveLayout
import com.ideasdeveloper.bms.app.core.layout.ContentWidth
import com.ideasdeveloper.bms.app.core.layout.contentWidth
import com.ideasdeveloper.bms.app.core.theme.AppTheme
import com.ideasdeveloper.bms.app.core.theme.accentButtonColors
import com.ideasdeveloper.bms.app.features.auth.data.LoginResponse
import com.ideasdeveloper.bms.app.features.company.data.CompanyApi
import com.ideasdeveloper.bms.app.features.company.data.CompanyException
import com.ideasdeveloper.bms.app.features.company.data.CompanySummaryResponse
import com.ideasdeveloper.bms.app.features.serveradmin.data.CreateServerAdminRequest
import com.ideasdeveloper.bms.app.features.serveradmin.data.ServerAdminApi
import com.ideasdeveloper.bms.app.features.serveradmin.data.ServerAdminResponse
import com.ideasdeveloper.bms.app.features.serveradmin.data.ServerPermissionInfo
import com.ideasdeveloper.bms.app.features.serveradmin.data.UpdateServerAdminRequest
import com.ideasdeveloper.bms.app.features.serveradmin.data.asDisplayText
import com.ideasdeveloper.bms.app.features.serveradmin.data.use
import com.ideasdeveloper.bms.app.features.shell.ui.AuthenticatedScreen
import com.ideasdeveloper.bms.app.features.shell.ui.AuthenticatedTopBar
import com.ideasdeveloper.bms.app.features.shell.ui.ClosableView
import com.ideasdeveloper.bms.app.features.shell.ui.ConfirmDialog
import kotlinx.coroutines.launch

@Composable
// Administradores del servidor (solo el dueno): alta, edicion, estado, contrasena, borrado, permisos y alcance por empresas.
fun ServerAdminsScreen(
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

    var admins by remember { mutableStateOf<List<ServerAdminResponse>>(emptyList()) }
    var catalog by remember { mutableStateOf<List<ServerPermissionInfo>>(emptyList()) }
    var companies by remember { mutableStateOf<List<CompanySummaryResponse>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    // Alta
    var creating by remember { mutableStateOf(false) }
    var newLogin by remember { mutableStateOf("") }
    var newName by remember { mutableStateOf("") }
    var newEmail by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var newConfirmation by remember { mutableStateOf("") }
    var newAllCompanies by remember { mutableStateOf(false) }
    var newCompanies by remember { mutableStateOf<Set<String>>(emptySet()) }
    var newPermissions by remember { mutableStateOf<Set<String>>(emptySet()) }

    // Edicion de un administrador
    var editingId by remember { mutableStateOf<String?>(null) }
    var editName by remember { mutableStateOf("") }
    var editEmail by remember { mutableStateOf("") }
    var editAllCompanies by remember { mutableStateOf(false) }
    var editCompanies by remember { mutableStateOf<Set<String>>(emptySet()) }
    var editPermissions by remember { mutableStateOf<Set<String>>(emptySet()) }

    var resettingPassword by remember { mutableStateOf<ServerAdminResponse?>(null) }
    var resetError by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<ServerAdminResponse?>(null) }

    fun reload() {
        val url = serverUrl ?: return
        val accessToken = token ?: return
        loading = true
        scope.launch {
            try {
                ServerAdminApi(url).use { api ->
                    admins = api.admins(accessToken)
                    catalog = api.permissions(accessToken)
                }
                error = null
            } catch (exception: CompanyException) {
                error = exception.message
            }
            val companyApi = CompanyApi(url)
            try {
                companies = companyApi.listCompanies(accessToken)
            } catch (exception: CompanyException) {
                error = error ?: exception.message
            } finally {
                companyApi.close()
                loading = false
            }
        }
    }

    LaunchedEffect(serverUrl, token) { reload() }

    // Ejecuta una operacion de escritura, muestra su resultado y recarga la lista.
    fun perform(success: String, block: suspend (ServerAdminApi, String) -> Unit, onDone: () -> Unit = {}) {
        val url = serverUrl ?: return
        val accessToken = token ?: return
        busy = true
        error = null
        message = null
        scope.launch {
            try {
                ServerAdminApi(url).use { api -> block(api, accessToken) }
                message = success
                onDone()
                reload()
            } catch (exception: CompanyException) {
                error = exception.message
            } finally {
                busy = false
            }
        }
    }

    val newPasswordProblem = when {
        newPassword.isNotEmpty() && newPassword.length < ServerPasswordMinimum -> tr("Minimo {0} caracteres.", ServerPasswordMinimum)
        newConfirmation.isNotEmpty() && newConfirmation != newPassword -> tr("Las contrasenas no coinciden.")
        else -> null
    }
    val canCreate = !busy && newLogin.isNotBlank() && newName.isNotBlank() && newEmail.isNotBlank() &&
        newPassword.length >= ServerPasswordMinimum && newPassword == newConfirmation

    resettingPassword?.let { target ->
        ResetAdminPasswordDialog(
            admin = target,
            busy = busy,
            error = resetError,
            onConfirm = { password ->
                val url = serverUrl
                val accessToken = token
                if (url != null && accessToken != null) {
                    busy = true
                    resetError = null
                    scope.launch {
                        try {
                            ServerAdminApi(url).use { api -> api.resetPassword(accessToken, target.id, password) }
                            resettingPassword = null
                            message = tr("Contrasena de {0} cambiada: debera cambiarla al iniciar sesion y sus sesiones se cerraron.", target.login)
                            error = null
                            reload()
                        } catch (exception: CompanyException) {
                            resetError = exception.message
                        } finally {
                            busy = false
                        }
                    }
                }
            },
            onDismiss = { resettingPassword = null },
        )
    }

    deleting?.let { target ->
        ConfirmDialog(
            title = tr("Eliminar administrador"),
            message = tr("Se eliminara a {0} ({1}) y se cerraran sus sesiones. Sus acciones pasadas siguen en el registro de auditoria. No se puede deshacer.", target.displayName.ifBlank { target.login }, target.login),
            confirmLabel = tr("Eliminar"),
            onConfirm = {
                deleting = null
                if (editingId == target.id) editingId = null
                perform(tr("Administrador {0} eliminado.", target.login), { api, accessToken -> api.deleteAdmin(accessToken, target.id) })
            },
            onDismiss = { deleting = null },
        )
    }

    AuthenticatedScreen(
        modifier = modifier.background(colors.screenBrush),
        topBar = {
            AuthenticatedTopBar(
                title = tr("Administradores del servidor"),
                subtitle = tr("Usuarios con permisos limitados"),
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
                color = colors.surface,
                shape = MaterialTheme.shapes.medium,
            ) {
                Column(
                    modifier = Modifier.padding(AdaptiveLayout.screenPadding - 2.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(
                        modifier = Modifier.widthIn(max = ContentWidth.Form).fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            tr("Un administrador solo puede hacer lo que sus permisos indican y, si se limita por empresas, solo en ellas. Solo el dueno del servidor gestiona administradores."),
                            color = colors.muted,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(tr("Nuevo administrador"), style = MaterialTheme.typography.titleLarge, color = colors.accentText)
                            Button(
                                enabled = !busy,
                                onClick = { creating = !creating },
                                colors = secondaryButtonColors(),
                            ) { Text(if (creating) tr("Ocultar") else tr("Crear nuevo")) }
                        }
                        if (creating) {
                            OutlinedTextField(
                                value = newLogin,
                                onValueChange = { newLogin = it.trim().lowercase() },
                                label = { Text(tr("Usuario")) },
                                singleLine = true,
                                enabled = !busy,
                                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None),
                                modifier = Modifier.fillMaxWidth(),
                            )
                            OutlinedTextField(
                                value = newName,
                                onValueChange = { newName = it },
                                label = { Text(tr("Nombre completo")) },
                                singleLine = true,
                                enabled = !busy,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            OutlinedTextField(
                                value = newEmail,
                                onValueChange = { newEmail = it.trim() },
                                label = { Text(tr("Correo")) },
                                singleLine = true,
                                enabled = !busy,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            OutlinedTextField(
                                value = newPassword,
                                onValueChange = { newPassword = it },
                                label = { Text(tr("Contrasena inicial (minimo {0})", ServerPasswordMinimum)) },
                                singleLine = true,
                                enabled = !busy,
                                visualTransformation = PasswordVisualTransformation(),
                                modifier = Modifier.fillMaxWidth(),
                            )
                            OutlinedTextField(
                                value = newConfirmation,
                                onValueChange = { newConfirmation = it },
                                label = { Text(tr("Confirmar contrasena")) },
                                singleLine = true,
                                enabled = !busy,
                                visualTransformation = PasswordVisualTransformation(),
                                isError = newPasswordProblem != null,
                                supportingText = {
                                    Text(newPasswordProblem ?: tr("El administrador debera cambiarla en su primer inicio de sesion."))
                                },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Text(tr("Empresas"), style = MaterialTheme.typography.titleMedium, color = colors.accentText)
                            CompanyScopeEditor(
                                allCompanies = newAllCompanies,
                                onAllCompaniesChange = { newAllCompanies = it },
                                selected = newCompanies,
                                onSelectedChange = { newCompanies = it },
                                companies = companies,
                                enabled = !busy,
                            )
                            Text(tr("Permisos"), style = MaterialTheme.typography.titleMedium, color = colors.accentText)
                            PermissionsEditor(
                                catalog = catalog,
                                selected = newPermissions,
                                onSelectedChange = { newPermissions = it },
                                enabled = !busy,
                            )
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                                Button(
                                    enabled = canCreate,
                                    onClick = {
                                        val request = CreateServerAdminRequest(
                                            login = newLogin.trim(),
                                            displayName = newName.trim(),
                                            email = newEmail.trim(),
                                            password = newPassword,
                                            allCompanies = newAllCompanies,
                                            companies = if (newAllCompanies) emptyList() else newCompanies.toList(),
                                            permissions = newPermissions.toList(),
                                        )
                                        perform(
                                            tr("Administrador {0} creado. Debera cambiar la contrasena inicial al entrar.", request.login),
                                            { api, accessToken -> api.createAdmin(accessToken, request) },
                                        ) {
                                            newLogin = ""
                                            newName = ""
                                            newEmail = ""
                                            newPassword = ""
                                            newConfirmation = ""
                                            newAllCompanies = false
                                            newCompanies = emptySet()
                                            newPermissions = emptySet()
                                            creating = false
                                        }
                                    },
                                    colors = accentButtonColors(),
                                ) { Text(tr("Crear")) }
                            }
                        }
                        Text(tr("Administradores"), style = MaterialTheme.typography.titleMedium, color = colors.accentText)
                        error?.let { Text(it, color = colors.danger) }
                        message?.let { Text(it, color = colors.success) }
                        if (loading) Text(tr("Cargando administradores..."), color = colors.muted)
                        if (!loading && error == null && admins.isEmpty()) {
                            Text(tr("Aun no hay administradores del servidor."), color = colors.muted)
                        }
                    }

                    AdaptiveGrid(modifier = Modifier.fillMaxWidth(), minItemWidth = 420.dp, gap = 12.dp, equalHeight = false) {
                        admins.forEach { admin ->
                            val isEditing = editingId == admin.id
                            AdminCard(
                                admin = admin,
                                catalog = catalog,
                                companies = companies,
                                busy = busy,
                                editing = isEditing,
                                editName = editName,
                                editEmail = editEmail,
                                editAllCompanies = editAllCompanies,
                                editCompanies = editCompanies,
                                editPermissions = editPermissions,
                                onEditNameChange = { editName = it },
                                onEditEmailChange = { editEmail = it.trim() },
                                onEditAllCompaniesChange = { editAllCompanies = it },
                                onEditCompaniesChange = { editCompanies = it },
                                onEditPermissionsChange = { editPermissions = it },
                                onStartEdit = {
                                    editingId = admin.id
                                    editName = admin.displayName
                                    editEmail = admin.email
                                    editAllCompanies = admin.allCompanies
                                    editCompanies = admin.companies.toSet()
                                    editPermissions = admin.permissions.toSet()
                                },
                                onCancelEdit = { editingId = null },
                                onSaveProfile = {
                                    val request = UpdateServerAdminRequest(
                                        displayName = editName.trim(),
                                        email = editEmail.trim(),
                                        allCompanies = editAllCompanies,
                                        companies = if (editAllCompanies) emptyList() else editCompanies.toList(),
                                    )
                                    perform(tr("Datos de {0} guardados.", admin.login), { api, accessToken -> api.updateAdmin(accessToken, admin.id, request) })
                                },
                                onSavePermissions = {
                                    val granted = editPermissions.toList()
                                    perform(tr("Permisos de {0} guardados.", admin.login), { api, accessToken -> api.updatePermissions(accessToken, admin.id, granted) })
                                },
                                onToggleActive = {
                                    val active = !admin.active
                                    perform(
                                        if (active) tr("Administrador {0} activado.", admin.login) else tr("Administrador {0} desactivado y sus sesiones cerradas.", admin.login),
                                        { api, accessToken -> api.setActive(accessToken, admin.id, active) },
                                    )
                                },
                                onResetPassword = {
                                    resetError = null
                                    resettingPassword = admin
                                },
                                onDelete = { deleting = admin },
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                }
            }
        }
    }
}

@Composable
private fun secondaryButtonColors() =
    ButtonDefaults.buttonColors(containerColor = AppTheme.colors.surfaceAlt, contentColor = AppTheme.colors.accentText)

@OptIn(ExperimentalLayoutApi::class)
@Composable
// Tarjeta de un administrador con sus acciones; en edicion muestra datos, alcance y permisos.
private fun AdminCard(
    admin: ServerAdminResponse,
    catalog: List<ServerPermissionInfo>,
    companies: List<CompanySummaryResponse>,
    busy: Boolean,
    editing: Boolean,
    editName: String,
    editEmail: String,
    editAllCompanies: Boolean,
    editCompanies: Set<String>,
    editPermissions: Set<String>,
    onEditNameChange: (String) -> Unit,
    onEditEmailChange: (String) -> Unit,
    onEditAllCompaniesChange: (Boolean) -> Unit,
    onEditCompaniesChange: (Set<String>) -> Unit,
    onEditPermissionsChange: (Set<String>) -> Unit,
    onStartEdit: () -> Unit,
    onCancelEdit: () -> Unit,
    onSaveProfile: () -> Unit,
    onSavePermissions: () -> Unit,
    onToggleActive: () -> Unit,
    onResetPassword: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = AppTheme.colors
    val highRisk = catalog.filter { it.risk == "high" }.map { it.id }.toSet()
    val hasHighRisk = admin.permissions.any { it in highRisk }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surfaceAlt, MaterialTheme.shapes.small)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(admin.displayName.ifBlank { admin.login }, style = MaterialTheme.typography.titleSmall, color = colors.accentText)
        Text(tr("Usuario: {0}", admin.login), style = MaterialTheme.typography.bodySmall, color = colors.muted)
        if (admin.email.isNotBlank()) Text(admin.email, style = MaterialTheme.typography.bodySmall, color = colors.muted)
        Text(
            if (admin.active) tr("Estado: activo") else tr("Estado: desactivado"),
            style = MaterialTheme.typography.bodySmall,
            color = if (admin.active) colors.success else colors.warning,
        )
        if (admin.mustChangePassword) {
            Text(tr("Aun debe cambiar su contrasena inicial."), style = MaterialTheme.typography.bodySmall, color = colors.warning)
        }
        Text(
            if (admin.allCompanies) tr("Empresas: todas")
            else if (admin.companies.isEmpty()) tr("Empresas: ninguna")
            else tr("Empresas: {0}", admin.companies.joinToString()),
            style = MaterialTheme.typography.bodySmall,
            color = colors.muted,
        )
        Text(tr("Permisos concedidos: {0}", admin.permissions.size), style = MaterialTheme.typography.bodySmall, color = colors.muted)
        if (hasHighRisk) {
            Text(tr("Tiene permisos de riesgo alto."), style = MaterialTheme.typography.bodySmall, color = colors.warning, fontWeight = FontWeight.SemiBold)
        }
        val created = admin.createdAt.asDisplayText()
        if (created.isNotBlank()) Text(tr("Creado: {0}", created), style = MaterialTheme.typography.bodySmall, color = colors.muted)

        if (editing) {
            OutlinedTextField(
                value = editName,
                onValueChange = onEditNameChange,
                label = { Text(tr("Nombre completo")) },
                singleLine = true,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = editEmail,
                onValueChange = onEditEmailChange,
                label = { Text(tr("Correo")) },
                singleLine = true,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(tr("Empresas"), style = MaterialTheme.typography.titleSmall, color = colors.accentText)
            CompanyScopeEditor(
                allCompanies = editAllCompanies,
                onAllCompaniesChange = onEditAllCompaniesChange,
                selected = editCompanies,
                onSelectedChange = onEditCompaniesChange,
                companies = companies,
                enabled = !busy,
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Button(
                    enabled = !busy && editName.isNotBlank() && editEmail.isNotBlank(),
                    onClick = onSaveProfile,
                    colors = accentButtonColors(),
                ) { Text(tr("Guardar datos y empresas")) }
            }
            Text(tr("Permisos"), style = MaterialTheme.typography.titleSmall, color = colors.accentText)
            PermissionsEditor(catalog = catalog, selected = editPermissions, onSelectedChange = onEditPermissionsChange, enabled = !busy)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Button(enabled = !busy, onClick = onSavePermissions, colors = accentButtonColors()) { Text(tr("Guardar permisos")) }
            }
        }

        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (editing) {
                Button(enabled = !busy, onClick = onCancelEdit, colors = secondaryButtonColors()) { Text(tr("Cerrar edicion")) }
            } else {
                Button(enabled = !busy, onClick = onStartEdit, colors = secondaryButtonColors()) { Text(tr("Editar")) }
            }
            Button(enabled = !busy, onClick = onToggleActive, colors = secondaryButtonColors()) {
                Text(if (admin.active) tr("Desactivar") else tr("Activar"))
            }
            Button(enabled = !busy, onClick = onResetPassword, colors = secondaryButtonColors()) { Text(tr("Restablecer contrasena")) }
            Button(
                enabled = !busy,
                onClick = onDelete,
                colors = ButtonDefaults.buttonColors(containerColor = colors.surface, contentColor = colors.danger),
            ) { Text(tr("Eliminar")) }
        }
    }
}

@Composable
// Todas las empresas o una seleccion de la lista; sin seleccion y sin "todas" el administrador no actua sobre ninguna.
private fun CompanyScopeEditor(
    allCompanies: Boolean,
    onAllCompaniesChange: (Boolean) -> Unit,
    selected: Set<String>,
    onSelectedChange: (Set<String>) -> Unit,
    companies: List<CompanySummaryResponse>,
    enabled: Boolean,
) {
    val colors = AppTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = allCompanies, onCheckedChange = onAllCompaniesChange, enabled = enabled)
            Text(tr("Todas las empresas (tambien las que se creen despues)"), color = colors.text)
        }
        if (!allCompanies) {
            if (companies.isEmpty()) Text(tr("No hay empresas para elegir."), color = colors.muted)
            companies.forEach { company ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = company.code in selected,
                        onCheckedChange = { checked ->
                            onSelectedChange(if (checked) selected + company.code else selected - company.code)
                        },
                        enabled = enabled,
                    )
                    Text("${company.name} (${company.code})", color = colors.text)
                }
            }
            if (selected.isEmpty()) {
                Text(tr("Sin empresas elegidas: no podra actuar sobre ninguna empresa."), color = colors.warning, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
// Casillas de permisos agrupadas segun el grupo que devuelve el servidor; los de riesgo alto se resaltan con aviso.
private fun PermissionsEditor(
    catalog: List<ServerPermissionInfo>,
    selected: Set<String>,
    onSelectedChange: (Set<String>) -> Unit,
    enabled: Boolean,
) {
    val colors = AppTheme.colors
    if (catalog.isEmpty()) {
        Text(tr("No se pudo cargar el catalogo de permisos."), color = colors.muted)
        return
    }
    val groups = catalog.groupBy { it.group }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        groups.forEach { (group, permissions) ->
            Text(group.ifBlank { tr("Otros") }, style = MaterialTheme.typography.titleSmall, color = colors.text)
            permissions.forEach { permission ->
                val high = permission.risk == "high"
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(if (high) Modifier.border(1.5.dp, colors.warning, MaterialTheme.shapes.small).padding(4.dp) else Modifier),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = permission.id in selected,
                        onCheckedChange = { checked ->
                            onSelectedChange(if (checked) selected + permission.id else selected - permission.id)
                        },
                        enabled = enabled,
                    )
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(permission.title.ifBlank { permission.id }, color = colors.text, fontWeight = if (high) FontWeight.SemiBold else FontWeight.Normal)
                        if (permission.description.isNotBlank()) {
                            Text(permission.description, style = MaterialTheme.typography.bodySmall, color = colors.muted)
                        }
                        if (high) {
                            Text(tr("Riesgo alto: conceder solo a personas de maxima confianza."), style = MaterialTheme.typography.bodySmall, color = colors.warning)
                        }
                        if (permission.scoped) {
                            Text(tr("Se limita a las empresas del administrador."), style = MaterialTheme.typography.bodySmall, color = colors.muted)
                        }
                    }
                }
            }
        }
    }
}

@Composable
// Nueva contrasena de un administrador: la cambia el dueno y el administrador debe cambiarla al entrar.
private fun ResetAdminPasswordDialog(
    admin: ServerAdminResponse,
    busy: Boolean,
    error: String?,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var password by remember(admin.id) { mutableStateOf("") }
    var confirmation by remember(admin.id) { mutableStateOf("") }
    val problem = when {
        password.isNotEmpty() && password.length < ServerPasswordMinimum -> tr("Minimo {0} caracteres.", ServerPasswordMinimum)
        confirmation.isNotEmpty() && confirmation != password -> tr("Las contrasenas no coinciden.")
        else -> null
    }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(tr("Restablecer contrasena de {0}", admin.login)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    tr("Se cerraran sus sesiones y debera cambiar esta contrasena al iniciar sesion."),
                    color = AppTheme.colors.muted,
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(tr("Contrasena nueva (minimo {0})", ServerPasswordMinimum)) },
                    singleLine = true,
                    enabled = !busy,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = confirmation,
                    onValueChange = { confirmation = it },
                    label = { Text(tr("Confirmar contrasena")) },
                    singleLine = true,
                    enabled = !busy,
                    visualTransformation = PasswordVisualTransformation(),
                    isError = problem != null,
                    supportingText = problem?.let { message -> { Text(message) } },
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let { Text(it, color = AppTheme.colors.danger) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && password.length >= ServerPasswordMinimum && password == confirmation,
                onClick = { onConfirm(password) },
            ) { Text(tr("Cambiar contrasena")) }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text(tr("Cancelar")) } },
    )
}
