package com.ideasdeveloper.bms.extensions.inmobiliaria

import com.ideasdeveloper.bms.modules.crm.CrmExtensionActionResult
import com.ideasdeveloper.bms.modules.crm.CrmHost
import com.ideasdeveloper.bms.modules.crm.CrmItems
import com.ideasdeveloper.bms.modules.crm.CrmLead
import com.ideasdeveloper.bms.modules.crm.CrmScreens
import com.ideasdeveloper.bms.modules.crm.CrmUser
import com.ideasdeveloper.bms.server.i18n.msg
import com.ideasdeveloper.bms.server.i18n.tr
import com.ideasdeveloper.bms.ui.UiBadge
import com.ideasdeveloper.bms.ui.UiBarChart
import com.ideasdeveloper.bms.ui.UiButton
import com.ideasdeveloper.bms.ui.UiCard
import com.ideasdeveloper.bms.ui.UiChartPoint
import com.ideasdeveloper.bms.ui.UiField
import com.ideasdeveloper.bms.ui.UiForm
import com.ideasdeveloper.bms.ui.UiGrid
import com.ideasdeveloper.bms.ui.UiList
import com.ideasdeveloper.bms.ui.UiListItem
import com.ideasdeveloper.bms.ui.UiMetric
import com.ideasdeveloper.bms.ui.UiNavigate
import com.ideasdeveloper.bms.ui.UiNode
import com.ideasdeveloper.bms.ui.UiRow
import com.ideasdeveloper.bms.ui.UiStatus
import com.ideasdeveloper.bms.ui.UiSuggest
import com.ideasdeveloper.bms.ui.UiTable
import com.ideasdeveloper.bms.ui.UiTableColumn
import com.ideasdeveloper.bms.ui.UiText
import com.ideasdeveloper.bms.ui.UiTextStyle
import com.ideasdeveloper.bms.ui.UiTone
import java.math.BigDecimal
import java.util.UUID

/** Lectura pura de las partes de una transaccion y de las etapas (filas numeradas de un formulario). */
object InmobiliariaDealForms {
    val NoAgent = msg("Ninguno")
    const val MaxStages = 30

    /**
     * Partes con algo escrito (filas p1_role, p1_agent, p1_customer, p1_name, p1_share, p1_notes...); las filas vacias se saltan.
     * [keep] relaciona el numero de fila con el id de la parte actual cuyo cliente el usuario no ve: el servicio conserva ese cliente.
     */
    fun parseParties(values: Map<String, String>, agents: List<CrmUser>, maxRows: Int = InmobiliariaService.MaxParties, keep: Map<Int, UUID> = emptyMap()): List<PartyInput> =
        (1..maxRows).mapNotNull { row ->
            fun text(key: String) = values["p${row}_$key"].orEmpty().trim()
            val agent = text("agent").takeIf { it != NoAgent }.orEmpty()
            val customer = text("customer")
            val name = text("name")
            val share = text("share")
            val notes = text("notes")
            if (agent.isEmpty() && customer.isEmpty() && name.isEmpty() && share.isEmpty() && notes.isEmpty()) return@mapNotNull null
            val role = PartyRole.parse(text("role")) ?: throw InmobiliariaException(tr("Elige el rol de la parte {0}.", row))
            val user = if (agent.isEmpty()) null else agents.firstOrNull { it.name == agent }?.id ?: throw InmobiliariaException(tr("La parte {0} tiene un agente desconocido.", row))
            PartyInput(
                role = role,
                userId = user,
                customerId = InmobiliariaInput.parseUuid(customer, tr("El cliente de la parte {0}", row)),
                name = name,
                sharePct = InmobiliariaInput.parsePercent(share, tr("El porcentaje de la parte {0}", row)) ?: BigDecimal.ZERO,
                notes = notes,
                keepPartyId = keep[row],
            )
        }

    /** Parametro de la peticion con las partes cuyo cliente no se muestra: "fila:idParte,fila:idParte". */
    fun keepParam(rows: Map<Int, UUID>): String = rows.entries.joinToString(",") { "${it.key}:${it.value}" }

    /** Lee [keepParam]; lo que no se entienda se ignora (el servicio solo acepta ids de partes actuales de la transaccion). */
    fun parseKeep(raw: String?): Map<Int, UUID> =
        raw.orEmpty().split(',').mapNotNull { entry ->
            val row = entry.substringBefore(':').trim().toIntOrNull() ?: return@mapNotNull null
            val id = runCatching { UUID.fromString(entry.substringAfter(':', "").trim()) }.getOrNull() ?: return@mapNotNull null
            row to id
        }.toMap()

    /**
     * Cliente elegido en un selector (campo "name" con sugerencias y "customerId"). [current] es el cliente que el formulario traia
     * con el texto con que se mostro. Texto vacio = ninguno; texto igual al mostrado = se conserva el actual (el id del formulario no se
     * toma de ahi); texto distinto sin un cliente nuevo elegido de la lista = error.
     */
    fun pickCustomer(name: String?, customerId: String?, current: Pair<UUID, String>?, label: String): UUID? {
        val typed = name.orEmpty().trim()
        if (typed.isEmpty()) return null
        val picked = InmobiliariaInput.parseUuid(customerId, label)
        if (picked != null && picked != current?.first) return picked
        if (current != null && typed == current.second) return current.first
        throw InmobiliariaException(tr("Elige el cliente de la lista."))
    }

    /** Cuantas filas de partes dibujar: las existentes y tres vacias mas (al menos 4), o las que trajo un formulario devuelto con error. */
    fun partyRows(existing: Int, submitted: Map<String, String>?, maxRows: Int = InmobiliariaService.MaxParties): Int =
        maxOf(existing + 3, 4, sentRows(submitted, "p")).coerceAtMost(maxRows)

    fun stageRows(existing: Int, submitted: Map<String, String>?): Int = maxOf(existing + 3, sentRows(submitted, "s")).coerceAtMost(MaxStages)

    private fun sentRows(submitted: Map<String, String>?, prefix: String): Int {
        val pattern = Regex("^$prefix(\\d+)_")
        return submitted?.keys?.mapNotNull { key -> pattern.find(key)?.groupValues?.get(1)?.toIntOrNull() }?.maxOrNull() ?: 0
    }

    /**
     * Etapas del formulario (s1_name, s1_outcome, s1_order...). [ids] son los ids con que se dibujo cada fila, en orden: la fila i con id
     * es esa etapa existente; una fila con id y sin nombre se quita (el servicio no deja borrar una etapa con transacciones).
     * El resultado va ordenado por el campo orden (y por fila si empatan).
     */
    fun parseStages(values: Map<String, String>, ids: List<UUID>, rows: Int): List<StageInput> {
        val entries = (1..rows).mapNotNull { row ->
            fun text(key: String) = values["s${row}_$key"].orEmpty().trim()
            val name = text("name")
            if (name.isEmpty()) return@mapNotNull null
            val outcome = DealOutcome.parse(text("outcome")) ?: throw InmobiliariaException(tr("Elige el resultado de la etapa '{0}' (Abierta, Ganada o Perdida).", name))
            val order = text("order").ifEmpty { row.toString() }.toIntOrNull() ?: throw InmobiliariaException(tr("El orden de la etapa '{0}' debe ser un numero entero.", name))
            Triple(order, row, StageInput(ids.getOrNull(row - 1), name, outcome))
        }
        return entries.sortedWith(compareBy({ it.first }, { it.second })).map { it.third }
    }
}

/** Pantallas de transacciones: lista con resumen por mes, ficha, alta y edicion, etapas y el panel del lead. */
internal object InmobiliariaDealScreens {
    private val LeadOutOfScope = msg("(lead fuera de tu alcance)")
    private val CustomerOutOfScope = msg("(cliente fuera de tu alcance)")

    private fun stageTone(outcome: DealOutcome) = InmobiliariaPropertyScreens.dealTone(outcome)

    // ---- Lista de cierres ----

    fun deals(context: CrmScreens.Context, params: Map<String, String>): List<UiNode> {
        val s = InmobiliariaUi.session(context)
        val stages = InmobiliariaService.stages(s)
        val filter = InmobiliariaFilters.deal(params, stages, context.users, context.scope.userId)
        var page = (params["page"]?.toIntOrNull() ?: 1).coerceAtLeast(1)
        var result = InmobiliariaService.listDeals(s, filter, InmobiliariaFilters.PageSize, (page - 1) * InmobiliariaFilters.PageSize)
        if (page > InmobiliariaFilters.pages(result.total)) {
            page = InmobiliariaFilters.pages(result.total)
            result = InmobiliariaService.listDeals(s, filter, InmobiliariaFilters.PageSize, (page - 1) * InmobiliariaFilters.PageSize)
        }
        val buyers = InmobiliariaLookups.leadNames(context, result.items.mapNotNull { it.buyerLeadId })
        val all = InmobiliariaFilters.All
        val fields = listOf(
            UiField("fStage", tr("Etapa"), type = "dropdown", options = listOf(all) + stages.map { it.name }),
            UiField(
                "fAgent", tr("Agente"), type = "dropdown",
                options = listOf(all) + listOfNotNull(InmobiliariaFilters.MineDeals.takeIf { context.scope.userId != null }) + context.users.map { it.name },
            ),
            UiField("fOpen", tr("Solo abiertas"), type = "boolean"),
        )
        val values = fields.associate { it.key to (params[it.key] ?: if (it.type == "dropdown") all else "false") }
        val list = UiList(
            result.items.map { deal ->
                UiListItem(
                    title = tr("{0} - {1}", deal.propertyCode, deal.propertyTitle),
                    subtitle = listOfNotNull(
                        deal.buyerLeadId?.let { tr("Comprador: {0}", buyers[it] ?: tr(LeadOutOfScope)) },
                        tr("Acordado {0} - Comision {1}", InmobiliariaFormat.money(deal.agreedPrice, deal.currency), InmobiliariaFormat.money(deal.commissionTotal, deal.currency)),
                        tr("Cierre estimado: {0} - Cierre real: {1}", InmobiliariaFormat.date(deal.estimatedCloseOn), InmobiliariaFormat.date(deal.closedOn)),
                    ).joinToString("\n"),
                    trailing = InmobiliariaFormat.money(deal.agreedPrice, deal.currency),
                    badges = listOf(UiBadge(deal.stageName, stageTone(deal.stageOutcome))),
                    action = UiNavigate(InmobiliariaScreenIds.Deal, mapOf("id" to deal.id.toString())),
                )
            },
            emptyText = tr("No hay transacciones con esos filtros."),
        )
        return listOfNotNull(
            InmobiliariaUi.row(
                UiButton(tr("Propiedades"), UiNavigate(InmobiliariaScreenIds.Inventory), style = "secondary"),
                UiButton(tr("Etapas"), UiNavigate(InmobiliariaScreenIds.Stages), style = "secondary").takeIf { s.access.canEditStages },
            ),
            closingsCard(s, filter.agentId),
            UiCard(
                listOf(
                    UiForm(
                        "filters", fields, values,
                        submit = UiButton(tr("Filtrar"), UiNavigate(InmobiliariaScreenIds.Deals, formId = "filters"), style = "secondary"),
                        minFieldWidth = 200,
                    ),
                ),
            ),
            UiCard(listOf(list), title = tr("{0} transaccion(es)", result.total)),
            InmobiliariaUi.pager(InmobiliariaScreenIds.Deals, InmobiliariaFilters.keep(params), page, result.total),
        )
    }

    /** Etiquetas del grafico: el mes, y la moneda solo si hay meses en monedas distintas. */
    fun chartPoints(months: List<ClosingMonth>): List<UiChartPoint> {
        val mixed = months.map { it.currency }.toSet().size > 1
        return months.map { UiChartPoint(InmobiliariaFormat.month(it.month) + if (mixed) " (${it.currency})" else "", it.totalCommission.toDouble()) }
    }

    private fun closingsCard(s: InmobiliariaSession, agentId: UUID?): UiNode {
        val months = InmobiliariaService.closingsByMonth(s, agentId)
        if (months.isEmpty()) {
            return UiCard(listOf(InmobiliariaUi.caption(tr("No hay cierres estimados: las transacciones abiertas con fecha de cierre estimado aparecen aqui."))), title = tr("Cierres estimados por mes"))
        }
        return UiCard(
            listOf(
                UiTable(
                    listOf(UiTableColumn("month", tr("Mes")), UiTableColumn("count", tr("Transacciones")), UiTableColumn("price", tr("Precio acordado")), UiTableColumn("commission", tr("Comision"))),
                    months.map { month ->
                        mapOf(
                            "month" to InmobiliariaFormat.month(month.month),
                            "count" to month.count.toString(),
                            "price" to InmobiliariaFormat.money(month.totalPrice, month.currency),
                            "commission" to InmobiliariaFormat.money(month.totalCommission, month.currency),
                        )
                    },
                ),
                UiBarChart(chartPoints(months)),
                InmobiliariaUi.caption(if (agentId != null) tr("Comision estimada por mes de cierre, de las transacciones abiertas del agente elegido.") else tr("Comision estimada por mes de cierre, de las transacciones abiertas.")),
            ),
            title = tr("Cierres estimados por mes"),
        )
    }

    // ---- Formulario de la transaccion ----

    private fun dealFields(creating: Boolean, stageNames: List<String>, agentNames: List<String>, partyRows: Int, defaultPct: String): List<UiField> = buildList {
        add(UiField("lead", tr("Lead comprador"), section = tr("Comprador"), placeholder = tr("Nombre, correo o id del lead"), help = tr("Solo leads que ves.")))
        add(
            UiField(
                "name", tr("Cliente comprador"), section = tr("Comprador"), placeholder = tr("Escribe para buscar un cliente"),
                suggest = UiSuggest(CrmScreens.CustomerSuggestRoute), help = tr("Opcional: sin cliente se usa el del lead."),
            ),
        )
        if (creating && stageNames.isNotEmpty()) add(UiField("stage", tr("Etapa"), type = "dropdown", options = stageNames, section = tr("Transaccion")))
        add(UiField("agreedPrice", tr("Precio acordado"), section = tr("Transaccion"), help = tr("Vacio: el precio de la propiedad.")))
        add(UiField("commissionPct", tr("Comision (%)"), section = tr("Transaccion"), help = tr("Porcentaje sobre el precio acordado. Vacio: {0} %.", defaultPct)))
        add(UiField("commissionAmount", tr("Comision fija (monto)"), section = tr("Transaccion"), help = tr("Opcional: reemplaza el calculo con el porcentaje.")))
        add(UiField("estimatedCloseOn", tr("Cierre estimado"), type = "date", section = tr("Transaccion")))
        add(UiField("comments", tr("Comentarios"), type = "textarea", section = tr("Transaccion")))
        for (row in 1..partyRows) {
            val section = tr("Parte {0}", row)
            add(UiField("p${row}_role", tr("Rol"), type = "dropdown", options = PartyRole.entries.map { it.label }, section = section))
            add(UiField("p${row}_agent", tr("Agente"), type = "dropdown", options = listOf(InmobiliariaDealForms.NoAgent) + agentNames, section = section))
            add(UiField("p${row}_name", tr("Nombre externo"), section = section, help = tr("Abogado, corredor u otra persona que no es usuario.")))
            add(UiField("p${row}_customer", tr("Cliente (id)"), section = section, help = tr("Opcional: id de un cliente que ves.")))
            add(UiField("p${row}_share", tr("% de la comision"), section = section, help = tr("Las partes deberian sumar 100 %.")))
            add(UiField("p${row}_notes", tr("Notas"), section = section))
        }
    }

    // El id del cliente de una parte solo se muestra si el usuario lo ve.
    private fun partyValues(row: Int, party: DealParty?, users: List<CrmUser>, visibleCustomers: Set<UUID>): Map<String, String> = mapOf(
        "p${row}_role" to (party?.role ?: PartyRole.Other).label,
        "p${row}_agent" to (party?.userId?.let { id -> users.firstOrNull { it.id == id }?.name } ?: InmobiliariaDealForms.NoAgent),
        "p${row}_name" to party?.name.orEmpty(),
        "p${row}_customer" to party?.customerId?.takeIf { it in visibleCustomers }?.toString().orEmpty(),
        "p${row}_share" to party?.let { InmobiliariaFormat.field(it.sharePct) }.orEmpty(),
        "p${row}_notes" to party?.notes.orEmpty(),
    )

    private fun dealForm(
        context: CrmScreens.Context,
        stageNames: List<String>,
        settings: Settings,
        deal: Deal?,
        base: Map<String, String>,
        submitted: Map<String, String>?,
        submit: UiButton,
        visibleCustomers: Set<UUID> = emptySet(),
    ): UiForm {
        val rows = InmobiliariaDealForms.partyRows(deal?.parties?.size ?: 0, submitted)
        val parties = (1..rows).flatMap { row -> partyValues(row, deal?.parties?.getOrNull(row - 1), context.users, visibleCustomers).toList() }.toMap()
        val sent = submitted.orEmpty()
        val filled = (1..rows).filter { row ->
            deal?.parties?.getOrNull(row - 1) != null ||
                listOf("agent", "name", "customer", "share", "notes").any { key -> sent["p${row}_$key"].orEmpty().let { it.isNotBlank() && it != InmobiliariaDealForms.NoAgent } }
        }
        return UiForm(
            "dealForm",
            dealFields(deal == null, stageNames, context.users.map { it.name }, rows, InmobiliariaFormat.field(settings.defaultCommissionPct)),
            base + parties + submitted.orEmpty(),
            submit = submit,
            collapsibleSections = true,
            openSections = listOf(tr("Comprador"), tr("Transaccion")) + filled.map { tr("Parte {0}", it) },
        )
    }

    // ---- Alta y ficha ----

    fun deal(context: CrmScreens.Context, params: Map<String, String>, submitted: Map<String, String>?): List<UiNode> {
        val s = InmobiliariaUi.session(context)
        val back = InmobiliariaUi.back(InmobiliariaScreenIds.Deals, tr("Volver a Cierres"))
        val id = params["id"]?.takeIf { it.isNotBlank() }
        if (id == null) return creation(context, s, params, submitted, back)
        val dealId = InmobiliariaUi.uuid(id) ?: return InmobiliariaUi.message(tr("Esa transaccion no existe.")) + back
        val deal = try {
            InmobiliariaService.getDeal(s, dealId)
        } catch (failure: InmobiliariaException) {
            return InmobiliariaUi.message(failure.message.orEmpty()) + back
        }
        return sheet(context, s, deal, submitted, back)
    }

    private fun creation(context: CrmScreens.Context, s: InmobiliariaSession, params: Map<String, String>, submitted: Map<String, String>?, back: UiButton): List<UiNode> {
        if (!s.access.canCreate) return InmobiliariaUi.message(tr("No tienes permiso para abrir transacciones.")) + back
        val ref = params["property"].orEmpty()
        if (ref.isBlank()) {
            return InmobiliariaUi.message(tr("Abre una propiedad y toca Nueva transaccion para elegir de cual es."), UiTone.Info) +
                InmobiliariaUi.back(InmobiliariaScreenIds.Inventory, tr("Ir a Propiedades"))
        }
        val property = try {
            InmobiliariaService.findPropertyByRef(s, ref)
        } catch (failure: InmobiliariaException) {
            return InmobiliariaUi.message(failure.message.orEmpty()) + back
        }
        val stages = InmobiliariaService.stages(s)
        val settings = InmobiliariaService.settings(s)
        val base = mapOf(
            "lead" to "", "name" to "", "customerId" to "",
            "stage" to (stages.firstOrNull { it.outcome == DealOutcome.Open }?.name.orEmpty()),
            "agreedPrice" to InmobiliariaFormat.field(property.price),
            "commissionPct" to InmobiliariaFormat.field(settings.defaultCommissionPct),
            "commissionAmount" to "", "estimatedCloseOn" to "", "comments" to "",
        )
        val form = dealForm(
            context, stages.map { it.name }, settings, null, base, submitted,
            UiButton(tr("Abrir transaccion"), InmobiliariaUi.request(InmobiliariaScreenIds.Deal, "create", mapOf("property" to property.id.toString()), formId = "dealForm")),
        )
        return listOf(
            back,
            UiText(tr("Nueva transaccion: {0} - {1}", property.code, property.title), UiTextStyle.Subtitle),
            UiText(tr("Precio de la propiedad: {0}", InmobiliariaFormat.money(property.price, property.currency)), UiTextStyle.Caption),
            UiCard(listOf(form), title = tr("Datos de la transaccion")),
        )
    }

    private fun sheet(context: CrmScreens.Context, s: InmobiliariaSession, deal: Deal, submitted: Map<String, String>?, back: UiButton): List<UiNode> {
        val canEdit = s.access.canEditDeal(deal)
        val idParams = mapOf("id" to deal.id.toString())
        val customerIds = (deal.parties.mapNotNull { it.customerId } + listOfNotNull(deal.buyerCustomerId)).distinct()
        val customers = InmobiliariaLookups.customerNames(context, customerIds)
        val leads = InmobiliariaLookups.leadNames(context, listOfNotNull(deal.buyerLeadId))
        val buyerLead = deal.buyerLeadId?.let { leads[it] }
        val header = UiRow(
            listOf(
                UiText(tr("{0} - {1}", deal.propertyCode, deal.propertyTitle), UiTextStyle.Title),
                UiStatus(deal.stageName, stageTone(deal.stageOutcome)),
            ),
        )
        val actions = InmobiliariaUi.row(
            back,
            UiButton(tr("Ver propiedad"), UiNavigate(InmobiliariaScreenIds.Property, mapOf("id" to deal.propertyId.toString())), style = "secondary"),
            deal.buyerLeadId?.takeIf { buyerLead != null }?.let { UiButton(tr("Ver lead comprador"), UiNavigate("lead", mapOf("id" to it.toString())), style = "secondary") },
        )
        val stageCard = if (!canEdit) null else {
            val stages = InmobiliariaService.stages(s)
            UiCard(
                listOf(
                    UiRow(
                        stages.map { stage ->
                            UiButton(
                                stage.name,
                                InmobiliariaUi.request(
                                    InmobiliariaScreenIds.Deal, "move", idParams + ("stage" to stage.id.toString()),
                                    confirm = tr("Mover la transaccion a {0}?", stage.name).takeIf { stage.outcome != DealOutcome.Open },
                                ),
                                style = if (stage.id == deal.stageId) "primary" else "secondary",
                                enabled = stage.id != deal.stageId,
                            )
                        },
                    ),
                    InmobiliariaUi.caption(tr("Una etapa ganada marca la propiedad como Vendida; salir de ella lo deshace.")),
                ),
                title = tr("Mover de etapa"),
            )
        }
        val metrics = UiGrid(
            listOf(
                UiMetric(tr("Precio acordado"), InmobiliariaFormat.money(deal.agreedPrice, deal.currency)),
                UiMetric(tr("Comision total"), InmobiliariaFormat.money(deal.commissionTotal, deal.currency), if (deal.commissionAmount != null) tr("Monto fijo") else InmobiliariaFormat.percent(deal.commissionPct)),
                UiMetric(tr("Cierre estimado"), InmobiliariaFormat.date(deal.estimatedCloseOn)),
                UiMetric(tr("Cierre real"), InmobiliariaFormat.date(deal.closedOn)),
                UiMetric(tr("Etapa"), deal.stageName, deal.stageOutcome.display),
            ),
            minItemWidth = 180,
        )
        val warning = deal.shareWarning?.let { UiText(it, tone = UiTone.Warning) }
        val partyTable = UiCard(
            listOfNotNull(
                UiTable(
                    listOf(UiTableColumn("role", tr("Rol")), UiTableColumn("who", tr("Quien")), UiTableColumn("pct", tr("% de la comision")), UiTableColumn("amount", tr("Monto")), UiTableColumn("notes", tr("Notas"))),
                    deal.parties.map { party ->
                        mapOf(
                            "role" to party.role.display,
                            "who" to partyName(context.users, customers, party),
                            "pct" to InmobiliariaFormat.percent(party.sharePct),
                            "amount" to InmobiliariaFormat.money(deal.partyAmount(party), deal.currency),
                            "notes" to party.notes,
                        )
                    },
                    emptyText = tr("Sin partes: agrega agentes y otras personas con su porcentaje de la comision."),
                ),
                warning,
            ),
            title = tr("Partes y comision"),
        )
        val buyer = listOfNotNull(
            deal.buyerLeadId?.let { tr("Lead comprador: {0}", buyerLead ?: tr(LeadOutOfScope)) },
            deal.buyerCustomerId?.let { tr("Cliente comprador: {0}", customers[it] ?: tr(CustomerOutOfScope)) },
            deal.comments.takeIf { it.isNotBlank() }?.let { tr("Comentarios: {0}", it) },
        )
        val details = UiCard(
            if (buyer.isEmpty()) listOf(InmobiliariaUi.caption(tr("Sin comprador ni comentarios."))) else buyer.map { UiText(it) },
            title = tr("Detalle"),
        )
        val edit = if (!canEdit) null else {
            val settings = InmobiliariaService.settings(s)
            val base = mapOf(
                "lead" to if (deal.buyerLeadId == null) "" else (buyerLead ?: tr(LeadOutOfScope)),
                "name" to if (deal.buyerCustomerId == null) "" else (customers[deal.buyerCustomerId] ?: tr(CustomerOutOfScope)),
                "customerId" to deal.buyerCustomerId?.takeIf { it in customers }?.toString().orEmpty(),
                "agreedPrice" to InmobiliariaFormat.field(deal.agreedPrice),
                "commissionPct" to InmobiliariaFormat.field(deal.commissionPct),
                "commissionAmount" to InmobiliariaFormat.field(deal.commissionAmount),
                "estimatedCloseOn" to deal.estimatedCloseOn?.toString().orEmpty(),
                "comments" to deal.comments,
            )
            // Las partes con cliente fuera de la vista viajan como referencia a la parte (no como id de cliente) para no perderlo al guardar.
            val keep = deal.parties.withIndex().filter { (_, party) -> party.customerId != null && party.customerId !in customers }.associate { (index, party) -> index + 1 to party.id }
            val updateParams = if (keep.isEmpty()) idParams else idParams + ("keep" to InmobiliariaDealForms.keepParam(keep))
            UiCard(
                listOf(
                    dealForm(
                        context, emptyList(), settings, deal, base, submitted,
                        UiButton(tr("Guardar cambios"), InmobiliariaUi.request(InmobiliariaScreenIds.Deal, "update", updateParams, formId = "dealForm")),
                        customers.keys,
                    ),
                ),
                title = tr("Editar transaccion"),
                collapsible = true,
                expanded = submitted != null,
            )
        }
        return listOfNotNull(header, actions, stageCard, metrics, details, partyTable, edit)
    }

    private fun partyName(users: List<CrmUser>, customers: Map<UUID, String>, party: DealParty): String = when {
        party.userId != null -> tr("{0} (agente)", InmobiliariaUi.agentName(users, party.userId))
        party.customerId != null -> customers[party.customerId] ?: tr(CustomerOutOfScope)
        else -> party.name
    }

    // ---- Etapas y ajustes ----

    fun stages(context: CrmScreens.Context, params: Map<String, String>, submitted: Map<String, String>?): List<UiNode> {
        val s = InmobiliariaUi.session(context)
        if (!s.access.canEditStages) return InmobiliariaUi.message(tr("No tienes permiso para editar las etapas.")) + InmobiliariaUi.back(InmobiliariaScreenIds.Deals, tr("Volver a Cierres"))
        val stages = InmobiliariaService.stages(s)
        val settings = InmobiliariaService.settings(s)
        // Los ids con que se dibujo cada fila viajan en la peticion; al devolver un error se conservan los de la primera vez.
        val known = stages.map { it.id }.toSet()
        val ids = params["ids"]?.split(',')?.mapNotNull { InmobiliariaUi.uuid(it) }?.filter { it in known }?.distinct()?.takeIf { it.isNotEmpty() } ?: stages.map { it.id }
        val rows = InmobiliariaDealForms.stageRows(ids.size, submitted)
        val outcomes = DealOutcome.entries.map { it.label }
        val fields = (1..rows).flatMap { row ->
            listOf(
                UiField("s${row}_name", tr("Etapa {0}", row), section = tr("Etapa {0}", row), help = if (row <= ids.size) tr("Vacio: se quita la etapa (solo si no tiene transacciones).") else ""),
                UiField("s${row}_outcome", tr("Resultado"), type = "dropdown", options = outcomes, section = tr("Etapa {0}", row)),
                UiField("s${row}_order", tr("Orden"), section = tr("Etapa {0}", row)),
            )
        }
        val saved = (1..rows).flatMap { row ->
            val stage = stages.firstOrNull { it.id == ids.getOrNull(row - 1) }
            listOf(
                "s${row}_name" to stage?.name.orEmpty(),
                "s${row}_outcome" to (stage?.outcome ?: DealOutcome.Open).label,
                "s${row}_order" to (if (stage != null) row.toString() else ""),
            )
        }.toMap()
        val stageForm = UiForm(
            "stagesForm", fields, saved + submitted.orEmpty(),
            submit = UiButton(tr("Guardar etapas"), InmobiliariaUi.request(InmobiliariaScreenIds.Stages, "saveStages", mapOf("ids" to ids.joinToString(",")), formId = "stagesForm")),
            minFieldWidth = 220,
        )
        val settingsForm = UiForm(
            "settingsForm",
            listOf(
                UiField("currency", tr("Moneda"), required = true, help = tr("Codigo de 3 letras (USD). Es la de las propiedades nuevas.")),
                UiField("commission", tr("Comision por defecto (%)"), required = true, help = tr("Se usa al abrir una transaccion sin porcentaje.")),
            ),
            mapOf("currency" to settings.currency, "commission" to InmobiliariaFormat.field(settings.defaultCommissionPct)) + submitted.orEmpty().filterKeys { it == "currency" || it == "commission" },
            submit = UiButton(tr("Guardar ajustes"), InmobiliariaUi.request(InmobiliariaScreenIds.Stages, "saveSettings", formId = "settingsForm"), style = "secondary"),
            minFieldWidth = 220,
        )
        return listOf(
            InmobiliariaUi.back(InmobiliariaScreenIds.Deals, tr("Volver a Cierres")),
            UiCard(
                listOf(
                    InmobiliariaUi.caption(tr("Las transacciones pasan por estas etapas en orden. Debe haber al menos una abierta y una ganada. Escribe un nombre en una fila vacia para agregar una etapa.")),
                    stageForm,
                ),
                title = tr("Etapas de las transacciones"),
            ),
            UiCard(listOf(settingsForm), title = tr("Ajustes")),
        )
    }

    // ---- Acciones ----

    private fun requireDealId(params: Map<String, String>): UUID = InmobiliariaUi.uuid(params["id"]) ?: throw InmobiliariaException(tr("Esa transaccion no existe."))

    private fun buyerCustomer(values: Map<String, String>, current: Pair<UUID, String>? = null): UUID? =
        InmobiliariaDealForms.pickCustomer(values["name"], values["customerId"], current, msg("El cliente comprador"))

    fun dealAction(context: CrmScreens.Context, s: InmobiliariaSession, op: String, params: Map<String, String>, values: Map<String, String>): CrmExtensionActionResult {
        val screen = InmobiliariaScreenIds.Deal
        val clean = params - "op"
        return when (op) {
            "create" -> {
                val propertyId = InmobiliariaUi.uuid(params["property"]) ?: throw InmobiliariaException(tr("Elige la propiedad de la transaccion."))
                val stageName = values["stage"].orEmpty().trim()
                val draft = DealDraft(
                    propertyId = propertyId,
                    buyerLeadId = InmobiliariaLookups.resolveLead(context, values["lead"].orEmpty()),
                    buyerCustomerId = buyerCustomer(values),
                    stageId = if (stageName.isEmpty()) null else InmobiliariaService.findStage(s, stageName).id,
                    agreedPrice = InmobiliariaInput.parseMoney(values["agreedPrice"], msg("El precio acordado")),
                    commissionPct = InmobiliariaInput.parsePercent(values["commissionPct"], msg("La comision")),
                    commissionAmount = InmobiliariaInput.parseMoney(values["commissionAmount"], msg("La comision fija")),
                    estimatedCloseOn = InmobiliariaInput.parseDate(values["estimatedCloseOn"], msg("El cierre estimado")),
                    comments = values["comments"].orEmpty(),
                    parties = InmobiliariaDealForms.parseParties(values, context.users),
                )
                val result = InmobiliariaService.createDeal(s, draft)
                InmobiliariaUi.success(screen, tr("Transaccion abierta."), mapOf("id" to result.deal.id.toString()), result.warnings)
            }
            "update" -> {
                val id = requireDealId(params)
                val current = InmobiliariaService.getDeal(s, id)
                val shown = current.buyerLeadId?.let { InmobiliariaLookups.leadNames(context, listOf(it))[it] ?: tr(LeadOutOfScope) }
                val currentCustomer = current.buyerCustomerId?.let { it to (InmobiliariaLookups.customerNames(context, listOf(it))[it] ?: tr(CustomerOutOfScope)) }
                val edit = DealEdit(
                    buyerLeadId = InmobiliariaLookups.resolveLead(context, values["lead"].orEmpty(), current.buyerLeadId?.let { it to shown.orEmpty() }),
                    buyerCustomerId = buyerCustomer(values, currentCustomer),
                    agreedPrice = InmobiliariaInput.parseMoney(values["agreedPrice"], msg("El precio acordado")),
                    commissionPct = InmobiliariaInput.parsePercent(values["commissionPct"], msg("La comision")) ?: current.commissionPct,
                    commissionAmount = InmobiliariaInput.parseMoney(values["commissionAmount"], msg("La comision fija")),
                    currency = current.currency,
                    estimatedCloseOn = InmobiliariaInput.parseDate(values["estimatedCloseOn"], msg("El cierre estimado")),
                    closedOn = current.closedOn,
                    comments = values["comments"].orEmpty(),
                )
                val keep = InmobiliariaDealForms.parseKeep(params["keep"])
                InmobiliariaService.updateDeal(s, id, edit, InmobiliariaDealForms.parseParties(values, context.users, keep = keep))
                InmobiliariaUi.success(screen, tr("Transaccion guardada."), mapOf("id" to id.toString()))
            }
            "move" -> {
                val id = requireDealId(params)
                val stageId = InmobiliariaUi.uuid(params["stage"]) ?: throw InmobiliariaException(tr("Elige la etapa."))
                val result = InmobiliariaService.moveDeal(s, id, stageId)
                InmobiliariaUi.success(screen, tr("Transaccion en la etapa {0}.", result.deal.stageName), mapOf("id" to id.toString()), result.warnings)
            }
            else -> InmobiliariaUi.failure(screen, tr("Esa accion no existe."), clean, values)
        }
    }

    fun stagesAction(s: InmobiliariaSession, op: String, params: Map<String, String>, values: Map<String, String>): CrmExtensionActionResult {
        val screen = InmobiliariaScreenIds.Stages
        val clean = params - "op"
        return when (op) {
            "saveStages" -> {
                // Solo cuentan los ids de etapas que existen ahora y sin repetirse; los demas se tratan como etapa nueva.
                val known = InmobiliariaService.stages(s).map { it.id }.toSet()
                val seen = mutableSetOf<UUID>()
                val ids = params["ids"]?.split(',')?.mapNotNull { InmobiliariaUi.uuid(it) }.orEmpty()
                val rows = InmobiliariaDealForms.stageRows(ids.size, values)
                val parsed = InmobiliariaDealForms.parseStages(values, ids, rows).map { stage -> if (stage.id != null && (stage.id !in known || !seen.add(stage.id))) stage.copy(id = null) else stage }
                InmobiliariaService.saveStages(s, parsed)
                InmobiliariaUi.success(screen, tr("Etapas guardadas."))
            }
            "saveSettings" -> {
                val pct = InmobiliariaInput.parsePercent(values["commission"], msg("La comision por defecto")) ?: throw InmobiliariaException(tr("Escribe la comision por defecto."))
                InmobiliariaService.saveSettings(s, Settings(values["currency"].orEmpty().trim().uppercase(), pct))
                InmobiliariaUi.success(screen, tr("Ajustes guardados."))
            }
            else -> InmobiliariaUi.failure(screen, tr("Esa accion no existe."), clean, values)
        }
    }

    // ---- Panel de la ficha del lead ----

    fun leadPanel(context: CrmScreens.Context, lead: CrmLead): List<UiNode> {
        val s = InmobiliariaUi.session(context)
        val deals = try {
            InmobiliariaService.listDeals(s, DealFilter(buyerLeadId = lead.id), 20, 0).items
        } catch (failure: InmobiliariaException) {
            return emptyList()
        }
        val items = CrmHost.items(context.tenant, lead.id, InmobiliariaService.Source)
        if (deals.isEmpty() && items.isEmpty()) return emptyList()
        val dealList = UiList(
            deals.map { deal ->
                UiListItem(
                    title = tr("{0} - {1}", deal.propertyCode, deal.propertyTitle),
                    subtitle = tr("Acordado {0} - Cierre estimado {1}", InmobiliariaFormat.money(deal.agreedPrice, deal.currency), InmobiliariaFormat.date(deal.estimatedCloseOn)),
                    badges = listOf(UiBadge(deal.stageName, stageTone(deal.stageOutcome))),
                    action = UiNavigate(InmobiliariaScreenIds.Deal, mapOf("id" to deal.id.toString())),
                )
            },
            emptyText = tr("Sin transacciones como comprador."),
        )
        val itemList = UiList(
            items.map { item ->
                UiListItem(
                    title = item.title,
                    subtitle = item.subtitle,
                    trailing = item.price?.let { InmobiliariaFormat.amount(it) }.orEmpty(),
                    badges = listOf(UiBadge(CrmItems.statusLabels[item.status] ?: item.status)),
                    action = InmobiliariaUi.uuid(item.externalId)?.let { UiNavigate(InmobiliariaScreenIds.Property, mapOf("id" to it.toString())) },
                )
            },
            emptyText = tr("Sin propiedades vinculadas."),
        )
        return listOf(
            UiCard(
                listOfNotNull(
                    InmobiliariaUi.heading(tr("Transacciones como comprador")).takeIf { deals.isNotEmpty() },
                    dealList.takeIf { deals.isNotEmpty() },
                    InmobiliariaUi.heading(tr("Propiedades vinculadas")).takeIf { items.isNotEmpty() },
                    itemList.takeIf { items.isNotEmpty() },
                ),
                title = tr("Inmobiliaria"),
            ),
        )
    }
}
