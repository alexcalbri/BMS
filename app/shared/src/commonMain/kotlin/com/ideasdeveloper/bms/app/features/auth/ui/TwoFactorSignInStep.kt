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

/**
 * Segundo paso del inicio de sesion: el codigo de 6 digitos de la aplicacion de autenticacion (se envia solo
 * al completar el sexto digito) o, con el interruptor, un codigo de recuperacion. [error] y [attemptsLeft] vienen
 * del intento anterior; [onCancel] vuelve al usuario y la contrasena.
 */
@Composable
fun TwoFactorSignInStep(
    error: String?,
    attemptsLeft: Int?,
    isLoading: Boolean,
    onSubmit: (String) -> Unit,
    onCancel: () -> Unit,
) {
    var useRecoveryCode by remember { mutableStateOf(false) }
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
            text = tr("Verificacion en dos pasos"),
            style = MaterialTheme.typography.titleLarge,
            color = AppTheme.colors.accentText,
        )
        Text(
            text = if (useRecoveryCode) {
                tr("Escribe uno de tus codigos de recuperacion. Cada uno sirve una sola vez.")
            } else {
                tr("Escribe el codigo de 6 digitos de tu aplicacion de autenticacion.")
            },
            color = AppTheme.colors.muted,
        )
        if (useRecoveryCode) {
            OutlinedTextField(
                value = code,
                onValueChange = { code = it },
                label = { Text(tr("Codigo de recuperacion")) },
                placeholder = { Text("XXXXX-XXXXX") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { if (code.isNotBlank() && !isLoading) onSubmit(code) }),
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                enabled = !isLoading,
                colors = appFieldColors(),
            )
        } else {
            OutlinedTextField(
                value = code,
                onValueChange = { value ->
                    val digits = value.filter(Char::isDigit).take(6)
                    code = digits
                    // Se envia solo al completar el sexto digito.
                    if (digits.length == 6 && !isLoading) onSubmit(digits)
                },
                label = { Text(tr("Codigo de 6 digitos")) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { if (code.length == 6 && !isLoading) onSubmit(code) }),
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                enabled = !isLoading,
                colors = appFieldColors(),
            )
        }
        error?.let { Text(it, color = AppTheme.colors.danger) }
        attemptsLeft?.let { Text(tr("Intentos restantes: {0}", it), color = AppTheme.colors.muted) }
        Button(
            onClick = { onSubmit(code) },
            enabled = !isLoading && (if (useRecoveryCode) code.isNotBlank() else code.length == 6),
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
        TextButton(
            enabled = !isLoading,
            onClick = {
                useRecoveryCode = !useRecoveryCode
                code = ""
            },
        ) {
            Text(
                if (useRecoveryCode) tr("Usar el codigo de mi aplicacion") else tr("Usar un codigo de recuperacion"),
                color = AppTheme.colors.text,
            )
        }
        TextButton(enabled = !isLoading, onClick = onCancel) {
            Text(tr("Volver"), color = AppTheme.colors.text)
        }
        Spacer(Modifier.height(4.dp))
    }
}
