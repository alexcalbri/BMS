package com.ideasdeveloper.bms.app.features.company.ui

import com.ideasdeveloper.bms.app.core.i18n.tr
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ideasdeveloper.bms.app.core.layout.AdaptiveLayout
import com.ideasdeveloper.bms.app.core.layout.ContentWidth
import com.ideasdeveloper.bms.app.core.layout.contentWidth
import com.ideasdeveloper.bms.app.core.theme.AppTheme
import com.ideasdeveloper.bms.app.core.theme.accentButtonColors
import com.ideasdeveloper.bms.app.features.auth.data.LoginResponse
import com.ideasdeveloper.bms.app.features.company.data.CompanyApi
import com.ideasdeveloper.bms.app.features.company.data.CompanyException
import com.ideasdeveloper.bms.app.features.company.data.CoreUpdateRequestDto
import com.ideasdeveloper.bms.app.features.company.data.CoreUpdateStatusResponse
import com.ideasdeveloper.bms.app.features.shell.ui.AuthenticatedScreen
import com.ideasdeveloper.bms.app.features.shell.ui.AuthenticatedTopBar
import com.ideasdeveloper.bms.app.features.shell.ui.ClosableView
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeSource

// Segundos entre consultas mientras la actualizacion esta pendiente o en curso.
private const val PollIntervalMillis = 5_000L

// Tiempo maximo sin respuesta del servidor antes de dejar de consultar.
private val RestartTimeout = 5.minutes

// Repositorio del Core: el enlace del commit que se va a instalar (el dueno lo revisa antes de confirmar).
private const val CommitUrlPrefix = "https://github.com/alexcalbri/BMS/commit/"

private fun CoreUpdateRequestDto.isActive(): Boolean = status == "pending" || status == "running"

// Consulta el estado abriendo y cerrando un cliente propio.
private suspend fun fetchStatus(url: String, token: String): CoreUpdateStatusResponse {
    val api = CompanyApi(url)
    try {
        return api.coreUpdateStatus(token)
    } finally {
        api.close()
    }
}

@Composable
fun ServerUpdateScreen(
    serverUrl: String?,
    session: LoginResponse?,
    onSettings: () -> Unit,
    onLogout: () -> Unit,
    onReturnToDashboard: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AppTheme.colors
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<CoreUpdateStatusResponse?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var requesting by remember { mutableStateOf(false) }
    var confirming by remember { mutableStateOf(false) }
    // Verdadero cuando la ultima consulta fallo por red (el servicio se esta reiniciando).
    var restarting by remember { mutableStateOf(false) }
    // Verdadero cuando el servidor no respondio durante 5 minutos y se detuvo el sondeo.
    var stalled by remember { mutableStateOf(false) }
    var pollRound by remember { mutableStateOf(0) }

    val token = session?.accessToken
    val request = status?.request
    // Clave del sondeo: cambia solo cuando hay otra solicitud activa o deja de haberla.
    val activeRequestId = request?.takeIf { it.isActive() }?.id

    // Carga inicial del estado.
    LaunchedEffect(serverUrl, token) {
        if (serverUrl == null || token == null) {
            error = tr("No hay una sesion de server owner activa.")
            return@LaunchedEffect
        }
        loading = true
        error = null
        try {
            status = fetchStatus(serverUrl, token)
        } catch (exception: CompanyException) {
            error = exception.message
        } finally {
            loading = false
        }
    }

    // Sondeo cada 5 s mientras haya una solicitud activa; si falla se conserva el ultimo estado.
    // pollRound cambia al pulsar "Reintentar" para reiniciar el sondeo.
    LaunchedEffect(activeRequestId, serverUrl, token, pollRound) {
        if (activeRequestId == null || serverUrl == null || token == null) return@LaunchedEffect
        stalled = false
        var lastSuccess = TimeSource.Monotonic.markNow()
        // Un solo cliente HTTP durante todo el sondeo.
        val api = CompanyApi(serverUrl)
        try {
            while (true) {
                delay(PollIntervalMillis)
                try {
                    status = api.coreUpdateStatus(token)
                    restarting = false
                    error = null
                    lastSuccess = TimeSource.Monotonic.markNow()
                } catch (exception: CompanyException) {
                    val code = exception.status
                    if (exception.network || code in 502..504) {
                        // El servicio se reinicia durante la actualizacion: se sigue consultando, con tope.
                        restarting = true
                        if (lastSuccess.elapsedNow() >= RestartTimeout) {
                            restarting = false
                            stalled = true
                            return@LaunchedEffect
                        }
                    } else if (code != null && code in 400..499) {
                        // Sesion vencida o sin permiso: seguir consultando no sirve.
                        restarting = false
                        error = exception.message
                        return@LaunchedEffect
                    } else {
                        restarting = false
                        error = exception.message
                    }
                }
            }
        } finally {
            api.close()
        }
    }

    // Vuelve a leer el estado (version, commit y firma) del servidor.
    fun reloadStatus() {
        val url = serverUrl ?: return
        val accessToken = token ?: return
        loading = true
        scope.launch {
            try {
                status = fetchStatus(url, accessToken)
            } catch (exception: CompanyException) {
                error = exception.message
            } finally {
                loading = false
            }
        }
    }

    // [commit] es el que se mostro en la confirmacion (null solo en servidores que aun no lo informan).
    fun requestUpdate(commit: String?) {
        val url = serverUrl ?: return
        val accessToken = token ?: return
        requesting = true
        error = null
        scope.launch {
            try {
                val api = CompanyApi(url)
                val created = try {
                    api.requestCoreUpdate(accessToken, commit)
                } finally {
                    api.close()
                }
                restarting = false
                status = status?.copy(request = created)
            } catch (exception: CompanyException) {
                error = when (exception.code) {
                    "CORE_COMMIT_INVALID" -> tr("El commit enviado no es valido. Actualiza la pantalla y revisalo de nuevo.")
                    "CORE_COMMIT_UNAVAILABLE" -> tr("No se pudo leer en GitHub el commit de la ultima version. Intentalo de nuevo en unos minutos.")
                    else -> exception.message
                }
                // Ya hay una solicitud activa o la rama avanzo: se recarga el estado para ver lo nuevo.
                if (exception.status == 409 || exception.code == "CORE_COMMIT_UNAVAILABLE") {
                    try {
                        status = fetchStatus(url, accessToken)
                    } catch (_: CompanyException) {
                    }
                }
                // La version publicada cambio mientras se revisaba: se vuelve a pedir confirmacion con el commit nuevo.
                if (exception.code == "CORE_UPDATE_COMMIT_CHANGED") confirming = true
            } finally {
                requesting = false
            }
        }
    }

    if (confirming) {
        val reviewed = status
        // El commit que se aprueba es el que esta a la vista ahora: si la rama avanza, el servidor lo rechaza.
        val reviewedCommit = reviewed?.latestCommit
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text(tr("Actualizar servidor")) },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    reviewed?.latestVersion?.let { Text(tr("Version {0}", it), color = AppTheme.colors.text, fontWeight = FontWeight.SemiBold) }
                    reviewed?.let { UpdateTargetInfo(it) }
                    Text(
                        tr("Se compilara la nueva version en el servidor (varios minutos) y el servicio se reiniciara; durante el reinicio la app no respondera unos segundos."),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = reviewed != null && (reviewedCommit != null || reviewed.ref == null),
                    onClick = {
                        confirming = false
                        requestUpdate(reviewedCommit)
                    },
                ) { Text(tr("Actualizar")) }
            },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text(tr("Cancelar")) } },
        )
    }

    AuthenticatedScreen(
        modifier = modifier.background(colors.screenBrush),
        topBar = {
            AuthenticatedTopBar(
                title = tr("Actualizar servidor"),
                subtitle = tr("Actualiza el Core y la webapp del servidor"),
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
                .contentWidth(ContentWidth.Wide, 0.94f),
        ) {
            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(AdaptiveLayout.screenPadding - 2.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val current = status
                if (current != null) {
                    Text(tr("Version actual {0}", current.currentVersion), color = colors.text, fontWeight = FontWeight.SemiBold)
                    Text(
                        current.latestVersion?.let { tr("Ultima version {0}", it) } ?: tr("No se pudo consultar el catalogo"),
                        color = colors.muted,
                    )
                    if (current.updateAvailable) UpdateTargetInfo(current)
                } else if (loading) {
                    Text(tr("Cargando..."), color = colors.muted)
                }
                error?.let { Text(it, color = colors.danger) }
                if (stalled) {
                    Text(
                        tr("El servidor no responde despues de 5 minutos. Revisa el servidor por SSH (journalctl -u bms)."),
                        color = colors.danger,
                    )
                    Button(onClick = { pollRound++ }, colors = accentButtonColors()) { Text(tr("Reintentar")) }
                }

                if (current != null) {
                    if (request == null || !request.isActive()) {
                        if (current.updateAvailable && current.latestVersion != null) {
                            // Un servidor que informa la rama (ref) y no puede leer el commit no deja actualizar: se instala solo
                            // el commit que el dueno ve. Un servidor anterior (sin ref) sigue actualizando como antes.
                            val commitMissing = current.latestCommit == null && current.ref != null
                            Button(
                                onClick = { confirming = true },
                                enabled = !requesting && !commitMissing,
                                colors = accentButtonColors(),
                            ) {
                                Text(if (requesting) tr("Solicitando...") else tr("Actualizar a {0}", current.latestVersion))
                            }
                            if (commitMissing) {
                                Text(
                                    tr("No se pudo leer en GitHub el commit de la ultima version, asi que no se puede actualizar ahora."),
                                    color = colors.warning,
                                )
                                Button(onClick = { reloadStatus() }, enabled = !loading, colors = accentButtonColors()) { Text(tr("Reintentar")) }
                            }
                        } else if (current.latestVersion != null) {
                            Text(tr("El servidor ya tiene la ultima version."), color = colors.muted)
                        }
                    }
                    if (request != null) {
                        UpdateRequestPanel(request = request, restarting = restarting)
                    }
                }
            }
        }
    }
}

// Lo que se va a instalar: rama, commit (con enlace a GitHub para revisarlo) y si el servidor exige firma.
@Composable
private fun UpdateTargetInfo(status: CoreUpdateStatusResponse) {
    val colors = AppTheme.colors
    val uriHandler = LocalUriHandler.current
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        status.ref?.takeIf { it.isNotBlank() }?.let { Text(tr("Rama: {0}", it), color = colors.muted) }
        status.latestCommit?.takeIf { it.isNotBlank() }?.let { commit ->
            TextButton(onClick = { runCatching { uriHandler.openUri(CommitUrlPrefix + commit) } }) {
                Text(tr("Commit {0} (ver en GitHub)", commit.take(8)), color = colors.accentText)
            }
        }
        // signatureRequired: hay llave de firma instalada, solo se instala un commit firmado; sin ella no se verifica el origen.
        Text(
            if (status.signatureRequired) tr("Firma exigida: solo se instala un commit firmado.") else tr("Sin firma: este servidor no verifica la firma del commit."),
            color = if (status.signatureRequired) colors.success else colors.warning,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

// Estado de la solicitud mas reciente con su log.
@Composable
private fun UpdateRequestPanel(request: CoreUpdateRequestDto, restarting: Boolean) {
    val colors = AppTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (request.status) {
            "pending" -> Text(tr("Pendiente: esperando al servidor"), color = colors.text, fontWeight = FontWeight.SemiBold)
            "running" -> Text(tr("Actualizando..."), color = colors.text, fontWeight = FontWeight.SemiBold)
            "done" -> {
                Text(tr("Actualizado a {0}", request.targetVersion), color = colors.text, fontWeight = FontWeight.SemiBold)
                Text(
                    tr("La version de la app de escritorio o movil es independiente y se actualiza por separado."),
                    color = colors.muted,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            "error" -> {
                Text(tr("La actualizacion fallo"), color = colors.danger, fontWeight = FontWeight.SemiBold)
                request.message?.takeIf { it.isNotBlank() }?.let { Text(it, color = colors.danger) }
            }
            else -> Text(request.status, color = colors.muted)
        }
        request.targetCommit?.takeIf { it.isNotBlank() }?.let { commit ->
            Text(tr("Commit {0}", commit.take(8)), color = colors.muted, style = MaterialTheme.typography.bodySmall)
        }
        when (request.signatureStatus) {
            "verified" -> Text(tr("Firma verificada"), color = colors.success, style = MaterialTheme.typography.bodySmall)
            "unsigned" -> Text(tr("Sin firma"), color = colors.warning, style = MaterialTheme.typography.bodySmall)
            else -> Unit
        }
        if (request.status == "pending" && request.stalePending) {
            Text(
                tr("El servidor aun no tiene el actualizador instalado. Ejecuta una vez sudo bash update.sh por SSH; despues las actualizaciones se podran hacer desde aqui."),
                color = colors.danger,
            )
        }
        if (restarting && request.isActive()) {
            Text(tr("El servidor se esta reiniciando..."), color = colors.muted)
        }
        request.logTail?.takeIf { it.isNotBlank() }?.let { LogBox(it) }
    }
}

// Log en monoespaciado, seleccionable y con scroll propio de alto limitado.
@Composable
private fun LogBox(text: String) {
    val colors = AppTheme.colors
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = colors.surface,
        shape = MaterialTheme.shapes.medium,
    ) {
        val scroll = rememberScrollState()
        var firstShow by remember { mutableStateOf(true) }
        // Sigue la cola del log salvo que el usuario haya subido; al mostrarse empieza abajo.
        LaunchedEffect(text) {
            val follow = firstShow || scroll.value >= scroll.maxValue - 40
            firstShow = false
            if (follow) {
                // Espera un cuadro para que el layout ya incluya el texto nuevo.
                withFrameNanos { }
                scroll.scrollTo(scroll.maxValue)
            }
        }
        Box(modifier = Modifier.heightIn(max = 280.dp).verticalScroll(scroll).padding(12.dp)) {
            SelectionContainer {
                Text(
                    text,
                    color = colors.text,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
