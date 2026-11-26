package com.ideasdeveloper.bms.extensions.inmobiliaria

import com.ideasdeveloper.bms.modules.crm.CrmExtensionActionResult
import com.ideasdeveloper.bms.modules.crm.CrmHost
import com.ideasdeveloper.bms.modules.crm.CrmScreens
import com.ideasdeveloper.bms.server.i18n.msg
import com.ideasdeveloper.bms.server.i18n.tr
import com.ideasdeveloper.bms.ui.UiBadge
import com.ideasdeveloper.bms.ui.UiButton
import com.ideasdeveloper.bms.ui.UiCard
import com.ideasdeveloper.bms.ui.UiColumn
import com.ideasdeveloper.bms.ui.UiField
import com.ideasdeveloper.bms.ui.UiForm
import com.ideasdeveloper.bms.ui.UiGrid
import com.ideasdeveloper.bms.ui.UiList
import com.ideasdeveloper.bms.ui.UiListItem
import com.ideasdeveloper.bms.ui.UiMetric
import com.ideasdeveloper.bms.ui.UiNavigate
import com.ideasdeveloper.bms.ui.UiNode
import com.ideasdeveloper.bms.ui.UiOpenUrl
import com.ideasdeveloper.bms.ui.UiRow
import com.ideasdeveloper.bms.ui.UiStatus
import com.ideasdeveloper.bms.ui.UiSuggest
import com.ideasdeveloper.bms.ui.UiTable
import com.ideasdeveloper.bms.ui.UiTableColumn
import com.ideasdeveloper.bms.ui.UiText
import com.ideasdeveloper.bms.ui.UiTextStyle
import com.ideasdeveloper.bms.ui.UiTone
import java.util.UUID

/** Lectura pura de los enlaces por portal de un formulario (filas l1_label, l1_url, l2_label...). */
object InmobiliariaLinkForms {
    /** Etiquetas que se sugieren al escribir un enlace. */
    val SuggestedLabels = listOf(msg("Sitio web"), msg("Sitio en espanol"), "Craigslist", "Encuentra24", "Google Drive")

    /** Las filas con algo escrito, en orden; las vacias se saltan. El servicio valida etiqueta y direccion. */
    fun parse(values: Map<String, String>, maxRows: Int = InmobiliariaService.MaxLinks): List<LinkInput> =
        (1..maxRows).mapNotNull { row ->
            val label = values["l${row}_label"].orEmpty().trim()
            val url = values["l${row}_url"].orEmpty().trim()
            if (label.isEmpty() && url.isEmpty()) null else LinkInput(label, url)
        }

    /** Cuantas filas dibujar: las que ya hay y tres vacias mas, o las que trajo un formulario devuelto con error. */
    fun rows(existing: Int, submitted: Map<String, String>?, maxRows: Int = InmobiliariaService.MaxLinks): Int {
        val sent = submitted?.keys?.mapNotNull { key -> Regex("^l(\\d+)_").find(key)?.groupValues?.get(1)?.toIntOrNull() }?.maxOrNull() ?: 0
        return maxOf(existing + 3, sent).coerceIn(1, maxRows)
    }
}

/** Pantallas del inventario: lista, ficha y formularios de la propiedad, y captaciones. */
internal object InmobiliariaPropertyScreens {
    val NoAgent = msg("Sin agente")
    private val OutOfScope = msg("(cliente fuera de tu alcance)")

    private fun statusTone(status: PropertyStatus) = when (status) {
        PropertyStatus.Intake -> UiTone.Info
        PropertyStatus.ForSale -> UiTone.Success
        PropertyStatus.Negotiation -> UiTone.Warning
        PropertyStatus.Sold -> UiTone.Neutral
        PropertyStatus.Withdrawn -> UiTone.Neutral
    }

    // ---- Lista de propiedades ----

    private fun propertyRow(context: CrmScreens.Context, property: Property): UiListItem {
        val perM2 = property.pricePerLotM2?.let { tr("{0}/m2 lote", InmobiliariaFormat.money(it, property.currency)) }
        val subtitle = listOfNotNull(
            listOf(property.zone, property.kind.display).filter { it.isNotEmpty() }.joinToString(" - "),
            perM2,
            tr("Agente: {0}", InmobiliariaUi.agentName(context.users, property.listingAgentId)),
        ).filter { it.isNotEmpty() }.joinToString("\n")
        return UiListItem(
            title = tr("{0} - {1}", property.code, property.title),
            subtitle = subtitle,
            trailing = InmobiliariaFormat.money(property.price, property.currency),
            badges = listOfNotNull(
                UiBadge(property.status.display, statusTone(property.status)),
                UiBadge(tr("Rebajada"), UiTone.Warning).takeIf { property.reduced },
            ),
            action = UiNavigate(InmobiliariaScreenIds.Property, mapOf("id" to property.id.toString())),
        )
    }

    fun inventory(context: CrmScreens.Context, params: Map<String, String>): List<UiNode> {
        val s = InmobiliariaUi.session(context)
        val filter = InmobiliariaFilters.property(params, context.users, context.scope.userId)
        val zones = InmobiliariaService.zones(s)
        var page = (params["page"]?.toIntOrNull() ?: 1).coerceAtLeast(1)
        var result = InmobiliariaService.searchProperties(s, filter, InmobiliariaFilters.PageSize, (page - 1) * InmobiliariaFilters.PageSize)
        if (page > InmobiliariaFilters.pages(result.total)) {
            page = InmobiliariaFilters.pages(result.total)
            result = InmobiliariaService.searchProperties(s, filter, InmobiliariaFilters.PageSize, (page - 1) * InmobiliariaFilters.PageSize)
        }
        val all = InmobiliariaFilters.All
        val fields = listOf(
            UiField("q", tr("Buscar"), placeholder = tr("Codigo, titulo, zona o desarrollo")),
            UiField("fZone", tr("Zona"), type = "dropdown", options = listOf(all) + zones),
            UiField("fKind", tr("Tipo"), type = "dropdown", options = listOf(all) + PropertyKind.entries.map { it.label }),
            UiField("fStatus", tr("Estado"), type = "dropdown", options = listOf(all) + PropertyStatus.entries.map { it.label }),
            UiField(
                "fAgent", tr("Agente"), type = "dropdown",
                options = listOf(all) + listOfNotNull(InmobiliariaFilters.MineListings.takeIf { context.scope.userId != null }) + context.users.map { it.name },
            ),
            UiField("minPrice", tr("Precio desde")),
            UiField("maxPrice", tr("Precio hasta")),
        )
        val values = fields.associate { it.key to (params[it.key] ?: if (it.type == "dropdown") all else "") }
        val canCreate = s.access.canCreate
        return listOfNotNull(
            InmobiliariaUi.row(
                UiButton(tr("Nueva captacion"), UiNavigate(InmobiliariaScreenIds.Property)).takeIf { canCreate },
                UiButton(tr("Captaciones"), UiNavigate(InmobiliariaScreenIds.Intake), style = "secondary"),
            ),
            UiCard(
                listOf(
                    UiForm(
                        "filters", fields, values,
                        submit = UiButton(tr("Filtrar"), UiNavigate(InmobiliariaScreenIds.Inventory, formId = "filters"), style = "secondary"),
                        minFieldWidth = 200,
                    ),
                ),
            ),
            UiCard(
                listOf(UiList(result.items.map { propertyRow(context, it) }, emptyText = tr("No hay propiedades con esos filtros."))),
                title = tr("{0} propiedad(es)", result.total),
            ),
            InmobiliariaUi.pager(InmobiliariaScreenIds.Inventory, InmobiliariaFilters.keep(params), page, result.total),
        )
    }

    // ---- Captaciones ----

    fun intake(context: CrmScreens.Context, params: Map<String, String>): List<UiNode> {
        val s = InmobiliariaUi.session(context)
        val filter = InmobiliariaFilters.intake(params, context.scope.userId)
        var page = (params["page"]?.toIntOrNull() ?: 1).coerceAtLeast(1)
        var result = InmobiliariaService.searchProperties(s, filter, InmobiliariaFilters.PageSize, (page - 1) * InmobiliariaFilters.PageSize)
        if (page > InmobiliariaFilters.pages(result.total)) {
            page = InmobiliariaFilters.pages(result.total)
            result = InmobiliariaService.searchProperties(s, filter, InmobiliariaFilters.PageSize, (page - 1) * InmobiliariaFilters.PageSize)
        }
        val fields = listOfNotNull(
            UiField("q", tr("Buscar"), placeholder = tr("Codigo, titulo, zona o desarrollo")),
            UiField("fMine", tr("Mis proximos listados"), type = "boolean").takeIf { context.scope.userId != null },
        )
        val values = fields.associate { it.key to (params[it.key] ?: if (it.type == "boolean") "false" else "") }
        val keep = InmobiliariaFilters.keep(params)
        val cards = result.items.map { property ->
            UiCard(
                listOf(
                    UiText(
                        listOf(property.zone, property.kind.display, InmobiliariaFormat.money(property.price, property.currency), tr("Agente: {0}", InmobiliariaUi.agentName(context.users, property.listingAgentId)))
                            .filter { it.isNotEmpty() }.joinToString(" - "),
                        UiTextStyle.Caption,
                    ),
                ),
                title = tr("{0} - {1}", property.code, property.title),
                actions = listOfNotNull(
                    UiButton(tr("Abrir"), UiNavigate(InmobiliariaScreenIds.Property, mapOf("id" to property.id.toString())), style = "secondary"),
                    UiButton(
                        tr("Publicar"),
                        InmobiliariaUi.request(InmobiliariaScreenIds.Intake, "publish", keep + ("id" to property.id.toString()), confirm = tr("Publicar {0}: pasa a En venta. Continuar?", property.code)),
                    ).takeIf { s.access.canEditProperty(property) },
                ),
            )
        }
        return listOfNotNull(
            InmobiliariaUi.row(
                UiButton(tr("Nueva captacion"), UiNavigate(InmobiliariaScreenIds.Property)).takeIf { s.access.canCreate },
                UiButton(tr("Todas las propiedades"), UiNavigate(InmobiliariaScreenIds.Inventory), style = "secondary"),
            ),
            UiCard(
                listOf(
                    UiForm(
                        "filters", fields, values,
                        submit = UiButton(tr("Filtrar"), UiNavigate(InmobiliariaScreenIds.Intake, formId = "filters"), style = "secondary"),
                        minFieldWidth = 220,
                    ),
                ),
            ),
            UiText(tr("{0} propiedad(es) en captacion, pendientes de publicar.", result.total), UiTextStyle.Caption),
        ) + (if (cards.isEmpty()) listOf<UiNode>(UiText(tr("No hay captaciones pendientes."), UiTextStyle.Caption)) else cards) + listOfNotNull(
            InmobiliariaUi.pager(InmobiliariaScreenIds.Intake, keep, page, result.total),
        )
    }

    // ---- Ficha y formularios de la propiedad ----

    private fun dataFields(agentNames: List<String>, creating: Boolean): List<UiField> = buildList {
        add(UiField("title", tr("Titulo"), required = true))
        add(UiField("code", tr("Codigo"), help = tr("Vacio: se genera uno (P-0001).")))
        add(UiField("kind", tr("Tipo"), type = "dropdown", options = PropertyKind.entries.map { it.label }))
        add(UiField("zone", tr("Zona")))
        add(UiField("development", tr("Desarrollo")))
        add(UiField("agent", tr("Agente del listado"), type = "dropdown", options = listOf(NoAgent) + agentNames))
        add(
            UiField(
                "name", tr("Vendedor (cliente)"), placeholder = tr("Escribe para buscar un cliente"),
                suggest = UiSuggest(CrmScreens.CustomerSuggestRoute),
                help = tr("Solo clientes que ves. Vacio: sin vendedor."),
            ),
        )
        if (creating) add(UiField("price", tr("Precio"), help = tr("Por ejemplo 125000 o 1,250,000.50.")))
        add(UiField("lotArea", tr("Area del lote (m2)")))
        add(UiField("builtArea", tr("Area construida (m2)")))
        add(UiField("bedrooms", tr("Habitaciones")))
        add(UiField("bathrooms", tr("Banos")))
        add(UiField("currency", tr("Moneda"), help = tr("Codigo de 3 letras (USD). Vacio: la de los ajustes.")))
        add(UiField("publishedOn", tr("Fecha de publicacion"), type = "date"))
        add(UiField("wordpressListing", tr("Id del listado en el sitio web"), help = tr("Opcional: numero del listado en WordPress.")))
        add(UiField("description", tr("Descripcion"), type = "textarea"))
        add(UiField("notes", tr("Notas internas"), type = "textarea"))
    }

    private fun dataValues(context: CrmScreens.Context, property: Property?, settings: Settings): Map<String, String> {
        val ownerId = property?.ownerCustomerId
        val visibleOwner = ownerId?.let { InmobiliariaLookups.customerNames(context, listOf(it))[it] }
        val ownerName = if (ownerId == null) "" else visibleOwner ?: tr(OutOfScope)
        val defaultAgent = context.users.firstOrNull { it.id == context.scope.userId }?.name ?: NoAgent
        return mapOf(
            "title" to property?.title.orEmpty(),
            "code" to property?.code.orEmpty(),
            "kind" to (property?.kind ?: PropertyKind.Other).label,
            "zone" to property?.zone.orEmpty(),
            "development" to property?.development.orEmpty(),
            "agent" to if (property == null) defaultAgent else context.users.firstOrNull { it.id == property.listingAgentId }?.name ?: NoAgent,
            "name" to ownerName,
            // El id del vendedor solo viaja en el formulario si el usuario ve a ese cliente.
            "customerId" to if (visibleOwner != null) ownerId.toString() else "",
            "lotArea" to InmobiliariaFormat.field(property?.lotAreaM2),
            "builtArea" to InmobiliariaFormat.field(property?.builtAreaM2),
            "bedrooms" to property?.bedrooms?.toString().orEmpty(),
            "bathrooms" to InmobiliariaFormat.field(property?.bathrooms),
            "currency" to (property?.currency ?: settings.currency),
            "publishedOn" to property?.publishedOn?.toString().orEmpty(),
            "wordpressListing" to property?.wordpressListingId?.toString().orEmpty(),
            "description" to property?.description.orEmpty(),
            "notes" to property?.notes.orEmpty(),
        )
    }

    private fun wordpressCard(propertyId: UUID?, listing: String): UiNode {
        val id = propertyId?.toString()
        return UiCard(
            listOf(
                UiText(
                    if (id == null) tr("Capta una propiedad nueva con los datos de un listado del sitio web.") else tr("Completa lo que esta vacio con los datos del listado y lo vincula a esta propiedad."),
                    UiTextStyle.Caption,
                ),
                UiForm(
                    "propertyWp",
                    listOf(UiField("listing", tr("Id del listado en el sitio"), required = true, help = tr("Es el numero (ID) del listado en WordPress."))),
                    mapOf("listing" to listing),
                    submit = UiButton(
                        tr("Traer datos del sitio"),
                        InmobiliariaUi.request(InmobiliariaScreenIds.Property, "import_wp", listOfNotNull(id?.let { "id" to it }).toMap(), formId = "propertyWp"),
                        style = "secondary",
                    ),
                    minFieldWidth = 220,
                ),
            ),
            title = tr("Traer datos del sitio"),
            collapsible = true,
            expanded = false,
        )
    }

    fun property(context: CrmScreens.Context, params: Map<String, String>, submitted: Map<String, String>?): List<UiNode> {
        val s = InmobiliariaUi.session(context)
        val settings = InmobiliariaService.settings(s)
        val wordpress = CrmHost.wordpressActive(context.tenant)
        val sent = submitted.orEmpty()
        val id = params["id"]?.takeIf { it.isNotBlank() }
        if (id == null) {
            if (!s.access.canCreate) return InmobiliariaUi.message(tr("No tienes permiso para captar propiedades.")) + InmobiliariaUi.back(InmobiliariaScreenIds.Inventory, tr("Volver a Propiedades"))
            val form = UiForm(
                "propertyData",
                dataFields(context.users.map { it.name }, creating = true),
                dataValues(context, null, settings) + mapOf("price" to "") + sent,
                submit = UiButton(tr("Captar propiedad"), InmobiliariaUi.request(InmobiliariaScreenIds.Property, "save", formId = "propertyData")),
            )
            return listOfNotNull(
                InmobiliariaUi.back(InmobiliariaScreenIds.Inventory, tr("Volver a Propiedades")),
                UiCard(listOf(form), title = tr("Nueva captacion")),
                wordpressCard(null, sent["listing"].orEmpty()).takeIf { wordpress },
            )
        }
        val propertyId = InmobiliariaUi.uuid(id) ?: return InmobiliariaUi.message(tr("Esa propiedad no existe.")) + InmobiliariaUi.back(InmobiliariaScreenIds.Inventory, tr("Volver a Propiedades"))
        val detail = try {
            InmobiliariaService.getProperty(s, propertyId)
        } catch (failure: InmobiliariaException) {
            return InmobiliariaUi.message(failure.message.orEmpty()) + InmobiliariaUi.back(InmobiliariaScreenIds.Inventory, tr("Volver a Propiedades"))
        }
        val property = detail.property
        val canEdit = s.access.canEditProperty(property)
        val view = sheet(context, s, detail)
        val idParams = mapOf("id" to property.id.toString())
        val edit: List<UiNode> = if (!canEdit) emptyList() else {
            val linkRows = InmobiliariaLinkForms.rows(detail.links.size, submitted)
            val linkFields = (1..linkRows).flatMap { row ->
                listOf(
                    UiField(
                        "l${row}_label", tr("Enlace {0}: nombre", row),
                        help = if (row == 1) tr("Sugeridos: {0}.", InmobiliariaLinkForms.SuggestedLabels.joinToString(", ") { tr(it) }) else "",
                    ),
                    UiField("l${row}_url", tr("Enlace {0}: direccion", row), type = "url", placeholder = tr("https://")),
                )
            }
            val linkValues = (1..linkRows).flatMap { row ->
                val link = detail.links.getOrNull(row - 1)
                listOf("l${row}_label" to link?.label.orEmpty(), "l${row}_url" to link?.url.orEmpty())
            }.toMap()
            val formOpen = submitted != null
            listOf(
                UiCard(
                    listOf(
                        UiForm(
                            "propertyPrice",
                            listOf(UiField("price", tr("Nuevo precio"), required = true), UiField("note", tr("Nota del cambio"))),
                            mapOf("price" to InmobiliariaFormat.field(property.price), "note" to "") + sent.filterKeys { it == "price" || it == "note" },
                            submit = UiButton(tr("Cambiar precio"), InmobiliariaUi.request(InmobiliariaScreenIds.Property, "price", idParams, formId = "propertyPrice")),
                            minFieldWidth = 220,
                        ),
                        InmobiliariaUi.caption(tr("El precio anterior queda en el historial y, si baja, la propiedad se ve Rebajada.")),
                    ),
                    title = tr("Precio"),
                    collapsible = true,
                    expanded = false,
                ),
                UiCard(
                    listOf(
                        UiForm(
                            "propertyStatus",
                            listOf(UiField("status", tr("Estado"), type = "dropdown", options = PropertyStatus.entries.map { it.label })),
                            mapOf("status" to property.status.label),
                            submit = UiButton(tr("Cambiar estado"), InmobiliariaUi.request(InmobiliariaScreenIds.Property, "status", idParams, formId = "propertyStatus")),
                            minFieldWidth = 220,
                        ),
                    ),
                    title = tr("Estado"),
                    collapsible = true,
                    expanded = false,
                ),
                UiCard(
                    listOf(
                        UiForm(
                            "propertyData",
                            dataFields(context.users.map { it.name }, creating = false),
                            dataValues(context, property, settings) + sent.filterKeys { it != "price" && it != "note" },
                            submit = UiButton(tr("Guardar datos"), InmobiliariaUi.request(InmobiliariaScreenIds.Property, "save", idParams, formId = "propertyData")),
                        ),
                    ),
                    title = tr("Datos de la propiedad"),
                    collapsible = true,
                    expanded = formOpen && sent.containsKey("title"),
                ),
                UiCard(
                    listOf(
                        UiForm(
                            "propertyLinks", linkFields, linkValues + sent.filterKeys { it.startsWith("l") && it.contains('_') },
                            submit = UiButton(tr("Guardar enlaces"), InmobiliariaUi.request(InmobiliariaScreenIds.Property, "links", idParams, formId = "propertyLinks")),
                            minFieldWidth = 320,
                        ),
                        InmobiliariaUi.caption(tr("Deja vacia una fila para quitar ese enlace. Hasta {0} enlaces.", InmobiliariaService.MaxLinks)),
                    ),
                    title = tr("Enlaces por portal"),
                    collapsible = true,
                    expanded = false,
                ),
                wordpressCard(property.id, sent["listing"].orEmpty()).takeIf { wordpress },
            ).filterNotNull()
        }
        val leadCard = if (!s.access.canCreate) null else UiCard(
            listOf(
                UiForm(
                    "propertyLead",
                    listOf(UiField("lead", tr("Lead"), required = true, placeholder = tr("Nombre, correo o id del lead"), help = tr("Solo leads que ves."))),
                    mapOf("lead" to sent["lead"].orEmpty()),
                    submit = UiButton(tr("Vincular"), InmobiliariaUi.request(InmobiliariaScreenIds.Property, "link_lead", idParams, formId = "propertyLead"), style = "secondary"),
                    minFieldWidth = 260,
                ),
            ),
            title = tr("Vincular a un lead"),
            collapsible = true,
            expanded = false,
        )
        return view + edit + listOfNotNull(leadCard)
    }

    private fun sheet(context: CrmScreens.Context, s: InmobiliariaSession, detail: PropertyDetail): List<UiNode> {
        val property = detail.property
        val canEdit = s.access.canEditProperty(property)
        val idParams = mapOf("id" to property.id.toString())
        val ownerName = property.ownerCustomerId?.let { InmobiliariaLookups.customerNames(context, listOf(it))[it] ?: tr(OutOfScope) }
        val header = UiRow(
            listOfNotNull(
                UiText(tr("{0} - {1}", property.code, property.title), UiTextStyle.Title),
                UiStatus(property.status.display, statusTone(property.status)),
                UiStatus(tr("Rebajada"), UiTone.Warning).takeIf { property.reduced },
            ),
        )
        val actions = InmobiliariaUi.row(
            UiButton(tr("Volver a Propiedades"), UiNavigate(InmobiliariaScreenIds.Inventory), style = "secondary"),
            UiButton(tr("Publicar"), InmobiliariaUi.request(InmobiliariaScreenIds.Property, "publish", idParams, confirm = tr("Publicar {0}: pasa a En venta. Continuar?", property.code)))
                .takeIf { canEdit && property.status == PropertyStatus.Intake },
            UiButton(
                tr("Nueva transaccion"), UiNavigate(InmobiliariaScreenIds.Deal, mapOf("property" to property.id.toString())),
                style = "secondary",
            ).takeIf { s.access.canCreate && property.status != PropertyStatus.Sold && property.status != PropertyStatus.Withdrawn },
        )
        val metrics = UiGrid(
            listOfNotNull(
                UiMetric(tr("Precio"), InmobiliariaFormat.money(property.price, property.currency)),
                property.previousPrice?.let { UiMetric(tr("Precio anterior"), InmobiliariaFormat.money(it, property.currency), if (property.reduced) tr("Rebajada") else "", UiTone.Warning) },
                UiMetric(tr("Por m2 de lote"), property.pricePerLotM2?.let { InmobiliariaFormat.money(it, property.currency) } ?: InmobiliariaFormat.Empty),
                UiMetric(tr("Por m2 construido"), property.pricePerBuiltM2?.let { InmobiliariaFormat.money(it, property.currency) } ?: InmobiliariaFormat.Empty),
                UiMetric(tr("Estado"), property.status.display),
            ),
            minItemWidth = 180,
        )
        val facts = listOf(
            tr("Tipo") to property.kind.display,
            tr("Zona") to property.zone,
            tr("Desarrollo") to property.development,
            tr("Agente del listado") to InmobiliariaUi.agentName(context.users, property.listingAgentId),
            tr("Vendedor") to ownerName.orEmpty(),
            tr("Area del lote") to property.lotAreaM2?.let { tr("{0} m2", InmobiliariaMath.plain(it)) }.orEmpty(),
            tr("Area construida") to property.builtAreaM2?.let { tr("{0} m2", InmobiliariaMath.plain(it)) }.orEmpty(),
            tr("Habitaciones") to property.bedrooms?.toString().orEmpty(),
            tr("Banos") to property.bathrooms?.let { InmobiliariaMath.plain(it) }.orEmpty(),
            tr("Publicada") to property.publishedOn?.toString().orEmpty(),
            tr("Vendida") to property.soldOn?.toString().orEmpty(),
            tr("Listado del sitio web") to property.wordpressListingId?.toString().orEmpty(),
            tr("Descripcion") to property.description,
            tr("Notas") to property.notes,
        ).filter { it.second.isNotBlank() }
        val data = UiCard(
            listOf(UiColumn(facts.map { (label, value) -> UiText(tr("{0}: {1}", label, value)) }, spacing = 4)),
            title = tr("Datos"),
        )
        // Solo enlaces http/https validos: el servicio ya los exige al guardar, pero la ficha no confia en lo guardado.
        val portals = detail.links.filter { InmobiliariaInput.isValidUrl(it.url) }
        val links = UiCard(
            listOf(UiRow(portals.map { UiButton(tr(it.label), UiOpenUrl(it.url), style = "secondary") })),
            title = tr("Enlaces por portal"),
        ).takeIf { portals.isNotEmpty() }
        val history = UiCard(
            listOf(
                UiTable(
                    listOf(UiTableColumn("date", tr("Fecha")), UiTableColumn("old", tr("Anterior")), UiTableColumn("new", tr("Nuevo")), UiTableColumn("by", tr("Por")), UiTableColumn("note", tr("Nota"))),
                    detail.priceChanges.map { change ->
                        mapOf(
                            "date" to InmobiliariaFormat.timestamp(change.changedAt),
                            "old" to InmobiliariaFormat.money(change.oldPrice, property.currency),
                            "new" to InmobiliariaFormat.money(change.newPrice, property.currency),
                            "by" to InmobiliariaUi.agentName(context.users, change.changedBy),
                            "note" to change.note,
                        )
                    },
                    emptyText = tr("Sin cambios de precio."),
                ),
            ),
            title = tr("Historial de precios"),
        )
        val buyers = InmobiliariaLookups.leadNames(context, detail.deals.mapNotNull { it.buyerLeadId })
        val deals = UiCard(
            listOf(
                UiList(
                    detail.deals.map { deal ->
                        UiListItem(
                            title = listOfNotNull(deal.stageName, deal.buyerLeadId?.let { buyers[it] }).joinToString(" - "),
                            subtitle = tr("Acordado {0} - Comision {1}", InmobiliariaFormat.money(deal.agreedPrice, deal.currency), InmobiliariaFormat.money(deal.commissionTotal, deal.currency)),
                            trailing = deal.estimatedCloseOn?.let { tr("Cierre {0}", it) }.orEmpty(),
                            badges = listOf(UiBadge(deal.stageOutcome.display, dealTone(deal.stageOutcome))),
                            action = UiNavigate(InmobiliariaScreenIds.Deal, mapOf("id" to deal.id.toString())),
                        )
                    },
                    emptyText = tr("Sin transacciones visibles para ti."),
                ),
            ),
            title = tr("Transacciones"),
        )
        return listOfNotNull(header, actions, metrics, data, links, history, deals)
    }

    fun dealTone(outcome: DealOutcome) = when (outcome) {
        DealOutcome.Open -> UiTone.Info
        DealOutcome.Won -> UiTone.Success
        DealOutcome.Lost -> UiTone.Danger
    }

    // ---- Acciones ----

    private fun requireId(params: Map<String, String>): UUID =
        InmobiliariaUi.uuid(params["id"]) ?: throw InmobiliariaException(tr("Esa propiedad no existe."))

    // Convierte el formulario en lo que lee InmobiliariaForms.parseProperty: agente "Sin agente" = ninguno y el vendedor es el cliente elegido.
    // [currentOwner] es el vendedor actual con el texto con que se mostro: si el texto no cambia, se conserva aunque el usuario no lo vea.
    private fun propertyValues(values: Map<String, String>, currentOwner: Pair<UUID, String>?): Map<String, String> {
        val agent = values["agent"].orEmpty().takeIf { it != NoAgent }.orEmpty()
        val owner = InmobiliariaDealForms.pickCustomer(values["name"], values["customerId"], currentOwner, msg("El vendedor"))?.toString().orEmpty()
        return values + mapOf("agent" to agent, "ownerCustomer" to owner)
    }

    // Id de propiedad opcional de la peticion: vacio = ninguna; escrito pero invalido = error (nunca se crea una nueva por un id roto).
    private fun optionalId(params: Map<String, String>): UUID? {
        val raw = params["id"]?.trim().orEmpty()
        if (raw.isEmpty()) return null
        return InmobiliariaUi.uuid(raw) ?: throw InmobiliariaException(tr("Esa propiedad no existe."))
    }

    fun action(context: CrmScreens.Context, s: InmobiliariaSession, screen: String, op: String, params: Map<String, String>, values: Map<String, String>): CrmExtensionActionResult {
        val clean = params - "op"
        if (screen == InmobiliariaScreenIds.Intake) {
            if (op != "publish") return InmobiliariaUi.failure(screen, tr("Esa accion no existe."), clean, values)
            val property = InmobiliariaService.publish(s, requireId(params))
            return InmobiliariaUi.success(screen, tr("{0} publicada: ahora esta En venta.", property.code), clean - "id")
        }
        val screenId = InmobiliariaScreenIds.Property
        return when (op) {
            "save" -> {
                val id = optionalId(params)
                val currentOwner = id?.let { InmobiliariaService.getProperty(s, it).property.ownerCustomerId }?.let { owner ->
                    owner to (InmobiliariaLookups.customerNames(context, listOf(owner))[owner] ?: tr(OutOfScope))
                }
                val fields = InmobiliariaForms.parseProperty(propertyValues(values, currentOwner)) { name -> InmobiliariaService.findAgent(s, name).id }
                if (id == null) {
                    val price = InmobiliariaInput.parseMoney(values["price"], msg("El precio"))
                    val created = InmobiliariaService.createProperty(s, fields, price)
                    InmobiliariaUi.success(screenId, tr("Propiedad {0} captada.", created.code), mapOf("id" to created.id.toString()))
                } else {
                    val updated = InmobiliariaService.updateProperty(s, id, fields)
                    InmobiliariaUi.success(screenId, tr("Propiedad {0} guardada.", updated.code), mapOf("id" to updated.id.toString()))
                }
            }
            "price" -> {
                val price = InmobiliariaInput.parseMoney(values["price"], msg("El precio")) ?: throw InmobiliariaException(tr("Escribe el nuevo precio."))
                val property = InmobiliariaService.changePrice(s, requireId(params), price, values["note"].orEmpty())
                InmobiliariaUi.success(screenId, tr("Precio actualizado a {0}.", InmobiliariaFormat.money(property.price, property.currency)), clean)
            }
            "status" -> {
                val status = PropertyStatus.parse(values["status"]) ?: throw InmobiliariaException(tr("Elige el estado."))
                val property = InmobiliariaService.changeStatus(s, requireId(params), status)
                InmobiliariaUi.success(screenId, tr("{0} ahora esta {1}.", property.code, property.status.display), clean)
            }
            "publish" -> {
                val property = InmobiliariaService.publish(s, requireId(params))
                InmobiliariaUi.success(screenId, tr("{0} publicada: ahora esta En venta.", property.code), clean)
            }
            "links" -> {
                val links = InmobiliariaService.replaceLinks(s, requireId(params), InmobiliariaLinkForms.parse(values))
                InmobiliariaUi.success(screenId, if (links.isEmpty()) tr("Sin enlaces por portal.") else tr("Enlaces guardados ({0}).", links.size), clean)
            }
            "link_lead" -> {
                val propertyId = requireId(params)
                val leadId = InmobiliariaLookups.resolveLead(context, values["lead"].orEmpty()) ?: throw InmobiliariaException(tr("Escribe el lead."))
                val created = InmobiliariaService.linkToLead(s, propertyId, leadId)
                InmobiliariaUi.success(screenId, if (created) tr("Propiedad vinculada al lead.") else tr("Esa propiedad ya estaba vinculada al lead."), clean)
            }
            "import_wp" -> {
                val listing = values["listing"].orEmpty().trim().toLongOrNull()?.takeIf { it > 0 } ?: throw InmobiliariaException(tr("El id del listado debe ser un numero entero positivo."))
                val imported = InmobiliariaService.importFromWordPress(s, listing, optionalId(params))
                InmobiliariaUi.success(screenId, tr("Datos del sitio traidos a {0}.", imported.property.code), mapOf("id" to imported.property.id.toString()), imported.warnings)
            }
            else -> InmobiliariaUi.failure(screenId, tr("Esa accion no existe."), clean, values)
        }
    }
}
