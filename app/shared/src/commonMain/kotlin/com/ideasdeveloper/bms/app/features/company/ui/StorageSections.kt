package com.ideasdeveloper.bms.app.features.company.ui

import com.ideasdeveloper.bms.app.core.i18n.tr
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.ideasdeveloper.bms.app.core.theme.AppTheme
import com.ideasdeveloper.bms.app.core.theme.accentButtonColors
import com.ideasdeveloper.bms.app.features.company.data.CompanyApi
import com.ideasdeveloper.bms.app.features.company.data.CompanyException
import com.ideasdeveloper.bms.app.features.company.data.CompanyStorageResponse
import com.ideasdeveloper.bms.app.features.company.data.ServerStorageResponse
import com.ideasdeveloper.bms.app.features.shell.ui.CollapsibleSection
import kotlinx.coroutines.launch
import kotlin.math.roundToLong

// Cuotas de almacenamiento de archivos por empresa (STORAGE_QUOTA.md): solo las ve y cambia el dueno del servidor.

private const val BytesPerGib = 1024L * 1024L * 1024L

// El servidor rechaza cuotas de mas de 1 PiB (INVALID_QUOTA).
private const val MaxQuotaGib = 1_048_576.0

// "12.3 GiB", "512.0 MiB"... con un decimal (sin String.format, que no existe en todos los targets).
private fun sizeText(bytes: Long): String {
    val units = listOf("B", "KiB", "MiB", "GiB", "TiB", "PiB")
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1024.0 && unit < units.lastIndex) {
        value /= 1024.0
        unit++
    }
    if (unit == 0) return "$bytes B"
    val tenths = (value * 10).roundToLong()
    return "${tenths / 10}.${tenths % 10} ${units[unit]}"
}

// Cuota efectiva legible; 0 es sin limite.
private fun quotaText(bytes: Long): String = if (bytes <= 0L) tr("sin limite") else sizeText(bytes)

// GiB para el campo de texto: entero si es exacto y, si no, hasta dos decimales.
private fun gibField(bytes: Long): String {
    if (bytes % BytesPerGib == 0L) return (bytes / BytesPerGib).toString()
    val hundredths = (bytes * 100.0 / BytesPerGib).roundToLong()
    val fraction = (hundredths % 100).toString().padStart(2, '0').trimEnd('0')
    return if (fraction.isEmpty()) "${hundredths / 100}" else "${hundredths / 100}.$fraction"
}

// GiB escritos por la persona a bytes; null si no es un numero entre 0 y 1 PiB.
private fun parseGib(text: String): Long? {
    val gib = text.trim().replace(',', '.').toDoubleOrNull() ?: return null
    if (gib.isNaN() || gib < 0.0 || gib > MaxQuotaGib) return null
    return (gib * BytesPerGib).roundToLong()
}

private suspend inline fun <T> CompanyApi.use(block: (CompanyApi) -> T): T = try {
    block(this)
} finally {
    close()
}

/**
 * Seccion del dueno del servidor en Empresa: cuota por defecto de cada empresa, espacio del disco de archivos y uso de
 * cada empresa. El minimo de espacio libre lo fija el servidor (company.minFreeDiskBytes) y aqui solo se muestra.
 */
@Composable
fun ServerStorageSection(serverUrl: String?, accessToken: String?, primaryColor: Color) {
    CollapsibleSection(
        tr("Almacenamiento de archivos"),
        primaryColor,
        subtitle = tr("Cuota por defecto y uso de las empresas"),
    ) {
        ServerStorageContent(serverUrl, accessToken)
    }
}

@Composable
private fun ServerStorageContent(serverUrl: String?, accessToken: String?) {
    val colors = AppTheme.colors
    val scope = rememberCoroutineScope()
    var storage by remember { mutableStateOf<ServerStorageResponse?>(null) }
    var quotaInput by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    fun show(loaded: ServerStorageResponse) {
        storage = loaded
        quotaInput = gibField(loaded.defaultQuotaBytes)
    }

    LaunchedEffect(serverUrl, accessToken) {
        val url = serverUrl ?: return@LaunchedEffect
        val token = accessToken ?: return@LaunchedEffect
        busy = true
        try {
            show(CompanyApi(url).use { api -> api.serverStorage(token) })
            error = null
        } catch (exception: CompanyException) {
            error = exception.message
        } finally {
            busy = false
        }
    }

    val current = storage
    if (current == null) {
        if (busy) Text(tr("Cargando..."), color = colors.muted)
    } else {
        Text(
            tr("Cada empresa puede guardar archivos hasta su cuota. Aqui se fija la cuota por defecto; una empresa puede tener la suya al editarla."),
            color = colors.muted,
        )
        OutlinedTextField(
            value = quotaInput,
            onValueChange = { quotaInput = it },
            label = { Text(tr("Cuota por defecto por empresa (GiB, 0 = sin limite)")) },
            singleLine = true,
            enabled = !busy,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Button(
                enabled = !busy && serverUrl != null && accessToken != null,
                onClick = {
                    val url = serverUrl ?: return@Button
                    val token = accessToken ?: return@Button
                    val bytes = parseGib(quotaInput)
                    if (bytes == null) {
                        error = tr("Escribe una cuota valida en GiB (0 = sin limite, maximo 1 PiB).")
                        message = null
                        return@Button
                    }
                    busy = true
                    error = null
                    message = null
                    scope.launch {
                        try {
                            show(CompanyApi(url).use { api -> api.updateDefaultStorageQuota(token, bytes) })
                            message = tr("Cuota por defecto guardada.")
                        } catch (exception: CompanyException) {
                            error = exception.message
                        } finally {
                            busy = false
                        }
                    }
                },
                colors = accentButtonColors(),
            ) { Text(tr("Guardar cuota por defecto")) }
        }
        Text(
            tr("Espacio libre minimo del disco de archivos: {0} (lo define el servidor)", sizeText(current.minFreeDiskBytes)),
            color = colors.muted,
        )
        if (current.freeDiskBytes != null && current.totalDiskBytes != null) {
            Text(
                tr("Espacio libre en el disco de archivos: {0} de {1}", sizeText(current.freeDiskBytes), sizeText(current.totalDiskBytes)),
                color = colors.muted,
            )
        }
        Text(tr("Uso por empresa"), color = colors.accentText)
        current.companies.forEach { company ->
            Text(
                tr("{0} ({1}): {2} de {3}", company.name.ifBlank { company.code }, company.code, sizeText(company.usedBytes), quotaText(company.quotaBytes)) +
                    if (company.customQuotaBytes != null) tr(" · cuota propia") else "",
                color = colors.text,
            )
        }
    }
    error?.let { Text(it, color = colors.danger) }
    message?.let { Text(it, color = colors.success) }
}

/**
 * Cuota de una empresa dentro de su edicion (solo el dueno del servidor): uso actual y cuota propia. Vacio vuelve a la
 * cuota por defecto y 0 es sin limite. Bajarla por debajo del uso actual se permite: solo se rechazan subidas nuevas.
 */
@Composable
fun CompanyStorageQuotaFields(
    companyCode: String,
    enabled: Boolean,
    loadStorage: suspend () -> CompanyStorageResponse?,
    saveStorage: suspend (Long?) -> CompanyStorageResponse,
) {
    val colors = AppTheme.colors
    val scope = rememberCoroutineScope()
    var storage by remember(companyCode) { mutableStateOf<CompanyStorageResponse?>(null) }
    var quotaInput by remember(companyCode) { mutableStateOf("") }
    var loadError by remember(companyCode) { mutableStateOf<String?>(null) }
    var busy by remember(companyCode) { mutableStateOf(false) }
    var message by remember(companyCode) { mutableStateOf<String?>(null) }
    var error by remember(companyCode) { mutableStateOf<String?>(null) }

    fun show(loaded: CompanyStorageResponse) {
        storage = loaded
        quotaInput = loaded.customQuotaBytes?.let { gibField(it) }.orEmpty()
    }

    LaunchedEffect(companyCode) {
        try {
            loadStorage()?.let { show(it) }
        } catch (exception: CompanyException) {
            loadError = exception.message
        }
    }

    loadError?.let { Text(it, color = colors.danger) }
    val current = storage ?: return
    Text(
        tr("Uso: {0} de {1}", sizeText(current.usedBytes), quotaText(current.quotaBytes)) +
            if (current.customQuotaBytes == null) tr(" (cuota por defecto)") else tr(" (cuota propia)"),
        color = colors.text,
    )
    OutlinedTextField(
        value = quotaInput,
        onValueChange = { quotaInput = it },
        label = { Text(tr("Cuota propia (GiB; 0 = sin limite; vacio = la por defecto)")) },
        singleLine = true,
        enabled = enabled && !busy,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth(),
    )
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Button(
            enabled = enabled && !busy,
            onClick = {
                val text = quotaInput.trim()
                val bytes = if (text.isEmpty()) null else parseGib(text)
                if (text.isNotEmpty() && bytes == null) {
                    error = tr("Escribe una cuota valida en GiB (0 = sin limite, maximo 1 PiB).")
                    message = null
                    return@Button
                }
                busy = true
                error = null
                message = null
                scope.launch {
                    try {
                        show(saveStorage(bytes))
                        message = tr("Cuota guardada.")
                    } catch (exception: CompanyException) {
                        error = exception.message
                    } finally {
                        busy = false
                    }
                }
            },
            colors = accentButtonColors(),
        ) { Text(tr("Guardar cuota")) }
    }
    error?.let { Text(it, color = colors.danger) }
    message?.let { Text(it, color = colors.success) }
}
