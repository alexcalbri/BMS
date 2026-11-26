package com.ideasdeveloper.bms.app.features.extensions.ui

import com.ideasdeveloper.bms.app.core.i18n.tr
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.ideasdeveloper.bms.app.core.theme.AppTheme
import com.ideasdeveloper.bms.app.core.theme.appFieldColors
import com.ideasdeveloper.bms.app.features.company.data.CompanyException
import com.ideasdeveloper.bms.app.features.extensions.data.ExtensionSummary
import com.ideasdeveloper.bms.app.features.extensions.data.GroupMemberView
import com.ideasdeveloper.bms.app.features.extensions.data.GroupOperationPreview
import com.ideasdeveloper.bms.app.features.extensions.data.GroupOperationRequest
import com.ideasdeveloper.bms.app.features.extensions.data.GroupOperationResult
import com.ideasdeveloper.bms.app.features.extensions.data.GroupOperationType
import com.ideasdeveloper.bms.app.features.extensions.data.UninstallOptionView
import com.ideasdeveloper.bms.app.features.extensions.data.UninstallPreviewResponse
import kotlinx.coroutines.launch

// Texto mostrado cuando un secreto ya esta guardado: el servidor nunca devuelve su valor.
private fun storedSecretPlaceholder(): String = tr("******** (guardado)")

@Composable
/**
 * Formulario de ajustes de una extension. Los secretos usan campo de contrasena y empiezan vacios:
 * solo se envia lo que el usuario cambio (un secreto vacio se deja como esta; "Quitar" lo borra).
 */
internal fun ConfigureExtensionDialog(
    extension: ExtensionSummary,
    context: ExtensionContext,
    onDone: (message: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = AppTheme.colors
    val scope = rememberCoroutineScope()
    val values = remember(extension.id) {
        mutableStateMapOf<String, String>().apply {
            extension.settings.forEach { put(it.key, if (it.secret) "" else it.value) }
        }
    }
    var cleared by remember(extension.id) { mutableStateOf(emptySet<String>()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    // Solo lo cambiado: los secretos nuevos o marcados para borrar y los demas valores distintos del guardado.
    val changes by remember(extension.id) {
        derivedStateOf {
            buildMap {
                extension.settings.forEach { setting ->
                    val value = values[setting.key].orEmpty()
                    when {
                        setting.secret && setting.key in cleared -> put(setting.key, "")
                        setting.secret -> if (value.isNotEmpty()) put(setting.key, value)
                        value != setting.value -> put(setting.key, value)
                        else -> Unit
                    }
                }
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(tr("Configurar {0}", extension.displayName)) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                extension.settings.forEach { setting ->
                    val isCleared = setting.key in cleared
                    val storedSecret = setting.secret && setting.isSet && !isCleared
                    OutlinedTextField(
                        value = values[setting.key].orEmpty(),
                        onValueChange = {
                            values[setting.key] = it
                            if (setting.secret && it.isNotEmpty() && isCleared) cleared = cleared - setting.key
                        },
                        label = { Text(setting.label.ifBlank { setting.key } + if (setting.required) " *" else "") },
                        placeholder = if (storedSecret) ({ Text(storedSecretPlaceholder()) }) else null,
                        supportingText = if (setting.help.isNotBlank()) ({ Text(setting.help) }) else null,
                        visualTransformation = if (setting.secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
                        singleLine = true,
                        enabled = !busy,
                        colors = appFieldColors(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (setting.secret && setting.isSet) {
                        if (isCleared) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(tr("Se borrara al guardar."), color = colors.warning, style = MaterialTheme.typography.bodySmall)
                                TextButton(enabled = !busy, onClick = { cleared = cleared - setting.key }) { Text(tr("Deshacer"), color = colors.accentText) }
                            }
                        } else {
                            TextButton(
                                enabled = !busy,
                                onClick = {
                                    cleared = cleared + setting.key
                                    values[setting.key] = ""
                                },
                            ) { Text(tr("Quitar valor guardado"), color = colors.danger) }
                        }
                    }
                }
                error?.let { Text(it, color = colors.danger) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && changes.isNotEmpty(),
                onClick = {
                    busy = true
                    error = null
                    scope.launch {
                        try {
                            context.run { token, target -> configure(token, target, extension.id, changes) }
                            onDone(tr("Ajustes de {0} guardados.", extension.displayName))
                        } catch (exception: CompanyException) {
                            error = exception.message
                            busy = false
                        }
                    }
                },
            ) { Text(if (busy) tr("Guardando...") else tr("Guardar")) }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(tr("Cancelar")) } },
    )
}

@Composable
/**
 * Desinstalacion en tres pasos: 1) vista previa con conteos, 2) elegir que conservar o borrar en
 * cada opcion (las "ask" no traen respuesta y son obligatorias), 3) confirmar.
 */
internal fun UninstallExtensionDialog(
    extension: ExtensionSummary,
    context: ExtensionContext,
    onDone: (message: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = AppTheme.colors
    val scope = rememberCoroutineScope()
    var step by remember { mutableIntStateOf(0) }
    var preview by remember { mutableStateOf<UninstallPreviewResponse?>(null) }
    val choices = remember { mutableStateMapOf<String, String>() }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(extension.id) {
        try {
            val loaded = context.run { token, target -> uninstallPreview(token, target, extension.id) }
            // Las opciones con valor por defecto empiezan con el; las "ask" quedan sin responder.
            loaded.options.forEach { if (it.default == "keep" || it.default == "delete") choices[it.id] = it.default }
            preview = loaded
        } catch (exception: CompanyException) {
            error = exception.message
        }
    }

    // Opciones del servidor; si la vista previa no las trae, las declaradas por la extension.
    val options: List<UninstallOptionView> = preview?.let { loaded ->
        loaded.options.ifEmpty { extension.uninstallOptions.map { UninstallOptionView(it.id, it.label, it.default) } }
    }.orEmpty()
    val allAnswered = options.all { choices[it.id] != null }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(tr("Desinstalar {0} ({1} de 3)", extension.displayName, step + 1)) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val loaded = preview
                when {
                    loaded == null && error == null -> Text(tr("Calculando que se va a borrar..."), color = colors.muted)
                    loaded == null -> Unit
                    step == 0 -> {
                        Text(tr("Esto es lo que encontramos en esta empresa:"), color = colors.text)
                        if (loaded.items.isEmpty()) Text(tr("No hay datos asociados."), color = colors.muted)
                        loaded.items.forEach { item ->
                            val action = when (item.action) {
                                "delete" -> tr("se borra")
                                "keep" -> tr("se conserva")
                                else -> item.action
                            }
                            Text("${item.label}: ${item.count} ($action)", color = colors.text)
                        }
                        Text(tr("Los clientes y contactos nunca se borran."), style = MaterialTheme.typography.bodySmall, color = colors.muted)
                    }
                    step == 1 -> {
                        if (options.isEmpty()) Text(tr("Esta extension no tiene opciones que elegir."), color = colors.muted)
                        options.forEach { option ->
                            Text(option.label.ifBlank { option.id }, color = colors.text, style = MaterialTheme.typography.titleSmall)
                            ChoiceRow(tr("Conservar"), choices[option.id] == "keep", !busy) { choices[option.id] = "keep" }
                            ChoiceRow(tr("Borrar"), choices[option.id] == "delete", !busy) { choices[option.id] = "delete" }
                            if (choices[option.id] == null) Text(tr("Debes elegir una opcion."), style = MaterialTheme.typography.bodySmall, color = colors.warning)
                        }
                    }
                    else -> {
                        Text(tr("Se desinstalara {0} de esta empresa con estas decisiones:", extension.displayName), color = colors.text)
                        options.forEach { option ->
                            val answer = if (choices[option.id] == "delete") tr("borrar") else tr("conservar")
                            Text("${option.label.ifBlank { option.id }}: $answer", color = colors.text)
                        }
                        Text(tr("No se puede deshacer; la extension se puede volver a instalar despues."), style = MaterialTheme.typography.bodySmall, color = colors.muted)
                    }
                }
                error?.let { Text(it, color = colors.danger) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && preview != null && (step != 1 || allAnswered),
                onClick = {
                    if (step < 2) {
                        step += 1
                    } else {
                        busy = true
                        error = null
                        scope.launch {
                            try {
                                context.run { token, target -> uninstall(token, target, extension.id, options.associate { it.id to (choices[it.id] ?: "keep") }) }
                                onDone(tr("{0} desinstalada.", extension.displayName))
                            } catch (exception: CompanyException) {
                                error = exception.message
                                busy = false
                            }
                        }
                    }
                },
            ) { Text(if (step < 2) tr("Siguiente") else if (busy) tr("Desinstalando...") else tr("Desinstalar"), color = if (step == 2) colors.danger else colors.accentText) }
        },
        dismissButton = {
            Row {
                if (step > 0) TextButton(onClick = { step -= 1 }, enabled = !busy) { Text(tr("Atras")) }
                TextButton(onClick = onDismiss, enabled = !busy) { Text(tr("Cancelar")) }
            }
        },
    )
}

@Composable
private fun ChoiceRow(label: String, selected: Boolean, enabled: Boolean, onSelect: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onSelect),
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Text(label, color = AppTheme.colors.text, modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
/**
 * Aplica la configuracion de esta empresa a otras del grupo: elegir empresas, ver que pasaria en
 * cada una, ejecutar y ver el resultado por empresa. Las fallidas se pueden reintentar.
 */
internal fun ApplyToGroupDialog(
    extension: ExtensionSummary,
    context: ExtensionContext,
    members: List<GroupMemberView>,
    onDone: (message: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = AppTheme.colors
    val scope = rememberCoroutineScope()
    var step by remember { mutableIntStateOf(0) }
    val selected = remember { mutableStateMapOf<String, Boolean>() }
    var preview by remember { mutableStateOf<GroupOperationPreview?>(null) }
    var result by remember { mutableStateOf<GroupOperationResult?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val names = remember(members) { members.associate { it.code to it.name.ifBlank { it.code } } }
    val chosen by remember(members) { derivedStateOf { members.filter { selected[it.code] == true && !it.suspended }.map { it.code } } }
    // Solo se aplican las empresas que la vista previa acepta.
    val applicable by remember { derivedStateOf { preview?.targets.orEmpty().filter { it.problem == null }.map { it.companyCode } } }
    val failed by remember { derivedStateOf { result?.targets.orEmpty().filter { it.status == "failed" } } }

    fun launchStep(block: suspend () -> Unit) {
        busy = true
        error = null
        scope.launch {
            try {
                block()
            } catch (exception: CompanyException) {
                error = exception.message
            } finally {
                busy = false
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDone(result?.let { tr("Aplicacion de {0} terminada.", extension.displayName) }) },
        title = { Text(tr("Aplicar {0} al grupo", extension.displayName)) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                when (step) {
                    0 -> {
                        Text(tr("Elige las empresas del grupo que recibiran la extension con los ajustes de esta empresa."), color = colors.text)
                        members.forEach { member ->
                            val checked = selected[member.code] == true && !member.suspended
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth().selectable(
                                    selected = checked, enabled = !busy && !member.suspended, role = Role.Checkbox,
                                    onClick = { selected[member.code] = !checked },
                                ),
                            ) {
                                Checkbox(checked = checked, onCheckedChange = null, enabled = !busy && !member.suspended)
                                Text(
                                    "${member.name.ifBlank { member.code }} (${member.code})" + if (member.suspended) tr(" · suspendida") else "",
                                    color = if (member.suspended) colors.muted else colors.text,
                                    modifier = Modifier.padding(start = 8.dp),
                                )
                            }
                        }
                    }
                    1 -> {
                        preview?.targets.orEmpty().forEach { target ->
                            Text(names[target.companyCode] ?: target.companyCode, style = MaterialTheme.typography.titleSmall, color = colors.text)
                            val problem = target.problem
                            if (problem != null) {
                                Text(tr("No se puede aplicar: {0}", problem), color = colors.danger, style = MaterialTheme.typography.bodySmall)
                            } else {
                                target.steps.forEachIndexed { index, text ->
                                    Text("${index + 1}. $text", color = colors.muted, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                        if (applicable.isEmpty()) Text(tr("Ninguna empresa puede recibir la extension."), color = colors.warning)
                    }
                    else -> {
                        result?.targets.orEmpty().forEach { target ->
                            val (label, tone) = when (target.status) {
                                "applied" -> tr("Aplicada") to colors.success
                                "failed" -> tr("Fallida") to colors.danger
                                "skipped" -> tr("Omitida") to colors.warning
                                else -> tr("Pendiente") to colors.muted
                            }
                            Text(
                                "${names[target.companyCode] ?: target.companyCode}: $label" + (target.reason?.takeIf { it.isNotBlank() }?.let { " - $it" } ?: ""),
                                color = tone,
                            )
                        }
                    }
                }
                error?.let { Text(it, color = colors.danger) }
            }
        },
        confirmButton = {
            when (step) {
                0 -> TextButton(
                    enabled = !busy && chosen.isNotEmpty(),
                    onClick = {
                        launchStep {
                            preview = context.run { token, target ->
                                previewGroup(token, target, extension.id, GroupOperationRequest(GroupOperationType.Apply, chosen))
                            }
                            step = 1
                        }
                    },
                ) { Text(if (busy) tr("Calculando...") else tr("Ver vista previa")) }
                1 -> TextButton(
                    enabled = !busy && applicable.isNotEmpty(),
                    onClick = {
                        launchStep {
                            result = context.run { token, target ->
                                applyGroup(token, target, extension.id, GroupOperationRequest(GroupOperationType.Apply, applicable))
                            }
                            step = 2
                        }
                    },
                ) { Text(if (busy) tr("Aplicando...") else tr("Aplicar")) }
                else -> Row {
                    if (failed.isNotEmpty()) {
                        TextButton(
                            enabled = !busy,
                            onClick = {
                                val operation = result ?: return@TextButton
                                launchStep {
                                    result = context.run { token, target ->
                                        retryGroup(token, target, extension.id, operation.operationId, GroupOperationRequest(GroupOperationType.Apply, failed.map { it.companyCode }))
                                    }
                                }
                            },
                        ) { Text(if (busy) tr("Reintentando...") else tr("Reintentar fallidas")) }
                    }
                    TextButton(onClick = { onDone(tr("Aplicacion de {0} terminada.", extension.displayName)) }, enabled = !busy) { Text(tr("Cerrar")) }
                }
            }
        },
        dismissButton = {
            if (step < 2) TextButton(onClick = onDismiss, enabled = !busy) { Text(tr("Cancelar")) }
        },
    )
}
