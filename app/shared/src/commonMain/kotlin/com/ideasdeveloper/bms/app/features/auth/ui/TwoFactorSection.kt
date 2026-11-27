package com.ideasdeveloper.bms.app.features.auth.ui

import com.ideasdeveloper.bms.app.core.i18n.tr
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.ideasdeveloper.bms.app.core.theme.AppTheme
import com.ideasdeveloper.bms.app.core.theme.accentButtonColors
import com.ideasdeveloper.bms.app.core.ui.QrImage
import com.ideasdeveloper.bms.app.features.auth.data.AccountApi
import com.ideasdeveloper.bms.app.features.auth.data.LoginException
import com.ideasdeveloper.bms.app.features.auth.data.LoginResponse
import com.ideasdeveloper.bms.app.features.auth.data.TwoFactorSetup
import com.ideasdeveloper.bms.app.features.auth.data.TwoFactorStatus
import com.ideasdeveloper.bms.app.features.auth.data.use
import kotlinx.coroutines.launch

// Cada flujo que abre la seccion en un dialogo.
private enum class TwoFactorDialog { Enable, Regenerate, Disable }

// Con tantos codigos de recuperacion o menos se avisa de que conviene generar otros.
private const val FewRecoveryCodes = 3

/**
 * Verificacion en dos pasos de la cuenta propia (TWO_FACTOR.md seccion 3): estado, activar (clave y enlace otpauth
 * para la aplicacion de autenticacion, codigo de confirmacion y codigos de recuperacion que se muestran una sola
 * vez), generar codigos nuevos y desactivar. [onEnabled] se llama cuando la persona termino de guardar sus codigos
 * tras activarla.
 */
@Composable
fun TwoFactorSection(
    serverUrl: String?,
    session: LoginResponse,
    modifier: Modifier = Modifier,
    onEnabled: () -> Unit = {},
) {
    val colors = AppTheme.colors
    var status by remember(session.accessToken) { mutableStateOf<TwoFactorStatus?>(null) }
    var unsupported by remember(session.accessToken) { mutableStateOf(false) }
    var loadError by remember(session.accessToken) { mutableStateOf<String?>(null) }
    var loading by remember(session.accessToken) { mutableStateOf(false) }
    var reloadTick by remember { mutableStateOf(0) }
    var dialog by remember { mutableStateOf<TwoFactorDialog?>(null) }
    val scope = rememberCoroutineScope()
    var cancellingRecovery by remember { mutableStateOf(false) }
    var recoveryError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(serverUrl, session.accessToken, session.companyCode, reloadTick) {
        val url = serverUrl ?: return@LaunchedEffect
        loading = true
        try {
            val loaded = AccountApi(url).use { it.twoFactorStatus(session.accessToken, session.companyCode) }
            // Un servidor sin la ruta (404) todavia no ofrece la verificacion en dos pasos.
            unsupported = loaded == null
            status = loaded
            loadError = null
        } catch (exception: LoginException) {
            loadError = exception.message
        } finally {
            loading = false
        }
    }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val current = status
        if (loading && current == null) {
            Text(tr("Cargando..."), color = colors.muted)
        } else if (unsupported) {
            Text(tr("Este servidor todavia no admite la verificacion en dos pasos."), color = colors.muted)
        } else if (current != null) {
            if (!current.available) {
                Text(
                    tr("El servidor no puede guardar la verificacion en dos pasos por ahora. Avisa a quien lo administra."),
                    color = colors.warning,
                )
            } else {
                Text(
                    if (current.enabled) tr("Estado: activada") else tr("Estado: desactivada"),
                    color = if (current.enabled) colors.success else colors.muted,
                    fontWeight = FontWeight.SemiBold,
                )
                if (current.enabled) {
                    Text(tr("Codigos de recuperacion disponibles: {0}", current.recoveryCodesLeft), color = colors.muted)
                    if (current.recoveryCodesLeft <= FewRecoveryCodes) {
                        Text(tr("Te quedan pocos codigos de recuperacion. Genera codigos nuevos."), color = colors.warning)
                    }
                }
                if (current.requiredByServer) {
                    Text(tr("El dueno del servidor exige la verificacion en dos pasos a los administradores."), color = colors.muted)
                }
                current.emailRecovery.pending?.let { pending ->
                    // Alguien (o la propia persona) pidio recuperar el acceso por correo: aviso con la opcion de cancelar.
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(colors.surfaceAlt, MaterialTheme.shapes.small)
                            .border(1.5.dp, colors.warning, MaterialTheme.shapes.small)
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(tr("Recuperacion del acceso por correo en curso"), fontWeight = FontWeight.SemiBold, color = colors.text)
                        Text(
                            tr("Alguien inicio la recuperacion del acceso por correo el {0} desde {1}.", utcText(pending.startedAt), pending.startedFrom.ifBlank { "-" }),
                            color = colors.text,
                        )
                        Text(
                            when {
                                !pending.codeSent -> tr("El codigo se enviara al correo de la cuenta el {0}. Con ese codigo se entra sin la aplicacion y se desactiva la verificacion en dos pasos.", utcText(pending.codeDueAt))
                                pending.codeExpiresAt != null -> tr("El codigo ya se envio al correo de la cuenta y vence el {0}.", utcText(pending.codeExpiresAt))
                                else -> tr("El codigo ya se envio al correo de la cuenta.")
                            },
                            color = colors.text,
                        )
                        Text(tr("Si no fuiste tu, cancela la recuperacion y cambia tu contrasena."), color = colors.warning)
                        OutlinedButton(
                            enabled = !cancellingRecovery,
                            onClick = {
                                val url = serverUrl ?: return@OutlinedButton
                                cancellingRecovery = true
                                recoveryError = null
                                scope.launch {
                                    try {
                                        AccountApi(url).use { api -> api.cancelEmailRecovery(session.accessToken, session.companyCode) }
                                        reloadTick += 1
                                    } catch (exception: LoginException) {
                                        // Ya no habia ninguna abierta (la cancelaron o vencio): solo se actualiza el estado.
                                        if (exception.code == "EMAIL_RECOVERY_NOT_PENDING") reloadTick += 1 else recoveryError = exception.message
                                    } finally {
                                        cancellingRecovery = false
                                    }
                                }
                            },
                        ) { Text(tr("Cancelar la recuperacion"), color = colors.danger) }
                        recoveryError?.let { Text(it, color = colors.danger) }
                    }
                }
                if (!current.enabled) {
                    Button(onClick = { dialog = TwoFactorDialog.Enable }, colors = accentButtonColors()) {
                        Text(tr("Activar la verificacion en dos pasos"))
                    }
                } else {
                    OutlinedButton(onClick = { dialog = TwoFactorDialog.Regenerate }) {
                        Text(tr("Generar codigos de recuperacion nuevos"))
                    }
                    OutlinedButton(enabled = !current.requiredByServer, onClick = { dialog = TwoFactorDialog.Disable }) {
                        Text(tr("Desactivar"), color = if (current.requiredByServer) colors.muted else colors.danger)
                    }
                }
            }
        }
        loadError?.let { Text(it, color = colors.danger) }
    }

    when (dialog) {
        TwoFactorDialog.Enable -> TwoFactorEnableDialog(
            serverUrl = serverUrl,
            session = session,
            onFinished = {
                dialog = null
                reloadTick += 1
                onEnabled()
            },
            onDismiss = {
                dialog = null
                reloadTick += 1
            },
        )
        TwoFactorDialog.Regenerate -> TwoFactorCodeDialog(
            title = tr("Generar codigos de recuperacion nuevos"),
            description = tr("Los codigos de recuperacion actuales dejaran de valer. Confirma con tu contrasena y el codigo de 6 digitos de tu aplicacion."),
            codeLabel = tr("Codigo de 6 digitos"),
            digitsOnly = true,
            confirmLabel = tr("Generar"),
            serverUrl = serverUrl,
            submit = { api, password, code -> api.twoFactorRegenerateRecoveryCodes(session.accessToken, session.companyCode, password, code) },
            onFinished = {
                dialog = null
                reloadTick += 1
            },
            onDismiss = { dialog = null },
        )
        TwoFactorDialog.Disable -> TwoFactorCodeDialog(
            title = tr("Desactivar la verificacion en dos pasos"),
            description = tr("Tu cuenta volvera a pedir solo la contrasena. Confirma con tu contrasena y un codigo de tu aplicacion o de recuperacion."),
            codeLabel = tr("Codigo de tu aplicacion o de recuperacion"),
            digitsOnly = false,
            confirmLabel = tr("Desactivar"),
            serverUrl = serverUrl,
            submit = { api, password, code ->
                api.twoFactorDisable(session.accessToken, session.companyCode, password, code)
                null
            },
            onFinished = {
                dialog = null
                reloadTick += 1
            },
            onDismiss = { dialog = null },
        )
        null -> Unit
    }
}

/**
 * Activacion en tres pasos dentro de un dialogo: contrasena actual, clave y codigo de confirmacion y, al final,
 * los codigos de recuperacion (solo se ven esta vez; no se puede cerrar sin marcar que se guardaron).
 */
@Composable
private fun TwoFactorEnableDialog(
    serverUrl: String?,
    session: LoginResponse,
    onFinished: () -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var password by remember { mutableStateOf("") }
    var setup by remember { mutableStateOf<TwoFactorSetup?>(null) }
    var code by remember { mutableStateOf("") }
    var recoveryCodes by remember { mutableStateOf<List<String>?>(null) }
    var enableNotice by remember { mutableStateOf<String?>(null) }
    var saved by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun perform(block: suspend (AccountApi) -> Unit) {
        val url = serverUrl ?: return
        busy = true
        error = null
        scope.launch {
            try {
                AccountApi(url).use { block(it) }
            } catch (exception: LoginException) {
                error = exception.message
            } finally {
                busy = false
            }
        }
    }

    val codes = recoveryCodes
    val currentSetup = setup

    AlertDialog(
        // Con los codigos a la vista no se cierra por accidente: ya no se pueden volver a leer.
        onDismissRequest = { if (!busy && codes == null) onDismiss() },
        title = { Text(tr("Activar la verificacion en dos pasos")) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (codes != null) {
                    RecoveryCodesContent(codes = codes, saved = saved, onSavedChange = { saved = it }, notice = enableNotice)
                } else if (currentSetup == null) {
                    Text(
                        tr("Necesitas una aplicacion de autenticacion (Google Authenticator, Microsoft Authenticator, Aegis, 1Password...). Primero confirma tu contrasena."),
                        color = AppTheme.colors.muted,
                    )
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text(tr("Contrasena actual")) },
                        singleLine = true,
                        enabled = !busy,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    Text(
                        tr("1. En tu aplicacion de autenticacion agrega una cuenta nueva con el codigo QR, con esta clave o con el enlace otpauth."),
                        color = AppTheme.colors.text,
                    )
                    // QR para escanear; la clave y el enlace de abajo siguen como alternativa.
                    Text(tr("Escanea este codigo con tu app de autenticacion"), color = AppTheme.colors.text)
                    QrImage(
                        text = currentSetup.otpauthUri,
                        contentDescription = tr("Codigo QR para agregar la cuenta a tu aplicacion de autenticacion"),
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    )
                    CopyableField(
                        label = tr("Clave"),
                        display = currentSetup.secret.chunked(4).joinToString(" "),
                        value = currentSetup.secret,
                    )
                    CopyableField(label = tr("Enlace otpauth"), display = currentSetup.otpauthUri, value = currentSetup.otpauthUri)
                    Text(tr("2. Escribe el codigo de 6 digitos que muestra la aplicacion."), color = AppTheme.colors.text)
                    OutlinedTextField(
                        value = code,
                        onValueChange = { value -> code = value.filter(Char::isDigit).take(6) },
                        label = { Text(tr("Codigo de 6 digitos")) },
                        singleLine = true,
                        enabled = !busy,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                error?.let { Text(it, color = AppTheme.colors.danger) }
            }
        },
        confirmButton = {
            if (codes != null) {
                TextButton(enabled = saved, onClick = onFinished) { Text(tr("Listo")) }
            } else if (currentSetup == null) {
                TextButton(
                    enabled = !busy && password.isNotEmpty(),
                    onClick = {
                        perform { api ->
                            setup = api.twoFactorSetup(session.accessToken, session.companyCode, password)
                            password = ""
                        }
                    },
                ) { Text(tr("Continuar")) }
            } else {
                TextButton(
                    enabled = !busy && code.length == 6,
                    onClick = {
                        perform { api ->
                            val enabled = api.twoFactorEnable(session.accessToken, session.companyCode, code)
                            enableNotice = enabled.notice?.takeIf { it.isNotBlank() }
                            recoveryCodes = enabled.recoveryCodes
                            code = ""
                        }
                    },
                ) { Text(tr("Activar")) }
            }
        },
        dismissButton = {
            if (codes == null) {
                TextButton(onClick = onDismiss, enabled = !busy) { Text(tr("Cancelar")) }
            }
        },
    )
}

/**
 * Dialogo con contrasena actual y un codigo, para generar codigos de recuperacion nuevos o desactivar.
 * [submit] devuelve los codigos a mostrar (generar) o null cuando no hay nada mas que ver (desactivar).
 */
@Composable
private fun TwoFactorCodeDialog(
    title: String,
    description: String,
    codeLabel: String,
    digitsOnly: Boolean,
    confirmLabel: String,
    serverUrl: String?,
    submit: suspend (AccountApi, String, String) -> List<String>?,
    onFinished: () -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var password by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var newCodes by remember { mutableStateOf<List<String>?>(null) }
    var saved by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val codes = newCodes

    AlertDialog(
        onDismissRequest = { if (!busy && codes == null) onDismiss() },
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (codes != null) {
                    RecoveryCodesContent(codes = codes, saved = saved, onSavedChange = { saved = it })
                } else {
                    Text(description, color = AppTheme.colors.muted)
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text(tr("Contrasena actual")) },
                        singleLine = true,
                        enabled = !busy,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = code,
                        onValueChange = { value -> code = if (digitsOnly) value.filter(Char::isDigit).take(6) else value },
                        label = { Text(codeLabel) },
                        singleLine = true,
                        enabled = !busy,
                        keyboardOptions = if (digitsOnly) KeyboardOptions(keyboardType = KeyboardType.Number) else KeyboardOptions.Default,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                error?.let { Text(it, color = AppTheme.colors.danger) }
            }
        },
        confirmButton = {
            if (codes != null) {
                TextButton(enabled = saved, onClick = onFinished) { Text(tr("Listo")) }
            } else {
                TextButton(
                    enabled = !busy && password.isNotEmpty() && code.isNotBlank(),
                    onClick = {
                        val url = serverUrl ?: return@TextButton
                        busy = true
                        error = null
                        scope.launch {
                            try {
                                val result = AccountApi(url).use { api -> submit(api, password, code) }
                                password = ""
                                code = ""
                                if (result == null) onFinished() else newCodes = result
                            } catch (exception: LoginException) {
                                error = exception.message
                            } finally {
                                busy = false
                            }
                        }
                    },
                ) { Text(confirmLabel) }
            }
        },
        dismissButton = {
            if (codes == null) {
                TextButton(onClick = onDismiss, enabled = !busy) { Text(tr("Cancelar")) }
            }
        },
    )
}

// Instante ISO 8601 del servidor (UTC) como "2026-11-27 10:00 UTC".
private fun utcText(iso: String): String = iso.replace('T', ' ').removeSuffix("Z").take(16) + " UTC"

// Los 10 codigos de recuperacion con aviso, boton para copiarlos y la casilla que confirma que se guardaron.
@Composable
private fun RecoveryCodesContent(codes: List<String>, saved: Boolean, onSavedChange: (Boolean) -> Unit, notice: String? = null) {
    val colors = AppTheme.colors
    // Aviso del servidor al activar (p. ej. se cerraron otras sesiones y se desconectaron los agentes de IA); los codigos siguen a la vista.
    notice?.let { Text(it, color = colors.warning) }
    Text(tr("Codigos de recuperacion"), style = MaterialTheme.typography.titleSmall, color = colors.accentText)
    Text(
        tr("Guardalos ahora en un lugar seguro. No se vuelven a mostrar y cada uno sirve una sola vez si pierdes el acceso a tu aplicacion."),
        color = colors.warning,
    )
    SelectionContainer {
        Text(codes.joinToString("\n"), fontFamily = FontFamily.Monospace, color = colors.text)
    }
    CopyButton(label = tr("Copiar los codigos"), value = codes.joinToString("\n"))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = saved, onCheckedChange = onSavedChange)
        Text(tr("Ya guarde mis codigos de recuperacion"), color = colors.text)
    }
}

// Texto seleccionable (a varias lineas si hace falta) con boton para copiar [value], que puede diferir de lo que se muestra.
@Composable
private fun CopyableField(label: String, display: String, value: String) {
    val colors = AppTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, color = colors.muted, style = MaterialTheme.typography.labelMedium)
        SelectionContainer {
            Text(display, color = colors.text, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
        }
        CopyButton(label = tr("Copiar"), value = value)
    }
}

@Composable
private fun CopyButton(label: String, value: String) {
    // Igual que CopyableCode: LocalClipboardManager sigue funcionando en todos los targets.
    @Suppress("DEPRECATION")
    val clipboard = LocalClipboardManager.current
    var copied by remember(value) { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
            onClick = {
                @Suppress("DEPRECATION")
                clipboard.setText(AnnotatedString(value))
                copied = true
            },
        ) { Text(label) }
        if (copied) Text(tr("Copiado"), color = AppTheme.colors.success)
    }
}

/** Aviso tras entrar con un codigo de recuperacion: cuantos quedan y que conviene generar otros. */
@Composable
fun RecoveryCodeUsedDialog(codesLeft: Int?, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Entraste con un codigo de recuperacion")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (codesLeft != null) {
                        tr("Te quedan {0} codigos de recuperacion. Cada uno sirve una sola vez.", codesLeft)
                    } else {
                        tr("Ese codigo ya no se puede volver a usar.")
                    },
                )
                Text(
                    tr("Genera codigos nuevos en Ajustes, en Verificacion en dos pasos, y revisa que tu aplicacion de autenticacion siga disponible."),
                    color = AppTheme.colors.muted,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(tr("Entendido")) } },
    )
}

/**
 * Aviso tras iniciar sesion: la recuperacion por correo dejo la verificacion en dos pasos desactivada (hay que
 * activarla de nuevo) y/o se cancelo una recuperacion por correo que estaba en curso porque se entro con normalidad.
 */
@Composable
fun TwoFactorNoticeDialog(twoFactorDisabled: Boolean, emailRecoveryCancelled: Boolean, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (twoFactorDisabled) tr("Verificacion en dos pasos desactivada") else tr("Recuperacion por correo cancelada")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (twoFactorDisabled) {
                    Text(tr("Entraste con el codigo de recuperacion por correo, asi que la verificacion en dos pasos de tu cuenta quedo desactivada."))
                    Text(tr("Activala de nuevo cuanto antes en Ajustes, en Verificacion en dos pasos."), color = AppTheme.colors.warning)
                }
                if (emailRecoveryCancelled) {
                    Text(tr("Se cancelo la recuperacion por correo que estaba en curso, porque entraste con normalidad."))
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(tr("Entendido")) } },
    )
}
