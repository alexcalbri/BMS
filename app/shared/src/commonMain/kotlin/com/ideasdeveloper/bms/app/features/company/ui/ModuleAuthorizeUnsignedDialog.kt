package com.ideasdeveloper.bms.app.features.company.ui

import com.ideasdeveloper.bms.app.core.i18n.tr
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import com.ideasdeveloper.bms.app.core.theme.AppTheme
import com.ideasdeveloper.bms.app.core.theme.appFieldColors
import com.ideasdeveloper.bms.app.core.ui.CopyableCode
import com.ideasdeveloper.bms.app.features.company.data.CompanyApi
import com.ideasdeveloper.bms.app.features.company.data.CompanyException
import com.ideasdeveloper.bms.app.features.company.data.ModulePackageInspection
import kotlinx.coroutines.launch

/**
 * Autorizar un modulo sin firma: primero se descarga y se inspecciona (commit con su enlace y hash reales), y la frase
 * de confirmacion lleva los primeros 8 caracteres del hash para que se confirme lo revisado. Igual que con las extensiones.
 */
@Composable
fun ModuleAuthorizeUnsignedDialog(
    serverUrl: String?,
    accessToken: String?,
    moduleId: String,
    displayName: String,
    onAuthorized: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = AppTheme.colors
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current
    var inspection by remember(moduleId) { mutableStateOf<ModulePackageInspection?>(null) }
    var loading by remember(moduleId) { mutableStateOf(true) }
    var busy by remember(moduleId) { mutableStateOf(false) }
    var error by remember(moduleId) { mutableStateOf<String?>(null) }
    var notice by remember(moduleId) { mutableStateOf<String?>(null) }
    var typed by remember(moduleId) { mutableStateOf("") }
    // Cambia para volver a inspeccionar (al abrir y cuando el modulo cambio).
    var inspectTick by remember(moduleId) { mutableStateOf(0) }

    LaunchedEffect(moduleId, inspectTick) {
        val url = serverUrl ?: return@LaunchedEffect
        val token = accessToken ?: return@LaunchedEffect
        loading = true
        inspection = null
        typed = ""
        val api = CompanyApi(url)
        try {
            inspection = api.inspectServerModule(token, moduleId)
            error = null
        } catch (exception: CompanyException) {
            error = exception.message
        } finally {
            api.close()
            loading = false
        }
    }

    val current = inspection
    val phrase = current?.let { "autorizar $moduleId ${it.packageSha256.take(8).lowercase()}" }.orEmpty()
    val blocked = current == null || current.signatureProblem != null || current.signed
    val matches = !blocked && typed.trim().equals(phrase, ignoreCase = true)

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(tr("Autorizar paquete sin firma")) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    tr("{0} no tiene una firma valida, asi que nadie garantiza su origen. Una vez instalado, su codigo corre con los privilegios del servidor: puede leer y escribir datos de todas las empresas.", displayName),
                    color = colors.text,
                )
                Text(tr("Autoriza solo paquetes que hayas revisado y de una fuente de confianza."), color = colors.warning)
                if (loading) Text(tr("Descargando y revisando el paquete..."), color = colors.muted)
                if (current != null) {
                    Text(tr("Esto es lo que se descargo ahora:"), color = colors.text, style = MaterialTheme.typography.titleSmall)
                    Text(tr("Commit {0}", current.commit.take(8)), color = colors.text)
                    if (current.commitUrl.isNotBlank()) {
                        TextButton(onClick = { runCatching { uriHandler.openUri(current.commitUrl) } }) {
                            Text(tr("Abrir el commit en el navegador"), color = colors.accentText)
                        }
                        CopyableCode(label = tr("Enlace"), code = current.commitUrl)
                    }
                    Text(tr("Hash {0}...", current.packageSha256.take(12)), color = colors.text)
                    CopyableCode(label = tr("Hash completo"), code = current.packageSha256)
                    if (current.signed) {
                        Text(tr("El paquete esta firmado: no necesita autorizacion."), color = colors.success)
                    }
                    current.signatureProblem?.let {
                        Text(tr("El paquete trae una firma que no vale ({0}). No se puede autorizar.", it), color = colors.danger)
                    }
                    if (!blocked) {
                        Text(tr("Para confirmar escribe: {0}", phrase), color = colors.text)
                        OutlinedTextField(
                            value = typed,
                            onValueChange = { typed = it },
                            label = { Text(tr("Texto de confirmacion")) },
                            singleLine = true,
                            enabled = !busy,
                            colors = appFieldColors(),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                notice?.let { Text(it, color = colors.warning) }
                error?.let { Text(it, color = colors.danger) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = matches && !busy,
                onClick = {
                    val reviewed = current ?: return@TextButton
                    val url = serverUrl ?: return@TextButton
                    val token = accessToken ?: return@TextButton
                    busy = true
                    error = null
                    scope.launch {
                        val api = CompanyApi(url)
                        try {
                            api.authorizeUnsignedServerModule(token, moduleId, typed.trim(), reviewed.packageSha256, reviewed.commit)
                            onAuthorized()
                        } catch (exception: CompanyException) {
                            if (exception.status == 409) {
                                // PACKAGE_CHANGED: lo descargado ya no es lo revisado; se inspecciona otra vez.
                                notice = tr("El paquete cambio desde que lo revisaste; revisalo de nuevo.")
                                inspectTick += 1
                            } else {
                                error = exception.message
                            }
                            busy = false
                        } finally {
                            api.close()
                        }
                    }
                },
            ) { Text(if (busy) tr("Autorizando...") else tr("Autorizar"), color = if (matches) colors.danger else colors.muted) }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(tr("Cancelar")) } },
    )
}
