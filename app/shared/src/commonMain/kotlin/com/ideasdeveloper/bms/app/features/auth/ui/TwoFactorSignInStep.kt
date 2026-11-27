package com.ideasdeveloper.bms.app.features.auth.ui

import com.ideasdeveloper.bms.app.core.i18n.tr
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.ideasdeveloper.bms.app.core.theme.AppTheme
import com.ideasdeveloper.bms.app.core.theme.accentButtonColors
import com.ideasdeveloper.bms.app.core.theme.appFieldColors

// Que se pide en el segundo paso.
private enum class SecondStepMode { AppCode, RecoveryCode, EmailExplain, EmailCode }

// Dias de espera que se muestran antes de que el servidor informe el valor real (TWO_FACTOR.md seccion 7).
private const val DefaultWaitDays = 7L

/**
 * Segundo paso del inicio de sesion: el codigo de 6 digitos de la aplicacion de autenticacion (se envia solo
 * al completar el sexto digito) o, con el interruptor, un codigo de recuperacion. Si el servidor ofrece la
 * recuperacion por correo ([emailRecoveryOffered]) hay ademas un enlace discreto para iniciarla y un campo para el
 * codigo que llego por correo. [error], [attemptsLeft] y [notice] vienen del intento anterior; [onCancel] vuelve
 * al usuario y la contrasena.
 */
@Composable
fun TwoFactorSignInStep(
    error: String?,
    attemptsLeft: Int?,
    notice: String?,
    emailRecoveryOffered: Boolean,
    waitDays: Long?,
    isLoading: Boolean,
    onSubmit: (code: String, emailRecovery: Boolean) -> Unit,
    onStartEmailRecovery: () -> Unit,
    onCancel: () -> Unit,
) {
    var mode by remember { mutableStateOf(SecondStepMode.AppCode) }
    var code by remember { mutableStateOf("") }

    // Tras un codigo rechazado el campo se vacia para escribir otro.
    LaunchedEffect(isLoading) {
        if (!isLoading && error != null) code = ""
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = if (mode == SecondStepMode.EmailExplain) tr("Recuperar el acceso por correo") else tr("Verificacion en dos pasos"),
            style = MaterialTheme.typography.titleLarge,
            color = AppTheme.colors.accentText,
        )
        if (mode == SecondStepMode.EmailExplain) {
            val days = waitDays ?: DefaultWaitDays
            Text(
                tr("Si no puedes usar tu aplicacion ni tus codigos de recuperacion, puedes pedir la recuperacion por correo. Ahora enviamos un aviso al correo de la cuenta, con un enlace para cancelarla si no fuiste tu. El codigo para entrar llega a ese correo dentro de {0} dias.", days),
                color = AppTheme.colors.text,
            )
            Text(
                tr("Al usar ese codigo se desactiva la verificacion en dos pasos de la cuenta y tendras que activarla de nuevo."),
                color = AppTheme.colors.warning,
            )
            notice?.let { Text(it, color = AppTheme.colors.success) }
            error?.let { Text(it, color = AppTheme.colors.danger) }
            if (notice == null) {
                Button(
                    onClick = onStartEmailRecovery,
                    enabled = !isLoading,
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    shape = MaterialTheme.shapes.medium,
                    colors = accentButtonColors(),
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(color = AppTheme.colors.onAccent, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                    } else {
                        Text(tr("Iniciar recuperacion por correo"))
                    }
                }
            }
            TextButton(enabled = !isLoading, onClick = { mode = SecondStepMode.AppCode; code = "" }) {
                Text(tr("Volver"), color = AppTheme.colors.text)
            }
            return@Column
        }
        Text(
            text = when (mode) {
                SecondStepMode.RecoveryCode -> tr("Escribe uno de tus codigos de recuperacion. Cada uno sirve una sola vez.")
                SecondStepMode.EmailCode -> tr("Escribe el codigo que llego al correo de la cuenta.")
                else -> tr("Escribe el codigo de 6 digitos de tu aplicacion de autenticacion.")
            },
            color = AppTheme.colors.muted,
        )
        if (mode == SecondStepMode.AppCode) {
            OutlinedTextField(
                value = code,
                onValueChange = { value ->
                    val digits = value.filter(Char::isDigit).take(6)
                    code = digits
                    // Se envia solo al completar el sexto digito.
                    if (digits.length == 6 && !isLoading) onSubmit(digits, false)
                },
                label = { Text(tr("Codigo de 6 digitos")) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { if (code.length == 6 && !isLoading) onSubmit(code, false) }),
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                enabled = !isLoading,
                colors = appFieldColors(),
            )
        } else {
            val emailCode = mode == SecondStepMode.EmailCode
            OutlinedTextField(
                value = code,
                onValueChange = { code = if (emailCode) it.uppercase() else it },
                label = { Text(if (emailCode) tr("Codigo del correo") else tr("Codigo de recuperacion")) },
                placeholder = { Text(if (emailCode) "XXXX-XXXX-XXXX" else "XXXXX-XXXXX") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { if (code.isNotBlank() && !isLoading) onSubmit(code, emailCode) }),
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                enabled = !isLoading,
                colors = appFieldColors(),
            )
        }
        notice?.let { Text(it, color = AppTheme.colors.success) }
        error?.let { Text(it, color = AppTheme.colors.danger) }
        attemptsLeft?.let { Text(tr("Intentos restantes: {0}", it), color = AppTheme.colors.muted) }
        Button(
            onClick = { onSubmit(code, mode == SecondStepMode.EmailCode) },
            enabled = !isLoading && (if (mode == SecondStepMode.AppCode) code.length == 6 else code.isNotBlank()),
            modifier = Modifier.fillMaxWidth().height(50.dp),
            shape = MaterialTheme.shapes.medium,
            colors = accentButtonColors(),
        ) {
            if (isLoading) {
                CircularProgressIndicator(color = AppTheme.colors.onAccent, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
            } else {
                Text(tr("Verificar"))
            }
        }
        if (mode != SecondStepMode.EmailCode) {
            TextButton(
                enabled = !isLoading,
                onClick = {
                    mode = if (mode == SecondStepMode.RecoveryCode) SecondStepMode.AppCode else SecondStepMode.RecoveryCode
                    code = ""
                },
            ) {
                Text(
                    if (mode == SecondStepMode.RecoveryCode) tr("Usar el codigo de mi aplicacion") else tr("Usar un codigo de recuperacion"),
                    color = AppTheme.colors.text,
                )
            }
        } else {
            TextButton(enabled = !isLoading, onClick = { mode = SecondStepMode.AppCode; code = "" }) {
                Text(tr("Usar el codigo de mi aplicacion"), color = AppTheme.colors.text)
            }
        }
        if (emailRecoveryOffered) {
            // Enlaces discretos: la recuperacion por correo es el ultimo recurso.
            if (mode != SecondStepMode.EmailCode) {
                TextButton(enabled = !isLoading, onClick = { mode = SecondStepMode.EmailCode; code = "" }) {
                    Text(tr("Ya tengo el codigo que llego por correo"), color = AppTheme.colors.muted, style = MaterialTheme.typography.bodySmall)
                }
            }
            TextButton(enabled = !isLoading, onClick = { mode = SecondStepMode.EmailExplain; code = "" }) {
                Text(tr("No puedo usar mi app ni mis codigos"), color = AppTheme.colors.muted, style = MaterialTheme.typography.bodySmall)
            }
        }
        TextButton(enabled = !isLoading, onClick = onCancel) {
            Text(tr("Volver"), color = AppTheme.colors.text)
        }
        Spacer(Modifier.height(4.dp))
    }
}
