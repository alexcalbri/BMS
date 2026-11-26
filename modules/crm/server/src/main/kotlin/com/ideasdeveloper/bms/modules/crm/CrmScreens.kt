package com.ideasdeveloper.bms.modules.crm

import com.ideasdeveloper.bms.server.i18n.msg
import com.ideasdeveloper.bms.server.i18n.tr
import com.ideasdeveloper.bms.server.modules.ModulePermissions
import com.ideasdeveloper.bms.server.modules.SmtpSettings
import com.ideasdeveloper.bms.ui.UiBadge
import com.ideasdeveloper.bms.ui.UiBoard
import com.ideasdeveloper.bms.ui.UiBoardCard
import com.ideasdeveloper.bms.ui.UiBoardColumn
import com.ideasdeveloper.bms.ui.UiButton
import com.ideasdeveloper.bms.ui.UiCard
import com.ideasdeveloper.bms.ui.UiField
import com.ideasdeveloper.bms.ui.UiFieldCondition
import com.ideasdeveloper.bms.ui.UiFieldValues
import com.ideasdeveloper.bms.ui.UiFiles
import com.ideasdeveloper.bms.ui.UiSuggest
import com.ideasdeveloper.bms.ui.UiSuggestions
import com.ideasdeveloper.bms.ui.UiSuggestion
import com.ideasdeveloper.bms.ui.UiForm
import com.ideasdeveloper.bms.ui.UiGrid
import com.ideasdeveloper.bms.ui.UiList
import com.ideasdeveloper.bms.ui.UiListItem
import com.ideasdeveloper.bms.ui.UiMetric
import com.ideasdeveloper.bms.ui.UiNavItem
import com.ideasdeveloper.bms.ui.UiNavigate
import com.ideasdeveloper.bms.ui.UiOpenExtensions
import com.ideasdeveloper.bms.ui.UiOpenModule
import com.ideasdeveloper.bms.ui.UiNavigation
import com.ideasdeveloper.bms.ui.UiNode
import com.ideasdeveloper.bms.ui.UiNotice
import com.ideasdeveloper.bms.ui.UiRequest
import com.ideasdeveloper.bms.ui.UiRow
import com.ideasdeveloper.bms.ui.UiScreen
import com.ideasdeveloper.bms.ui.UiStatus
import com.ideasdeveloper.bms.ui.UiText
import com.ideasdeveloper.bms.ui.UiTextStyle
import com.ideasdeveloper.bms.ui.UiTimeline
import com.ideasdeveloper.bms.ui.UiTimelineEntry
import com.ideasdeveloper.bms.ui.UiTone
import java.sql.Connection
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

/** CRM screens built from generic UI pieces; the app draws them with the user's theme. */
object CrmScreens {
    const val UiRoute = "/modules/crm/ui"

    const val View = "crm.view"
    const val Manage = "crm.leads.manage"
    const val Delete = "crm.leads.delete"
    const val Reception = "crm.reception"
    const val SeeAll = "crm.leads.all"
    const val Settings = "crm.settings"
    const val Assign = "crm.assign"

    val MentionedBadge = msg("Mencionado")
    private const val BoardPerStage = 30
    private const val PageSize = 50
    private val AllOption = msg("Todos")
    private val MineOption = msg("Mis leads")
    private val timeFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    // [alsoPermission] opens the section too (assigners reach the suggestions in "Sin calificar").
    private data class Section(val id: String, val label: String, val permission: String, val alsoPermission: String? = null) {
        fun allows(permissions: ModulePermissions) = permissions.has(permission) || alsoPermission != null && permissions.has(alsoPermission)
    }

    // The menu: Nuevo lead, Leads (pipeline and list), Sin calificar, Seguimientos and Configuracion (tabs).
    private val sections = listOf(
        Section("new", msg("Nuevo lead"), Manage),
        Section("leads", msg("Leads"), View),
        // Id "reception" and the Reception permission stay: "Recepcion" is a role.
        Section("reception", msg("Sin calificar"), Reception, alsoPermission = Assign),
        Section("followups", msg("Seguimientos"), View),
        Section("settings", msg("Configuracion"), Settings),
    )

    /** A tab of "Configuracion": [screen] is the id that opens it (the old section ids), [permission] who sees the tab. */
    class SettingsTab(val screen: String, val label: String, val permission: String)

    private val settingsTabs = listOf(
        SettingsTab("settings", msg("General"), Settings),
        SettingsTab("automations", msg("Automatizaciones"), Settings),
        SettingsTab("team", msg("Equipo"), Settings),
        SettingsTab("mail", msg("Correo"), Settings),
        SettingsTab("templates", msg("Plantillas"), Settings),
        SettingsTab("spam", msg("Anti-spam"), Settings),
        SettingsTab("integrations", msg("Integraciones"), Settings),
        SettingsTab("import", msg("Importar"), Settings),
    )

    // Screens outside the menu: the lead detail, the "pipeline" view of Leads, the other tabs of Configuracion
    // (their old section ids) and the screens opened from a lead or an import.
    private val hidden = mapOf("lead" to View, "pipeline" to View, "properties" to Manage, "rooms" to Manage, "importjob" to Settings) +
        settingsTabs.filter { it.screen != "settings" }.associate { it.screen to it.permission }

    // Old screen ids that still open: "Asignaciones" now lives in "Sin calificar" and "Acciones en lote" in the list of Leads.
    private val aliases = mapOf("assignments" to "reception", "bulk" to "leads")

    fun canonical(id: String) = aliases[id] ?: id

    // View of Leads an old route opens ("pipeline", "bulk"); null: the one the user used last.
    private val forcedViews = mapOf("pipeline" to "pipeline", "bulk" to "list")

    fun forcedView(id: String): String? = forcedViews[id]

    const val ViewKey = "view"
    const val ViewPipeline = "pipeline"
    const val ViewList = "list"
    private val leadViewIds = setOf(ViewPipeline, ViewList)

    /** The view sent with the request wins over the remembered one; none (or an unknown one) keeps the remembered one. */
    fun resolveLeadsView(param: String?, stored: String): String = param?.takeIf { it in leadViewIds } ?: stored.takeIf { it in leadViewIds } ?: ViewPipeline

    // The tab of Configuracion a screen belongs to (null: it is not in Configuracion).
    fun settingsTabOf(id: String): String? = canonical(id).let { screen -> if (screen == "importjob") "import" else screen.takeIf { s -> settingsTabs.any { it.screen == s } } }

    /** Screens of the tabs of Configuracion the user sees (a tab with its own permission hides without it). */
    fun visibleTabs(permissions: ModulePermissions): List<String> = settingsTabs.filter { permissions.has(it.permission) }.map { it.screen }

    // Menu item a screen belongs to.
    private fun menuItemOf(screen: String): String = when {
        screen in setOf("pipeline", "leads", "lead", "rooms", "properties") -> "leads"
        settingsTabOf(screen) != null -> "settings"
        else -> CrmScreenProviders.section(screen)?.let { (provider, section) ->
            // Una seccion oculta de una extension marca su propio id, o la primera del menu del mismo proveedor.
            if (section.inMenu) section.id else CrmScreenProviders.sections(provider).firstOrNull { it.inMenu }?.id ?: section.id
        } ?: screen
    }

    /** Si [id] es una pantalla propia del CRM (las de las extensiones no cuentan). */
    internal fun isOwnScreen(id: String) = canonical(id).let { screen -> sections.any { it.id == screen } || screen in hidden }

    fun isScreen(id: String) = isOwnScreen(id) || CrmScreenProviders.section(canonical(id)) != null

    fun permissionFor(screen: String): String? = canonical(screen).let { id ->
        sections.firstOrNull { it.id == id }?.permission ?: hidden[id] ?: CrmScreenProviders.section(id)?.second?.permission
    }

    // First screen a user opens: the leads, then what is waiting for them, then the rest.
    private val homeOrder = listOf("leads", "reception", "followups", "new", "settings")

    fun home(permissions: ModulePermissions): String? = homeOrder.firstOrNull { canOpen(it, permissions) }

    // Whether the user may open the screen [id] (a section with an alternative permission counts both).
    fun canOpen(id: String, permissions: ModulePermissions): Boolean {
        val screen = canonical(id)
        sections.firstOrNull { it.id == screen }?.let { return it.allows(permissions) }
        hidden[screen]?.let { return permissions.has(it) }
        return CrmScreenProviders.section(screen)?.second?.let { permissions.has(it.permission) } ?: false
    }

    // Ids of the sections the user sees in the menu ([interestsEnabled]: no section depends on it today).
    fun menu(permissions: ModulePermissions, interestsEnabled: Boolean): List<String> =
        sections.filter { it.allows(permissions) }.map { it.id }

    /** Everything a screen needs, loaded once per request. */
    class Context(
        val tenant: Connection,
        val permissions: ModulePermissions,
        val scope: CrmScope,
        val stages: List<CrmStage>,
        val interests: List<CrmInterest>,
        val users: List<CrmUser>,
        // Server SMTP, used when the company sends with it.
        val serverMail: SmtpSettings? = null,
        // Public origin of this server (https://host), for the webhook URLs shown in Integraciones.
        val publicBase: String = "",
        val today: LocalDate = LocalDate.now(),
        // Custom lead fields defined in Configuracion.
        val fields: List<CrmField> = emptyList(),
        // Sections of the custom fields and the date fields that are the check-in and check-out.
        val sections: List<CrmFieldSection> = emptyList(),
        val stay: CrmStayMapping = CrmStayMapping.None,
    ) {
        val names = CrmNames(users, interests)
        val interestsEnabled get() = scope.interestsEnabled
        fun stage(id: UUID) = stages.firstOrNull { it.id == id }

        // Stage names as the viewer reads them (dynamic translation, I18N.md §8); the stored names stay the values. One call per request.
        private val stageLabels: Map<String, String> by lazy {
            val names = stages.map { it.name }
            names.zip(CrmTexts.labels(tenant, permissions.companyCode, names)).toMap()
        }

        /** The name of a stage in the viewer's language (the name itself when there is no translation). */
        fun stageLabel(name: String): String = stageLabels[name] ?: name

        /** [UiField.optionLabels] for [options] that mix stage names and fixed texts (null when nothing changes). */
        fun optionLabels(options: List<String>): List<String>? = options.map { stageLabels[it] ?: tr(it) }.takeIf { it != options }

        // Interests the user may see and assign (all of them with "see all").
        val usableInterests get() = interests.filter { scope.seesAll || it.id in scope.interests }

        // Who may choose a lead's responsible user: assigners and users that see every lead.
        val canAssign get() = permissions.has(Assign) || scope.seesAll

        // The leads where the user has an active mention (one read, only when needed).
        val mentionedLeadIds: Set<UUID> by lazy { scope.userId?.let { CrmMentions.leadIds(tenant, it) }.orEmpty() }

        /** How the user reaches [lead]: with their scope (full) or only through a mention (read and notes). Writes ask [CrmMentions.LeadAccess.canWrite]. */
        fun readAccess(lead: CrmLead): CrmMentions.LeadAccess =
            if (scope.canSee(lead.interestId, lead.ownerUserIds)) CrmMentions.LeadAccess.Full
            else CrmMentions.leadAccess(scope, lead.interestId, lead.ownerUserIds, lead.id in mentionedLeadIds, lead.archivedAt != null)

        // Users a note can mention: anybody with access to the CRM.
        val mentionable: List<CrmUser> by lazy { CrmRepository.mentionableUsers(tenant) }

        companion object {
            fun load(tenant: Connection, permissions: ModulePermissions, serverMail: SmtpSettings? = null, publicBase: String = "") = Context(
                tenant, permissions, CrmRepository.scope(tenant, permissions),
                CrmRepository.stages(tenant), CrmRepository.interests(tenant), CrmRepository.users(tenant), serverMail, publicBase = publicBase,
                fields = CrmFields.all(tenant), sections = CrmFieldSections.all(tenant), stay = CrmStay.load(tenant),
            )
        }
    }

    fun build(
        requested: String,
        context: Context,
        params: Map<String, String>,
        notice: UiNotice? = null,
        submitted: Map<String, String>? = null,
    ): UiScreen {
        val screen = canonical(requested)
        val permission = permissionFor(screen)
            ?: return frame(context, screen, tr("Lead"), UiNotice(tr("Esa pantalla no existe."), UiTone.Warning), emptyList())
        val section = sections.firstOrNull { it.id == screen }
        CrmScreenProviders.section(screen)?.let { (provider, extensionSection) ->
            if (!context.permissions.has(permission)) {
                return frame(context, screen, extensionSection.label, UiNotice(tr("No tienes permiso para ver esta seccion."), UiTone.Danger), emptyList())
            }
            if (!CrmScreenProviders.enabled(context.tenant, provider)) {
                return frame(context, screen, extensionSection.label, UiNotice(CrmExtensions.inactiveMessage(provider.extensionId), UiTone.Warning), emptyList())
            }
            return try {
                frame(context, screen, extensionSection.label, notice, provider.screen(context, screen, params, submitted))
            } catch (failure: Exception) {
                org.slf4j.LoggerFactory.getLogger("CrmScreenProviders").warn("Fallo la pantalla {} de la extension {}.", screen, provider.extensionId, failure)
                frame(context, screen, extensionSection.label, UiNotice(tr("No se pudo abrir esta pantalla de la extension."), UiTone.Danger), emptyList())
            }
        }
        val title = when {
            section != null -> tr(section.label)
            screen == "pipeline" -> tr("Leads")
            settingsTabOf(screen) != null -> tr("Configuracion")
            else -> tr("Lead")
        }
        val allowed = section?.allows(context.permissions) ?: context.permissions.has(permission)
        // Old routes: "pipeline" and "bulk" open their own view of Leads; the other screens ignore it.
        val forced = forcedView(requested)
        if (!allowed) {
            return frame(context, screen, title, UiNotice(tr("No tienes permiso para ver esta seccion."), UiTone.Danger), emptyList())
        }
        if (screen == "rooms") {
            val lead = params["lead"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }?.let { CrmRepository.lead(context.tenant, it) }
                ?.takeIf { context.scope.canSee(it.interestId, it.ownerUserIds) }
                ?: return frame(context, "leads", tr("Habitaciones"), UiNotice(tr("Ese lead no existe o no tienes acceso."), UiTone.Warning), emptyList())
            return frame(context, "leads", tr("Habitaciones"), notice, CrmCloudbedsScreens.rooms(context, lead, params), showTitle = false)
        }
        if (screen == "properties") {
            val lead = params["lead"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }?.let { CrmRepository.lead(context.tenant, it) }
                ?.takeIf { context.scope.canSee(it.interestId, it.ownerUserIds) }
                ?: return frame(context, "leads", tr("Propiedades"), UiNotice(tr("Ese lead no existe o no tienes acceso."), UiTone.Warning), emptyList())
            return frame(context, "leads", tr("Propiedades"), notice, CrmWordPressScreens.properties(context, lead, params), showTitle = false)
        }
        if (screen == "lead") {
            // Opening the lead also works through a mention (read-only, with notes): see CrmMentions.LeadAccess.
            val lead = params["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }?.let { CrmRepository.lead(context.tenant, it) }
                ?.takeIf { context.readAccess(it).canRead }
                ?: return frame(context, "leads", tr("Lead"), notice ?: UiNotice(tr("Ese lead no existe o no tienes acceso."), UiTone.Warning), leadViews(context, null, emptyMap()))
            return frame(context, "leads", lead.name, notice, leadDetail(context, lead, submitted), showTitle = false)
        }
        val body = when (screen) {
            "pipeline", "leads" -> leadViews(context, forced, params)
            "reception" -> reception(context, params)
            "followups" -> followUps(context, params)
            "new" -> newLead(context, submitted)
            "mail" -> tabBar(context, screen) + CrmAdminScreens.mail(context, submitted)
            "templates" -> tabBar(context, screen) + CrmAdminScreens.templates(context, params, submitted)
            "spam" -> tabBar(context, screen) + CrmAdminScreens.spam(context, submitted)
            "team" -> tabBar(context, screen) + CrmAdminScreens.team(context)
            "integrations" -> tabBar(context, screen) + CrmIntegrationScreens.integrations(context, params, submitted)
            "import" -> tabBar(context, screen) + CrmImportScreens.imports(context, params)
            "importjob" -> tabBar(context, screen) + CrmImportScreens.job(context, params)
            "automations" -> tabBar(context, screen) + automations(context, params, submitted)
            else -> tabBar(context, screen) + settings(context, params, submitted)
        }
        return frame(context, screen, title, notice, body)
    }

    private fun frame(context: Context, selected: String, title: String, notice: UiNotice?, body: List<UiNode>, showTitle: Boolean = true) = UiScreen(
        title = title,
        navigation = UiNavigation(
            title = CrmModule.displayName,
            items = menuItems(context),
            selected = menuItemOf(selected),
        ),
        notice = notice,
        body = (if (showTitle) listOf<UiNode>(UiText(title, UiTextStyle.Title)) else emptyList()) + body,
    )

    // Menu items: the own sections, with the active extensions' sections (that the user may open) before "settings".
    private fun menuItems(context: Context): List<UiNavItem> {
        val own = menu(context.permissions, context.interestsEnabled)
        val ownItems = sections.filter { it.id in own }.map { it.id to UiNavItem(it.id, tr(it.label), UiNavigate(it.id)) }
        val extra = CrmScreenProviders.active(context.tenant).flatMap { provider ->
            CrmScreenProviders.sections(provider).filter { it.inMenu && context.permissions.has(it.permission) }
                .map { UiNavItem(it.id, it.label, UiNavigate(it.id)) }
        }
        val settingsAt = ownItems.indexOfFirst { it.first == "settings" }.takeIf { it >= 0 } ?: ownItems.size
        return ownItems.map { it.second }.let { it.take(settingsAt) + extra + it.drop(settingsAt) }
    }

    // Tabs of Configuracion: a row of buttons (the module navigation has one level), the current one in primary.
    private fun tabBar(context: Context, current: String): List<UiNode> {
        val selected = settingsTabOf(current)
        val tabs = settingsTabs.filter { context.permissions.has(it.permission) }
        val buttons = tabs.map { UiButton(tr(it.label), UiNavigate(it.screen), style = if (it.screen == selected) "primary" else "secondary") }
        // Ultima pestana: abre la pantalla nativa de extensiones filtrada al CRM (apps viejas la ignoran).
        val extensions = UiButton(tr("Extensiones"), UiOpenExtensions(CrmModule.id), style = "secondary").takeIf { context.permissions.has(Settings) }
        return listOf(UiRow(buttons + listOfNotNull(extensions)))
    }

    // Tab Automatizaciones: the rules of the company (CrmAutomationScreens) and the exit rule.
    private fun automations(context: Context, params: Map<String, String>, submitted: Map<String, String>?): List<UiNode> =
        CrmAutomationScreens.tab(context, params, submitted) + CrmExitScreens.card(context, submitted)

    // ---- Filters ----

    // Filter values travel as the names shown in the dropdowns (fInterest, fOwner, fStage, q).
    fun filter(context: Context, params: Map<String, String>): CrmLeadFilter {
        val interest = params["fInterest"].orEmpty()
        val owner = params["fOwner"].orEmpty()
        return CrmLeadFilter(
            interestId = context.interests.firstOrNull { it.name == interest }?.id,
            withoutInterest = interest == CrmForms.NoInterest,
            ownerUserId = if (owner == MineOption) context.scope.userId else context.users.firstOrNull { it.name == owner }?.id,
            unassigned = owner == CrmForms.Unassigned,
            stageId = context.stages.firstOrNull { it.name == params["fStage"] }?.id,
            search = params["q"].orEmpty().take(100),
        )
    }

    // Request parameter with the assigned users (ids separated by comma) a lead form was drawn with.
    const val OwnersBeforeKey = "owners0"

    // Fields of the stage editor with the manual translation of the name, one per language ("stageName_en").
    const val StageTranslationPrefix = "stageName_"

    private const val ShowArchivedKey = "showArchived"
    private const val ArchivedColumn = "archived"

    // [keep]: the filters and page of the view, so the user comes back to the same place.
    private fun restoreRequest(lead: CrmLead, from: String?, keep: Map<String, String> = emptyMap()) =
        UiRequest(UiRoute + "/lead", params = keep + mapOf("id" to lead.id.toString(), "op" to "restore") + listOfNotNull(from?.let { "from" to it }))

    /**
     * Cards and list rows only open the lead, so the archived ones of a view get their "Restaurar" buttons
     * in a card of their own below it (null when there is nothing to restore or the user cannot).
     */
    private fun restoreCard(context: Context, archived: List<CrmLead>, from: String, keep: Map<String, String>): UiNode? {
        if (!context.permissions.has(Manage) || archived.isEmpty()) return null
        return UiCard(
            archived.map { lead ->
                UiRow(listOf(UiText(lead.name), UiButton(tr("Restaurar"), restoreRequest(lead, from, keep), style = "secondary")))
            },
            title = tr("Restaurar archivados ({0})", archived.size),
            collapsible = true,
            expanded = false,
        )
    }

    private fun filterParams(params: Map<String, String>) = params.filterKeys { it in setOf("fInterest", "fOwner", "fStage", "q", ShowArchivedKey) }.filterValues { it.isNotBlank() }

    /** The checkbox sent with the filters wins over the remembered one; none sent keeps the remembered one. */
    fun resolveShowArchived(param: String?, stored: Boolean): Boolean = param?.let { it == "true" } ?: stored

    /**
     * "Mostrar archivados" of the lead views. Remembered per user in crm_user_settings; the server owner
     * has no user row, so it only lasts while the filter is sent.
     */
    private fun showArchived(context: Context, params: Map<String, String>): Boolean {
        val userId = context.scope.userId
        val stored = userId?.let { CrmRepository.showArchived(context.tenant, it) } ?: false
        val chosen = resolveShowArchived(params[ShowArchivedKey], stored)
        if (userId != null && chosen != stored) CrmRepository.setShowArchived(context.tenant, userId, chosen)
        return chosen
    }


    private fun filterForm(
        context: Context,
        screen: String,
        params: Map<String, String>,
        withStage: Boolean,
        withSearch: Boolean,
        // Pipeline and list: the "Mostrar archivados" checkbox, with the state [archivedShown].
        archivedShown: Boolean? = null,
        // Sent along with the form values by "Filtrar" (the view of Leads).
        submitParams: Map<String, String> = emptyMap(),
    ): UiNode {
        val fields = buildList {
            if (withSearch) add(UiField("q", tr("Buscar"), placeholder = tr("Nombre, correo, telefono, origen o mensaje")))
            if (context.interestsEnabled) {
                val options = listOf(AllOption) + context.usableInterests.map { it.name } + listOfNotNull(CrmForms.NoInterest.takeIf { context.scope.seesAll || context.scope.reception })
                add(UiField("fInterest", tr("Interes"), type = "dropdown", options = options))
            }
            val owners = listOf(AllOption) + listOfNotNull(MineOption.takeIf { context.scope.userId != null }) + CrmForms.Unassigned + context.users.map { it.name }
            add(UiField("fOwner", tr("Asignado"), type = "dropdown", options = owners))
            if (withStage) {
                val stageOptions = listOf(AllOption) + context.stages.map { it.name }
                add(UiField("fStage", tr("Etapa"), type = "dropdown", options = stageOptions, optionLabels = context.optionLabels(stageOptions)))
            }
            if (archivedShown != null) add(UiField(ShowArchivedKey, tr("Mostrar archivados"), type = "boolean"))
        }
        val values = fields.associate {
            it.key to when {
                it.key == ShowArchivedKey -> archivedShown.toString()
                else -> params[it.key] ?: if (it.type == "dropdown") AllOption else ""
            }
        }
        return UiCard(
            listOf(
                UiForm(
                    "filters", fields, values,
                    submit = UiButton(tr("Filtrar"), UiNavigate(screen, submitParams, formId = "filters"), style = "secondary"),
                    minFieldWidth = 200,
                ),
            ),
        )
    }

    // ---- Pipeline ----

    /**
     * "Leads": the pipeline and the list share the filters (interest, "Mis leads", search and "Mostrar archivados")
     * and a selector. [forced] is the view an old route asks for; otherwise the one sent with the request, or
     * the last one the user chose.
     */
    private fun leadViews(context: Context, forced: String?, params: Map<String, String>): List<UiNode> {
        val view = leadsView(context, forced, params)
        val showArchived = showArchived(context, params)
        val canManage = context.permissions.has(Manage)
        val selector = UiRow(
            listOfNotNull(
                // The filter form travels with the switch, so both views show the same filters.
                UiButton(tr("Pipeline"), UiNavigate("leads", mapOf(ViewKey to ViewPipeline), formId = "filters"), style = if (view == ViewPipeline) "primary" else "secondary"),
                UiButton(tr("Lista"), UiNavigate("leads", mapOf(ViewKey to ViewList), formId = "filters"), style = if (view == ViewList) "primary" else "secondary"),
                UiButton(tr("Nuevo lead"), UiNavigate("new")).takeIf { canManage },
            ),
        )
        val filters = filterForm(context, "leads", params, withStage = view == ViewList, withSearch = true, archivedShown = showArchived, submitParams = mapOf(ViewKey to view))
        return if (view == ViewPipeline) {
            listOf(summary(context), selector, filters) + pipelineBody(context, params, showArchived)
        } else {
            listOf(selector, filters) + listBody(context, params, showArchived)
        }
    }

    /**
     * The view to show. The user's choice is remembered in crm_user_settings; the server owner has no user
     * row, so it only lasts while the view is sent. An old route that forces a view does not change the choice.
     */
    private fun leadsView(context: Context, forced: String?, params: Map<String, String>): String {
        if (forced != null) return forced
        val userId = context.scope.userId
        val stored = userId?.let { CrmRepository.leadsView(context.tenant, it) } ?: ViewPipeline
        val chosen = resolveLeadsView(params[ViewKey], stored)
        if (userId != null && chosen != stored) CrmRepository.setLeadsView(context.tenant, userId, chosen)
        return chosen
    }

    private fun pipelineBody(context: Context, params: Map<String, String>, showArchived: Boolean): List<UiNode> {
        // The stage filter is the list's: the board always shows every stage.
        // The board also shows the leads the user was mentioned in (read-only: moving them is refused).
        val filter = filter(context, params - "fStage").copy(withMentions = true)
        val (byStage, totals) = CrmRepository.board(context.tenant, context.scope, filter, BoardPerStage)
        val canMove = context.permissions.has(Manage)
        val keep = filterParams(params) - "fStage" + (ShowArchivedKey to showArchived.toString())
        val stageColumns = context.stages.map { stage ->
            val cards = byStage[stage.id].orEmpty()
            val total = totals[stage.id] ?: 0
            UiBoardColumn(
                id = stage.id.toString(),
                title = context.stageLabel(stage.name),
                tone = stage.tone.id,
                caption = if (total > cards.size) tr("Mostrando {0} de {1}", cards.size, total) else "",
                cards = cards.map { card(context, it, stage) },
                footer = if (total > cards.size) UiButton(tr("Ver todos"), UiNavigate("bulk", keep + ("fStage" to stage.name)), style = "secondary") else null,
            )
        }
        // After the last stage: leads archived (they are left out of the stage columns above).
        var archivedShownLeads = emptyList<CrmLead>()
        val archivedColumn = if (showArchived) {
            val archivedFilter = filter.copy(archived = true)
            val archivedTotal = CrmRepository.count(context.tenant, context.scope, archivedFilter)
            val archived = CrmRepository.leads(context.tenant, context.scope, archivedFilter, BoardPerStage, 0)
            archivedShownLeads = archived
            listOf(
                UiBoardColumn(
                    id = ArchivedColumn,
                    title = tr("Archivados"),
                    tone = StageTone.Neutral.id,
                    caption = if (archivedTotal > archived.size) tr("Mostrando {0} de {1}", archived.size, archivedTotal) else "",
                    cards = archived.map { card(context, it, context.stage(it.stageId)) },
                    footer = if (archivedTotal > archived.size) UiButton(tr("Ver todos"), UiNavigate("bulk", keep), style = "secondary") else null,
                ),
            )
        } else {
            emptyList()
        }
        return listOfNotNull(
            UiBoard(
                columns = stageColumns + archivedColumn,
                move = if (canMove) UiRequest(UiRoute + "/pipeline", params = keep + ("op" to "move")) else null,
                emptyText = tr("Crea etapas en Configuracion."),
                actions = CrmBoardActions.declared(context, keep, archivedColumn = showArchived),
            ),
            restoreCard(context, archivedShownLeads.filter { context.readAccess(it).canWrite }, "pipeline", keep),
        )
    }

    private fun summary(context: Context): UiNode {
        val summary = CrmRepository.summary(context.tenant, context.scope, context.today.minusDays(29), context.today)
        return UiGrid(
            listOf(
                UiCard(listOf(UiMetric(tr("Leads abiertos"), summary.open.toString()))),
                UiCard(listOf(UiMetric(tr("Nuevos (30 dias)"), summary.createdSince.toString()))),
                UiCard(listOf(UiMetric(tr("Ganados (30 dias)"), summary.wonSince.toString(), captionTone = UiTone.Success))),
                UiCard(
                    listOf(
                        UiMetric(
                            tr("Seguimientos vencidos"), summary.overdue.toString(),
                            caption = if (summary.overdue > 0) tr("Revisa Seguimientos") else tr("Al dia"),
                            captionTone = if (summary.overdue > 0) UiTone.Danger else UiTone.Success,
                        ),
                    ),
                ),
            ),
            minItemWidth = 180,
        )
    }

    private fun badges(context: Context, lead: CrmLead, stage: CrmStage?): List<UiBadge> = buildList {
        if (context.interestsEnabled) {
            add(if (lead.interestId == null) UiBadge(tr(CrmForms.NoInterest), UiTone.Warning) else UiBadge(context.names.interest(lead.interestId), UiTone.Info))
        }
        if (stage?.kind == StageKind.Open) followUpState(lead.followUpOn, context.today)?.let { (label, tone) -> add(UiBadge(label, tone)) }
        lead.suggestedOwnerUserId?.let { add(UiBadge(tr("Sugerido: {0}", context.names.user(it)), UiTone.Info)) }
        if (lead.reviewReason.isNotEmpty()) add(UiBadge(tr("Por revisar"), UiTone.Danger))
        if (lead.archivedAt != null) add(UiBadge(tr("Archivado"), UiTone.Neutral))
        // Seen only because of a mention (not inside the user's scope): read and notes.
        if (context.readAccess(lead) == CrmMentions.LeadAccess.ReadNotes) add(UiBadge(tr(MentionedBadge), UiTone.Info))
    }

    private fun card(context: Context, lead: CrmLead, stage: CrmStage?) = UiBoardCard(
        id = lead.id.toString(),
        title = lead.name,
        subtitle = lead.message.ifBlank { lead.source }.replace('\n', ' ').let { if (it.length > 90) it.take(87) + "..." else it },
        details = listOfNotNull(
            // Initials of everybody assigned; the names are in the lead.
            lead.ownerUserIds.takeIf { it.isNotEmpty() }?.let { tr("Asignados: {0}", CrmAssignees.initialsLabels(context.names, it).joinToString(", ")) },
            // Archived leads sit outside the stages: say where they were.
            stage?.takeIf { lead.archivedAt != null }?.let { tr("Etapa: {0}", context.stageLabel(it.name)) },
            lead.estimatedValue?.let { tr("Valor: {0}", it.toPlainString()) },
        ) + CrmStay.cardLines(lead, CrmStay.effective(context.stay, context.fields), context.fields),
        badges = badges(context, lead, stage),
        action = UiNavigate("lead", mapOf("id" to lead.id.toString())),
    )

    // ---- Lists ----

    private fun listItem(context: Context, lead: CrmLead, suggestion: Boolean = false) = UiListItem(
        title = lead.name,
        subtitle = listOf(
            context.stage(lead.stageId)?.name?.let { context.stageLabel(it) }.orEmpty(), lead.email, lead.phone,
            lead.ownerUserIds.takeIf { it.isNotEmpty() }?.let { CrmAssignees.initialsLabels(context.names, it).joinToString(", ") }.orEmpty(),
            lead.suggestedOwnerUserId.takeIf { suggestion }?.let { tr("Sugerido: {0}", context.names.user(it)) }.orEmpty(),
        ).filter { it.isNotBlank() }.joinToString(" - "),
        trailing = lead.createdAt.atZone(ZoneId.systemDefault()).toLocalDate().toString(),
        badges = badges(context, lead, context.stage(lead.stageId)),
        action = UiNavigate("lead", mapOf("id" to lead.id.toString())),
    )

    private fun pager(screen: String, params: Map<String, String>, page: Int, total: Int, extra: Map<String, String> = emptyMap()): UiNode? {
        val pages = (total + PageSize - 1) / PageSize
        if (pages <= 1) return null
        val keep = filterParams(params) + extra
        return UiRow(
            listOfNotNull(
                UiButton(tr("Anterior"), UiNavigate(screen, keep + ("page" to (page - 1).toString())), style = "secondary").takeIf { page > 1 },
                UiText(tr("Pagina {0} de {1}", page, pages), UiTextStyle.Caption),
                UiButton(tr("Siguiente"), UiNavigate(screen, keep + ("page" to (page + 1).toString())), style = "secondary").takeIf { page < pages },
            ),
        )
    }

    /** The page of the list view the filters and page of [params] show. */
    class ListPage(val leads: List<CrmLead>, val total: Int, val page: Int) {
        /** The leads of the page the user may act on (bulk bar, restore): the ones seen through a mention only are read-only. */
        fun writable(context: Context): List<CrmLead> = leads.filter { context.readAccess(it).canWrite }
    }

    /** What the list view shows for [params]; the bulk bar uses it to know which leads the checkboxes were drawn for. */
    fun listPage(context: Context, params: Map<String, String>, showArchived: Boolean = showArchived(context, params)): ListPage {
        // Archived leads come mixed in, marked "Archivado".
        val filter = filter(context, params).copy(includeArchived = showArchived, withMentions = true)
        val total = CrmRepository.count(context.tenant, context.scope, filter)
        val page = (params["page"]?.toIntOrNull() ?: 1).coerceIn(1, maxOf(1, (total + PageSize - 1) / PageSize))
        return ListPage(CrmRepository.leads(context.tenant, context.scope, filter, PageSize, (page - 1) * PageSize), total, page)
    }

    private fun listBody(context: Context, params: Map<String, String>, showArchived: Boolean): List<UiNode> {
        val listed = listPage(context, params, showArchived)
        val (leads, total, page) = Triple(listed.leads, listed.total, listed.page)
        // Checkboxes and restore buttons only for the leads the user may act on (never the ones seen through a mention).
        val writable = listed.writable(context)
        val keep = filterParams(params) + (ShowArchivedKey to showArchived.toString()) + ("page" to page.toString())
        return listOfNotNull(
            UiCard(
                listOfNotNull(
                    UiList(leads.map { listItem(context, it) }, emptyText = tr("No hay leads con estos filtros.")),
                    pager("leads", params, page, total, mapOf(ViewKey to ViewList)),
                ),
                title = tr("{0} lead(s)", total),
            ),
            // Only with crm.leads.manage: one checkbox per lead of this page and the action.
            CrmImportScreens.bulkCard(context, writable, page, total, keep - "page" + (ViewKey to ViewList)),
            restoreCard(context, writable.filter { it.archivedAt != null }, "list", keep),
        )
    }

    private fun reception(context: Context, params: Map<String, String>): List<UiNode> {
        val filter = CrmLeadFilter(unqualified = true)
        val total = CrmRepository.count(context.tenant, context.scope, filter)
        val page = (params["page"]?.toIntOrNull() ?: 1).coerceIn(1, maxOf(1, (total + PageSize - 1) / PageSize))
        val leads = CrmRepository.leads(context.tenant, context.scope, filter, PageSize, (page - 1) * PageSize)
        // Every pending suggestion in the user's scope, even for leads already qualified or on another page.
        val suggested = if (context.canAssign) CrmRepository.pendingSuggestions(context.tenant, context.scope, 100) else emptyList()
        val suggestions = UiCard(
            suggested.map { lead ->
                val id = mapOf("id" to lead.id.toString(), "from" to "reception")
                UiRow(
                    listOfNotNull(
                        UiText(tr("{0} - Sugerido: {1}", lead.name, context.names.user(lead.suggestedOwnerUserId))),
                        UiButton(tr("Aprobar"), UiRequest(UiRoute + "/lead", params = id + ("op" to "approve"))),
                        UiButton(tr("Descartar"), UiRequest(UiRoute + "/lead", params = id + ("op" to "discard")), style = "secondary"),
                    ),
                )
            },
            title = tr("Sugerencias de asignado ({0})", suggested.size),
        ).takeIf { suggested.isNotEmpty() }
        return listOfNotNull(
            UiText(tr("Leads sin usuarios asignados y sin interes. Abre cada uno y dale un interes o asignalo a alguien: saldra de esta lista en cuanto tenga al menos uno de los dos. El usuario que sugiere el origen del lead se aprueba (se anade a los asignados) o se descarta aqui.")),
            suggestions,
            UiCard(
                listOfNotNull(UiList(leads.map { listItem(context, it, suggestion = context.canAssign) }, emptyText = tr("No hay leads por calificar.")), pager("reception", params, page, total)),
                title = tr("{0} sin calificar", total),
            ),
        )
    }

    private fun followUps(context: Context, params: Map<String, String>): List<UiNode> {
        val filter = filter(context, params)
        val leads = CrmRepository.followUps(context.tenant, context.scope, filter, context.today.plusDays(7), 200)
        val overdue = leads.filter { it.followUpOn!!.isBefore(context.today) }
        val today = leads.filter { it.followUpOn == context.today }
        val upcoming = leads.filter { it.followUpOn!!.isAfter(context.today) }
        fun group(title: String, items: List<CrmLead>, empty: String) = UiCard(
            listOf(UiList(items.map { listItem(context, it).copy(trailing = it.followUpOn.toString()) }, emptyText = empty)),
            title = "$title (${items.size})",
        )
        return listOf(
            filterForm(context, "followups", params, withStage = false, withSearch = false),
            group(tr("Vencidos"), overdue, tr("Sin seguimientos vencidos.")),
            group(tr("Hoy"), today, tr("Nada para hoy.")),
            group(tr("Proximos 7 dias"), upcoming, tr("Sin seguimientos en la proxima semana.")),
        )
    }

    // ---- Lead form ----

    // Route the new lead form asks while the contact is typed, to reuse an existing customer.
    const val CustomerSuggestRoute = "$UiRoute/suggest/customers"

    // Route the note of a lead asks while "@" is typed (the lead id goes as ?lead=).
    const val MentionSuggestRoute = "$UiRoute/suggest/mentions"

    private fun leadFields(
        context: Context,
        readOnly: Boolean,
        currentInterest: UUID?,
        // Assigned users without access to the CRM: listed as "(sin acceso)" so they can be unchecked, never offered to others.
        legacyOwners: List<CrmUser> = emptyList(),
        suggestCustomers: Boolean = false,
        showCustomerCode: Boolean = false,
    ): List<UiField> {
        val suggest = UiSuggest(CustomerSuggestRoute).takeIf { suggestCustomers }
        val customer = tr(CrmFieldSections.system.first { it.first == CrmFieldSections.Customer }.second)
        val followup = tr(CrmFieldSections.system.first { it.first == CrmFieldSections.Followup }.second)
        val message = tr(CrmFieldSections.system.first { it.first == CrmFieldSections.Message }.second)
        // The built-in fields of each built-in section.
        val builtIn: Map<String, List<UiField>> = mapOf(
            CrmFieldSections.Customer to buildList {
                add(UiField("name", tr("Nombre"), required = true, readOnly = readOnly, section = customer, suggest = suggest))
                add(
                    UiField(
                        "email", tr("Correo"), type = "email", readOnly = readOnly, section = customer, suggest = suggest?.copy(minLength = 3),
                        help = if (suggestCustomers) tr("Si ya hay un cliente con este correo o telefono, el lead se le asigna.") else "",
                    ),
                )
                add(UiField("phone", tr("Telefono"), type = "phone", readOnly = readOnly, section = customer, suggest = suggest?.copy(minLength = 3)))
                if (showCustomerCode) add(UiField("customerCode", tr("Codigo del cliente"), readOnly = true, section = customer))
                add(UiField("language", tr("Idioma"), readOnly = readOnly, section = customer))
            },
            CrmFieldSections.Followup to buildList {
                add(UiField("stage", tr("Etapa"), type = "dropdown", required = true, readOnly = readOnly, options = context.stages.map { it.name }, optionLabels = context.optionLabels(context.stages.map { it.name }), section = followup))
                if (context.interestsEnabled) {
                    val usable = context.usableInterests.map { it.name }
                    val current = currentInterest?.let { context.names.interest(it) }
                    val options = listOfNotNull(CrmForms.NoInterest.takeIf { context.scope.seesAll || context.scope.reception || currentInterest == null }) +
                        (usable + listOfNotNull(current)).distinct()
                    add(UiField("interest", tr("Interes"), type = "dropdown", readOnly = readOnly, options = options, section = followup))
                }
                add(
                    UiField(
                        "owner", tr("Asignado"), type = "dropdown", multiple = true, readOnly = readOnly || !context.canAssign,
                        options = (context.users + legacyOwners).map { it.name }, section = followup,
                        help = tr("Pueden ser varias personas; todas ven el lead y reciben sus seguimientos."),
                    ),
                )
                add(UiField("followUpOn", tr("Proximo seguimiento"), type = "date", readOnly = readOnly, section = followup))
                add(UiField("source", tr("Origen"), readOnly = readOnly, placeholder = tr("Sitio web, WhatsApp, referido..."), section = followup))
                add(UiField("estimatedValue", tr("Valor estimado"), type = "number", readOnly = readOnly, section = followup))
            },
            CrmFieldSections.Message to listOf(
                UiField("message", tr("Mensaje del lead"), type = "textarea", readOnly = readOnly, section = message),
                UiField("details", tr("Detalles"), type = "textarea", readOnly = readOnly, help = tr("Cotizacion, personas y lo propio de esta oportunidad. Gustos y preferencias que valen siempre van en Notas del cliente."), section = message),
            ),
            CrmFieldSections.Documents to listOf(
                UiField(
                    CrmFields.Documents, tr(CrmFields.DocumentsLabel), type = "files", readOnly = readOnly, files = Files, section = tr(CrmFields.DocumentsLabel),
                    help = tr("Adjunta documentos o carpetas completas. Se guardan al guardar el lead."),
                ),
            ),
        )
        // Walk the sections in order: the built-in fields of a built-in section first, then the custom fields placed in it.
        val sections = CrmFieldSections.sorted(context.sections)
        return buildList {
            sections.forEach { section ->
                section.systemKey?.let { builtIn[it] }?.let { addAll(it) }
                context.fields.filter { it.sectionId == section.id && it.type != CrmFieldType.Button }.sortedWith(compareBy({ it.position }, { it.label.lowercase() }))
                    .forEach { add(customField(it, readOnly, tr(section.name))) }
            }
            // The fields without section (or whose section is gone) go last, in "Campos personalizados".
            val placed = sections.map { it.id }.toSet()
            context.fields.filter { (it.sectionId == null || it.sectionId !in placed) && it.type != CrmFieldType.Button }.sortedWith(compareBy({ it.position }, { it.label.lowercase() }))
                .forEach { add(customField(it, readOnly, tr(CrmFields.Section))) }
        }
    }

    // Sections of the lead form that start open: the ones used every time, and the custom fields when one is required.
    private fun openLeadSections(context: Context): List<String> =
        listOf(CrmFieldSections.system[0].second, CrmFieldSections.system[1].second).map { tr(it) } + context.fields.filter { it.required }.map { tr(CrmFieldSections.title(it, context.sections)) }.distinct()

    // Where the app uploads and downloads the files of the lead form (see CrmServerModule).
    const val FilesRoute = "$UiRoute/files"
    private val Files = UiFiles(upload = FilesRoute, download = FilesRoute, maxFileBytes = CrmAttachments.MaxFileBytes)

    private fun customField(field: CrmField, readOnly: Boolean, section: String) = UiField(
        field.key,
        field.label,
        type = field.type.uiType,
        required = field.required,
        readOnly = readOnly,
        options = field.options,
        section = section,
        multiple = field.multiple,
        files = Files.takeIf { field.type == CrmFieldType.Files },
    )

    /** Suggestions of the new lead form: choosing one fills the contact and links that customer. */
    fun customerSuggestions(customers: List<CrmRepository.CustomerSuggestion>): UiSuggestions {
        val exact = customers.any { it.exact }
        return UiSuggestions(
            heading = if (exact) tr("Ya existe un cliente con este correo o telefono: el lead se le asignara.") else tr("Clientes existentes"),
            items = customers.map { customer ->
                val leads = when (customer.openLeads) {
                    0 -> ""
                    1 -> tr("1 lead abierto")
                    else -> tr("{0} leads abiertos", customer.openLeads)
                }
                UiSuggestion(
                    title = customer.name.ifBlank { customer.email.ifBlank { customer.phone } },
                    subtitle = listOf(customer.email, customer.phone, leads).filter { it.isNotBlank() }.joinToString(" - "),
                    values = mapOf("name" to customer.name, "email" to customer.email, "phone" to customer.phone, "customerId" to customer.id.toString()),
                )
            },
        )
    }

    fun leadValues(context: Context, lead: CrmLead, legacyOwners: List<CrmUser> = emptyList()): Map<String, String> = mapOf(
        "name" to lead.name,
        "email" to lead.email,
        "phone" to lead.phone,
        "customerCode" to lead.customerCode,
        "language" to lead.language,
        "stage" to context.stage(lead.stageId)?.name.orEmpty(),
        "interest" to context.names.interest(lead.interestId),
        "owner" to UiFieldValues.options(lead.ownerUserIds.mapNotNull { id -> (context.users + legacyOwners).firstOrNull { it.id == id }?.name }),
        "followUpOn" to lead.followUpOn?.toString().orEmpty(),
        "source" to lead.source,
        "estimatedValue" to lead.estimatedValue?.toPlainString().orEmpty(),
        "message" to lead.message,
        "details" to lead.details,
    ) + CrmFields.formValues(context.fields, lead.customFields) + fileValues(context, lead)

    // Files of the lead for its files fields; fields without files get "".
    private fun fileValues(context: Context, lead: CrmLead): Map<String, String> {
        val stored = CrmAttachments.forLead(context.tenant, lead.id)
        val keys = listOf(CrmFields.Documents) + context.fields.filter { it.type == CrmFieldType.Files }.map { it.key }
        return keys.associateWith { UiFieldValues.files(stored[it].orEmpty()) }
    }

    private fun newLead(context: Context, submitted: Map<String, String>?): List<UiNode> {
        if (context.stages.isEmpty()) return listOf(UiText(tr("Crea al menos una etapa en Configuracion."), tone = UiTone.Warning))
        val defaults = mapOf(
            "stage" to context.stages.first().name,
            "interest" to if (context.interestsEnabled && !context.scope.seesAll && !context.scope.reception) context.usableInterests.firstOrNull()?.name.orEmpty() else CrmForms.NoInterest,
            "owner" to UiFieldValues.options(listOfNotNull(context.scope.userId?.let { id -> context.users.firstOrNull { it.id == id }?.name })),
            // Every custom field is sent even if left untouched, so required ones are checked.
        ) + context.fields.filter { it.type != CrmFieldType.Button }.associate { it.key to "" } + (CrmFields.Documents to "")
        return listOf(
            UiText(tr("Correo y telefono son opcionales. Mientras escribes se sugieren los clientes existentes; si ya hay uno con ese correo o telefono, el lead se le asigna en lugar de crear otro.")),
            UiCard(
                listOf(
                    UiForm(
                        "lead", leadFields(context, readOnly = false, currentInterest = null, suggestCustomers = true), defaults + submitted.orEmpty(),
                        submit = UiButton(tr("Crear lead"), UiRequest(UiRoute + "/new", params = mapOf("op" to "create"), formId = "lead")),
                        collapsibleSections = true,
                        openSections = openLeadSections(context),
                    ),
                ),
            ),
        )
    }

    private fun leadDetail(context: Context, lead: CrmLead, submitted: Map<String, String>?): List<UiNode> {
        val stage = context.stage(lead.stageId)
        // Through a mention only (not in the user's scope) the lead is read-only: notes are the one thing they can add.
        val access = context.readAccess(lead)
        val canEdit = context.permissions.has(Manage) && access.canWrite
        val canNote = CrmMentions.canAddNote(access, context.permissions.has(Manage))
        val id = mapOf("id" to lead.id.toString())
        val header = UiRow(
            listOfNotNull(
                UiText(lead.name, UiTextStyle.Title),
                stage?.let { UiStatus(context.stageLabel(it.name), it.tone.id) },
                lead.archivedAt?.let { UiStatus(tr("Archivado"), UiTone.Neutral) },
                UiText(tr("Creado {0}", lead.createdAt.atZone(ZoneId.systemDefault()).toLocalDate()), UiTextStyle.Caption),
            ),
        )
        val actions = UiRow(
            listOfNotNull(
                // An archived lead does not move: it comes back first, to the stage it had.
                UiButton(tr("Restaurar"), restoreRequest(lead, null)).takeIf { lead.archivedAt != null && canEdit },
                UiButton(tr("Volver a Leads"), UiNavigate("leads"), style = "secondary"),
                UiButton(
                    tr("Eliminar lead"), UiRequest(UiRoute + "/lead", params = id + ("op" to "delete"), confirm = tr("Se elimina el lead y su actividad; el cliente queda en Clientes. Continuar?")),
                    style = "danger",
                ).takeIf { context.permissions.has(Delete) && access.canWrite },
            ),
        )
        val legacyOwners = CrmRepository.legacyUsers(context.tenant, context.users, lead.ownerUserIds)
        val form = UiCard(
            listOf(
                UiForm(
                    "lead",
                    leadFields(context, readOnly = !canEdit, currentInterest = lead.interestId, legacyOwners = legacyOwners, showCustomerCode = context.scope.seesAll),
                    // The customer code (WiFi portal credential) is only for who sees every lead.
                    leadValues(context, lead, legacyOwners).let { if (context.scope.seesAll) it else it - "customerCode" } + submitted.orEmpty() +
                        (if (context.scope.seesAll) mapOf("customerCode" to lead.customerCode) else emptyMap()),
                    // owners0: the assigned users the form was drawn with; saving applies only the difference to them.
                    submit = if (canEdit) UiButton(tr("Guardar cambios"), UiRequest(UiRoute + "/lead", params = id + ("op" to "save") + (OwnersBeforeKey to lead.ownerUserIds.joinToString(",")), formId = "lead")) else null,
                    collapsibleSections = true,
                    openSections = openLeadSections(context),
                ),
            ),
            title = tr("Datos del lead"),
        )
        // "Mencionar a" next to the note; the text also recognizes @Nombre and @correo, and the app suggests users while "@" is typed.
        val mentionOptions = context.mentionable.filter { it.id != context.scope.userId }.map { it.name }
        val note = UiCard(
            listOf(
                UiForm(
                    "note",
                    listOfNotNull(
                        UiField(
                            "note", tr("Nota del lead"), type = "textarea", required = true, placeholder = tr("Llamada, respuesta enviada, acuerdos... Escribe @ para mencionar a alguien."),
                            // The lead goes in the route: the app only adds q and field (and the form values).
                            suggest = UiSuggest(MentionSuggestRoute + "?lead=" + lead.id, minLength = 0, trigger = "@"),
                        ),
                        UiField(
                            "mentions", tr("Mencionar a"), type = "dropdown", multiple = true, options = mentionOptions,
                            help = tr("Pueden leer este lead y responder con notas, aunque no sea suyo. Reciben un aviso."),
                        ).takeIf { mentionOptions.isNotEmpty() },
                    ),
                    submit = UiButton(tr("Agregar nota"), UiRequest(UiRoute + "/lead", params = id + ("op" to "note"), formId = "note")),
                ),
            ),
            title = tr("Notas del lead"),
        )
        val mentions = mentionsCard(context, lead, id, CrmMentions.canRevoke(access, context.permissions.has(Manage)))
        // Tastes and preferences of the customer (Clientes), so whoever attends the lead does not ask them again.
        // Through a mention only the notes and the conversation show: no customer notes, items, reservations or suggestions.
        val customerNotes = CrmCustomerEvents.access?.takeIf { access.canWrite }?.customerNotes(context.tenant, lead.customerId)?.let { notes ->
            UiCard(
                listOf(
                    UiTimeline(
                        notes.map { note ->
                            UiTimelineEntry(
                                note.body,
                                caption = listOf(note.author, note.companyName, note.at.atZone(ZoneId.systemDefault()).toLocalDate().toString())
                                    .filter { it.isNotBlank() }.joinToString(" - "),
                            )
                        },
                        emptyText = tr("Sin notas del cliente. Anota en su ficha sus gustos y preferencias para no preguntarlos en cada lead."),
                    ),
                ),
                title = tr("Notas del cliente"),
                actions = listOf(UiButton(tr("Abrir ficha"), UiOpenModule("clientes", "customer", mapOf("id" to lead.customerId.toString())), style = "secondary")),
            )
        }
        val noteMentions = CrmMentions.byNote(context.tenant, lead.id)
        val activities = CrmRepository.activity(context.tenant, lead.id, 100)
        // The notes written by the staff are shown with their translation under them (one provider call for all of them); nothing else is sent.
        val noted = activities.filter { it.kind == "note" && it.body.isNotBlank() }
        val noteTexts = noted.map { it.id }.zip(CrmTexts.notes(context.tenant, context.permissions.companyCode, noted.map { it.body })).toMap()
        val timeline = UiCard(
            listOf(
                UiTimeline(
                    activities.map { activity ->
                        val mentioned = activity.id?.let { noteMentions[it] }.orEmpty().map { user -> CrmRepository.displayNames(context.tenant, context.names, listOf(user)) }
                        entry(activity, mentioned, noteTexts[activity.id])
                    },
                ),
            ),
            title = tr("Actividad"),
        )
        val review = lead.reviewReason.takeIf { it.isNotEmpty() && access.canWrite }?.let { reason ->
            UiCard(
                listOf(UiText(reason, tone = UiTone.Danger)),
                title = tr("Por revisar"),
                actions = listOfNotNull(UiButton(tr("Marcar revisado"), UiRequest(UiRoute + "/lead", params = id + ("op" to "reviewed")), style = "secondary").takeIf { canEdit }),
            )
        }
        val suggestion = lead.suggestedOwnerUserId?.takeIf { access.canWrite }?.let { suggested ->
            UiCard(
                listOf(UiText(tr("El origen del lead sugiere asignar a {0}. Al aprobar se anade a los asignados.", context.names.user(suggested)))),
                title = tr("Asignado sugerido"),
                actions = if (context.canAssign && access.canWrite) listOf(
                    UiButton(tr("Aprobar"), UiRequest(UiRoute + "/lead", params = id + ("op" to "approve"))),
                    UiButton(tr("Descartar"), UiRequest(UiRoute + "/lead", params = id + ("op" to "discard")), style = "secondary"),
                ) else emptyList(),
            )
        }
        val conversation = CrmAdminScreens.conversation(context, lead, canEdit, submitted)
        val items = CrmWordPressScreens.items(context, lead, canEdit).takeIf { access.canWrite }
        val reservation = CrmCloudbedsScreens.reservation(context, lead, canEdit).takeIf { access.canWrite }
        val buttons = buttonsCard(context, lead, canEdit)
        return listOfNotNull(header, actions, review, suggestion, customerNotes, form, buttons, items, reservation, conversation, mentions, note.takeIf { canNote }, timeline) +
            (if (access.canWrite) CrmScreenProviders.leadPanels(context, lead) else emptyList())
    }

    /**
     * The button fields of the lead, grouped by the section they were placed in (the form has no buttons: they act, they hold no value).
     * Pressing one asks for confirmation and runs its template or manual automations; only who can edit the lead sees them.
     */
    private fun buttonsCard(context: Context, lead: CrmLead, canEdit: Boolean): UiNode? {
        if (!canEdit) return null
        val buttons = context.fields.filter { it.type == CrmFieldType.Button && !CrmButtons.read(it.config).isEmpty }
        if (buttons.isEmpty()) return null
        val ordered = CrmFieldSections.ordered(buttons, context.sections)
        val children = mutableListOf<UiNode>()
        ordered.groupBy { CrmFieldSections.title(it, context.sections) }.forEach { (section, group) ->
            children += UiText(tr(section), UiTextStyle.Caption)
            children += UiRow(
                group.map { field ->
                    UiButton(
                        field.label,
                        UiRequest(UiRoute + "/lead", params = mapOf("id" to lead.id.toString(), "op" to "button", "field" to field.key), confirm = CrmButtons.confirmText(field)),
                        style = "secondary",
                    )
                },
            )
        }
        return UiCard(children, title = tr("Acciones"))
    }

    /**
     * "Mencionados": who can read this lead because a note mentioned them, with "Quitar acceso" for whoever manages
     * the lead ([canRevoke]). Null when nobody is mentioned.
     */
    private fun mentionsCard(context: Context, lead: CrmLead, id: Map<String, String>, canRevoke: Boolean): UiNode? {
        val active = CrmMentions.active(context.tenant, lead.id)
        if (active.isEmpty()) return null
        return UiCard(
            listOf<UiNode>(
                UiText(tr("Pueden leer este lead y responder con notas mientras este abierto. No pueden moverlo, editarlo ni asignarlo."), UiTextStyle.Caption),
            ) + active.map { mention ->
                UiRow(
                    listOfNotNull(
                        UiText(CrmRepository.displayNames(context.tenant, context.names, listOf(mention.userId))),
                        UiText(listOf(mention.byName.takeIf { it.isNotBlank() }?.let { tr("por {0}", it) }.orEmpty(), time(mention.at)).filter { it.isNotBlank() }.joinToString(" - "), UiTextStyle.Caption),
                        UiButton(
                            tr("Quitar acceso"), UiRequest(UiRoute + "/lead", params = id + ("op" to "unmention") + ("user" to mention.userId.toString()), confirm = tr("Deja de poder leer este lead, salvo que lo mencionen de nuevo. Continuar?")),
                            style = "secondary",
                        ).takeIf { canRevoke },
                    ),
                )
            },
            title = tr("Mencionados ({0})", active.size),
        )
    }

    // [mentioned]: names of the users the note mentions; the entry is highlighted (the app has no inline styles) and says who.
    private fun entry(activity: CrmActivity, mentioned: List<String> = emptyList(), translation: String? = null): UiTimelineEntry {
        val (title, tone) = when (activity.kind) {
            "created" -> tr("Lead creado") to UiTone.Info
            "note" -> if (mentioned.isEmpty()) tr("Nota del lead") to UiTone.Neutral else tr("Nota del lead - menciona a {0}", CrmMentions.label(mentioned)) to UiTone.Info
            "stage" -> tr("Cambio de etapa") to UiTone.Success
            "owner" -> tr("Asignados") to UiTone.Warning
            "interest" -> tr("Interes") to UiTone.Info
            "files" -> tr("Archivos") to UiTone.Info
            else -> tr("Actualizado") to UiTone.Neutral
        }
        val body = if (activity.kind == "stage" && activity.fromStage != null && activity.toStage != null) "${activity.fromStage} -> ${activity.toStage}" else activity.body
        return UiTimelineEntry(title, body, "${activity.author} - ${time(activity.createdAt)}", tone, translation)
    }

    private fun time(instant: Instant) = timeFormat.format(instant.atZone(ZoneId.systemDefault()))

    // ---- Settings ----

    private fun customFieldsCard(context: Context, params: Map<String, String>, submitted: Map<String, String>?): List<UiNode> {
        val templates = CrmMail.templates(context.tenant)
        val automations = CrmAutomationStore.all(context.tenant)
        val manual = automations.filter { it.trigger.type == CrmTriggerType.Manual }
        val editing = params["field"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }?.let { id -> context.fields.firstOrNull { it.id == id } }
        val editingSection = params["section"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }?.let { id -> context.sections.firstOrNull { it.id == id && !it.isSystem } }
        val mapped = CrmStay.effective(context.stay, context.fields)
        val rows = CrmFieldSections.ordered(context.fields, context.sections).map { field ->
            val detail = buildList {
                add(tr(field.type.label))
                add(tr(CrmFieldSections.title(field, context.sections)))
                if (field.type == CrmFieldType.Dropdown) add(if (field.multiple) tr("varias opciones") else tr("una opcion"))
                if (field.type == CrmFieldType.Button) add(CrmButtons.describe(field.config, templates, automations))
                if (field.required) add(tr("obligatorio"))
                if (field.key == mapped.startKey) add(tr("ingreso de la estadia"))
                if (field.key == mapped.endKey) add(tr("salida de la estadia"))
            }.joinToString(", ")
            UiRow(
                listOfNotNull(
                    UiText(tr("{0}. {1} ({2})", field.position, field.label, detail)),
                    UiButton(tr("Editar"), UiNavigate("settings", mapOf("field" to field.id.toString())), style = "secondary"),
                    UiButton(
                        tr("Eliminar"),
                        UiRequest(
                            UiRoute + "/settings", params = mapOf("op" to "fieldDelete", "id" to field.id.toString()),
                            confirm = if (CrmStay.isMapped(mapped, field.key)) {
                                tr("Se elimina el campo {0} y lo que tenga en todos los leads. Es una fecha de estadia: Cambiar el mapeo puede borrar las fechas de estadia que usan Cloudbeds, el hotspot y la salida automatica. Continuar?", field.label)
                            } else {
                                tr("Se elimina el campo {0} y lo que tenga en todos los leads. Continuar?", field.label)
                            },
                        ),
                        style = "danger",
                    ),
                ),
            )
        }
        val section = if (editing == null) tr("Nuevo campo") else tr("Editar {0}", editing.label)
        val dropdown = UiFieldCondition("type", listOf(CrmFieldType.Dropdown.label))
        val isButton = UiFieldCondition("type", listOf(CrmFieldType.Button.label))
        // The "Obligatorio" checkbox makes no sense in a button: shown for every other type.
        val notButton = UiFieldCondition("type", CrmFieldType.entries.filter { it != CrmFieldType.Button }.map { it.label })
        val formFields = listOf(
            UiField("label", tr("Nombre"), required = true, section = section),
            UiField(
                "type", tr("Tipo"), type = "dropdown", required = true, readOnly = editing != null, options = CrmFieldType.entries.map { it.label }, section = section,
                help = if (editing != null) tr("El tipo no cambia despues de crear el campo.") else "",
            ),
            UiField(
                "section", tr("Seccion de la ficha"), type = "dropdown", options = listOf(CrmFields.Section) + CrmFieldSections.sorted(context.sections).filter { s -> context.sections.any { it.id == s.id } }.map { it.name }, section = section,
                help = tr("Los campos sin seccion van en {0}.", tr(CrmFields.Section)),
            ),
            UiField(
                "options", tr("Opciones de la lista"), type = "textarea", required = true, section = section, visibleWhen = dropdown,
                help = tr("Una opcion por linea. Quitar una opcion no borra lo que ya tengan los leads, pero ya no se podra elegir."),
            ),
            UiField("multiple", tr("Permitir elegir varias opciones"), type = "boolean", section = section, visibleWhen = dropdown),
            UiField(
                "buttonAction", tr("Que hace el boton"), type = "dropdown", options = listOf(CrmButtons.ActionTemplate, CrmButtons.ActionAutomations), section = section, visibleWhen = isButton,
                help = tr("El boton aparece en la ficha del lead (no en el formulario), pide confirmacion y muestra el resultado."),
            ),
            UiField("buttonTemplate", tr("Plantilla"), type = "dropdown", options = templates.map { it.name }, section = section, visibleWhen = UiFieldCondition("buttonAction", listOf(CrmButtons.ActionTemplate))),
            UiField(
                "buttonAutomations", tr("Automatizaciones manuales"), type = "dropdown", multiple = true, options = manual.map { it.name }, section = section,
                visibleWhen = UiFieldCondition("buttonAction", listOf(CrmButtons.ActionAutomations)),
                help = tr("Se ejecutan en el orden elegido y se detienen en la primera que no termine. Se crean en Automatizaciones con el disparador Manual."),
            ),
            UiField("required", tr("Obligatorio"), type = "boolean", section = section, visibleWhen = notButton),
            UiField("position", tr("Posicion"), type = "number", help = tr("Orden dentro de su seccion en la ficha del lead."), section = section),
        )
        val values = editing?.let {
            mapOf(
                "label" to it.label, "type" to it.type.label, "options" to it.options.joinToString("\n"), "section" to CrmFieldSections.title(it, context.sections),
                "multiple" to it.multiple.toString(), "required" to it.required.toString(), "position" to it.position.toString(),
            ) + CrmButtons.formValues(it.config, templates, manual)
        } ?: mapOf("type" to CrmFieldType.Text.label, "section" to CrmFields.Section, "position" to ((context.fields.maxOfOrNull { it.position } ?: -1) + 1).toString())
        val saveParams = mapOf("op" to "fieldSave") + listOfNotNull(editing?.let { "id" to it.id.toString() })
        val fieldsCard = UiCard(
            listOf(UiText(tr("Campos propios de la empresa que aparecen en la ficha de cada lead, en la seccion que elijas (sin seccion: {0}). Las fechas de la estadia se eligen en Integraciones.", tr(CrmFields.Section)))) +
                rows.ifEmpty { listOf(UiText(tr("Aun no hay campos personalizados."), tone = UiTone.Neutral)) } +
                listOfNotNull(UiButton(tr("Nuevo campo"), UiNavigate("settings"), style = "secondary").takeIf { editing != null }) +
                UiForm(
                    "customField", formFields,
                    values + submitted.orEmpty().filterKeys { it in setOf("label", "type", "section", "options", "multiple", "required", "position", "buttonAction", "buttonTemplate", "buttonAutomations") },
                    submit = UiButton(if (editing == null) tr("Agregar campo") else tr("Guardar campo"), UiRequest(UiRoute + "/settings", params = saveParams, formId = "customField")),
                    minFieldWidth = 220,
                ),
            title = tr("Campos personalizados"),
        )
        val ordered = CrmFieldSections.sorted(context.sections)
        val sectionRows = ordered.mapIndexed { index, item ->
            val move = { direction: String -> UiRequest(UiRoute + "/settings", params = mapOf("op" to "sectionMove", "id" to item.id.toString(), "dir" to direction)) }
            UiRow(
                listOfNotNull(
                    UiText(tr("{0}. {1}{2} ({3} campos propios)", index + 1, tr(item.name), if (item.isSystem) tr(" (fija)") else "", context.fields.count { it.sectionId == item.id })),
                    // "Datos del cliente" is always first: it never moves and nothing goes before it.
                    UiButton(tr("Subir"), move("up"), style = "secondary").takeIf { index > 1 },
                    UiButton(tr("Bajar"), move("down"), style = "secondary").takeIf { index > 0 && index < ordered.lastIndex },
                    UiButton(tr("Renombrar"), UiNavigate("settings", mapOf("section" to item.id.toString())), style = "secondary").takeIf { !item.isSystem },
                    UiButton(
                        tr("Eliminar"),
                        UiRequest(
                            UiRoute + "/settings", params = mapOf("op" to "sectionDelete", "id" to item.id.toString()),
                            confirm = tr("Se elimina la seccion {0}; sus campos quedan en {1}, sin borrarlos. Continuar?", item.name, tr(CrmFields.Section)),
                        ),
                        style = "danger",
                    ).takeIf { !item.isSystem },
                ),
            )
        }
        val sectionTitle = if (editingSection == null) tr("Nueva seccion") else tr("Renombrar {0}", editingSection.name)
        val sectionValues = editingSection?.let { mapOf("sectionName" to it.name) }.orEmpty()
        val sectionParams = mapOf("op" to "sectionSave") + listOfNotNull(editingSection?.let { "id" to it.id.toString() })
        val sectionsCard = UiCard(
            listOf(UiText(tr("La ficha del lead se arma con estas secciones, en este orden. Las fijas (Datos del cliente, Seguimiento, Mensaje y detalles, Documentos) no se borran ni se renombran; puedes poner campos propios en cualquiera de ellas o en las que crees. Datos del cliente siempre va primero. Los campos sin seccion van al final, en {0}.", tr(CrmFields.Section)))) +
                sectionRows +
                listOfNotNull(UiButton(tr("Nueva seccion"), UiNavigate("settings"), style = "secondary").takeIf { editingSection != null }) +
                UiForm(
                    "fieldSection",
                    listOf(UiField("sectionName", tr("Nombre"), required = true, section = sectionTitle)),
                    sectionValues + submitted.orEmpty().filterKeys { it == "sectionName" },
                    submit = UiButton(if (editingSection == null) tr("Agregar seccion (al final)") else tr("Guardar nombre"), UiRequest(UiRoute + "/settings", params = sectionParams, formId = "fieldSection")),
                    minFieldWidth = 220,
                ),
            title = tr("Secciones de la ficha"),
        )
        return listOf(sectionsCard, fieldsCard)
    }

    private fun settings(context: Context, params: Map<String, String>, submitted: Map<String, String>?): List<UiNode> {
        val editingStage = params["stage"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }?.let { context.stage(it) }
        val editingInterest = params["interest"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }?.let { id -> context.interests.firstOrNull { it.id == id } }
        val nodes = mutableListOf<UiNode>()

        nodes += UiCard(
            listOf(
                UiText(
                    tr("Activa los intereses si los leads se reparten por linea de negocio (surf, yoga, eventos...) o por tipo de propiedad. Cada usuario vera solo los leads de los intereses que le asignes; quien tenga \"Ver todos los leads\" ve todo y los leads sin interes esperan en Recepcion."),
                ),
                UiForm(
                    "interestsToggle",
                    listOf(UiField("enabled", tr("Clasificar leads por interes"), type = "boolean")),
                    mapOf("enabled" to context.interestsEnabled.toString()),
                    submit = UiButton(tr("Guardar"), UiRequest(UiRoute + "/settings", params = mapOf("op" to "interests"), formId = "interestsToggle"), style = "secondary"),
                ),
            ),
            title = tr("Intereses"),
        )

        if (context.interestsEnabled) {
            val rows = context.interests.map { interest ->
                UiRow(
                    listOf(
                        UiText(interest.name),
                        UiButton(tr("Renombrar"), UiNavigate("settings", mapOf("interest" to interest.id.toString())), style = "secondary"),
                        UiButton(
                            tr("Eliminar"),
                            UiRequest(UiRoute + "/settings", params = mapOf("op" to "interestDelete", "id" to interest.id.toString()), confirm = tr("Sus leads volveran a Recepcion. Continuar?")),
                            style = "danger",
                        ),
                    ),
                )
            }
            val interestParams = mapOf("op" to "interestSave") + listOfNotNull(editingInterest?.let { "id" to it.id.toString() })
            nodes += UiCard(
                rows.ifEmpty { listOf(UiText(tr("Aun no hay intereses."), tone = UiTone.Warning)) } + UiForm(
                    "interest",
                    listOf(UiField("name", if (editingInterest == null) tr("Nuevo interes") else tr("Nuevo nombre de {0}", editingInterest.name), required = true)),
                    mapOf("name" to (submitted?.get("name") ?: editingInterest?.name.orEmpty())),
                    submit = UiButton(if (editingInterest == null) tr("Agregar interes") else tr("Guardar nombre"), UiRequest(UiRoute + "/settings", params = interestParams, formId = "interest")),
                ),
                title = tr("Catalogo de intereses"),
            )

            if (context.interests.isNotEmpty()) {
                val assigned = CrmRepository.userInterests(context.tenant)
                val userForms = context.users.map { user ->
                    val formId = "user_${user.id}"
                    UiCard(
                        listOf(
                            UiForm(
                                formId,
                                context.interests.map { UiField(it.id.toString(), it.name, type = "boolean") },
                                context.interests.associate { it.id.toString() to (it.id in assigned[user.id].orEmpty()).toString() },
                                submit = UiButton(tr("Guardar"), UiRequest(UiRoute + "/settings", params = mapOf("op" to "userInterests", "user" to user.id.toString()), formId = formId), style = "secondary"),
                                minFieldWidth = 160,
                            ),
                        ),
                        title = user.name,
                    )
                }
                nodes += UiCard(
                    listOf(UiText(tr("Marca los intereses de cada usuario. Los usuarios con \"Ver todos los leads\" y el dueno de la empresa ven todo."))) +
                        userForms.ifEmpty { listOf(UiText(tr("Ningun usuario tiene permisos del CRM todavia."), tone = UiTone.Warning)) },
                    title = tr("Intereses por usuario"),
                    // One form per user: closed so the stages below stay at hand.
                    collapsible = true,
                    expanded = false,
                )
            }
        }

        nodes += customFieldsCard(context, params, submitted)
        nodes += CrmAdminScreens.digestCard(context)

        val stageRows = context.stages.map { stage ->
            UiRow(
                listOf(
                    UiStatus(tr("{0}. {1} ({2})", stage.position, stage.name, tr(stage.kind.label)), stage.tone.id),
                    UiButton(tr("Editar"), UiNavigate("settings", mapOf("stage" to stage.id.toString())), style = "secondary"),
                    UiButton(
                        tr("Eliminar"),
                        UiRequest(UiRoute + "/settings", params = mapOf("op" to "stageDelete", "id" to stage.id.toString()), confirm = tr("Eliminar la etapa {0}?", stage.name)),
                        style = "danger",
                    ),
                ),
            )
        }
        // The name of a stage is shown translated to who uses another language than the company's (I18N.md §8): the provider of the CRM
        // translates it unless a manual translation is written here, one per language.
        val translationLanguages = CrmTexts.otherLanguages(context.permissions.companyCode)
        val manualNames = editingStage?.let { stage -> translationLanguages.associateWith { CrmTexts.manual(context.tenant, listOf(stage.name), it)[stage.name].orEmpty() } }.orEmpty()
        val translationKeys = translationLanguages.map { "$StageTranslationPrefix$it" }
        val stageValues = editingStage?.let { mapOf("name" to it.name, "kind" to it.kind.label, "tone" to it.tone.label, "position" to it.position.toString()) + manualNames.mapKeys { "$StageTranslationPrefix${it.key}" } }
            ?: mapOf("kind" to StageKind.Open.label, "tone" to StageTone.Neutral.label, "position" to ((context.stages.maxOfOrNull { it.position } ?: -1) + 1).toString())
        val stageParams = mapOf("op" to "stageSave") + listOfNotNull(editingStage?.let { "id" to it.id.toString() })
        nodes += UiCard(
            stageRows + UiForm(
                "stage",
                listOf(
                    UiField("name", tr("Nombre"), required = true, section = if (editingStage == null) tr("Nueva etapa") else tr("Editar {0}", editingStage.name)),
                    UiField("kind", tr("Tipo"), type = "dropdown", options = StageKind.entries.map { it.label }, help = tr("Ganada y Perdida cierran el lead."), section = if (editingStage == null) tr("Nueva etapa") else tr("Editar {0}", editingStage.name)),
                    UiField("tone", tr("Color"), type = "dropdown", options = StageTone.entries.map { it.label }, section = if (editingStage == null) tr("Nueva etapa") else tr("Editar {0}", editingStage.name)),
                    UiField("position", tr("Posicion"), type = "number", help = tr("Orden en el pipeline, de izquierda a derecha."), section = if (editingStage == null) tr("Nueva etapa") else tr("Editar {0}", editingStage.name)),
                ) + translationLanguages.map { language ->
                    UiField(
                        "$StageTranslationPrefix$language", tr("Nombre en {0} (opcional)", CrmTexts.languageName(language)),
                        help = tr("Se muestra a quien usa la app en ese idioma. Vacio: se traduce solo, si la empresa tiene un traductor configurado."),
                        section = if (editingStage == null) tr("Nueva etapa") else tr("Editar {0}", editingStage.name),
                    )
                },
                stageValues + submitted.orEmpty().filterKeys { it in setOf("name", "kind", "tone", "position") + translationKeys },
                submit = UiButton(if (editingStage == null) tr("Agregar etapa") else tr("Guardar etapa"), UiRequest(UiRoute + "/settings", params = stageParams, formId = "stage")),
                minFieldWidth = 200,
            ),
            title = tr("Etapas del pipeline"),
        )

        if (CrmRepository.leadCount(context.tenant) == 0) {
            nodes += UiCard(
                listOf(
                    UiText(tr("Mientras no haya leads puedes reemplazar las etapas por una plantilla.")),
                    UiRow(
                        CrmTemplates.all.map { template ->
                            UiButton(
                                tr(template.label),
                                UiRequest(
                                    UiRoute + "/settings", params = mapOf("op" to "template", "template" to template.id),
                                    confirm = tr("Se reemplazan las etapas actuales por: {0}. Continuar?", template.stages.joinToString { it.name }),
                                ),
                                style = "secondary",
                            )
                        },
                    ),
                ),
                title = tr("Plantillas de etapas"),
            )
        }
        return nodes
    }
}
