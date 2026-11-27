package com.ideasdeveloper.bms.app.features.company.ui

import com.ideasdeveloper.bms.app.core.i18n.tr
import com.ideasdeveloper.bms.app.core.theme.accentButtonColors
import com.ideasdeveloper.bms.app.features.shell.ui.CollapsibleSection
import com.ideasdeveloper.bms.app.core.theme.AppTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import com.ideasdeveloper.bms.app.core.layout.AdaptiveGrid
import com.ideasdeveloper.bms.app.core.layout.ContentWidth
import com.ideasdeveloper.bms.app.core.ui.CopyableCode
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.ideasdeveloper.bms.app.features.company.data.CompanyApi
import com.ideasdeveloper.bms.app.features.company.data.CompanyException
import com.ideasdeveloper.bms.app.features.company.data.CompanyPermissionResponse
import com.ideasdeveloper.bms.app.features.company.data.CompanyUserResponse
import com.ideasdeveloper.bms.app.features.company.data.CreateCompanyUserRequest
import com.ideasdeveloper.bms.app.features.company.data.UpdateCompanyUserRequest
import kotlinx.coroutines.launch

/**
 * Usuarios y permisos de una base de empresa. La usa el business_owner para su empresa (y para
 * editarse a si mismo) y el server_owner para el personal del espacio del servidor o al entrar a una
 * empresa; los permisos vienen de los modulos activos.
 */
@Composable
fun CompanyUsersSection(
    serverUrl: String?,
    accessToken: String?,
    companyCode: String?,
    primaryColor: Color,
    description: String,
    // Muestra "Desactivar verificacion en dos pasos" en los usuarios que la tienen activa. [resetViaServer]: usa la ruta del
    // servidor (dueno o administrador con permiso) en vez de la del business owner.
    canResetTwoFactor: Boolean = false,
    resetViaServer: Boolean = false,
    // La sesion de empresa se abrio desde el selector del servidor: la contrasena que se pide es la del servidor de quien actua.
    switcherSession: Boolean = false,
) {
    val scope = rememberCoroutineScope()
    var resettingUser by remember { mutableStateOf<CompanyUserResponse?>(null) }
    var users by remember { mutableStateOf<List<CompanyUserResponse>>(emptyList()) }
    var permissions by remember { mutableStateOf<List<CompanyPermissionResponse>>(emptyList()) }
    var newUsername by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var newDisplayName by remember { mutableStateOf("") }
    var newEmail by remember { mutableStateOf("") }
    // Error de contrasena actual al cambiar el correo del business owner (se muestra junto al campo).
    var currentPasswordError by remember { mutableStateOf<String?>(null) }
    var selectedPermissions by remember { mutableStateOf<Set<String>>(emptySet()) }
    var isLoading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    // Usuario que se esta editando.
    var editingUserId by remember { mutableStateOf<String?>(null) }
    val permissionTitles = remember(permissions) { permissions.associate { it.permissionId to it.title } }

    LaunchedEffect(serverUrl, accessToken, companyCode) {
        val activeServerUrl = serverUrl ?: return@LaunchedEffect
        val token = accessToken ?: return@LaunchedEffect
        val code = companyCode ?: return@LaunchedEffect
        isLoading = true
        try {
            val userData = CompanyApi(activeServerUrl).use { api -> api.companyUsers(token, code) }
            users = userData.users
            permissions = userData.permissions
            error = null
        } catch (exception: CompanyException) {
            error = exception.message
        } finally {
            isLoading = false
        }
    }

    resettingUser?.let { target ->
        TwoFactorResetDialog(
            targetName = target.displayName?.takeIf { it.isNotBlank() } ?: target.username,
            hint = if (switcherSession) tr("Entraste a esta empresa desde el servidor: escribe tu contrasena del servidor.") else null,
            submit = { currentPassword ->
                val activeServerUrl = serverUrl ?: throw CompanyException(tr("Configura el servidor."))
                val token = accessToken ?: throw CompanyException(tr("Inicia sesion de nuevo."))
                val code = companyCode ?: throw CompanyException(tr("Configura el servidor."))
                CompanyApi(activeServerUrl).use { api -> api.resetUserTwoFactor(token, code, target.userId, currentPassword, resetViaServer) }
            },
            onDone = { resultMessage ->
                resettingUser = null
                error = null
                message = resultMessage
                // Se actualiza la lista para ver el estado nuevo.
                scope.launch {
                    val activeServerUrl = serverUrl ?: return@launch
                    val token = accessToken ?: return@launch
                    val code = companyCode ?: return@launch
                    try {
                        users = CompanyApi(activeServerUrl).use { api -> api.companyUsers(token, code) }.users
                    } catch (_: CompanyException) {
                    }
                }
            },
            onDismiss = { resettingUser = null },
        )
    }

    // El encabezado y el alta quedan en un ancho de formulario; la lista de usuarios usa la cuadricula.
    Column(
        modifier = Modifier.widthIn(max = ContentWidth.Form).fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
    Text(tr("Usuarios y permisos"), style = MaterialTheme.typography.titleMedium, color = AppTheme.colors.accentText)
    Text(description, color = AppTheme.colors.muted)
    // El alta es independiente de la lista: empieza cerrada para ver los usuarios primero.
    CollapsibleSection(tr("Nuevo usuario"), primaryColor, subtitle = tr("Usuario, nombre, correo, contrasena y permisos")) {
        OutlinedTextField(
            value = newUsername,
            onValueChange = { newUsername = it },
            label = { Text(tr("Usuario")) },
            singleLine = true,
            enabled = !isLoading,
            modifier = Modifier.fillMaxWidth(),
        )
        // Obligatorios para todos: los cambios se registran con el nombre y el correo recibe codigos de contrasena.
        OutlinedTextField(
            value = newDisplayName,
            onValueChange = { newDisplayName = it },
            label = { Text(tr("Nombre completo")) },
            singleLine = true,
            enabled = !isLoading,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = newEmail,
            onValueChange = { newEmail = it.trim() },
            label = { Text(tr("Correo")) },
            singleLine = true,
            enabled = !isLoading,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = newPassword,
            onValueChange = { newPassword = it },
            label = { Text(tr("Contrasena inicial")) },
            singleLine = true,
            enabled = !isLoading,
            modifier = Modifier.fillMaxWidth(),
        )
        permissions.forEach { permission ->
            PermissionCheckbox(
                permission = permission,
                checked = selectedPermissions.contains(permission.permissionId),
                enabled = !isLoading,
                primaryColor = primaryColor,
                onCheckedChange = { checked ->
                    selectedPermissions = if (checked) {
                        selectedPermissions + permission.permissionId
                    } else {
                        selectedPermissions - permission.permissionId
                    }
                },
            )
        }
        Button(
            enabled = !isLoading && serverUrl != null && accessToken != null && companyCode != null && newUsername.isNotBlank() && newPassword.isNotBlank() &&
                newDisplayName.isNotBlank() && newEmail.isNotBlank(),
            onClick = {
                val activeServerUrl = serverUrl ?: return@Button
                val token = accessToken ?: return@Button
                val code = companyCode ?: return@Button
                isLoading = true
                error = null
                message = null
                scope.launch {
                    try {
                        CompanyApi(activeServerUrl).use { api ->
                            api.createCompanyUser(
                                token,
                                code,
                                CreateCompanyUserRequest(
                                    username = newUsername,
                                    password = newPassword,
                                    permissions = selectedPermissions.toList(),
                                    displayName = newDisplayName,
                                    email = newEmail,
                                ),
                            )
                            val userData = api.companyUsers(token, code)
                            users = userData.users
                            permissions = userData.permissions
                        }
                        newUsername = ""
                        newPassword = ""
                        newDisplayName = ""
                        newEmail = ""
                        selectedPermissions = emptySet()
                        message = tr("Usuario creado.")
                    } catch (exception: CompanyException) {
                        error = exception.message
                    } finally {
                        isLoading = false
                    }
                }
            },
            colors = accentButtonColors(),
        ) {
            Text(tr("Crear usuario"))
        }
    }
    message?.let { Text(it, color = AppTheme.colors.success) }
    error?.let { Text(it, color = AppTheme.colors.danger) }
    }
    AdaptiveGrid(modifier = Modifier.fillMaxWidth(), minItemWidth = 360.dp, gap = 12.dp, equalHeight = false) {
    users.forEach { user ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(AppTheme.colors.surfaceAlt, MaterialTheme.shapes.small)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(user.displayName?.let { "$it (${user.username})" } ?: user.username, fontWeight = FontWeight.SemiBold, color = AppTheme.colors.accentText)
            Text(user.email ?: tr("Sin correo: lo completa al iniciar sesion"), color = AppTheme.colors.muted)
            if (user.userCode.isNotBlank()) CopyableCode(label = tr("Codigo de usuario"), code = user.userCode)
            Text(if (user.isActive) tr("Activo") else tr("Inactivo"), color = AppTheme.colors.muted)
            user.twoFactorEnabled?.let { enabled ->
                Text(
                    if (enabled) tr("Verificacion en dos pasos: activada") else tr("Verificacion en dos pasos: desactivada"),
                    color = AppTheme.colors.muted,
                )
            }
            if (editingUserId == user.userId) {
                UserEditForm(
                    user = user,
                    permissions = permissions,
                    busy = isLoading,
                    primaryColor = primaryColor,
                    onCancel = { editingUserId = null },
                    onSave = { request ->
                        val activeServerUrl = serverUrl ?: return@UserEditForm
                        val token = accessToken ?: return@UserEditForm
                        val code = companyCode ?: return@UserEditForm
                        isLoading = true
                        error = null
                        currentPasswordError = null
                        message = null
                        scope.launch {
                            try {
                                val updated = CompanyApi(activeServerUrl).use { api ->
                                    api.updateCompanyUser(token, code, user.userId, request)
                                }
                                users = users.map { if (it.userId == updated.userId) updated else it }
                                editingUserId = null
                                message = if (user.isBusinessOwner && request.password.isNotEmpty()) {
                                    tr("Datos actualizados. Tus sesiones se cerraron: inicia sesion con la contrasena nueva.")
                                } else {
                                    tr("Usuario actualizado.")
                                }
                            } catch (exception: CompanyException) {
                                // Los errores de contrasena actual se muestran junto a su campo.
                                if (exception.code == "PASSWORD_REQUIRED" || exception.code == "INVALID_PASSWORD" || exception.code == "TOO_MANY_ATTEMPTS") {
                                    currentPasswordError = exception.message
                                } else {
                                    error = exception.message
                                }
                            } finally {
                                isLoading = false
                            }
                        }
                    },
                    currentPasswordError = currentPasswordError,
                    onCurrentPasswordEdited = { currentPasswordError = null },
                )
            } else {
                Text(
                    if (user.isBusinessOwner) {
                        tr("Business owner: tiene todos los permisos")
                    } else {
                        tr("Permisos: {0}", user.permissions.map { id -> permissionTitles[id] ?: id }.ifEmpty { listOf(tr("sin permisos")) }.joinToString())
                    },
                    color = AppTheme.colors.text,
                )
                OutlinedButton(
                    enabled = !isLoading,
                    onClick = {
                        editingUserId = user.userId
                        error = null
                        currentPasswordError = null
                        message = null
                    },
                ) {
                    Text(tr("Editar"))
                }
                // Quien perdio su autenticador y sus codigos; el business owner (uno mismo) no aparece aqui.
                // El servidor decide (canResetTwoFactor); sin ese dato (servidor anterior) se usa la regla del cliente.
                if (user.twoFactorEnabled == true && (user.canResetTwoFactor ?: (canResetTwoFactor && !user.isBusinessOwner))) {
                    OutlinedButton(
                        enabled = !isLoading,
                        onClick = {
                            resettingUser = user
                            error = null
                            message = null
                        },
                    ) {
                        Text(tr("Desactivar verificacion en dos pasos"), color = AppTheme.colors.danger)
                    }
                }
            }
        }
    }
    }
}

/**
 * Todos los datos de un usuario. Para el business owner no hay permisos ni estado (los tiene todos y
 * siempre esta activo) y cambiar su usuario exige una contrasena nueva: es su usuario de PostgreSQL.
 */
@Composable
private fun UserEditForm(
    user: CompanyUserResponse,
    permissions: List<CompanyPermissionResponse>,
    busy: Boolean,
    primaryColor: Color,
    onCancel: () -> Unit,
    onSave: (UpdateCompanyUserRequest) -> Unit,
    currentPasswordError: String? = null,
    onCurrentPasswordEdited: () -> Unit = {},
) {
    // Contrasena actual: solo para el business owner cuando cambia su correo; solo vive en este formulario.
    var currentPassword by remember(user.userId) { mutableStateOf("") }
    var username by remember(user.userId) { mutableStateOf(user.username) }
    var displayName by remember(user.userId) { mutableStateOf(user.displayName.orEmpty()) }
    var email by remember(user.userId) { mutableStateOf(user.email.orEmpty()) }
    var password by remember(user.userId) { mutableStateOf("") }
    var active by remember(user.userId) { mutableStateOf(user.isActive) }
    var selected by remember(user.userId) { mutableStateOf(user.permissions.toSet()) }
    // El business owner confirma su contrasena al cambiar el correo o al escribir una nueva; tambien si el servidor la reclama.
    val needsCurrentPassword = user.isBusinessOwner &&
        (!email.trim().equals(user.email.orEmpty().trim(), ignoreCase = true) || password.isNotEmpty() || currentPasswordError != null)
    val renamed = username.trim() != user.username
    val passwordProblem = when {
        password.isNotEmpty() && password.length < 12 -> tr("Minimo 12 caracteres.")
        user.isBusinessOwner && renamed && password.isEmpty() -> tr("Para cambiar el usuario del business owner escribe una contrasena nueva.")
        else -> null
    }

    OutlinedTextField(
        value = username,
        onValueChange = { username = it.trim() },
        label = { Text(tr("Usuario")) },
        singleLine = true,
        enabled = !busy,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = displayName,
        onValueChange = { displayName = it },
        label = { Text(tr("Nombre completo")) },
        singleLine = true,
        enabled = !busy,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = email,
        onValueChange = { email = it.trim() },
        label = { Text(tr("Correo")) },
        singleLine = true,
        enabled = !busy,
        modifier = Modifier.fillMaxWidth(),
    )
    if (needsCurrentPassword) {
        Text(tr("Para cambiar el correo o la contrasena, confirma tu contrasena actual."), color = AppTheme.colors.muted)
        OutlinedTextField(
            value = currentPassword,
            onValueChange = {
                currentPassword = it
                onCurrentPasswordEdited()
            },
            label = { Text(tr("Contrasena actual")) },
            singleLine = true,
            enabled = !busy,
            visualTransformation = PasswordVisualTransformation(),
            isError = currentPasswordError != null,
            supportingText = currentPasswordError?.let { message -> { Text(message) } },
            modifier = Modifier.fillMaxWidth(),
        )
    }
    OutlinedTextField(
        value = password,
        onValueChange = { password = it },
        label = { Text(tr("Contrasena nueva")) },
        singleLine = true,
        enabled = !busy,
        visualTransformation = PasswordVisualTransformation(),
        isError = passwordProblem != null,
        supportingText = {
            Text(
                passwordProblem ?: if (user.isBusinessOwner) {
                    tr("Vacia mantiene la actual. Si la cambias se cierran las sesiones del business owner en todas sus empresas.")
                } else {
                    tr("Vacia mantiene la actual. Si la cambias se cierran las sesiones del usuario.")
                }
            )
        },
        modifier = Modifier.fillMaxWidth(),
    )
    if (!user.isBusinessOwner) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = active, onCheckedChange = { active = it }, enabled = !busy)
            Text(if (active) tr("Activo: puede iniciar sesion") else tr("Inactivo: no puede iniciar sesion"), color = AppTheme.colors.text)
        }
        Text(tr("Permisos"), fontWeight = FontWeight.SemiBold, color = AppTheme.colors.accentText)
        permissions.forEach { permission ->
            PermissionCheckbox(
                permission = permission,
                checked = selected.contains(permission.permissionId),
                enabled = !busy,
                primaryColor = primaryColor,
                onCheckedChange = { checked ->
                    selected = if (checked) selected + permission.permissionId else selected - permission.permissionId
                },
            )
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            enabled = !busy && username.isNotBlank() && displayName.isNotBlank() && email.isNotBlank() && passwordProblem == null &&
                (!needsCurrentPassword || currentPassword.isNotEmpty()),
            onClick = {
                onSave(
                    UpdateCompanyUserRequest(
                        username = username.trim(),
                        displayName = displayName.trim(),
                        email = email.trim(),
                        isActive = active,
                        permissions = selected.toList(),
                        password = password,
                        currentPassword = currentPassword.takeIf { needsCurrentPassword },
                    )
                )
            },
            colors = accentButtonColors(),
        ) {
            Text(tr("Guardar"))
        }
        OutlinedButton(enabled = !busy, onClick = onCancel) {
            Text(tr("Cancelar"))
        }
    }
}

@Composable
private fun PermissionCheckbox(
    permission: CompanyPermissionResponse,
    checked: Boolean,
    enabled: Boolean,
    primaryColor: Color,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
        Column {
            Text(permission.title, fontWeight = FontWeight.SemiBold, color = AppTheme.colors.accentText)
            Text("${permission.moduleId}: ${permission.description}", color = AppTheme.colors.muted)
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
