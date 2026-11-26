package com.ideasdeveloper.bms

import com.ideasdeveloper.bms.app.core.i18n.AppLanguage
import com.ideasdeveloper.bms.app.core.i18n.tr
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.intl.Locale
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.savedstate.read
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.ideasdeveloper.bms.app.core.company.CompanyIdentityStore
import com.ideasdeveloper.bms.app.core.config.ClientConfigurationStore
import com.ideasdeveloper.bms.app.core.session.SessionStore
import com.ideasdeveloper.bms.app.core.theme.BMSTheme
import com.ideasdeveloper.bms.app.core.theme.ThemeStore
import com.ideasdeveloper.bms.app.features.auth.data.LoginApi
import com.ideasdeveloper.bms.app.features.auth.data.ServerPermission
import com.ideasdeveloper.bms.app.features.auth.data.can
import com.ideasdeveloper.bms.app.features.auth.data.isServerAdmin
import com.ideasdeveloper.bms.app.features.auth.data.isServerLevel
import com.ideasdeveloper.bms.app.features.auth.data.isServerOwner
import com.ideasdeveloper.bms.app.features.company.data.CompanyException
import com.ideasdeveloper.bms.app.features.serveradmin.data.PasswordChangeRequiredCode
import com.ideasdeveloper.bms.app.features.serveradmin.data.ServerAdminApi
import com.ideasdeveloper.bms.app.features.serveradmin.data.use
import com.ideasdeveloper.bms.app.features.serveradmin.ui.AuditLogScreen
import com.ideasdeveloper.bms.app.features.activitylog.ui.ActivityLogScreen
import com.ideasdeveloper.bms.app.features.serveradmin.ui.ForcedPasswordChangeScreen
import com.ideasdeveloper.bms.app.features.serveradmin.ui.ServerAdminsScreen
import com.ideasdeveloper.bms.app.features.auth.ui.LoginScreen
import com.ideasdeveloper.bms.app.features.auth.ui.ProfileDialog
import com.ideasdeveloper.bms.app.features.company.ui.CompanyManagementScreen
import com.ideasdeveloper.bms.app.features.company.ui.CompanyProvisioningScreen
import com.ideasdeveloper.bms.app.features.company.ui.ServerModuleManagementScreen
import com.ideasdeveloper.bms.app.features.company.ui.ServerStaffScreen
import com.ideasdeveloper.bms.app.features.company.ui.ServerLogsScreen
import com.ideasdeveloper.bms.app.features.company.ui.ServerUpdateScreen
import com.ideasdeveloper.bms.app.features.dashboard.ui.DashboardScreen
import com.ideasdeveloper.bms.app.features.extensions.data.ExtensionTarget
import com.ideasdeveloper.bms.app.features.extensions.ui.ExtensionsScreen
import com.ideasdeveloper.bms.app.features.modules.ui.ServerDrivenModuleScreen
import com.ideasdeveloper.bms.app.features.modules.ui.ModuleDeepLinks
import com.ideasdeveloper.bms.app.features.notifications.ui.LocalNotificationNavigator
import com.ideasdeveloper.bms.app.features.notifications.ui.NotificationCenter
import com.ideasdeveloper.bms.app.features.settings.ui.SettingsScreen
import com.ideasdeveloper.bms.app.features.shell.ui.ConfirmDialog
import com.ideasdeveloper.bms.navigation.NavRoute
import com.ideasdeveloper.bms.navigation.NavRoute.Companion.routeName

@Composable
// Orquesta la navegacion principal segun exista o no una sesion restaurada.
fun App(initialServerUrl: String = "") {
    // Idioma del dispositivo: solo se usa mientras no haya idioma guardado ni sesion.
    val deviceLanguageTag = Locale.current.language
    remember(deviceLanguageTag) { AppLanguage.useDeviceLanguage(deviceLanguageTag) }
    BMSTheme {
        // Restaura la sesion, identidad visual y configuracion local antes de construir rutas.
        val navController = rememberNavController()
        val restoredSession = remember { SessionStore.restore() }
        val session by SessionStore.session.collectAsState()
        // Tras cerrar o vencer la sesion no se vuelve a usar la restaurada al iniciar.
        val currentSession = session
        val sessionExpired by SessionStore.expired.collectAsState()
        var confirmingLogout by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()
        remember { CompanyIdentityStore.restore() }
        var clientConfiguration by remember { mutableStateOf(ClientConfigurationStore.load()) }
        // Trae los temas de los modulos instalados en el servidor configurado.
        LaunchedEffect(clientConfiguration?.serverUrl) { ThemeStore.refresh(clientConfiguration?.serverUrl) }

        // Regresa al dashboard limpiando pantallas duplicadas de la pila.
        fun openDashboard() {
            navController.navigate("dashboard") {
                popUpTo("dashboard") {
                    inclusive = true
                }
                launchSingleTop = true
            }
        }

        // Deja solo la pantalla de login en la pila, sin importar desde donde se llegue.
        fun openLogin() {
            navController.navigate("login") {
                popUpTo(navController.graph.id) {
                    inclusive = true
                }
                launchSingleTop = true
            }
        }

        // Cierra la sesion: primero avisa al servidor para invalidar el token (best-effort), luego limpia local.
        // [serverUrl] se captura antes de borrar la configuracion local para no perder la llamada al servidor.
        fun logout(serverUrl: String? = clientConfiguration?.serverUrl) {
            val current = SessionStore.session.value
            val url = serverUrl
            if (current != null && !url.isNullOrBlank()) {
                scope.launch {
                    try {
                        val api = LoginApi(url)
                        try {
                            api.logout(current.companyCode, current.accessToken)
                        } finally {
                            api.close()
                        }
                    } catch (exception: CancellationException) {
                        throw exception
                    } catch (_: IllegalArgumentException) {
                        // URL malformada: no hay servidor al que avisar, se limpia solo lo local.
                    }
                }
            }
            SessionStore.clear()
            openLogin()
        }

        // Los botones de "Cerrar sesion" primero piden confirmacion.
        fun requestLogout() {
            confirmingLogout = true
        }

        // Cierra la sesion cuando vence el token aunque el usuario no haga ninguna peticion.
        LaunchedEffect(session) {
            if (session == null) return@LaunchedEffect
            val remaining = SessionStore.remaining() ?: return@LaunchedEffect
            delay(remaining)
            SessionStore.expire()
        }

        // Sesion vencida (por tiempo o rechazada por el servidor): de vuelta al login, que muestra el aviso.
        LaunchedEffect(sessionExpired) {
            if (sessionExpired) {
                confirmingLogout = false
                openLogin()
            }
        }

        // Sin sesion no queda nada del centro de notificaciones (la campana deja de actualizarse sola).
        LaunchedEffect(session == null) {
            if (session == null) {
                NotificationCenter.clear()
                ModuleDeepLinks.clear()
            }
        }

        // Los permisos de un administrador del servidor cambian en cualquier momento: se leen del servidor
        // al abrir la sesion (una sesion restaurada no los guarda) y asi tambien se conoce el cambio obligatorio de contrasena.
        LaunchedEffect(currentSession?.accessToken, clientConfiguration?.serverUrl) {
            val active = currentSession ?: return@LaunchedEffect
            val url = clientConfiguration?.serverUrl ?: return@LaunchedEffect
            if (!active.isServerAdmin) return@LaunchedEffect
            try {
                val me = ServerAdminApi(url).use { api -> api.me(active.accessToken) }
                SessionStore.update {
                    it.copy(
                        serverPermissions = me.permissions,
                        serverCompanies = if (me.allCompanies) null else me.companies,
                        mustChangePassword = me.mustChangePassword,
                    )
                }
            } catch (exception: CompanyException) {
                if (exception.code == PasswordChangeRequiredCode) SessionStore.update { it.copy(mustChangePassword = true) }
            }
        }

        // Define las rutas activas de la app y decide la pantalla inicial. Los clics en las
        // notificaciones abren la pantalla de su modulo con la misma navegacion de los enlaces entre modulos.
        CompositionLocalProvider(
            LocalNotificationNavigator provides { action ->
                ModuleDeepLinks.open(action)
                // Desde otra pantalla de modulo la reemplaza (se vuelve a crear para tomar el enlace pendiente);
                // desde cualquier otra no apila dos veces el mismo destino.
                val onModule = navController.currentDestination?.route == "module/{moduleId}"
                navController.navigate("module/${action.moduleId}") {
                    launchSingleTop = !onModule
                    if (onModule) popUpTo("module/{moduleId}") { inclusive = true }
                }
            },
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                NavHost(
                    navController = navController,
                    startDestination = if (restoredSession == null) "login" else "dashboard"
                ) {
                    composable("login") {
                        LoginScreen(
                            initialServerUrl = initialServerUrl,
                            onLoginSuccess = {
                                // Recarga la configuracion guardada por el login antes de entrar al dashboard.
                                clientConfiguration = ClientConfigurationStore.load()
                                navController.navigate("dashboard") {
                                    popUpTo("login") {
                                        inclusive = true
                                    }
                                    launchSingleTop = true
                                }
                            },
                        )
                    }
                    composable("dashboard") {
                        // Muestra el punto de entrada autenticado con los modulos disponibles.
                        DashboardScreen(
                            session = currentSession,
                            serverUrl = clientConfiguration?.serverUrl,
                            onNavigateTo = { route -> navController.navigate(route.routeName()) },
                            onLogout = ::requestLogout,
                            // La configuracion guardada ahora apunta a la otra empresa.
                            onCompanySwitched = { clientConfiguration = ClientConfigurationStore.load() },
                        )
                    }
                    composable("module/{moduleId}") { backStackEntry ->
                        val moduleId = backStackEntry.arguments?.read {
                            getStringOrNull("moduleId")
                        }.orEmpty()
                        val moduleName = moduleId.toModuleTitle()
                        // La administracion de Empresa usa pantallas propias para dueno del servidor y dueno del negocio.
                        if (moduleId == "server-modules" && (currentSession.can(ServerPermission.ModulesView) || currentSession.can(ServerPermission.ExtensionsInstall))) {
                            ServerModuleManagementScreen(
                                serverUrl = clientConfiguration?.serverUrl,
                                session = currentSession,
                                onSettings = { navController.navigate(NavRoute.Settings.routeName()) },
                                onLogout = ::requestLogout,
                                onReturnToDashboard = ::openDashboard,
                            )
                        } else if (moduleId == "server-staff" && currentSession.can(ServerPermission.Workspace)) {
                            ServerStaffScreen(
                                serverUrl = clientConfiguration?.serverUrl,
                                session = currentSession,
                                onSettings = { navController.navigate(NavRoute.Settings.routeName()) },
                                onLogout = ::requestLogout,
                                onReturnToDashboard = ::openDashboard,
                            )
                        } else if (moduleId == "server-logs" && currentSession.can(ServerPermission.LogsView)) {
                            ServerLogsScreen(
                                serverUrl = clientConfiguration?.serverUrl,
                                session = currentSession,
                                onSettings = { navController.navigate(NavRoute.Settings.routeName()) },
                                onLogout = ::requestLogout,
                                onReturnToDashboard = ::openDashboard,
                            )
                        } else if (moduleId == "server-update" && currentSession.can(ServerPermission.CoreUpdate)) {
                            ServerUpdateScreen(
                                serverUrl = clientConfiguration?.serverUrl,
                                session = currentSession,
                                onSettings = { navController.navigate(NavRoute.Settings.routeName()) },
                                onLogout = ::requestLogout,
                                onReturnToDashboard = ::openDashboard,
                            )
                        } else if (moduleId == "server-admins" && currentSession.isServerOwner) {
                            // Solo el dueno del servidor gestiona administradores.
                            ServerAdminsScreen(
                                serverUrl = clientConfiguration?.serverUrl,
                                session = currentSession,
                                onSettings = { navController.navigate(NavRoute.Settings.routeName()) },
                                onLogout = ::requestLogout,
                                onReturnToDashboard = ::openDashboard,
                            )
                        } else if (moduleId == "server-audit" && currentSession.can(ServerPermission.AuditView)) {
                            AuditLogScreen(
                                serverUrl = clientConfiguration?.serverUrl,
                                session = currentSession,
                                onSettings = { navController.navigate(NavRoute.Settings.routeName()) },
                                onLogout = ::requestLogout,
                                onReturnToDashboard = ::openDashboard,
                            )
                        } else if (moduleId == "company-activity") {
                            // Registro de actividad de la empresa: solo sesiones de empresa; el servidor decide con 403 si no hay permiso.
                            if (currentSession.isServerLevel || currentSession?.companyCode == null) {
                                ReturnToDashboardEffect(onReturn = ::openDashboard)
                            } else {
                                ActivityLogScreen(
                                    serverUrl = clientConfiguration?.serverUrl,
                                    session = currentSession,
                                    onSettings = { navController.navigate(NavRoute.Settings.routeName()) },
                                    onLogout = ::requestLogout,
                                    onReturnToDashboard = ::openDashboard,
                                )
                            }
                        } else if (currentSession.isServerLevel && (moduleId in ServerOnlyScreens || (moduleId == "empresa" && !currentSession.can(ServerPermission.CompaniesView)))) {
                            // Pantalla del servidor para la que la sesion ya no tiene permiso (por ejemplo, se lo quitaron): vuelve al inicio.
                            ReturnToDashboardEffect(onReturn = ::openDashboard)
                        } else if (moduleId == "empresa") {
                            if (currentSession.isServerLevel) {
                                CompanyProvisioningScreen(
                                    serverUrl = clientConfiguration?.serverUrl,
                                    session = currentSession,
                                    onSettings = { navController.navigate(NavRoute.Settings.routeName()) },
                                    onLogout = ::requestLogout,
                                    onReturnToDashboard = ::openDashboard,
                                )
                            } else {
                                CompanyManagementScreen(
                                    serverUrl = clientConfiguration?.serverUrl,
                                    session = currentSession,
                                    onSettings = { navController.navigate(NavRoute.Settings.routeName()) },
                                    onLogout = ::requestLogout,
                                    onReturnToDashboard = ::openDashboard,
                                )
                            }
                        } else {
                            // Los demas modulos se renderizan desde metadata recibida del servidor.
                            ServerDrivenModuleScreen(
                                moduleId = moduleId,
                                serverUrl = clientConfiguration?.serverUrl,
                                session = currentSession,
                                fallbackTitle = moduleName,
                                onSettings = { navController.navigate(NavRoute.Settings.routeName()) },
                                onLogout = ::requestLogout,
                                onReturnToDashboard = ::openDashboard,
                                onCompanySwitched = { clientConfiguration = ClientConfigurationStore.load() },
                                onOpenModule = { action ->
                                    ModuleDeepLinks.open(action)
                                    navController.navigate("module/${action.moduleId}")
                                },
                                // Entrada "Extensiones" de la Configuracion del modulo.
                                onOpenExtensions = { id -> navController.navigate(NavRoute.ModuleExtensions(id).routeName()) },
                            )
                        }
                    }
                    composable("module/{moduleId}/extensions") { backStackEntry ->
                        val moduleId = backStackEntry.arguments?.read {
                            getStringOrNull("moduleId")
                        }.orEmpty()
                        val serverOwner = currentSession.isServerLevel
                        // El business owner trabaja en su empresa; el server owner en el espacio del servidor.
                        val companyCode = currentSession?.companyCode ?: currentSession?.workspaceCompanyCode
                        if (companyCode != null) {
                            ExtensionsScreen(
                                serverUrl = clientConfiguration?.serverUrl,
                                session = currentSession,
                                target = ExtensionTarget(companyCode, asServerOwner = serverOwner),
                                companyName = null,
                                moduleId = moduleId,
                                onSettings = { navController.navigate(NavRoute.Settings.routeName()) },
                                onLogout = ::requestLogout,
                                // Vuelve al modulo (su Configuracion), no al inicio.
                                onClose = { navController.popBackStack() },
                            )
                        }
                    }
                    composable("settings") {
                        SettingsScreen(
                            configuration = clientConfiguration,
                            session = currentSession,
                            onLogout = ::requestLogout,
                            onReturnToDashboard = ::openDashboard,
                            onClearLocalConfiguration = {
                                // Captura la URL antes de borrarla para que logout avise al servidor.
                                val serverUrl = clientConfiguration?.serverUrl
                                ClientConfigurationStore.clear()
                                CompanyIdentityStore.clear()
                                clientConfiguration = null
                                logout(serverUrl)
                            },
                        )
                    }
                }
                // Bloquea toda la app hasta que el administrador cambie su contrasena inicial.
                if (currentSession != null && currentSession.mustChangePassword) {
                    ForcedPasswordChangeScreen(
                        serverUrl = clientConfiguration?.serverUrl,
                        accessToken = currentSession.accessToken,
                        onLogout = ::requestLogout,
                    )
                }
            }
        }

        // Usuarios creados antes de que nombre y correo fueran obligatorios los completan antes de seguir.
        currentSession?.takeIf { !it.profileComplete }?.let { incomplete ->
            ProfileDialog(
                serverUrl = clientConfiguration?.serverUrl,
                session = incomplete,
                required = true,
                onSaved = { profile -> SessionStore.update { it.copy(displayName = profile.displayName, email = profile.email, profileComplete = profile.profileComplete) } },
                onDismiss = {},
                onLogout = ::requestLogout,
            )
        }

        // Va al final para quedar encima del dialogo de perfil obligatorio.
        if (confirmingLogout && currentSession != null) {
            ConfirmDialog(
                title = tr("Cerrar sesion"),
                message = tr("Se cerrara tu sesion en este dispositivo y tendras que volver a iniciar sesion. Los cambios que no hayas guardado se perderan."),
                confirmLabel = tr("Cerrar sesion"),
                onConfirm = {
                    confirmingLogout = false
                    logout()
                },
                onDismiss = { confirmingLogout = false },
            )
        }
    }
}

// Pantallas nativas del servidor: solo existen para sesiones del nivel servidor y segun sus permisos.
private val ServerOnlyScreens = setOf("server-modules", "server-staff", "server-logs", "server-update", "server-admins", "server-audit")

@Composable
// Regresa al dashboard en cuanto se muestra; sustituye a una pantalla a la que la sesion ya no tiene acceso.
private fun ReturnToDashboardEffect(onReturn: () -> Unit) {
    LaunchedEffect(Unit) { onReturn() }
}

// Convierte ids de modulo en titulos legibles para las pantallas internas.
private fun String.toModuleTitle(): String = when (this) {
    "empresa" -> tr("Empresa")
    "server-modules" -> tr("Modulos del servidor")
    "server-staff" -> tr("Empresa del servidor")
    "server-logs" -> tr("Errores del servidor")
    "server-update" -> tr("Actualizar servidor")
    "server-admins" -> tr("Administradores del servidor")
    "server-audit" -> tr("Registro de auditoria")
    "company-activity" -> tr("Registro de actividad")
    "clientes" -> tr("Clientes")
    else -> replace("-", " ")
        .replace("_", " ")
        .split(" ")
        .filter { it.isNotBlank() }
        .joinToString(" ") { segment ->
            segment.replaceFirstChar { char -> char.uppercase() }
        }
}
