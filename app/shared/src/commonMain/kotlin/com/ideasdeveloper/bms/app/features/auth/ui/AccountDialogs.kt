package com.ideasdeveloper.bms.app.features.auth.ui

import com.ideasdeveloper.bms.app.core.i18n.tr
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.ideasdeveloper.bms.app.core.i18n.AppLanguage
import com.ideasdeveloper.bms.app.core.theme.AppTheme
import com.ideasdeveloper.bms.app.features.auth.data.AccountApi
import com.ideasdeveloper.bms.app.features.auth.data.AccountProfile
import com.ideasdeveloper.bms.app.features.auth.data.LoginException
import com.ideasdeveloper.bms.app.features.auth.data.LoginResponse
import com.ideasdeveloper.bms.app.features.auth.data.use
import kotlinx.coroutines.launch

/**
 * Nombre y correo del usuario. [required]: un usuario creado antes de que fueran obligatorios no
 * puede continuar hasta completarlos (solo puede cerrar sesion).
 */
@Composable
fun ProfileDialog(
    serverUrl: String?,
    session: LoginResponse,
    required: Boolean,
    onSaved: (AccountProfile) -> Unit,
    onDismiss: () -> Unit,
    onLogout: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var displayName by remember { mutableStateOf(session.displayName.orEmpty()) }
    var email by remember { mutableStateOf(session.email.orEmpty()) }
    var userCode by remember { mutableStateOf("") }
    // Idioma elegido en el dialogo; null = seguir al de la empresa. Se aplica al guardar.
    var languageChoice by remember { mutableStateOf(AppLanguage.choice) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    // Contrasena actual: solo se pide al cambiar el correo; vive unicamente en el estado del dialogo.
    var currentPassword by remember { mutableStateOf("") }
    var passwordError by remember { mutableStateOf<String?>(null) }
    val originalEmail = session.email.orEmpty().trim()
    val emailChanged = !email.trim().equals(originalEmail, ignoreCase = true)
    // El servidor la pide siempre que el correo difiera del guardado (tambien si no habia correo).
    val needsPassword = emailChanged
    // Tambien se muestra si el servidor la reclama aunque session.email este desactualizado.
    val showPassword = needsPassword || passwordError != null

    LaunchedEffect(serverUrl, session.accessToken, session.companyCode) {
        val url = serverUrl ?: return@LaunchedEffect
        try {
            userCode = AccountApi(url).use { it.profile(session.accessToken, session.companyCode).userCode }
        } catch (exception: LoginException) {
            error = exception.message
        }
    }

    AlertDialog(
        onDismissRequest = { if (!required) onDismiss() },
        title = { Text(if (required) tr("Completa tu perfil") else tr("Mi perfil")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (required) {
                    Text(tr("Para continuar escribe tu nombre y tu correo. Tus cambios quedan registrados con tu nombre y el correo sirve para recuperar tu contrasena."))
                }
                OutlinedTextField(
                    value = displayName,
                    onValueChange = { displayName = it },
                    label = { Text(tr("Nombre completo")) },
                    singleLine = true,
                    enabled = !saving,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it.trim() },
                    label = { Text(tr("Correo")) },
                    singleLine = true,
                    enabled = !saving,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(tr("Idioma"), color = AppTheme.colors.muted)
                LanguageSelector(selected = languageChoice, enabled = !saving) { languageChoice = it }
                if (showPassword) {
                    Text(tr("Para cambiar el correo, confirma tu contrasena."), color = AppTheme.colors.muted)
                    OutlinedTextField(
                        value = currentPassword,
                        onValueChange = {
                            currentPassword = it
                            passwordError = null
                        },
                        label = { Text(tr("Contrasena actual")) },
                        singleLine = true,
                        enabled = !saving,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        isError = passwordError != null,
                        supportingText = passwordError?.let { message -> { Text(message) } },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (userCode.isNotBlank()) {
                    OutlinedTextField(
                        value = userCode,
                        onValueChange = {},
                        label = { Text(tr("Codigo de usuario")) },
                        readOnly = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                error?.let { Text(it, color = AppTheme.colors.danger) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !saving && serverUrl != null && displayName.isNotBlank() && email.isNotBlank() &&
                    (!showPassword || currentPassword.isNotEmpty()),
                onClick = {
                    val url = serverUrl ?: return@TextButton
                    saving = true
                    error = null
                    passwordError = null
                    scope.launch {
                        try {
                            val password = currentPassword.takeIf { showPassword && it.isNotEmpty() }
                            val profile = AccountApi(url).use {
                                it.updateProfile(session.accessToken, session.companyCode, displayName, email, languageChoice, password)
                            }
                            currentPassword = ""
                            AppLanguage.select(languageChoice)
                            onSaved(profile)
                        } catch (exception: LoginException) {
                            // Los errores de contrasena se muestran junto a su campo.
                            if (exception.code == "PASSWORD_REQUIRED" || exception.code == "INVALID_PASSWORD" || exception.code == "TOO_MANY_ATTEMPTS") {
                                passwordError = exception.message
                            } else {
                                error = exception.message
                            }
                        } finally {
                            saving = false
                        }
                    }
                },
            ) { Text(tr("Guardar")) }
        },
        dismissButton = {
            if (required) {
                TextButton(onClick = onLogout, enabled = !saving) { Text(tr("Cerrar sesion")) }
            } else {
                TextButton(onClick = onDismiss, enabled = !saving) { Text(tr("Cancelar")) }
            }
        },
    )
}

/**
 * Selector de idioma: seguir al de la empresa, Espanol o English. [selected] null = seguir a la empresa.
 * Los nombres de los idiomas se escriben en su propio idioma y no se traducen.
 */
@Composable
fun LanguageSelector(selected: String?, enabled: Boolean, onSelect: (String?) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        LanguageOption(tr("Seguir el idioma de la empresa"), null, selected, enabled, onSelect)
        LanguageOption("Espa\u00f1ol", "es", selected, enabled, onSelect)
        LanguageOption("English", "en", selected, enabled, onSelect)
    }
}

@Composable
private fun LanguageOption(label: String, value: String?, selected: String?, enabled: Boolean, onSelect: (String?) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = value == selected, enabled = enabled, role = Role.RadioButton, onClick = { onSelect(value) }),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = value == selected, onClick = null, enabled = enabled)
        Spacer(Modifier.width(8.dp))
        Text(label, color = AppTheme.colors.text)
    }
}

/**
 * Recuperacion de contrasena: pide un codigo que llega al correo del usuario y luego cambia la
 * contrasena con ese codigo. [companyCode] null es el dueno del servidor.
 */
@Composable
fun PasswordResetDialog(
    serverUrl: String,
    companyCode: String?,
    initialUsername: String,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var username by remember { mutableStateOf(initialUsername.trim()) }
    var codeSent by remember { mutableStateOf(false) }
    var code by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var done by remember { mutableStateOf(false) }
    // El servidor pidio ademas el codigo de la aplicacion de autenticacion (cuentas con verificacion en dos pasos).
    var needsTwoFactor by remember { mutableStateOf(false) }
    var twoFactorCode by remember { mutableStateOf("") }
    val minimum = if (companyCode == null) 16 else 12

    fun run(block: suspend (AccountApi) -> Unit) {
        working = true
        error = null
        scope.launch {
            try {
                AccountApi(serverUrl).use { block(it) }
            } catch (exception: LoginException) {
                error = exception.message
            } finally {
                working = false
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!working) onDismiss() },
        title = { Text(tr("Recuperar contrasena")) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    if (companyCode == null) tr("Dueno del servidor") else tr("Empresa: {0}", companyCode),
                    color = AppTheme.colors.muted,
                )
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it.trim() },
                    label = { Text(tr("Usuario")) },
                    singleLine = true,
                    enabled = !working && !codeSent && !done,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (codeSent && !done) {
                    OutlinedTextField(
                        value = code,
                        onValueChange = { value -> code = value.filter(Char::isDigit).take(6) },
                        label = { Text(tr("Codigo de 6 digitos")) },
                        singleLine = true,
                        enabled = !working,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = newPassword,
                        onValueChange = { newPassword = it },
                        label = { Text(tr("Nueva contrasena (minimo {0})", minimum)) },
                        singleLine = true,
                        enabled = !working,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = confirmation,
                        onValueChange = { confirmation = it },
                        label = { Text(tr("Confirmar contrasena")) },
                        singleLine = true,
                        enabled = !working,
                        visualTransformation = PasswordVisualTransformation(),
                        isError = confirmation.isNotEmpty() && confirmation != newPassword,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (needsTwoFactor) {
                        // El codigo del correo sigue valido: solo falta este segundo codigo.
                        OutlinedTextField(
                            value = twoFactorCode,
                            onValueChange = { twoFactorCode = it },
                            label = { Text(tr("Codigo de tu aplicacion o de recuperacion")) },
                            singleLine = true,
                            enabled = !working,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                info?.let { Text(it, color = AppTheme.colors.success) }
                error?.let { Text(it, color = AppTheme.colors.danger) }
            }
        },
        confirmButton = {
            when {
                done -> TextButton(onClick = onDismiss) { Text(tr("Iniciar sesion")) }
                !codeSent -> TextButton(
                    enabled = !working && username.isNotBlank(),
                    onClick = {
                        run { api ->
                            info = api.requestPasswordReset(username, companyCode)
                            codeSent = true
                        }
                    },
                ) { Text(tr("Enviar codigo")) }
                else -> TextButton(
                    enabled = !working && code.length == 6 && newPassword.length >= minimum && newPassword == confirmation &&
                        (!needsTwoFactor || twoFactorCode.isNotBlank()),
                    onClick = {
                        working = true
                        error = null
                        scope.launch {
                            try {
                                AccountApi(serverUrl).use { api ->
                                    info = api.confirmPasswordReset(username, companyCode, code, newPassword, twoFactorCode.takeIf { needsTwoFactor })
                                }
                                done = true
                            } catch (exception: LoginException) {
                                // La cuenta tiene dos pasos: aparece el segundo campo y se vuelve a enviar todo junto.
                                if (exception.code == "TWO_FACTOR_REQUIRED") needsTwoFactor = true
                                error = exception.message
                            } finally {
                                working = false
                            }
                        }
                    },
                ) { Text(tr("Cambiar contrasena")) }
            }
        },
        dismissButton = {
            if (!done) {
                if (codeSent) {
                    TextButton(enabled = !working, onClick = { run { api -> info = api.requestPasswordReset(username, companyCode) } }) {
                        Text(tr("Reenviar codigo"))
                    }
                }
                TextButton(enabled = !working, onClick = onDismiss) { Text(tr("Cancelar")) }
            }
        },
    )
}
