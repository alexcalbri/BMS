package com.ideasdeveloper.bms.modules.membresia

import com.ideasdeveloper.bms.server.i18n.msg
import com.ideasdeveloper.bms.server.i18n.tr
import com.ideasdeveloper.bms.server.modules.ModulePermissions
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
import com.ideasdeveloper.bms.ui.UiNavItem
import com.ideasdeveloper.bms.ui.UiNavigate
import com.ideasdeveloper.bms.ui.UiNavigation
import com.ideasdeveloper.bms.ui.UiNode
import com.ideasdeveloper.bms.ui.UiNotice
import com.ideasdeveloper.bms.ui.UiOpenModule
import com.ideasdeveloper.bms.ui.UiRequest
import com.ideasdeveloper.bms.ui.UiRow
import com.ideasdeveloper.bms.ui.UiScreen
import com.ideasdeveloper.bms.ui.UiStatus
import com.ideasdeveloper.bms.ui.UiSuggest
import com.ideasdeveloper.bms.ui.UiSuggestion
import com.ideasdeveloper.bms.ui.UiSuggestions
import com.ideasdeveloper.bms.ui.UiTable
import com.ideasdeveloper.bms.ui.UiTableColumn
import com.ideasdeveloper.bms.ui.UiText
import com.ideasdeveloper.bms.ui.UiTextStyle
import com.ideasdeveloper.bms.ui.UiTone
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

/** Lo que el usuario puede hacer en el modulo (permisos efectivos); las pantallas solo ofrecen lo permitido. */
data class MembresiaAccess(
    val view: Boolean,
    val visits: Boolean,
    val manage: Boolean,
    val plans: Boolean,
    val import: Boolean = false,
    /** Hay al menos una fuente de importacion activa (la ruta lo calcula); sin ella no se ofrece "Importar". */
    val importEnabled: Boolean = false,
) {
    companion object {
        fun from(permissions: ModulePermissions) = MembresiaAccess(
            view = permissions.has(MembresiaModule.View),
            visits = permissions.has(MembresiaModule.Visits),
            manage = permissions.has(MembresiaModule.Manage),
            plans = permissions.has(MembresiaModule.Plans),
            import = permissions.has(MembresiaModule.Import),
        )
    }
}

/**
 * Pantallas nativas del modulo como funciones puras: reciben datos ya leidos (sin JDBC) y devuelven el UiScreen.
 * Nunca muestran el codigo de acceso WiFi del cliente ni sus campos personalizados.
 */
object MembresiaScreens {
    const val MembersSuggestRoute = "${MembresiaModule.UiRoute}/suggest/members"
    const val CustomersSuggestRoute = "${MembresiaModule.UiRoute}/suggest/customers"

    private data class Section(val id: String, val label: String, val allowed: (MembresiaAccess) -> Boolean)

    private val sections = listOf(
        Section("recepcion", msg("Recepción")) { it.view },
        Section("miembros", msg("Miembros")) { it.view },
        Section("niveles", msg("Por nivel")) { it.view },
        Section("nueva", msg("Nueva membresía")) { it.manage },
        Section("planes", msg("Planes")) { it.plans },
        Section("ingresos", msg("Ingresos")) { it.manage },
        Section("ajustes", msg("Ajustes")) { it.plans },
    )

    // Pantallas a las que se llega desde otras (no son secciones del menu).
    private val inner = listOf(
        Section("miembro", tr("Miembro")) { it.view },
        Section("visitas", tr("Visitas")) { it.view },
        Section("renovar", tr("Renovar")) { it.manage },
        Section("editar", tr("Editar")) { it.manage },
        Section("importar", tr("Importar")) { it.import },
    )

    fun isScreen(id: String): Boolean = (sections + inner).any { it.id == id }

    fun allowed(access: MembresiaAccess, id: String): Boolean =
        (sections + inner).firstOrNull { it.id == id }?.allowed?.invoke(access) ?: false

    /** Primera seccion que el usuario puede abrir; null si no tiene ninguna. */
    fun home(access: MembresiaAccess): String? = sections.firstOrNull { it.allowed(access) }?.id

    fun navigation(access: MembresiaAccess, selected: String): UiNavigation = UiNavigation(
        title = tr(MembresiaModule.DisplayName),
        items = (sections + Section("importar", msg("Importar")) { it.import && it.importEnabled })
            .filter { it.allowed(access) }.map { UiNavItem(it.id, tr(it.label), UiNavigate(it.id)) },
        selected = selected,
    )

    // ---------------------------------------------------------------- utilidades

    private val timeFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())

    fun money(amount: BigDecimal, currency: String): String = "${amount.setScale(2, RoundingMode.HALF_UP).toPlainString()} $currency"

    /** Texto con que un plan aparece en los selectores; la ruta lo usa para encontrar el plan elegido. */
    fun planLabel(plan: Plan, currency: String): String = "${plan.name} · ${money(plan.price, currency)}"

    private fun screen(access: MembresiaAccess, selected: String, title: String, notice: UiNotice?, body: List<UiNode>) =
        UiScreen(title = title, navigation = navigation(access, selected), notice = notice, body = body)

    private fun badge(status: DisplayStatus) = UiBadge(tr(status.label), status.tone)

    private fun post(action: String, params: Map<String, String> = emptyMap(), confirm: String? = null, formId: String? = null) =
        UiRequest("${MembresiaModule.UiRoute}/$action", "POST", params, confirm, formId)

    private fun openMember(customerId: UUID) = UiNavigate("miembro", mapOf("customer" to customerId.toString()))

    private fun statusOf(member: MemberSummary, today: LocalDate, expiringDays: Int): DisplayStatus? =
        member.current?.let { MembresiaRules.displayStatus(it, today, expiringDays) }

    private fun memberItem(member: MemberSummary, today: LocalDate, expiringDays: Int): UiListItem {
        val status = statusOf(member, today, expiringDays)
        return UiListItem(
            title = member.customerName.ifBlank { member.email.ifBlank { member.phone } },
            subtitle = listOf(member.email, member.phone).filter { it.isNotBlank() }.joinToString(" · "),
            trailing = member.current?.let { tr("{0} · hasta {1}", it.planName, it.endsOn) }.orEmpty(),
            badges = listOf(status?.let(::badge) ?: UiBadge(tr("Sin membresía"), UiTone.Neutral)),
            action = openMember(member.customerId),
        )
    }

    // ---------------------------------------------------------------- recepcion

    /** Recepcion pensada para tablet: busqueda arriba, resultados y tres metricas del dia. */
    fun recepcion(
        access: MembresiaAccess,
        query: String,
        results: List<MemberSummary>,
        counts: Map<DisplayStatus, Int>,
        visitsToday: Int,
        today: LocalDate,
        expiringDays: Int,
        notice: UiNotice? = null,
    ): UiScreen {
        val body = buildList<UiNode> {
            add(
                UiForm(
                    id = "search",
                    fields = listOf(
                        UiField("name", tr("Buscar miembro"), placeholder = tr("Nombre, correo o teléfono"), suggest = UiSuggest(MembersSuggestRoute, minLength = 2)),
                    ),
                    values = mapOf("name" to query),
                    submit = UiButton(tr("Buscar"), UiNavigate("recepcion", formId = "search")),
                ),
            )
            if (query.isNotBlank()) {
                add(UiList(results.map { memberItem(it, today, expiringDays) }, emptyText = tr("No hay miembros con ese dato.")))
            }
            add(
                UiGrid(
                    listOf(
                        UiMetric(tr("Activas"), (counts[DisplayStatus.ACTIVA] ?: 0).toString(), tr("Membresías vigentes"), UiTone.Success),
                        UiMetric(tr("Por vencer"), (counts[DisplayStatus.POR_VENCER] ?: 0).toString(), tr("Vencen pronto"), UiTone.Warning),
                        UiMetric(tr("Visitas hoy"), visitsToday.toString(), tr("Incluye invitados")),
                    ),
                    minItemWidth = 160,
                ),
            )
            add(UiButton(tr("Ver visitas de hoy"), UiNavigate("visitas"), style = "secondary"))
        }
        return screen(access, "recepcion", tr("Recepción"), notice, body)
    }

    // ---------------------------------------------------------------- miembro

    /**
     * Ficha del miembro: estado grande, metricas y acciones segun permisos. [overrideReason] no nulo = el registro de
     * visita pidio confirmacion: se muestra el aviso y el boton "Registrar igual".
     */
    fun miembro(
        access: MembresiaAccess,
        detail: MemberDetail,
        today: LocalDate,
        expiringDays: Int,
        notice: UiNotice? = null,
        overrideReason: String? = null,
        wifiCode: String? = null,
    ): UiScreen {
        val member = detail.summary
        val current = member.current
        val status = current?.let { MembresiaRules.displayStatus(it, today, expiringDays) }
        val customerId = member.customerId.toString()
        val shownNotice = notice ?: overrideReason?.let { UiNotice(it, UiTone.Warning) }
        val body = buildList<UiNode> {
            add(UiStatus(status?.let { tr(it.label) } ?: tr("Sin membresía"), status?.tone ?: UiTone.Neutral))
            val contact = listOf(member.email, member.phone).filter { it.isNotBlank() }.joinToString(" · ")
            if (contact.isNotEmpty()) add(UiText(contact, UiTextStyle.Caption))
            // Unico lugar donde se muestra el codigo WiFi: solo lectura y solo para quien registra visitas o gestiona.
            if ((access.visits || access.manage) && !wifiCode.isNullOrBlank()) {
                add(UiForm(id = "wifi", fields = listOf(UiField("wifiCode", tr("Código WiFi"), readOnly = true)), values = mapOf("wifiCode" to wifiCode)))
            }
            if (current != null) {
                add(UiText(tr("{0} · {1} a {2}", current.planName, current.startsOn, current.endsOn), UiTextStyle.Subtitle))
                val place = listOf(current.unit.takeIf { it.isNotBlank() }?.let { tr("Unidad: {0}", it) }, current.hostName.takeIf { it.isNotBlank() }?.let { tr("Anfitrión: {0}", it) })
                    .filterNotNull().joinToString(" · ")
                if (place.isNotEmpty()) add(UiText(place, UiTextStyle.Caption))
                add(UiGrid(metrics(current, status, today), minItemWidth = 160))
            }

            val buttons = buildList<UiNode> {
                if (access.visits && current != null) {
                    add(UiButton(tr("Registrar visita"), post("checkin", mapOf("customer" to customerId))))
                    if (overrideReason != null) {
                        add(UiButton(tr("Registrar igual"), post("checkin", mapOf("customer" to customerId, "override" to "true")), style = "danger"))
                    }
                }
                if (access.manage) {
                    if (current != null && current.status != StoredStatus.Cancelada) {
                        add(UiButton(tr("Renovar"), UiNavigate("renovar", mapOf("id" to current.id.toString())), style = "secondary"))
                        add(UiButton(tr("Editar"), UiNavigate("editar", mapOf("id" to current.id.toString())), style = "secondary"))
                    }
                    if (status?.current != true) {
                        add(UiButton(tr("Nueva membresía"), UiNavigate("nueva", mapOf("customer" to customerId)), style = "secondary"))
                    }
                }
                add(UiButton(tr("Abrir ficha del cliente"), UiOpenModule("clientes", "customer", mapOf("id" to customerId)), style = "secondary"))
            }
            add(UiRow(buttons, fill = true))

            if (access.visits && current != null && status?.current == true && current.guestPassesTotal > 0) {
                val left = MembresiaRules.guestPassesLeft(current)
                add(
                    UiCard(
                        title = tr("Invitado ({0} de {1} pases)", left, current.guestPassesTotal),
                        children = if (left > 0) {
                            listOf(
                                UiForm(
                                    id = "guest",
                                    fields = listOf(UiField("guestName", tr("Nombre del invitado"), required = true)),
                                    submit = UiButton(tr("Registrar invitado"), post("guest", mapOf("customer" to customerId), formId = "guest")),
                                ),
                            )
                        } else {
                            listOf(UiText(tr("No quedan pases de invitado en este período."), UiTextStyle.Caption))
                        },
                    ),
                )
            }

            if (access.manage && current != null && current.status != StoredStatus.Cancelada) {
                add(
                    UiCard(
                        title = tr("Cancelar membresía"),
                        collapsible = true,
                        expanded = false,
                        children = listOf(
                            UiForm(
                                id = "cancel",
                                fields = listOf(UiField("reason", tr("Motivo"), type = "textarea")),
                                submit = UiButton(
                                    tr("Cancelar membresía"),
                                    post("cancelar", mapOf("id" to current.id.toString()), confirm = "¿Cancelar esta membresía? No se puede deshacer.", formId = "cancel"),
                                    style = "danger",
                                ),
                            ),
                        ),
                    ),
                )
            }

            add(
                UiCard(
                    title = tr("Membresías"),
                    children = listOf(
                        UiTable(
                            columns = listOf(
                                UiTableColumn("plan", tr("Plan")), UiTableColumn("starts", tr("Inicio")), UiTableColumn("ends", tr("Fin")),
                                UiTableColumn("status", tr("Estado")), UiTableColumn("amount", tr("Monto")),
                            ),
                            rows = detail.memberships.map {
                                mapOf(
                                    "plan" to it.planName,
                                    "starts" to it.startsOn.toString(),
                                    "ends" to it.endsOn.toString(),
                                    "status" to tr(MembresiaRules.displayStatus(it, today, expiringDays).label),
                                    "amount" to money(it.amount, it.currency),
                                )
                            },
                            emptyText = tr("Sin membresías."),
                        ),
                    ),
                ),
            )
            add(UiCard(title = tr("Últimas visitas"), children = listOf(visitsTable(detail.visits, withCustomer = false))))
        }
        return screen(access, "recepcion", member.customerName, shownNotice, body)
    }

    private fun metrics(current: Membership, status: DisplayStatus?, today: LocalDate): List<UiNode> {
        val days = MembresiaRules.daysLeft(current.endsOn, today)
        val daysCaption = when (status) {
            DisplayStatus.PROGRAMADA -> tr("Empieza el {0}", current.startsOn)
            DisplayStatus.VENCIDA -> tr("Venció el {0}", current.endsOn)
            DisplayStatus.CANCELADA -> tr("Cancelada")
            else -> tr("Hasta el {0}", current.endsOn)
        }
        val visitsLeft = MembresiaRules.visitsLeft(current)
        return listOf(
            UiMetric(tr("Días restantes"), days.coerceAtLeast(0).toString(), daysCaption, status?.tone ?: UiTone.Neutral),
            UiMetric(
                tr("Visitas"),
                current.includedVisits?.let { tr("{0} de {1}", current.visitsUsed, it) } ?: current.visitsUsed.toString(),
                if (visitsLeft == null) tr("Ilimitadas") else tr("Quedan {0}", visitsLeft),
                if (visitsLeft == 0) UiTone.Warning else UiTone.Neutral,
            ),
            UiMetric(tr("Pases de invitado"), tr("{0} de {1}", MembresiaRules.guestPassesLeft(current), current.guestPassesTotal), tr("Disponibles")),
        )
    }

    private fun visitsTable(visits: List<Visit>, withCustomer: Boolean): UiTable {
        val columns = buildList {
            add(UiTableColumn("when", tr("Fecha")))
            if (withCustomer) add(UiTableColumn("customer", tr("Cliente")))
            addAll(listOf(UiTableColumn("kind", tr("Tipo")), UiTableColumn("guest", tr("Invitado")), UiTableColumn("by", tr("Registró")), UiTableColumn("note", tr("Aviso"))))
        }
        return UiTable(
            columns,
            visits.map { visit ->
                buildMap {
                    put("when", timeFormat.format(visit.visitedAt))
                    put("customer", visit.customerName)
                    put("kind", if (visit.kind == "invitado") tr("Invitado") else tr("Miembro"))
                    put("guest", visit.guestName.orEmpty())
                    put("by", visit.recordedBy)
                    put("note", if (visit.override) tr("Registrada igual") else visit.note)
                }
            },
            emptyText = tr("Sin visitas."),
        )
    }

    // ---------------------------------------------------------------- listas

    private val listStatuses = listOf(
        DisplayStatus.ACTIVA, DisplayStatus.POR_VENCER, DisplayStatus.VENCIDA, DisplayStatus.PROGRAMADA, DisplayStatus.CANCELADA,
    )

    /** Membresias de un estado (null = todas) con botones para cambiar de estado. */
    fun miembros(
        access: MembresiaAccess,
        status: DisplayStatus?,
        memberships: List<Membership>,
        counts: Map<DisplayStatus, Int>,
        today: LocalDate,
        expiringDays: Int,
        notice: UiNotice? = null,
    ): UiScreen {
        val filters = buildList<UiNode> {
            add(UiButton(tr("Todas"), UiNavigate("miembros"), style = if (status == null) "primary" else "secondary"))
            listStatuses.forEach {
                add(UiButton(tr("{0} ({1})", tr(it.label), counts[it] ?: 0), UiNavigate("miembros", mapOf("estado" to it.key)), style = if (status == it) "primary" else "secondary"))
            }
        }
        val items = memberships.map { membership ->
            val shown = MembresiaRules.displayStatus(membership, today, expiringDays)
            UiListItem(
                title = membership.customerName,
                subtitle = tr("{0} · {1} a {2}", membership.planName, membership.startsOn, membership.endsOn),
                trailing = membership.endsOn.toString(),
                badges = listOf(badge(shown)),
                action = openMember(membership.customerId),
            )
        }
        val body = listOf(UiRow(filters, spacing = 8), UiList(items, emptyText = tr("No hay membresías en este estado.")))
        return screen(access, "miembros", tr("Miembros"), notice, body)
    }

    /** Membresias vigentes agrupadas por plan. */
    fun niveles(
        access: MembresiaAccess,
        byPlan: Map<String, List<Membership>>,
        today: LocalDate,
        expiringDays: Int,
        notice: UiNotice? = null,
    ): UiScreen {
        val body: List<UiNode> = if (byPlan.isEmpty()) {
            listOf(UiText(tr("No hay membresías vigentes."), UiTextStyle.Caption))
        } else {
            byPlan.map { (plan, list) ->
                UiCard(
                    title = tr("{0} ({1} {2})", plan, list.size, if (list.size == 1) tr("activa") else tr("activas")),
                    children = listOf(
                        UiList(
                            list.map { membership ->
                                UiListItem(
                                    title = membership.customerName,
                                    subtitle = tr("Hasta {0}", membership.endsOn),
                                    badges = listOf(badge(MembresiaRules.displayStatus(membership, today, expiringDays))),
                                    action = openMember(membership.customerId),
                                )
                            },
                        ),
                    ),
                )
            }
        }
        return screen(access, "niveles", tr("Por nivel"), notice, body)
    }

    fun visitas(access: MembresiaAccess, date: LocalDate, visits: List<Visit>, notice: UiNotice? = null): UiScreen =
        screen(
            access, "recepcion", tr("Visitas de hoy"), notice,
            listOf(UiText(tr("{0} · {1} {2}", date, visits.size, if (visits.size == 1) tr("visita") else tr("visitas")), UiTextStyle.Subtitle), visitsTable(visits, withCustomer = true)),
        )

    // ---------------------------------------------------------------- altas

    /** Valores iniciales de la membresia nueva; [customer] viene de la ficha de un miembro. */
    fun nuevaValues(today: LocalDate, customer: MemberSummary? = null): Map<String, String> = buildMap {
        put("startsOn", today.toString())
        if (customer != null) {
            put("customerId", customer.customerId.toString())
            put("name", customer.customerName)
            put("email", customer.email)
            put("phone", customer.phone)
        }
    }

    fun nueva(
        access: MembresiaAccess,
        plans: List<Plan>,
        settings: MembresiaSettings,
        hosts: List<HostCandidate>,
        values: Map<String, String>,
        notice: UiNotice? = null,
    ): UiScreen {
        val fields = listOf(
            UiField(
                "name", tr("Cliente"), required = true, section = tr("Cliente"),
                help = tr("Escribe para buscar un cliente; si no existe se crea con estos datos."),
                suggest = UiSuggest(CustomersSuggestRoute, minLength = 2),
            ),
            UiField("email", tr("Correo"), type = "email", section = tr("Cliente")),
            UiField("phone", tr("Teléfono"), type = "phone", section = tr("Cliente")),
            UiField("plan", tr("Plan"), type = "dropdown", required = true, options = plans.map { planLabel(it, settings.currency) }, section = tr("Membresía")),
            UiField("startsOn", tr("Inicio"), type = "date", section = tr("Membresía")),
            UiField("amount", tr("Monto ({0})", settings.currency), placeholder = tr("Vacío = precio del plan"), section = tr("Membresía")),
        ) + detailFields(settings, hosts, section = tr("Detalles"))
        val form = UiForm(
            id = "nueva",
            fields = fields,
            values = values,
            submit = UiButton(tr("Crear membresía"), post("crear", formId = "nueva")),
        )
        val body = if (plans.isEmpty()) {
            listOf(UiText(tr("No hay planes activos. Crea uno en Planes."), UiTextStyle.Caption), form)
        } else {
            listOf(form)
        }
        return screen(access, "nueva", tr("Nueva membresía"), notice, body)
    }

    private fun detailFields(settings: MembresiaSettings, hosts: List<HostCandidate>, section: String, currentSource: String = "", currentHost: String = ""): List<UiField> = listOf(
        UiField("source", tr("Fuente"), type = "dropdown", options = (settings.sources + currentSource).filter { it.isNotBlank() }.distinct(), section = section),
        UiField("host", tr("Anfitrión"), type = "dropdown", options = (hosts.map { it.name } + currentHost).filter { it.isNotBlank() }.distinct(), section = section),
        UiField("unit", tr("Unidad"), section = section, help = tr("Oficina, escritorio o espacio asignado.")),
        UiField("notes", tr("Notas"), type = "textarea", section = section),
    )

    fun renovar(
        access: MembresiaAccess,
        membership: Membership,
        plans: List<Plan>,
        settings: MembresiaSettings,
        today: LocalDate,
        values: Map<String, String>? = null,
        notice: UiNotice? = null,
    ): UiScreen {
        val defaultStart = MembresiaRules.renewalStart(membership.endsOn, today)
        val planChosen = plans.firstOrNull { it.id == membership.planId }?.let { planLabel(it, settings.currency) }.orEmpty()
        val form = UiForm(
            id = "renovar",
            fields = listOf(
                UiField("plan", tr("Plan"), type = "dropdown", required = true, options = plans.map { planLabel(it, settings.currency) }),
                UiField("startsOn", tr("Inicio"), type = "date", help = tr("Vacío = {0}.", defaultStart)),
                UiField("amount", tr("Monto ({0})", settings.currency), placeholder = tr("Vacío = precio del plan")),
            ),
            values = values ?: mapOf("plan" to planChosen, "startsOn" to defaultStart.toString()),
            submit = UiButton(tr("Renovar"), post("renovar", mapOf("id" to membership.id.toString()), formId = "renovar")),
        )
        val body = listOf(
            UiText(tr("{0} · {1} · vence el {2}", membership.customerName, membership.planName, membership.endsOn), UiTextStyle.Subtitle),
            form,
            UiButton(tr("Volver"), UiNavigate("miembro", mapOf("customer" to membership.customerId.toString())), style = "secondary"),
        )
        return screen(access, "recepcion", tr("Renovar membresía"), notice, body)
    }

    fun editar(
        access: MembresiaAccess,
        membership: Membership,
        settings: MembresiaSettings,
        hosts: List<HostCandidate>,
        values: Map<String, String>? = null,
        notice: UiNotice? = null,
    ): UiScreen {
        val form = UiForm(
            id = "editar",
            fields = listOf(
                UiField("endsOn", tr("Fin"), type = "date", required = true),
                UiField("amount", tr("Monto ({0})", settings.currency), required = true),
            ) + detailFields(settings, hosts, section = tr("Detalles"), currentSource = membership.source, currentHost = membership.hostName),
            values = values ?: mapOf(
                "endsOn" to membership.endsOn.toString(),
                "amount" to membership.amount.setScale(2, RoundingMode.HALF_UP).toPlainString(),
                "source" to membership.source,
                "host" to membership.hostName,
                "unit" to membership.unit,
                "notes" to membership.notes,
            ),
            submit = UiButton(tr("Guardar cambios"), post("editar", mapOf("id" to membership.id.toString()), formId = "editar")),
        )
        val body = listOf(
            UiText(tr("{0} · {1} · desde {2}", membership.customerName, membership.planName, membership.startsOn), UiTextStyle.Subtitle),
            form,
            UiButton(tr("Volver"), UiNavigate("miembro", mapOf("customer" to membership.customerId.toString())), style = "secondary"),
        )
        return screen(access, "recepcion", tr("Editar membresía"), notice, body)
    }

    // ---------------------------------------------------------------- planes, ingresos, ajustes

    fun planValues(plan: Plan?): Map<String, String> = if (plan == null) {
        mapOf("durationDays" to "30", "guestPasses" to "0", "wifi" to "true", "displayOrder" to "0")
    } else {
        mapOf(
            "name" to plan.name,
            "description" to plan.description,
            "price" to plan.price.setScale(2, RoundingMode.HALF_UP).toPlainString(),
            "durationDays" to plan.durationDays.toString(),
            "includedVisits" to plan.includedVisits?.toString().orEmpty(),
            "guestPasses" to plan.guestPasses.toString(),
            "wifi" to plan.wifiAccess.toString(),
            "displayOrder" to plan.displayOrder.toString(),
        )
    }

    fun planes(
        access: MembresiaAccess,
        plans: List<Plan>,
        currency: String,
        editing: Plan?,
        values: Map<String, String>,
        notice: UiNotice? = null,
    ): UiScreen {
        val items = plans.map { plan ->
            UiListItem(
                title = plan.name,
                subtitle = listOf(
                    tr("{0} días", plan.durationDays),
                    plan.includedVisits?.let { tr("{0} visitas", it) } ?: tr("visitas ilimitadas"),
                    tr("{0} invitados", plan.guestPasses),
                    if (plan.wifiAccess) tr("WiFi") else tr("sin WiFi"),
                ).joinToString(" · "),
                trailing = money(plan.price, currency),
                badges = listOf(if (plan.active) UiBadge(tr("Activo"), UiTone.Success) else UiBadge(tr("Inactivo"), UiTone.Neutral)),
                action = UiNavigate("planes", mapOf("edit" to plan.id.toString())),
            )
        }
        val form = UiForm(
            id = "plan",
            fields = listOf(
                UiField("name", tr("Nombre"), required = true),
                UiField("description", tr("Descripción"), type = "textarea"),
                UiField("price", tr("Precio ({0})", currency), required = true),
                UiField("durationDays", tr("Duración (días)"), type = "number", required = true),
                UiField("includedVisits", tr("Visitas incluidas"), type = "number", help = tr("Vacío = ilimitadas.")),
                UiField("guestPasses", tr("Pases de invitado"), type = "number"),
                UiField("wifi", tr("Incluye WiFi"), type = "boolean"),
                UiField("displayOrder", tr("Orden"), type = "number"),
            ),
            values = values,
            submit = UiButton(
                if (editing == null) tr("Crear plan") else tr("Guardar plan"),
                post("plan-guardar", editing?.let { mapOf("id" to it.id.toString()) }.orEmpty(), formId = "plan"),
            ),
        )
        val editorActions = buildList<UiNode> {
            if (editing != null) {
                add(
                    UiButton(
                        if (editing.active) tr("Desactivar plan") else tr("Activar plan"),
                        post("plan-activo", mapOf("id" to editing.id.toString(), "active" to (!editing.active).toString()), confirm = if (editing.active) "¿Desactivar este plan? Las membresías vigentes siguen igual." else null),
                        style = if (editing.active) "danger" else "secondary",
                    ),
                )
                add(UiButton(tr("Nuevo plan"), UiNavigate("planes"), style = "secondary"))
            }
        }
        val body = listOf(
            UiList(items, emptyText = tr("Aún no hay planes.")),
            UiCard(title = if (editing == null) tr("Nuevo plan") else tr("Editar {0}", editing.name), children = listOf<UiNode>(form) + editorActions),
        )
        return screen(access, "planes", tr("Planes"), notice, body)
    }

    fun ingresos(
        access: MembresiaAccess,
        months: List<RevenueMonth>,
        byPlan: List<RevenueByPlan>,
        currency: String,
        notice: UiNotice? = null,
    ): UiScreen {
        val last = months.lastOrNull()
        val body = buildList<UiNode> {
            if (last != null) {
                add(UiMetric(tr("Ingresos de {0}", last.month), money(last.amount, currency), tr("{0} {1}", last.count, if (last.count == 1) tr("membresía") else tr("membresías")), UiTone.Success))
            }
            add(UiBarChart(months.map { UiChartPoint(it.month.toString(), it.amount.toDouble()) }))
            add(
                UiCard(
                    title = tr("Por mes"),
                    children = listOf(
                        UiTable(
                            listOf(UiTableColumn("month", tr("Mes")), UiTableColumn("count", tr("Membresías")), UiTableColumn("amount", tr("Ingresos"))),
                            months.asReversed().map { mapOf("month" to it.month.toString(), "count" to it.count.toString(), "amount" to money(it.amount, currency)) },
                        ),
                    ),
                ),
            )
            add(
                UiCard(
                    title = tr("Por plan (últimos {0} meses)", months.size),
                    children = listOf(
                        UiTable(
                            listOf(UiTableColumn("plan", tr("Plan")), UiTableColumn("count", tr("Membresías")), UiTableColumn("amount", tr("Ingresos"))),
                            byPlan.map { mapOf("plan" to it.planName, "count" to it.count.toString(), "amount" to money(it.amount, currency)) },
                        ),
                    ),
                ),
            )
        }
        return screen(access, "ingresos", tr("Ingresos"), notice, body)
    }

    fun ajustesValues(settings: MembresiaSettings): Map<String, String> = mapOf(
        "currency" to settings.currency,
        "expiringDays" to settings.expiringDays.toString(),
        "sources" to settings.sources.joinToString("\n"),
    )

    fun ajustes(access: MembresiaAccess, values: Map<String, String>, notice: UiNotice? = null): UiScreen {
        val form = UiForm(
            id = "ajustes",
            fields = listOf(
                UiField("currency", tr("Moneda"), required = true, help = tr("Código de 3 letras, por ejemplo USD.")),
                UiField("expiringDays", tr("Días de aviso de vencimiento"), type = "number", required = true, help = tr("El equipo recibe un aviso esos días antes de que venza.")),
                UiField("sources", tr("Fuentes"), type = "textarea", help = tr("Una por línea.")),
            ),
            values = values,
            submit = UiButton(tr("Guardar ajustes"), post("ajustes-guardar", formId = "ajustes")),
        )
        return screen(access, "ajustes", tr("Ajustes"), notice, listOf(form))
    }

    // ---------------------------------------------------------------- importar

    /**
     * Pantalla Importar. [sources] son (id, titulo) de las fuentes activas; con varias se eligen con botones y
     * [nodes] es lo que dibuja la fuente elegida.
     */
    fun importar(
        access: MembresiaAccess,
        sources: List<Pair<String, String>>,
        selectedId: String?,
        nodes: List<UiNode>,
        notice: UiNotice? = null,
    ): UiScreen {
        val body = buildList<UiNode> {
            if (sources.isEmpty()) {
                add(UiText(tr("No hay una fuente de importación activa. Instala una extensión de importación (por ejemplo, Monday) desde Extensiones."), UiTextStyle.Body))
            } else {
                if (sources.size > 1) {
                    add(UiRow(sources.map { (id, title) -> UiButton(title, UiNavigate("importar", mapOf("source" to id)), style = if (id == selectedId) "primary" else "secondary") }, spacing = 8))
                    if (selectedId == null) add(UiText(tr("Elige de dónde importar."), UiTextStyle.Caption))
                }
                addAll(nodes)
            }
        }
        return screen(access, "importar", tr("Importar"), notice, body)
    }

    // ---------------------------------------------------------------- sugerencias

    /** Sugerencias al escribir el cliente; al elegir una se llenan customerId, name, email y phone. */
    fun suggestions(members: List<MemberSummary>, today: LocalDate = LocalDate.now(), expiringDays: Int = 7): UiSuggestions = UiSuggestions(
        items = members.map { member ->
            val status = statusOf(member, today, expiringDays)?.label ?: tr("Sin membresía")
            UiSuggestion(
                title = member.customerName.ifBlank { member.email.ifBlank { member.phone } },
                subtitle = listOf(member.email, member.phone, status).filter { it.isNotBlank() }.joinToString(" · "),
                values = mapOf(
                    "customerId" to member.customerId.toString(),
                    "name" to member.customerName,
                    "email" to member.email,
                    "phone" to member.phone,
                ),
            )
        },
    )
}
