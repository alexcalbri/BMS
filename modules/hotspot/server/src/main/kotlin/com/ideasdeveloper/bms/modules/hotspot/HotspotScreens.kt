package com.ideasdeveloper.bms.modules.hotspot

import com.ideasdeveloper.bms.server.i18n.msg
import com.ideasdeveloper.bms.server.i18n.tr
import com.ideasdeveloper.bms.server.modules.ModulePermissions
import com.ideasdeveloper.bms.ui.UiBarChart
import com.ideasdeveloper.bms.ui.UiButton
import com.ideasdeveloper.bms.ui.UiCard
import com.ideasdeveloper.bms.ui.UiDownload
import com.ideasdeveloper.bms.ui.UiField
import com.ideasdeveloper.bms.ui.UiFieldCondition
import com.ideasdeveloper.bms.ui.UiForm
import com.ideasdeveloper.bms.ui.UiGrid
import com.ideasdeveloper.bms.ui.UiImage
import com.ideasdeveloper.bms.ui.UiMetric
import com.ideasdeveloper.bms.ui.UiNavItem
import com.ideasdeveloper.bms.ui.UiNavigate
import com.ideasdeveloper.bms.ui.UiNavigation
import com.ideasdeveloper.bms.ui.UiNode
import com.ideasdeveloper.bms.ui.UiNotice
import com.ideasdeveloper.bms.ui.UiRequest
import com.ideasdeveloper.bms.ui.UiRow
import com.ideasdeveloper.bms.ui.UiScreen
import com.ideasdeveloper.bms.ui.UiStatus
import com.ideasdeveloper.bms.ui.UiTable
import com.ideasdeveloper.bms.ui.UiText
import com.ideasdeveloper.bms.ui.UiTextStyle
import com.ideasdeveloper.bms.ui.UiTone
import java.sql.Connection
import java.time.LocalDate

/**
 * Hotspot administration built only from generic UI pieces, modeled on the Conectiva panel:
 * Dashboard, Visitantes, Reportes, Portal cautivo and Configuracion. The screens carry no colors:
 * the app draws them with the theme the user chose.
 */
object HotspotScreens {
    const val UiRoute = "/modules/hotspot/ui"
    const val ExportRoute = "/modules/hotspot/report/export"
    private const val VisitorsPageSize = 25

    const val ReportsView = "hotspot.reports.view"
    const val ReportsExport = "hotspot.reports.export"
    const val SettingsView = "hotspot.settings.view"
    const val SettingsManage = "hotspot.settings.manage"
    // Opens the module and shows the user's own WiFi code.
    const val View = "hotspot.view"
    // Never granted: only the business owner (and server owners in the server company) hold it.
    const val OwnerOnly = "hotspot.owner"

    private data class Section(val id: String, val label: String, val permission: String)

    private val sections = listOf(
        Section("dashboard", msg("Dashboard"), ReportsView),
        Section("visitors", msg("Visitantes"), ReportsView),
        Section("reports", msg("Reportes"), ReportsView),
        Section("access", msg("Accesos WiFi"), View),
        Section("portal", msg("Portal cautivo"), SettingsView),
        Section("design", msg("Diseño"), SettingsView),
        Section("settings", msg("Configuracion"), SettingsView),
        Section("integrations", msg("Integraciones"), OwnerOnly),
    )

    fun isScreen(id: String) = sections.any { it.id == id }

    fun permissionFor(screen: String): String? = sections.firstOrNull { it.id == screen }?.permission

    // First section the user may open; null when the user has no hotspot permission at all.
    fun home(permissions: ModulePermissions): String? = sections.firstOrNull { permissions.has(it.permission) }?.id

    fun build(
        screen: String,
        tenant: Connection,
        permissions: ModulePermissions,
        params: Map<String, String>,
        domainStatus: String,
        domainActive: Boolean,
        sendingAvailable: Boolean,
        notice: UiNotice? = null,
        submittedValues: Map<String, String>? = null,
        // Console and site pickers of the UniFi connection.
        pickers: List<UiNode> = emptyList(),
        // Network form as the user typed it, shown again when saving it failed.
        networkValues: Map<String, String>? = null,
        // Body of sections built outside (Accesos WiFi, Integraciones).
        customBody: List<UiNode>? = null,
    ): UiScreen {
        val settings = HotspotSettingsRepository.load(tenant)
        val title = tr(sections.first { it.id == screen }.label)
        if (!permissions.has(permissionFor(screen)!!)) {
            return frame(settings, permissions, screen, title, UiNotice(tr("No tienes permiso para ver esta seccion."), UiTone.Danger), emptyList())
        }
        val values = submittedValues ?: HotspotSettingsRepository.toFormValues(
            settings, domainStatus, sendingAvailable, HotspotCrmPipeline.stageName(tenant, settings.crmStageId),
        )
        val body = customBody ?: when (screen) {
            "dashboard" -> dashboard(tenant, settings, permissions, domainActive)
            "visitors" -> visitors(tenant, params)
            "reports" -> reports(tenant, permissions, params)
            "portal" -> portal(permissions, values, domainStatus, domainActive)
            "design" -> design(tenant, permissions, params, values)
            else -> settingsForm(tenant, permissions, values, pickers, params, networkValues)
        }
        return frame(settings, permissions, screen, title, notice, body)
    }

    private fun frame(
        settings: HotspotSettings,
        permissions: ModulePermissions,
        selected: String,
        title: String,
        notice: UiNotice?,
        body: List<UiNode>,
    ) = UiScreen(
        title = title,
        navigation = UiNavigation(
            title = settings.networkName,
            items = sections.filter { permissions.has(it.permission) }.map { UiNavItem(it.id, tr(it.label), UiNavigate(it.id)) },
            selected = selected,
        ),
        notice = notice,
        body = listOf(UiText(title, UiTextStyle.Title)) + body,
    )

    private fun dashboard(tenant: Connection, settings: HotspotSettings, permissions: ModulePermissions, domainActive: Boolean): List<UiNode> {
        val today = LocalDate.now()
        val todayCount = HotspotReports.summary(tenant, today, today).registrations
        val yesterdayCount = HotspotReports.summary(tenant, today.minusDays(1), today.minusDays(1)).registrations
        val (trend, trendTone) = trend(todayCount, yesterdayCount)
        val newThisMonth = HotspotReports.newEmailsSince(tenant, today.withDayOfMonth(1))
        val controller = HotspotIntegrations.controller(tenant)
        val health = when {
            controller == null -> UiStatus(tr("Falta elegir el controlador de red en Integraciones"), UiTone.Warning)
            !controller.isConfigured(settings) -> UiStatus(tr("Falta configurar {0}", controller.name), UiTone.Warning)
            settings.domain == null -> UiStatus(tr("Falta configurar el dominio del portal"), UiTone.Warning)
            !domainActive -> UiStatus(tr("El dominio del portal aun no esta activo"), UiTone.Warning)
            else -> UiStatus(tr("Todo funcionando correctamente"), UiTone.Success)
        }
        val from = today.minusDays(6)
        return listOf(
            UiGrid(
                minItemWidth = 220,
                children = listOf(
                    UiCard(listOf(UiMetric(tr("Visitantes hoy"), todayCount.toString(), trend, trendTone))),
                    UiCard(listOf(UiMetric(tr("Correos obtenidos"), HotspotReports.totalEmails(tenant).toString(), tr("+{0} este mes", newThisMonth), UiTone.Success))),
                    UiCard(listOf(UiText(tr("Red Wi-Fi activa"), UiTextStyle.Caption), health)),
                ),
            ),
            UiCard(
                title = tr("Conexiones por dia"),
                actions = listOfNotNull(exportButton(permissions, mapOf("from" to from.toString(), "to" to today.toString()))),
                children = listOf(UiBarChart(HotspotReports.connectionsPerDay(tenant, from, today))),
            ),
            UiCard(
                title = tr("Ultimos registros"),
                actions = listOf(UiButton(tr("Ver todos"), UiNavigate("visitors"), style = "secondary")),
                children = listOf(UiTable(HotspotReports.columns, HotspotReports.visitors(tenant, "", 10, 0).rows, tr("Aun no hay registros."))),
            ),
        )
    }

    private fun visitors(tenant: Connection, params: Map<String, String>): List<UiNode> {
        val search = params["q"].orEmpty().take(100)
        val page = params["page"]?.toIntOrNull()?.coerceAtLeast(1) ?: 1
        val result = HotspotReports.visitors(tenant, search, VisitorsPageSize, (page - 1) * VisitorsPageSize)
        val first = if (result.total == 0L) 0 else (page - 1) * VisitorsPageSize + 1
        val last = minOf(result.total, (page * VisitorsPageSize).toLong())
        val searchParams = if (search.isBlank()) emptyMap() else mapOf("q" to search)
        return listOf(
            UiCard(
                listOf(
                    UiForm(
                        id = "search",
                        fields = listOf(UiField("q", tr("Buscar"), placeholder = tr("Nombre, correo o celular"))),
                        values = mapOf("q" to search),
                        submit = UiButton(tr("Buscar"), UiNavigate("visitors", formId = "search")),
                    ),
                )
            ),
            UiCard(
                title = tr("Visitantes"),
                children = listOf(
                    UiTable(HotspotReports.columns, result.rows, if (search.isBlank()) tr("Aun no hay registros.") else tr("Sin resultados para \"{0}\".", search)),
                    UiRow(
                        listOf(
                            UiText(tr("Mostrando {0}-{1} de {2}", first, last, result.total), UiTextStyle.Caption),
                            UiButton(tr("Anterior"), UiNavigate("visitors", searchParams + ("page" to (page - 1).toString())), style = "secondary", enabled = page > 1),
                            UiButton(tr("Siguiente"), UiNavigate("visitors", searchParams + ("page" to (page + 1).toString())), style = "secondary", enabled = last < result.total),
                        )
                    ),
                ),
            ),
        )
    }

    private fun reports(tenant: Connection, permissions: ModulePermissions, params: Map<String, String>): List<UiNode> {
        val today = LocalDate.now()
        var from = params["from"]?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: today.minusDays(29)
        var to = params["to"]?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: today
        if (to.isBefore(from)) from = to.also { to = from }
        if (from.plusDays(366).isBefore(to)) from = to.minusDays(366)
        val range = mapOf("from" to from.toString(), "to" to to.toString())
        val summary = HotspotReports.summary(tenant, from, to)
        return listOf(
            UiCard(
                listOf(
                    UiForm(
                        id = "range",
                        fields = listOf(UiField("from", tr("Desde"), "date"), UiField("to", tr("Hasta"), "date")),
                        values = range,
                        submit = UiButton(tr("Consultar"), UiNavigate("reports", formId = "range")),
                        minFieldWidth = 180,
                    ),
                )
            ),
            UiGrid(
                minItemWidth = 180,
                children = listOf(
                    UiCard(listOf(UiMetric(tr("Registros"), summary.registrations.toString()))),
                    UiCard(listOf(UiMetric(tr("Visitantes unicos"), summary.visitors.toString(), tr("Correos distintos")))),
                    UiCard(listOf(UiMetric(tr("Conexiones"), summary.connections.toString(), tr("Autorizadas en la red")))),
                    UiCard(listOf(UiMetric(tr("Fallas al autorizar"), summary.failures.toString(), captionTone = if (summary.failures > 0) UiTone.Danger else UiTone.Neutral))),
                ),
            ),
            UiCard(
                title = tr("Conexiones por dia"),
                actions = listOfNotNull(exportButton(permissions, range)),
                children = listOf(UiBarChart(HotspotReports.connectionsPerDay(tenant, from, to))),
            ),
            UiCard(
                title = tr("Registros del periodo"),
                children = listOf(UiTable(HotspotReports.columns, HotspotReports.latest(tenant, from, to, limit = 50), tr("Sin registros en este periodo."))),
            ),
        )
    }

    private fun portal(permissions: ModulePermissions, values: Map<String, String>, domainStatus: String, domainActive: Boolean): List<UiNode> {
        val tone = when {
            domainActive -> UiTone.Success
            domainStatus.startsWith("Error") -> UiTone.Danger
            else -> UiTone.Warning
        }
        val logo = values["logoUrl"].orEmpty().takeIf { it.startsWith("https://") || it.startsWith("http://") }
        return listOfNotNull(
            UiCard(title = tr("Estado del portal"), children = listOf(UiStatus(domainStatus, tone))),
            logo?.let { UiCard(title = tr("Logo actual"), children = listOf(UiImage(it, height = 80, description = tr("Logo del portal")))) },
            // Apariencia, textos legales, tiempos y dominio: each section opens on its own.
            UiCard(listOf(settingsFormNode("portal", "portal", portalFields(), permissions, values).copy(collapsibleSections = true))),
        )
    }

    // Form value key of each view's HTML, kept when saving fails so it can be corrected.
    fun templateKey(viewId: String) = "template_$viewId"

    // The colors form of Diseño, saved through the same route as the templates.
    const val ColorsView = "colores"

    // Form value key of each color, kept when saving fails.
    fun colorKey(key: String) = "color_$key"

    /**
     * Custom HTML of each entry view (see [HotspotTemplates]): what it must contain, the placeholders
     * it may use and the editor. Saving validates it; an empty editor goes back to the built-in design.
     * params "example" fills that view's editor with a starting page, without saving it.
     */
    private fun design(tenant: Connection, permissions: ModulePermissions, params: Map<String, String>, values: Map<String, String>): List<UiNode> {
        val saved = HotspotTemplates.load(tenant)
        val canManage = permissions.has(SettingsManage)
        val intro = UiCard(
            title = tr("Como funciona"),
            children = listOf(
                UiText(
                    tr("Puedes reemplazar el diseno de las paginas de entrada del portal por tu propio HTML (pagina completa, con su CSS). Al guardar se valida que conserve lo que el portal necesita; si falta algo no se guarda y se indica que corregir. Deja el editor vacio y guarda para volver al diseno de BMS. No se permiten scripts, eventos (onclick...), iframes ni formularios que envien a otra direccion. Las demas paginas (codigo por correo, exito, mensajes) usan el diseno de BMS con los colores de arriba."),
                    UiTextStyle.Caption,
                ),
                UiText(
                    tr("En todas: {{error}} (obligatorio; muestra los mensajes) y {{campos_ocultos}} (obligatorio, dentro del formulario; identifica el dispositivo y la red). Opcionales: {{titulo}}, {{subtitulo}}, {{red}}, {{logo_url}}, {{pie}}, {{color_primario}}, {{color_secundario}}, {{color_texto}}."),
                    UiTextStyle.Caption,
                ),
            ),
        )
        val own = HotspotSettingsRepository.load(tenant).portalColors
        fun color(key: String, saved: String?) = values[colorKey(key)] ?: saved.orEmpty()
        val colors = UiCard(
            title = tr("Colores del portal"),
            children = listOfNotNull(
                UiText(
                    tr("Colores de todas las paginas del portal (y de {{color_primario}}, {{color_secundario}} y {{color_texto}} en tu HTML). Deja un color vacio para usar el de la empresa (Empresa); si la empresa no tiene, se usa el de BMS."),
                    UiTextStyle.Caption,
                ),
                UiForm(
                    id = "colors",
                    fields = listOf(
                        UiField("primary", tr("Color principal (botones y fondo)"), "color", readOnly = !canManage, placeholder = tr("Vacio = color de la empresa")),
                        UiField("secondary", tr("Color secundario (degradado del fondo)"), "color", readOnly = !canManage, placeholder = tr("Vacio = color de la empresa")),
                        UiField("text", tr("Color del texto sobre los botones"), "color", readOnly = !canManage, placeholder = tr("Vacio = color de la empresa")),
                    ),
                    values = mapOf(
                        "primary" to color("primary", own.primary),
                        "secondary" to color("secondary", own.secondary),
                        "text" to color("text", own.text),
                    ),
                    submit = if (canManage) UiButton(tr("Guardar colores"), UiRequest("$UiRoute/design", params = mapOf("view" to ColorsView), formId = "colors")) else null,
                    minFieldWidth = 200,
                ),
            ),
        )
        val cards = HotspotTemplates.views.map { view ->
            val requirements = if (view == HotspotTemplates.Form) {
                tr("Formulario: <form action=\"{0}\" method=\"post\"> con los campos name, email, phone y <input type=\"checkbox\" name=\"terms\" value=\"si\">; birthday es opcional. Valores: {{nombre}}, {{correo}}, {{celular}}, {{cumpleanos}} (lo ya escrito si hubo un error), {{consentimiento}}, {{terminos_url}}, {{privacidad_url}}.", view.action)
            } else {
                tr("Formulario: <form action=\"{0}\" method=\"post\"> con el campo name=\"code\". Valor: {{tipo_codigo}} (\"{1}\").", view.action, tr(view.label))
            }
            val text = values[templateKey(view.id)]
                ?: if (params["example"] == view.id) HotspotTemplates.example(view) else saved[view.id].orEmpty()
            UiCard(
                title = tr(view.label),
                collapsible = true,
                expanded = values.containsKey(templateKey(view.id)) || params["example"] == view.id,
                children = listOfNotNull(
                    UiStatus(if (view.id in saved) tr("Usa tu HTML") else tr("Usa el diseno de BMS"), if (view.id in saved) UiTone.Success else UiTone.Neutral),
                    UiText(requirements, UiTextStyle.Caption),
                    UiForm(
                        id = view.id,
                        fields = listOf(
                            UiField("html", tr("HTML de la pagina"), "textarea", readOnly = !canManage,
                                placeholder = tr("<!DOCTYPE html> ... (vacio = diseno de BMS)")),
                        ),
                        values = mapOf("html" to text),
                        submit = if (canManage) UiButton(tr("Validar y guardar"), UiRequest("$UiRoute/design", params = mapOf("view" to view.id), formId = view.id)) else null,
                    ),
                    if (!canManage) null else UiButton(tr("Cargar un ejemplo"), UiNavigate("design", mapOf("example" to view.id)), style = "secondary"),
                ),
            )
        }
        return listOf(colors, intro) + cards
    }

    private fun settingsForm(
        tenant: Connection,
        permissions: ModulePermissions,
        values: Map<String, String>,
        pickers: List<UiNode>,
        params: Map<String, String>,
        networkValues: Map<String, String>?,
    ): List<UiNode> {
        val settings = HotspotSettingsRepository.load(tenant)
        val controller = HotspotIntegrations.controller(tenant)
        val crmInstalled = HotspotCrmPipeline.isInstalled(tenant)
        val crmStages = HotspotCrmPipeline.stages(tenant)
        // Only the enabled ecosystem's settings are shown; access times and mail work with any of them.
        val controllerCards = when {
            controller == null -> listOf(
                UiCard(
                    title = tr("Controlador de red"),
                    children = listOf(UiStatus(tr("Elige el controlador de red (UniFi u otro) en Integraciones."), UiTone.Warning)),
                )
            )
            controller.id == HotspotIntegrations.NetworkUnifi -> listOf(unifiCard(settings, permissions, values)) + pickers
            else -> emptyList()
        }
        val crmFields = listOf(
            UiField(
                "crmAccessEnabled", tr("Validar el codigo de cliente con un estado del CRM"), "boolean",
                help = tr("Al activarlo, el codigo de cliente solo funciona si su lead esta en el estado seleccionado."),
            ),
            UiField(
                "crmStage", tr("Estado del embudo que permite el acceso"), "dropdown", required = settings.crmAccessEnabled,
                options = crmStages.map { it.name },
                help = if (crmInstalled) tr("Se valida el estado actual del lead y, si tiene fechas, la fecha de inicio y fin.") else tr("Instala CRM para seleccionar un estado."),
                visibleWhen = UiFieldCondition("crmAccessEnabled", listOf("true")),
            ),
            UiField(
                "crmScheduleMode", tr("Programacion de la revision"), "select", required = true,
                options = listOf(msg("Diario a una hora"), msg("Cada intervalo")),
            ),
            UiField(
                "crmCheckTime", tr("Hora diaria de revision (HH:mm)"), required = true,
                visibleWhen = UiFieldCondition("crmScheduleMode", listOf("Diario a una hora")),
            ),
            UiField(
                "crmCheckIntervalMinutes", tr("Intervalo de revision (minutos)"), "number", required = true,
                visibleWhen = UiFieldCondition("crmScheduleMode", listOf("Cada intervalo")),
            ),
        ).map { field -> if (field.key == "crmAccessEnabled" && !crmInstalled) field.copy(readOnly = true) else field }
        val crmCard = UiCard(
            title = tr("Integracion con CRM"),
            children = listOfNotNull(
                if (crmInstalled) null else UiStatus(tr("CRM no esta instalado en esta empresa; los codigos de cliente permanecen desactivados."), UiTone.Warning),
                settingsFormNode("settings", "crm", crmFields, permissions, values),
            ),
            collapsible = true,
            expanded = false,
        )
        // The controller and the networks stay open; the rest is configured once and starts closed.
        return controllerCards + networks(tenant, settings, controller, permissions, params, networkValues) +
            crmCard +
            UiCard(title = tr("Acceso"), children = listOf(settingsFormNode("settings", "times", accessFields(), permissions, values)), collapsible = true, expanded = false) +
            UiCard(title = tr("Correo"), children = listOf(settingsFormNode("settings", "mail", mailFields(), permissions, values)), collapsible = true, expanded = false)
    }

    /**
     * UniFi connection. Buscar consolas shows only while no console is saved; with a console but no
     * site it offers to pick the site. Clearing the console ID and saving brings the search back.
     */
    private fun unifiCard(settings: HotspotSettings, permissions: ModulePermissions, values: Map<String, String>): UiNode {
        val canManage = permissions.has(SettingsManage)
        val consoleId = settings.unifiConsoleId?.takeIf { it.isNotBlank() }
        val (hint, action) = when {
            !canManage -> null to null
            consoleId == null -> tr("Escribe la API key y pulsa Buscar consolas para elegir la consola y el sitio por su nombre.") to
                UiButton(tr("Buscar consolas"), unifiStep("consoles"))
            settings.unifiSiteId.isNullOrBlank() -> tr("Falta elegir el sitio de la consola.") to UiButton(tr("Elegir sitio"), unifiStep("console", consoleId))
            else -> null to null
        }
        return UiCard(
            title = tr("Conexion con UniFi"),
            actions = listOfNotNull(action),
            children = listOfNotNull(
                hint?.let { UiText(it, UiTextStyle.Caption) },
                settingsFormNode("settings", "unifi", unifiFields(), permissions, values),
            ),
        )
    }

    /**
     * WiFi networks and their access type. ?networkId=<id> opens a network in the form; saving and
     * deleting post to the settings route with ?network=save|delete&id=<id>. The SSID is picked from
     * the networks of the enabled controller; without one it is typed.
     */
    private fun networks(
        tenant: Connection,
        settings: HotspotSettings,
        controller: NetworkController?,
        permissions: ModulePermissions,
        params: Map<String, String>,
        submitted: Map<String, String>?,
    ): List<UiNode> {
        val canManage = permissions.has(SettingsManage)
        val networks = HotspotNetworks.list(tenant)
        val sourceNames = (listOf(HotspotPasses.hotspotCustomers) + HotspotPasses.sources(tenant)).associate { it.id to tr(it.name) }
        val list = UiCard(
            title = tr("Redes WiFi"),
            children = listOf(
                UiText(
                    tr("Segun la red a la que se conecta el visitante, el portal muestra el formulario de registro o pide su codigo de cliente o de usuario. Las redes que no esten en la lista usan el formulario."),
                    UiTextStyle.Caption,
                ),
                if (networks.isEmpty()) {
                    UiText(tr("Aun no hay redes configuradas."), UiTextStyle.Caption)
                } else {
                    UiGrid(minItemWidth = 260, children = networks.map { networkCard(it, sourceNames, canManage) })
                },
            ),
        )
        if (!canManage) return listOf(list)
        val editing = params["networkId"]?.let { id -> networks.firstOrNull { it.id.toString() == id } }
        val console = consoleSsids(settings, controller)
        // Networks already configured are left out, except the one being edited.
        val available = console.ssids?.let { ssids ->
            val taken = networks.map { it.ssid }.toSet() - setOfNotNull(editing?.ssid)
            (ssids + listOfNotNull(editing?.ssid)).distinct().filter { it !in taken }
        }
        val title = editing?.let { tr("Editar red {0}", it.ssid) } ?: tr("Agregar red")
        if (editing == null && available != null && available.isEmpty()) {
            return listOf(list, UiCard(title = title, children = listOf(UiText(tr("Todas las redes de la consola ya estan configuradas."), UiTextStyle.Caption))))
        }
        val crmAccessEnabled = settings.crmAccessEnabled && HotspotCrmPipeline.isInstalled(tenant)
        val sources = if (crmAccessEnabled) HotspotPasses.choosable(tenant) else emptyList()
        val usersEnabled = HotspotIntegrations.isEnabled(tenant, HotspotIntegrations.Users)
        val form = UiCard(
            title = title,
            actions = if (editing == null) emptyList() else listOf(UiButton(tr("Cancelar"), UiNavigate("settings"), style = "secondary")),
            children = listOfNotNull(
                console.problem?.let { UiStatus(it, UiTone.Warning) },
                UiForm(
                    id = "network",
                    fields = listOf(ssidField(available, controller)) + networkFields(sources, usersEnabled, crmAccessEnabled),
                    values = submitted ?: HotspotNetworks.toFormValues(editing),
                    submit = UiButton(tr("Guardar red"), networkAction("save", editing?.id?.toString(), withForm = true)),
                ),
            ),
        )
        return listOf(list, form)
    }

    // SSIDs read from the controller (null when they cannot be read) and why they could not be read.
    private data class ConsoleSsids(val ssids: List<String>?, val problem: String?)

    private fun consoleSsids(settings: HotspotSettings, controller: NetworkController?): ConsoleSsids = when {
        controller == null -> ConsoleSsids(null, null)
        !controller.isConfigured(settings) -> ConsoleSsids(null, tr("Configura {0} para elegir la red de la lista de la consola.", controller.name))
        else -> try {
            ConsoleSsids(controller.wifiNetworks(settings), null)
        } catch (failure: NetworkControllerException) {
            ConsoleSsids(null, tr("No se pudieron leer las redes de {0}: {1} Escribe el nombre de la red.", controller.name, failure.message.orEmpty()))
        }
    }

    private fun ssidField(options: List<String>?, controller: NetworkController?): UiField = if (options != null) {
        UiField("ssid", tr("Red WiFi (SSID)"), "dropdown", required = true, options = options,
            help = tr("Redes WiFi de la consola de {0}.", controller?.name.orEmpty()))
    } else {
        UiField("ssid", tr("Nombre de la red (SSID)"), required = true, placeholder = "MiEmpresa_WiFi",
            help = tr("Exactamente como aparece en el controlador de red; distingue mayusculas y minusculas."))
    }

    private fun networkCard(network: HotspotNetwork, sourceNames: Map<String, String>, canManage: Boolean): UiNode {
        fun minutes(value: Int?) = value?.let { tr("{0} minutos", it) } ?: tr("el de la configuracion general")
        return UiCard(
            title = network.ssid,
            children = listOfNotNull(
                UiText(tr("Acceso: {0}", tr(HotspotNetworks.modeLabel(network.accessMode))), UiTextStyle.Caption),
                if (!network.byCustomerCode) null else UiText(
                    tr("Pueden entrar: {0}", network.accessSources.joinToString(", ") { sourceNames[it] ?: it }),
                    UiTextStyle.Caption,
                ),
                UiText(tr("Tiempo de acceso: {0}", minutes(network.accessMinutes)), UiTextStyle.Caption),
                UiText(tr("Tiempo para reconectar: {0}", minutes(network.reconnectMinutes)), UiTextStyle.Caption),
                if (!network.shared) null else UiText(tr("Compartida con empresas vinculadas"), UiTextStyle.Caption),
                if (!canManage) null else UiRow(
                    listOf(
                        UiButton(tr("Editar"), UiNavigate("settings", mapOf("networkId" to network.id.toString())), style = "secondary"),
                        UiButton(
                            tr("Eliminar"),
                            networkAction("delete", network.id.toString(), confirm = tr("Eliminar la red {0}? Volvera a usar el formulario de registro.", network.ssid)),
                            style = "danger",
                        ),
                    )
                ),
            ),
        )
    }

    private fun networkAction(step: String, id: String?, withForm: Boolean = false, confirm: String? = null) = UiRequest(
        route = "$UiRoute/settings",
        params = buildMap {
            put("network", step)
            if (id != null) put("id", id)
        },
        confirm = confirm,
        formId = if (withForm) "network" else null,
    )

    // The access sources only matter on customer-code networks; the form registers customers as type hotspot.
    private fun networkFields(sources: List<PassSource>, usersEnabled: Boolean, crmAccessEnabled: Boolean): List<UiField> {
        val byCustomerCode = UiFieldCondition("accessMode", listOf(HotspotNetworks.modeLabel(HotspotNetworks.CustomerCode)))
        return listOf(
            UiField("accessMode", tr("Tipo de acceso"), "select", required = true, options = HotspotNetworks.modeOptions(usersEnabled, crmAccessEnabled),
                help = if (usersEnabled) {
                    tr("Formulario: registro y codigo por correo; el visitante queda como cliente tipo hotspot. Codigo de cliente: el codigo de su ficha en Clientes. Codigo de usuario: el codigo de Accesos WiFi de cada usuario.")
                } else {
                    tr("Formulario: registro y codigo por correo; el visitante queda como cliente tipo hotspot. Codigo de cliente: el codigo de su ficha en Clientes. Activa la integracion Usuarios para el acceso por codigo de usuario.")
                }),
        ) + sources.map { source ->
            UiField(HotspotNetworks.sourceKey(source.id), tr(source.name), "boolean", help = tr(source.description),
                section = tr("Quien puede conectarse con codigo de cliente"), visibleWhen = byCustomerCode)
        } + listOf(
            UiField("accessMinutes", tr("Tiempo de acceso (minutos)"), "number", section = tr("Tiempos"),
                help = tr("Vacio usa el de la configuracion general. Con un pase de otro modulo la sesion no pasa del fin del pase ni de su saldo.")),
            UiField("reconnectMinutes", tr("Tiempo para reconectar (minutos)"), "number", section = tr("Tiempos"),
                help = tr("Vacio usa el de la configuracion general. 0 = sin espera. No aplica a los pases de otros modulos.")),
            UiField("shared", tr("Compartida con empresas vinculadas"), "boolean", section = tr("Red compartida"),
                help = tr("La misma red existe en el gateway de una empresa vinculada (por ejemplo, creada con UniFi Fabric) y alli tambien esta marcada como compartida. Un dispositivo con acceso vigente en una entra a la otra sin volver a registrarse, solo por el tiempo que le queda.")),
        )
    }

    // UniFi picker steps post the UniFi form so a typed API key survives the round trip.
    fun unifiStep(step: String, id: String? = null) = UiRequest(
        route = "$UiRoute/settings",
        params = buildMap {
            put("unifi", step)
            if (id != null) put("id", id)
        },
        formId = "unifi",
    )

    fun consolePicker(consoles: List<ControllerOption>, selectedId: String?): UiNode = UiCard(
        title = tr("Selecciona la consola"),
        children = if (consoles.isEmpty()) {
            listOf(UiText(tr("La API key no tiene acceso a ninguna consola."), UiTextStyle.Caption))
        } else {
            listOf(
                UiGrid(
                    minItemWidth = 260,
                    children = consoles.map { console ->
                        UiCard(
                            title = console.name,
                            children = listOfNotNull(
                                UiText(console.detail.ifBlank { tr("Consola UniFi") }, UiTextStyle.Caption),
                                UiText(console.id, UiTextStyle.Caption),
                                if (console.id == selectedId) UiStatus(tr("Seleccionada"), UiTone.Success) else null,
                                UiButton(tr("Usar esta consola"), unifiStep("console", console.id)),
                            ),
                        )
                    },
                ),
            )
        },
    )

    fun sitePicker(sites: List<ControllerOption>, selectedId: String?): UiNode = UiCard(
        title = tr("Selecciona el sitio"),
        children = if (sites.isEmpty()) {
            listOf(UiText(tr("Esta consola no tiene sitios disponibles en la API de UniFi."), UiTextStyle.Caption))
        } else {
            listOf(
                UiGrid(
                    minItemWidth = 260,
                    children = sites.map { site ->
                        UiCard(
                            title = site.name,
                            children = listOfNotNull(
                                site.detail.takeIf { it.isNotBlank() }?.let { UiText(it, UiTextStyle.Caption) },
                                UiText(site.id, UiTextStyle.Caption),
                                if (site.id == selectedId) UiStatus(tr("Seleccionado"), UiTone.Success) else null,
                                UiButton(tr("Usar este sitio"), unifiStep("site", site.id)),
                            ),
                        )
                    },
                ),
            )
        },
    )

    // A form of [screen] saved on its own; it only carries the values of its fields.
    private fun settingsFormNode(screen: String, id: String, fields: List<UiField>, permissions: ModulePermissions, values: Map<String, String>): UiForm {
        val canManage = permissions.has(SettingsManage)
        val keys = fields.map { it.key }.toSet()
        return UiForm(
            id = id,
            fields = if (canManage) fields else fields.map { it.copy(readOnly = true) },
            values = values.filterKeys { it in keys },
            submit = if (canManage) UiButton(tr("Guardar cambios"), UiRequest("$UiRoute/$screen", formId = id)) else null,
        )
    }

    private fun exportButton(permissions: ModulePermissions, range: Map<String, String>): UiButton? =
        if (!permissions.has(ReportsExport)) null else UiButton(
            label = tr("Exportar CSV"),
            action = UiDownload(ExportRoute, "hotspot-${range["from"]}-${range["to"]}.csv", range),
            style = "secondary",
        )

    private fun trend(today: Long, yesterday: Long): Pair<String, String> = when {
        yesterday == 0L && today == 0L -> tr("Sin visitantes ayer") to UiTone.Neutral
        yesterday == 0L -> tr("Ayer no hubo visitantes") to UiTone.Success
        else -> {
            val percent = (today - yesterday) * 1000 / yesterday / 10.0
            (if (percent >= 0) tr("+{0}% vs ayer", percent) else tr("{0}% vs ayer", percent)) to (if (percent >= 0) UiTone.Success else UiTone.Danger)
        }
    }

    private fun portalFields() = listOf(
        UiField("domain", tr("Dominio del portal"), section = tr("Dominio"), placeholder = "wifi.miempresa.com",
            help = tr("Crea un registro DNS A/AAAA que apunte a este servidor. HTTPS se configura solo en unos minutos.")),
        UiField("portalUrl", tr("Direccion del portal externo (para el controlador de red)"), readOnly = true, section = tr("Dominio")),
        UiField("networkName", tr("Nombre de la red WiFi"), required = true, section = tr("Apariencia"),
            help = tr("Se muestra cuando el controlador de red no envia el nombre de la red. El acceso por red se configura en Configuracion > Redes WiFi.")),
        UiField("portalTitle", tr("Titulo"), required = true, section = tr("Apariencia")),
        UiField("portalSubtitle", tr("Subtitulo"), section = tr("Apariencia")),
        UiField("logoUrl", tr("URL del logo"), "url", section = tr("Apariencia"),
            help = tr("Los colores del portal se configuran en Diseño; vacios usan los de la empresa (o los de BMS).")),
        UiField("consentText", tr("Texto de consentimiento"), "textarea", required = true, section = tr("Textos legales")),
        UiField("termsUrl", tr("URL de terminos"), "url", section = tr("Textos legales")),
        UiField("privacyUrl", tr("URL de privacidad"), "url", section = tr("Textos legales")),
        UiField("footerText", tr("Pie de pagina"), section = tr("Textos legales")),
        UiField("successUrl", tr("Abrir al conectarse (URLs)"), section = tr("Textos legales"), placeholder = "https://miempresa.com, https://promo.miempresa.com",
            help = tr("Una o varias direcciones separadas por coma. Al conectarse se abre una de ellas al azar.")),
    )

    // Only shown while UniFi is the enabled network controller.
    fun unifiFields() = listOf(
        UiField("unifiConsoleId", tr("ID de consola"), help = tr("Para cambiar de consola borralo y guarda; volvera a aparecer Buscar consolas.")),
        UiField("unifiSiteId", tr("ID de sitio"), help = tr("Se llena al elegir el sitio despues de la consola.")),
        UiField("unifiApiKey", tr("API key de UniFi"), "password", help = tr("Si ya hay una clave guardada se muestra oculta; dejala asi para conservarla.")),
    )

    private fun accessFields() = listOf(
        UiField("accessMinutes", tr("Tiempo de acceso (minutos)"), "number", required = true),
        UiField("reconnectMinutes", tr("Tiempo para reconectar (minutos)"), "number", required = true,
            help = tr("Un correo no puede volver a conectarse hasta que pase este tiempo desde su ultimo acceso. 0 = sin espera.")),
        UiField("codeTtlMinutes", tr("Vigencia del codigo (minutos)"), "number", required = true),
        UiField("newCustomersPerHour", tr("Clientes nuevos por hora (maximo)"), "number", required = true,
            help = tr("Freno contra abusos: cuantas direcciones nuevas puede registrar el formulario por hora en toda la empresa. Los clientes que ya existen no cuentan.")),
        UiField("dataLimitMb", tr("Limite de datos (MB, 0 = sin limite)"), "number"),
        UiField("rxKbps", tr("Descarga (kbps, 0 = sin limite)"), "number"),
        UiField("txKbps", tr("Subida (kbps, 0 = sin limite)"), "number"),
    )

    // Codes are emailed the same way whatever network controller is enabled.
    private fun mailFields() = listOf(
        UiField("mailSource", tr("Correo de envio"), readOnly = true,
            help = tr("Los codigos se envian con el SMTP de la empresa (lo configura el dueno de la empresa); si no hay, con el del servidor.")),
        UiField("emailSubject", tr("Asunto del correo"), required = true),
    )
}
