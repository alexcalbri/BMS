package com.ideasdeveloper.bms.modules.crm

import com.ideasdeveloper.bms.server.i18n.msg
import com.ideasdeveloper.bms.server.i18n.tr
import com.ideasdeveloper.bms.ui.UiBadge
import com.ideasdeveloper.bms.ui.UiButton
import com.ideasdeveloper.bms.ui.UiCard
import com.ideasdeveloper.bms.ui.UiField
import com.ideasdeveloper.bms.ui.UiFieldCondition
import com.ideasdeveloper.bms.ui.UiForm
import com.ideasdeveloper.bms.ui.UiList
import com.ideasdeveloper.bms.ui.UiListItem
import com.ideasdeveloper.bms.ui.UiNavigate
import com.ideasdeveloper.bms.ui.UiNode
import com.ideasdeveloper.bms.ui.UiOpenUrl
import com.ideasdeveloper.bms.ui.UiRequest
import com.ideasdeveloper.bms.ui.UiRow
import com.ideasdeveloper.bms.ui.UiStatus
import com.ideasdeveloper.bms.ui.UiText
import com.ideasdeveloper.bms.ui.UiTextStyle
import com.ideasdeveloper.bms.ui.UiTone
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Items of interest of a lead, the property search of the website and the WordPress settings. */
object CrmWordPressScreens {
    private const val UiRoute = CrmScreens.UiRoute
    const val PluginPath = WordPressPluginInfo.Path
    private val Any = msg("Todos")
    private val timeFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    private fun statusTone(status: String) = when (status) {
        "sold", "booked" -> UiTone.Success
        "chosen" -> UiTone.Info
        "offered" -> UiTone.Warning
        "discarded" -> UiTone.Neutral
        else -> UiTone.Info
    }

    // ---- Lead detail: properties, rooms and other items ----

    fun items(context: CrmScreens.Context, lead: CrmLead, canEdit: Boolean): UiNode? {
        val items = CrmItems.list(context.tenant, lead.id)
        val wordpress = CrmHost.wordpressSettings(context.tenant).ready && CrmExtensions.isEnabled(context.tenant, CrmExtensionIds.WordPress)
        if (items.isEmpty() && !wordpress && !canEdit) return null
        val id = lead.id.toString()
        fun action(op: String, item: CrmItem, extra: Map<String, String> = emptyMap()) =
            UiRequest(UiRoute + "/lead", params = mapOf("id" to id, "op" to op, "item" to item.id.toString()) + extra)
        val rows = items.flatMap { item ->
            val info = listOfNotNull(
                item.subtitle.ifEmpty { null },
                item.startsOn?.let { start -> tr("Del {0}", start) + item.endsOn?.let { tr(" al {0}", it) }.orEmpty() },
                item.url.ifEmpty { null },
            ).joinToString("\n")
            val buttons = if (!canEdit) emptyList() else listOfNotNull(
                UiButton(tr("Ofrecida"), action("itemStatus", item, mapOf("status" to "offered")), style = "secondary").takeIf { item.status == "interested" },
                UiButton(tr("Elegida"), action("itemStatus", item, mapOf("status" to "chosen")), style = "secondary").takeIf { item.status in setOf("interested", "offered") },
                UiButton(tr("Descartar"), action("itemStatus", item, mapOf("status" to "discarded")), style = "secondary").takeIf { item.status !in setOf("discarded", "sold", "booked") },
                UiButton(tr("Quitar"), action("itemRemove", item, emptyMap()).copy(confirm = tr("Quitar {0} del lead?", item.title)), style = "danger").takeIf { item.status !in setOf("sold", "booked") },
            )
            val sale = if (canEdit && item.source == WordPressPluginInfo.Source && item.status !in setOf("sold", "discarded")) {
                UiForm(
                    "sold_${item.id}",
                    listOf(
                        UiField("price", tr("Precio de venta"), type = "number"),
                        UiField("date", tr("Fecha de venta"), type = "date"),
                    ),
                    mapOf("price" to item.price?.toPlainString().orEmpty(), "date" to context.today.toString()),
                    submit = UiButton(
                        tr("Cerrar venta"),
                        UiRequest(UiRoute + "/lead", params = mapOf("id" to id, "op" to "itemSold", "item" to item.id.toString()), formId = "sold_${item.id}",
                            confirm = tr("Se marca la propiedad como vendida en el sitio y el lead pasa a ganado. Continuar?")),
                    ),
                    minFieldWidth = 180,
                )
            } else null
            listOfNotNull(
                UiList(listOf(UiListItem(item.title, info, badges = listOf(UiBadge(CrmItems.statusLabel(item.status), statusTone(item.status)))))),
                buttons.takeIf { it.isNotEmpty() }?.let { UiRow(it) },
                sale,
            )
        }
        val manual = if (canEdit) UiForm(
            "manualItem",
            listOf(UiField("title", tr("Agregar a mano"), placeholder = tr("Apto 3B, Torre Mar")), UiField("subtitle", tr("Detalle"))),
            submit = UiButton(tr("Agregar"), UiRequest(UiRoute + "/lead", params = mapOf("id" to id, "op" to "itemManual"), formId = "manualItem"), style = "secondary"),
            minFieldWidth = 220,
        ) else null
        return UiCard(
            rows.ifEmpty { listOf(UiText(tr("Sin propiedades ni habitaciones vinculadas."), UiTextStyle.Caption)) } + listOfNotNull(manual),
            title = tr("Intereses del lead"),
            actions = listOfNotNull(UiButton(tr("Buscar propiedades"), UiNavigate("properties", mapOf("lead" to id)), style = "secondary").takeIf { wordpress && canEdit }),
        )
    }

    // ---- Property search on the website ----

    fun properties(context: CrmScreens.Context, lead: CrmLead, params: Map<String, String>): List<UiNode> {
        val taxonomies = try {
            CrmHost.wordpressTaxonomies(context.tenant)
        } catch (failure: CrmWordPressException) {
            return listOf(UiText(failure.message.orEmpty(), tone = UiTone.Danger))
        }
        val filterKeys = listOf("property_type" to tr("Tipo"), "ct_status" to tr("Estado"), "city" to tr("Ciudad"), "beds" to tr("Habitaciones"))
        val fields = listOf(UiField("q", tr("Buscar"), placeholder = tr("Direccion, titulo o MLS"))) +
            filterKeys.filter { taxonomies[it.first].orEmpty().isNotEmpty() }.map { (key, label) ->
                UiField("f_$key", label, type = "dropdown", options = listOf(Any) + taxonomies[key].orEmpty())
            } + listOf(UiField("min", tr("Precio desde"), type = "number"), UiField("max", tr("Precio hasta"), type = "number"))
        val values = fields.associate { it.key to (params[it.key] ?: if (it.type == "dropdown") Any else "") }
        val page = params["page"]?.toIntOrNull()?.coerceAtLeast(1) ?: 1
        val filters = filterKeys.mapNotNull { (key, _) -> params["f_$key"]?.takeIf { it.isNotBlank() && it != Any }?.let { key to it } }.toMap()
        val linked = CrmItems.list(context.tenant, lead.id, WordPressPluginInfo.Source).map { it.externalId }.toSet()
        val result = try {
            CrmHost.wordpressSearch(context.tenant, params["q"].orEmpty(), filters, params["min"].orEmpty(), params["max"].orEmpty(), page)
        } catch (failure: CrmWordPressException) {
            return listOf(UiText(failure.message.orEmpty(), tone = UiTone.Danger))
        }
        val keep = params.filterKeys { it == "lead" || it == "q" || it == "min" || it == "max" || it.startsWith("f_") }
        val list = UiList(
            result.items.map { listing ->
                val isLinked = listing.id.toString() in linked
                UiListItem(
                    title = listing.title,
                    subtitle = listOf(listing.priceText, listing.summary, listing.agentName.ifEmpty { null }?.let { tr("Agente: {0}", it) }.orEmpty()).filter { it.isNotEmpty() }.joinToString(" - "),
                    trailing = if (isLinked) tr("Vinculada") else tr("Vincular"),
                    badges = listOfNotNull(UiBadge(tr("Vinculada"), UiTone.Success).takeIf { isLinked }),
                    action = if (isLinked) null else UiRequest(UiRoute + "/properties", params = keep + mapOf("op" to "link", "listing" to listing.id.toString())),
                )
            },
            emptyText = tr("No hay propiedades con esos filtros."),
        )
        val pages = (result.total + 19) / 20
        return listOf(
            UiRow(listOf(UiButton(tr("Volver al lead"), UiNavigate("lead", mapOf("id" to lead.id.toString())), style = "secondary"), UiText(tr("Propiedades para {0}", lead.name), UiTextStyle.Subtitle))),
            UiCard(listOf(UiForm("propertySearch", fields, values, submit = UiButton(tr("Buscar"), UiNavigate("properties", mapOf("lead" to lead.id.toString()), formId = "propertySearch"), style = "secondary"), minFieldWidth = 180))),
            UiCard(
                listOfNotNull(
                    UiText(tr("Toca una propiedad para vincularla al lead."), UiTextStyle.Caption),
                    list,
                    UiRow(
                        listOfNotNull(
                            UiButton(tr("Anterior"), UiNavigate("properties", keep + ("page" to (page - 1).toString())), style = "secondary").takeIf { page > 1 },
                            UiText(tr("Pagina {0} de {1}", page, maxOf(1, pages)), UiTextStyle.Caption),
                            UiButton(tr("Siguiente"), UiNavigate("properties", keep + ("page" to (page + 1).toString())), style = "secondary").takeIf { page < pages },
                        ),
                    ).takeIf { pages > 1 },
                ),
                title = tr("{0} propiedad(es)", result.total),
            ),
        )
    }

    // ---- Settings: Integraciones > WordPress ----

    fun settings(context: CrmScreens.Context, secretShown: String?): List<UiNode> {
        val settings = CrmHost.wordpressSettings(context.tenant)
        val on = UiFieldCondition("enabled", listOf("true"))
        val status = when {
            !settings.ready -> UiStatus(tr("WordPress desactivado"), UiTone.Neutral)
            settings.lastError.isNotEmpty() -> UiStatus(tr("Error: {0}", settings.lastError), UiTone.Danger)
            settings.lastEventAt != null -> UiStatus(tr("Conectado con {0}; ultimo evento {1}", settings.siteName.ifEmpty { settings.siteUrl.orEmpty() }, timeFormat.format(settings.lastEventAt.atZone(ZoneId.systemDefault()))), UiTone.Success)
            else -> UiStatus(tr("Sin eventos todavia: instala el plugin y prueba la conexion"), UiTone.Warning)
        }
        val agents = CrmHost.wordpressAgents(context.tenant)
        val agentForm = if (agents.isEmpty()) UiText(tr("Sincroniza los agentes para elegir el usuario de cada uno."), UiTextStyle.Caption) else UiForm(
            "wpAgents",
            agents.map { agent ->
                UiField("agent_${agent.wpUserId}", "${agent.name} (${agent.email})", type = "dropdown", options = listOf(CrmForms.Unassigned) + context.users.map { it.name })
            },
            agents.associate { agent -> "agent_${agent.wpUserId}" to (agent.userId?.let { id -> context.users.firstOrNull { it.id == id }?.name } ?: CrmForms.Unassigned) },
            submit = UiButton(tr("Guardar agentes"), UiRequest(UiRoute + "/integrations", params = mapOf("op" to "wpAgentsSave"), formId = "wpAgents"), style = "secondary"),
            minFieldWidth = 260,
        )
        return listOfNotNull(
            UiText(tr("WordPress (Real Estate 7)"), UiTextStyle.Subtitle),
            UiCard(
                listOf(
                    status,
                    UiText(
                        tr("1. Descarga el plugin BMS Connector {0} e instalalo en WordPress (Plugins > Anadir nuevo > Subir plugin). 2. Genera el secreto aqui. 3. En WordPress, Ajustes > BMS Connector: direccion de BMS {1}, empresa {2} y el secreto. Los formularios, consultas de propiedades y visitas crean leads; favoritos, busquedas guardadas y vistas quedan en la actividad del lead.", WordPressPluginInfo.Version, context.publicBase, context.permissions.companyCode),
                    ),
                    UiForm(
                        "wordpress",
                        listOf(
                            UiField("enabled", tr("Conectar el sitio"), type = "boolean"),
                            UiField("siteUrl", tr("Direccion del sitio"), type = "url", visibleWhen = on, placeholder = "https://mi-inmobiliaria.com"),
                        ),
                        mapOf("enabled" to settings.enabled.toString(), "siteUrl" to settings.siteUrl.orEmpty()),
                        submit = UiButton(tr("Guardar"), UiRequest(UiRoute + "/integrations", params = mapOf("op" to "wpSave"), formId = "wordpress")),
                    ),
                ),
                actions = listOf(
                    UiButton(tr("Descargar plugin"), UiOpenUrl(context.publicBase + PluginPath), style = "secondary"),
                    UiButton(
                        if (settings.secret == null) tr("Generar secreto") else tr("Nuevo secreto"),
                        UiRequest(UiRoute + "/integrations", params = mapOf("op" to "wpSecret"), confirm = if (settings.secret == null) null else tr("El secreto actual deja de funcionar hasta que pegues el nuevo en el plugin. Continuar?")),
                        style = "secondary",
                    ),
                    UiButton(tr("Probar conexion"), UiRequest(UiRoute + "/integrations", params = mapOf("op" to "wpPing")), style = "secondary", enabled = settings.ready),
                ),
            ),
            secretShown?.let { UiCard(listOf(UiText(tr("Copia este secreto en Ajustes > BMS Connector. No se vuelve a mostrar.")), UiText(it, UiTextStyle.Subtitle)), title = tr("Secreto del plugin")) },
            UiCard(
                listOf(UiText(tr("El agente de la propiedad consultada se sugiere como asignado del lead (al aprobarlo se anade a los asignados)."), UiTextStyle.Caption), agentForm),
                title = tr("Agentes del sitio"),
                actions = listOf(UiButton(tr("Sincronizar agentes"), UiRequest(UiRoute + "/integrations", params = mapOf("op" to "wpAgentsSync")), style = "secondary", enabled = settings.ready)),
            ),
        )
    }

    fun saleDate(raw: String?, today: LocalDate): LocalDate =
        raw?.trim()?.takeIf { it.isNotEmpty() }?.let { runCatching { LocalDate.parse(it) }.getOrNull() ?: throw CrmValidationException(tr("La fecha de venta debe tener el formato AAAA-MM-DD.")) } ?: today
}
