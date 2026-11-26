package com.ideasdeveloper.bms.app.features.settings.ui

import com.ideasdeveloper.bms.app.core.i18n.tr
import com.ideasdeveloper.bms.app.core.theme.accentButtonColors
import com.ideasdeveloper.bms.app.core.layout.AdaptiveLayout
import com.ideasdeveloper.bms.app.core.layout.ContentWidth
import com.ideasdeveloper.bms.app.core.layout.contentWidth
import com.ideasdeveloper.bms.app.core.theme.AppTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ideasdeveloper.bms.app.core.company.CompanyIdentityStore
import com.ideasdeveloper.bms.app.core.i18n.AppLanguage
import com.ideasdeveloper.bms.app.core.config.ClientConfiguration
import com.ideasdeveloper.bms.app.core.version.ClientVersion
import com.ideasdeveloper.bms.app.core.version.VersionApi
import com.ideasdeveloper.bms.app.core.session.SessionStore
import com.ideasdeveloper.bms.app.features.auth.data.AccountApi
import com.ideasdeveloper.bms.app.features.auth.data.LoginResponse
import com.ideasdeveloper.bms.app.features.auth.data.use
import com.ideasdeveloper.bms.app.features.auth.ui.LanguageSelector
import com.ideasdeveloper.bms.app.features.auth.ui.ProfileDialog
import com.ideasdeveloper.bms.app.features.shell.ui.AuthenticatedScreen
import com.ideasdeveloper.bms.app.features.shell.ui.AuthenticatedTopBar
import com.ideasdeveloper.bms.app.features.shell.ui.ClosableView
import com.ideasdeveloper.bms.app.features.shell.ui.ConfirmDialog
import com.ideasdeveloper.bms.app.features.shell.ui.toComposeColor
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.RadioButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.semantics.Role
import com.ideasdeveloper.bms.app.core.theme.BuiltInThemes
import com.ideasdeveloper.bms.app.core.theme.ThemeStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
// Muestra ajustes locales seguros del cliente; los cambios sensibles siguen viviendo en el servidor.
fun SettingsScreen(
    configuration: ClientConfiguration?,
    session: LoginResponse?,
    onReturnToDashboard: () -> Unit,
    onLogout: () -> Unit,
    onClearLocalConfiguration: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val companyIdentity = remember { CompanyIdentityStore.current() }
    val colors = AppTheme.colors
    val primaryColor = colors.accent
    val scope = rememberCoroutineScope()
    var versionStatus by remember { mutableStateOf<String?>(null) }
    var checkingVersion by remember { mutableStateOf(false) }
    var editingProfile by remember { mutableStateOf(false) }
    var confirmingClear by remember { mutableStateOf(false) }
    val selectedTheme by ThemeStore.selection.collectAsState()
    val moduleThemes by ThemeStore.moduleThemes.collectAsState()
    // Al abrir Ajustes vuelve a pedir los temas por si se instalo o quito un modulo.
    LaunchedEffect(configuration?.serverUrl) { ThemeStore.refresh(configuration?.serverUrl) }

    if (confirmingClear) {
        ConfirmDialog(
            title = tr("Limpiar configuracion local"),
            message = tr("Se borraran de este dispositivo la URL del servidor, la empresa guardada y tu sesion. Tendras que configurar el servidor e iniciar sesion de nuevo. Los datos del servidor no se modifican."),
            confirmLabel = tr("Limpiar y cerrar sesion"),
            onConfirm = {
                confirmingClear = false
                onClearLocalConfiguration()
            },
            onDismiss = { confirmingClear = false },
        )
    }

    if (editingProfile && session != null) {
        ProfileDialog(
            serverUrl = configuration?.serverUrl,
            session = session,
            required = false,
            onSaved = { profile ->
                SessionStore.update { it.copy(displayName = profile.displayName, email = profile.email, profileComplete = profile.profileComplete) }
                editingProfile = false
            },
            onDismiss = { editingProfile = false },
            onLogout = onLogout,
        )
    }

    AuthenticatedScreen(
        modifier = modifier.background(colors.screenBrush),
        topBar = {
            AuthenticatedTopBar(
                title = tr("Ajustes"),
                subtitle = tr("Cliente"),
                primaryColor = primaryColor,
                onReturnToDashboard = onReturnToDashboard,
                onSettings = {},
                onLogout = onLogout,
            )
        },
    ) {
        // La vista empieza debajo de la barra superior y lleva la X en su esquina superior derecha.
        ClosableView(
            onClose = onReturnToDashboard,
            modifier = Modifier
                .align(Alignment.Center)
                .padding(bottom = 16.dp)
                .contentWidth(ContentWidth.Form, 0.9f),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(AdaptiveLayout.gap),
            ) {
                SettingsCard(primaryColor = primaryColor) {
                    Text(tr("Apariencia"), fontWeight = FontWeight.Bold, color = AppTheme.colors.accentText)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        tr("El tema se guarda en este dispositivo. Los modulos instalados en el servidor pueden agregar temas."),
                        color = AppTheme.colors.muted,
                    )
                    Spacer(Modifier.height(6.dp))
                    ThemeOption(tr("Automatico (segun el sistema)"), BuiltInThemes.System, selectedTheme)
                    ThemeOption(BuiltInThemes.light.name, BuiltInThemes.Light, selectedTheme)
                    ThemeOption(BuiltInThemes.dark.name, BuiltInThemes.Dark, selectedTheme)
                    moduleThemes.forEach { theme ->
                        ThemeOption(tr("{0} (modulo {1})", theme.name, theme.id.substringBefore('.')), theme.id, selectedTheme)
                    }
                }

                SettingsCard(primaryColor = primaryColor) {
                    Text(tr("Idioma"), fontWeight = FontWeight.Bold, color = AppTheme.colors.accentText)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        tr("El idioma se guarda en este dispositivo y en tu perfil."),
                        color = AppTheme.colors.muted,
                    )
                    Spacer(Modifier.height(6.dp))
                    LanguageSelector(selected = AppLanguage.choice, enabled = true) { choice ->
                        // Local e inmediato; ademas se guarda en el perfil si hay sesion con nombre y correo.
                        AppLanguage.select(choice)
                        val url = configuration?.serverUrl
                        val name = session?.displayName
                        val mail = session?.email
                        if (session != null && !url.isNullOrBlank() && !name.isNullOrBlank() && !mail.isNullOrBlank()) {
                            val token = session.accessToken
                            val companyCode = session.companyCode
                            scope.launch {
                                try {
                                    AccountApi(url).use { it.updateProfile(token, companyCode, name, mail, choice) }
                                } catch (exception: CancellationException) {
                                    throw exception
                                } catch (_: Exception) {
                                    // Sin conexion o el servidor lo rechazo: el idioma queda guardado en este dispositivo.
                                }
                            }
                        }
                    }
                }

                SettingsCard(primaryColor = primaryColor) {
                    Text(tr("Conexion"), fontWeight = FontWeight.Bold, color = AppTheme.colors.accentText)
                    Spacer(Modifier.height(10.dp))
                    SettingRow(tr("Servidor"), configuration?.serverUrl ?: tr("Sin configurar"))
                    SettingRow(tr("Empresa"), configuration?.companyCode ?: tr("Administracion del servidor"))
                }

                SettingsCard(primaryColor = primaryColor) {
                    Text(tr("Sesion"), fontWeight = FontWeight.Bold, color = AppTheme.colors.accentText)
                    Spacer(Modifier.height(10.dp))
                    SettingRow(tr("Usuario"), session?.username ?: tr("Sin sesion"))
                    SettingRow(tr("Nombre"), session?.displayName ?: tr("Sin nombre"))
                    SettingRow(tr("Correo"), session?.email ?: tr("Sin correo"))
                    SettingRow(tr("Rol"), session?.role ?: tr("Sin rol"))
                    SettingRow(tr("Empresa activa"), session?.companyCode ?: tr("Servidor"))
                    if (session != null) {
                        Spacer(Modifier.height(10.dp))
                        OutlinedButton(onClick = { editingProfile = true }) {
                            Text(tr("Editar nombre y correo"))
                        }
                    }
                }

                SettingsCard(primaryColor = primaryColor) {
                    Text(tr("Version"), fontWeight = FontWeight.Bold, color = AppTheme.colors.accentText)
                    Spacer(Modifier.height(10.dp))
                    SettingRow(tr("Cliente"), ClientVersion)
                    versionStatus?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(it, color = AppTheme.colors.muted)
                    }
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = {
                            val serverUrl = configuration?.serverUrl.orEmpty()
                            if (serverUrl.isBlank()) {
                                versionStatus = tr("No hay servidor configurado para revisar actualizaciones.")
                                return@Button
                            }
                            checkingVersion = true
                            scope.launch {
                                val version = VersionApi(serverUrl).use { it.version() }
                                versionStatus = when {
                                    version == null -> tr("No se pudo consultar la version del servidor.")
                                    !version.latestAppVersion.isNullOrBlank() && version.latestAppVersion != ClientVersion ->
                                        tr("Nueva version del cliente disponible: {0}.", version.latestAppVersion)
                                    else -> tr("El cliente esta actualizado.")
                                }
                                checkingVersion = false
                            }
                        },
                        enabled = !checkingVersion,
                        colors = accentButtonColors(),
                    ) {
                        Text(if (checkingVersion) tr("Revisando...") else tr("Revisar actualizaciones"))
                    }
                }

                SettingsCard(primaryColor = primaryColor) {
                    Text(tr("Datos locales"), fontWeight = FontWeight.Bold, color = AppTheme.colors.accentText)
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = tr("Limpia la URL, empresa guardada y sesion local. No modifica datos del servidor."),
                        color = AppTheme.colors.muted,
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(onClick = { confirmingClear = true }) {
                        Text(tr("Limpiar configuracion local"))
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsCard(
    primaryColor: Color,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = AppTheme.colors.surface, contentColor = AppTheme.colors.accentText),
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(modifier = Modifier.padding(if (AdaptiveLayout.roomy) 14.dp else 16.dp), content = content)
    }
}

@Composable
private fun ThemeOption(label: String, themeId: String, selectedTheme: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = themeId == selectedTheme, role = Role.RadioButton, onClick = { ThemeStore.select(themeId) }),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = themeId == selectedTheme, onClick = null)
        Spacer(Modifier.width(8.dp))
        Text(label, color = AppTheme.colors.text)
    }
}

@Composable
private fun SettingRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = AppTheme.colors.muted)
        Text(value, color = AppTheme.colors.text, fontWeight = FontWeight.SemiBold)
    }
}

private suspend inline fun <T> VersionApi.use(block: suspend (VersionApi) -> T): T {
    return try {
        block(this)
    } finally {
        close()
    }
}
