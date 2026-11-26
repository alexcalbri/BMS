package com.ideasdeveloper.bms.modules.eventos

import com.ideasdeveloper.bms.server.i18n.msg
import com.ideasdeveloper.bms.server.i18n.tr
import com.ideasdeveloper.bms.server.modules.ModulePermissions
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
import com.ideasdeveloper.bms.ui.UiTable
import com.ideasdeveloper.bms.ui.UiTableColumn
import com.ideasdeveloper.bms.ui.UiText
import com.ideasdeveloper.bms.ui.UiTextStyle
import com.ideasdeveloper.bms.ui.UiTone
import java.math.BigDecimal
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.IsoFields
import java.util.UUID

/** A button that moves an event to [target]; [confirm] is asked first when set. */
data class EventAction(val label: String, val target: EventStatus, val style: String = "primary", val confirm: String? = null)

/** A button of the registration card: [op] is the operation of the `inscripcion` screen. */
data class RegistrationAction(val label: String, val op: String, val style: String = "primary", val confirm: String? = null)

/**
 * Native screens of the module (upcoming, past, event card, registration card, attendance). Every builder is pure: it
 * receives data already loaded and filtered, and returns a [UiScreen]. EventosRoutes loads the data and EventosActions
 * parses what the forms send. The forms (new, edit, duplicate, settings) are in EventosForms.
 */
object EventosScreens {
    const val Route: String = EventosModule.UiRoute
    const val CustomerSuggestRoute: String = "$Route/suggest/customers"
    const val ImportScreen = "importar"
    const val ListLimit = 200
    const val PastLimit = 100
    const val RosterLimit = 500
    const val AgendaWeeks = 12
    val AutoPrice = msg("Automático")
    private val TimeFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    private val RegisterKeys = setOf("customer", "customer_id", "new_name", "new_email", "new_phone", "seats", "price_kind", "amount", "paid", "note")
    private val PaymentKeys = setOf("pay_date", "pay_amount", "pay_method", "pay_note")

    /** What the user can do in the module (the permissions are also checked by the routes and the service). */
    data class Caps(val register: Boolean, val manage: Boolean, val import: Boolean = false) {
        companion object {
            fun of(permissions: ModulePermissions) = Caps(
                register = permissions.has(EventosModule.Register),
                manage = permissions.has(EventosModule.Manage),
                import = permissions.has(EventosModule.Import),
            )
        }
    }

    /** What every screen needs: who looks ([caps]), the users and settings, today, the current instant and the import menu. */
    data class Context(
        val caps: Caps,
        val users: List<EventosUser>,
        val settings: EventosSettings,
        val today: LocalDate,
        val now: Instant,
        // The user holds eventos.import and some import source is active: the "Importar" item shows.
        val importEnabled: Boolean = false,
        val zone: ZoneId = ZoneId.systemDefault(),
    ) {
        fun userName(id: UUID?): String? = id?.let { wanted -> users.firstOrNull { it.id == wanted }?.name ?: tr("Usuario retirado") }
    }

    /** One ISO week of the agenda. */
    data class Week(val monday: LocalDate, val events: List<EventWithStats>) {
        val title: String
            get() = tr("Semana {0}: {1} a {2}", monday.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR), monday, monday.plusDays(6))
    }

    /** Data of the event card: the event with its numbers, registrations that are not cancelled (public) and payments (private). */
    data class EventDetail(val event: EventWithStats, val registrations: List<Registration>, val payments: List<EventPayment>)

    /** Data of the registration card. [email] is empty unless the user may register people. */
    data class RegistrationDetail(val registration: Registration, val event: Event, val email: String, val membership: ActiveMembership?)

    // ---- small helpers ----

    fun hint(text: String, tone: String? = null) = UiText(text, UiTextStyle.Caption, tone)

    private fun subtitle(text: String) = UiText(text, UiTextStyle.Subtitle)

    fun screen(context: Context, title: String, selected: String, notice: UiNotice?, body: List<UiNode>) =
        UiScreen(title = title, navigation = navigation(context.caps, selected, context.importEnabled), notice = notice, body = body)

    /** A POST to [screen] with the operation [op]; [formId] attaches the values of a form as the JSON body. */
    fun post(screen: String, op: String, params: Map<String, String> = emptyMap(), confirm: String? = null, formId: String? = null) =
        UiRequest("$Route/$screen", params = mapOf("op" to op) + params, confirm = confirm, formId = formId)

    fun open(screen: String, id: UUID) = UiNavigate(screen, mapOf("id" to id.toString()))

    fun money(value: BigDecimal?, currency: String): String =
        value?.let { "${EventosRules.money(it).toPlainString()} $currency" } ?: "—"

    fun priceLabel(kind: PriceKind): String = when (kind) {
        PriceKind.Member -> msg("Miembro")
        PriceKind.General -> msg("General")
        PriceKind.Courtesy -> msg("Cortesía")
    }

    fun statusTone(status: EventStatus): String = when (status) {
        EventStatus.Draft -> UiTone.Neutral
        EventStatus.Published -> UiTone.Success
        EventStatus.Full -> UiTone.Warning
        EventStatus.Done -> UiTone.Info
        EventStatus.Cancelled -> UiTone.Danger
    }

    fun whenLabel(event: Event, zone: ZoneId): String {
        val start = EventosRules.stamp(event.startsAt, zone)
        val end = event.endsAt ?: return start
        val sameDay = EventosRules.day(end, zone) == EventosRules.day(event.startsAt, zone)
        return tr("{0} a {1}", start, if (sameDay) end.atZone(zone).format(TimeFormat) else EventosRules.stamp(end, zone))
    }

    // ---- navigation ----

    fun navigation(caps: Caps, selected: String, import: Boolean = false): UiNavigation = UiNavigation(
        title = tr(EventosModule.displayName),
        items = listOfNotNull(
            UiNavItem("proximos", tr("Próximos"), UiNavigate("proximos")),
            UiNavItem("pasados", tr("Pasados"), UiNavigate("pasados")),
            UiNavItem("nuevo", tr("Nuevo evento"), UiNavigate("nuevo", mapOf("kind" to EventKind.Public.code))).takeIf { caps.manage },
            UiNavItem("nuevo-privado", tr("Nuevo privado"), UiNavigate("nuevo", mapOf("kind" to EventKind.Private.code))).takeIf { caps.manage },
            UiNavItem("ajustes", tr("Ajustes"), UiNavigate("ajustes")).takeIf { caps.manage },
            UiNavItem(ImportScreen, tr("Importar"), UiNavigate(ImportScreen)).takeIf { caps.import && import },
        ),
        selected = selected,
    )

    // ---- rules of the buttons (pure) ----

    /** Buttons that change the state of [event] (manage). Cancelling has its own form because it needs a reason. */
    fun statusActions(caps: Caps, event: Event): List<EventAction> {
        if (!caps.manage) return emptyList()
        return when (event.status) {
            EventStatus.Draft -> listOf(EventAction(if (event.kind == EventKind.Private) tr("Confirmar") else tr("Publicar"), EventStatus.Published))
            EventStatus.Published, EventStatus.Full ->
                listOf(EventAction(tr("Marcar realizado"), EventStatus.Done, "secondary", "¿Marcar este evento como realizado?"))
            EventStatus.Cancelled -> listOf(EventAction(tr("Reabrir"), EventStatus.Draft, "secondary"))
            EventStatus.Done -> emptyList()
        }
    }

    fun canCancel(caps: Caps, event: Event): Boolean =
        caps.manage && (event.status == EventStatus.Published || event.status == EventStatus.Full)

    /** The registration form shows for who may register people, in a public event that admits registrations. */
    fun canRegister(caps: Caps, event: Event): Boolean = caps.register && EventosRules.registerError(event.kind, event.status) == null

    fun canTakeAttendance(caps: Caps, event: Event, now: Instant, zone: ZoneId): Boolean =
        caps.register && event.kind == EventKind.Public &&
            EventosRules.attendanceError(RegistrationStatus.Registered, event.status, event.startsAt, now, zone) == null

    /** Buttons of the registration card for who may register people (the service applies the rules again). */
    fun registrationActions(caps: Caps, registration: Registration, event: Event, now: Instant, zone: ZoneId): List<RegistrationAction> {
        if (!caps.register || registration.status == RegistrationStatus.Cancelled) return emptyList()
        val attendanceOpen = EventosRules.attendanceError(registration.status, event.status, event.startsAt, now, zone) == null
        return buildList {
            if (registration.status == RegistrationStatus.Waitlist) add(RegistrationAction(tr("Pasar a inscrito"), "promote"))
            add(
                if (registration.paid) RegistrationAction(tr("Marcar no pagado"), "unpaid", "secondary") else RegistrationAction(tr("Marcar pagado"), "paid"),
            )
            if (attendanceOpen) {
                add(
                    if (registration.attendedAt != null) RegistrationAction(tr("Quitar asistencia"), "unattend", "secondary")
                    else RegistrationAction(tr("Registrar asistencia"), "attend"),
                )
            }
            add(RegistrationAction(tr("Cancelar inscripción"), "cancel", "danger", "¿Cancelar esta inscripción? No se puede deshacer."))
        }
    }

    // ---- agenda ----

    /**
     * Groups [events] by ISO week (Monday to Sunday) starting with the week of [today]; events before that week or after
     * [weeks] weeks are left out. Inside a week they keep the order of their start.
     */
    fun weekBuckets(events: List<EventWithStats>, today: LocalDate, zone: ZoneId, weeks: Int = AgendaWeeks): List<Week> {
        val first = today.with(DayOfWeek.MONDAY)
        val limit = first.plusWeeks(weeks.toLong())
        return events
            .map { it to EventosRules.day(it.event.startsAt, zone) }
            .filter { (_, day) -> !day.isBefore(first) && day.isBefore(limit) }
            .groupBy { (_, day) -> day.with(DayOfWeek.MONDAY) }
            .toSortedMap()
            .map { (monday, rows) ->
                Week(monday, rows.map { it.first }.sortedWith(compareBy({ it.event.startsAt }, { it.event.id })))
            }
    }

    fun occupancyLabel(item: EventWithStats): String {
        val event = item.event
        if (event.kind == EventKind.Private) return tr("Privado")
        val capacity = event.capacity
        return if (capacity != null) "${item.stats.occupied}/$capacity" else tr("{0} inscritos", item.stats.occupied)
    }

    fun eventBadges(item: EventWithStats): List<UiBadge> {
        val event = item.event
        return buildList {
            add(UiBadge(tr(EventosRules.statusLabel(event.kind, event.status)), statusTone(event.status)))
            if (event.series.isNotBlank()) add(UiBadge(event.series))
            if (event.kind == EventKind.Public && item.stats.waiting > 0) add(UiBadge(tr("{0} en espera", item.stats.waiting), UiTone.Warning))
        }
    }

    fun eventItem(item: EventWithStats, zone: ZoneId): UiListItem {
        val event = item.event
        return UiListItem(
            title = event.title,
            subtitle = listOf(EventosRules.stamp(event.startsAt, zone), event.space).filter { it.isNotBlank() }.joinToString(" · "),
            trailing = occupancyLabel(item),
            badges = eventBadges(item),
            action = open("evento", event.id),
        )
    }

    // ---- Próximos ----

    /** The label of the type filter for what the user picked (a label or a code); "Todos" for anything else. */
    fun kindFilterLabel(text: String?): String = when (EventosActions.kindFilter(text)) {
        EventKind.Public -> msg("Públicos")
        EventKind.Private -> msg("Privados")
        null -> EventosActions.AllOption
    }

    private fun withCurrent(base: List<String>, current: String?): List<String> =
        (listOf(EventosActions.AllOption) + base + listOfNotNull(current?.takeIf { it.isNotBlank() && !EventosActions.isAll(it) }))
            .distinctBy { it.lowercase() }

    private fun filterCard(context: Context, params: Map<String, String>): UiNode {
        val fields = listOf(
            UiField("tipo", "Tipo", type = "dropdown", options = listOf(EventosActions.AllOption, msg("Públicos"), msg("Privados"))),
            UiField("categoria", tr("Categoría"), type = "dropdown", options = withCurrent(context.settings.categories, params["categoria"])),
            UiField("espacio", tr("Espacio"), type = "dropdown", options = withCurrent(context.settings.spaces, params["espacio"])),
            UiField("serie", tr("Serie")),
            UiField("q", tr("Buscar"), placeholder = tr("Título, serie, espacio o anfitrión")),
        )
        val values = mapOf(
            "tipo" to kindFilterLabel(params["tipo"]),
            "categoria" to (params["categoria"]?.takeIf { it.isNotBlank() } ?: EventosActions.AllOption),
            "espacio" to (params["espacio"]?.takeIf { it.isNotBlank() } ?: EventosActions.AllOption),
            "serie" to params["serie"].orEmpty(),
            "q" to params["q"].orEmpty(),
        )
        return UiCard(
            listOf(
                UiForm(
                    "filters", fields, values,
                    submit = UiButton(tr("Filtrar"), UiNavigate("proximos", formId = "filters"), style = "secondary"),
                    minFieldWidth = 200,
                ),
            ),
        )
    }

    fun proximos(context: Context, events: List<EventWithStats>, overview: EventosOverview, params: Map<String, String>, notice: UiNotice? = null): UiScreen {
        val weeks = weekBuckets(events.take(ListLimit), context.today, context.zone)
        val body = buildList<UiNode> {
            add(
                UiGrid(
                    listOf(
                        UiCard(listOf(UiMetric(tr("Próximos 30 días"), overview.upcoming.toString()))),
                        UiCard(listOf(UiMetric(tr("Inscritos esta semana"), overview.registeredThisWeek.toString()))),
                        UiCard(listOf(UiMetric(tr("Llenos"), overview.full.toString(), captionTone = if (overview.full > 0) UiTone.Warning else UiTone.Neutral))),
                    ),
                    minItemWidth = 180,
                ),
            )
            add(filterCard(context, params))
            if (events.size > ListLimit) add(hint(tr("Se muestran los primeros {0} eventos: usa los filtros para acotar.", ListLimit), UiTone.Warning))
            if (weeks.isEmpty()) {
                add(
                    UiCard(
                        listOfNotNull(
                            UiText(tr("No hay eventos próximos con estos filtros.")),
                            UiButton(tr("Nuevo evento"), UiNavigate("nuevo", mapOf("kind" to EventKind.Public.code)), style = "secondary").takeIf { context.caps.manage },
                        ),
                    ),
                )
            } else {
                weeks.forEach { week ->
                    add(UiCard(listOf(UiList(week.events.map { eventItem(it, context.zone) })), title = tr("{0} ({1})", week.title, week.events.size)))
                }
            }
        }
        return screen(context, tr("Próximos eventos"), "proximos", notice, body)
    }

    // ---- Pasados ----

    fun pasados(context: Context, events: List<EventWithStats>, notice: UiNotice? = null): UiScreen {
        val body = buildList<UiNode> {
            if (events.size > PastLimit) add(hint(tr("Se muestran los últimos {0} eventos.", PastLimit), UiTone.Warning))
            add(UiCard(listOf(UiList(events.take(PastLimit).map { eventItem(it, context.zone) }, emptyText = tr("Aún no hay eventos realizados ni cancelados."))), title = tr("Realizados y cancelados")))
        }
        return screen(context, tr("Eventos pasados"), "pasados", notice, body)
    }

    // ---- Evento ----

    private fun fact(label: String, value: String?): UiNode? = value?.takeIf { it.isNotBlank() }?.let { hint(tr("{0}: {1}", label, it)) }

    private fun registrationItem(item: Registration, currency: String, zone: ZoneId, waiting: Boolean): UiListItem = UiListItem(
        title = item.customerName,
        subtitle = if (waiting) "En espera desde ${item.waitlistedAt?.let { EventosRules.stamp(it, zone) } ?: "—"}" else item.note,
        trailing = if (waiting) EventosRules.seatsLabel(item.seats) else tr("{0} · {1}", EventosRules.seatsLabel(item.seats), money(item.amount, currency)),
        badges = buildList {
            if (item.paid) add(UiBadge(tr("Pagado"), UiTone.Success))
            if (item.attendedAt != null) add(UiBadge(tr("Asistió"), UiTone.Info))
            if (item.priceKind != PriceKind.General) add(UiBadge(tr(priceLabel(item.priceKind))))
        },
        action = open("inscripcion", item.id),
    )

    private fun registerCard(context: Context, detail: EventDetail, submitted: Map<String, String>?): UiNode {
        val event = detail.event.event
        val free = EventosRules.available(event.capacity, detail.event.stats.occupied)
        val values = mapOf("seats" to "1", "price_kind" to AutoPrice, "paid" to "false") + submitted.orEmpty().filterKeys { it in RegisterKeys }
        val newCustomer = tr("Cliente nuevo (si no está en la lista)")
        val fields = listOf(
            UiField(
                "customer", tr("Cliente"), placeholder = tr("Escribe para buscar"), section = tr("Cliente"),
                help = tr("Elige un cliente de la lista de sugerencias o escribe abajo los datos de uno nuevo."),
                suggest = UiSuggest(CustomerSuggestRoute),
            ),
            UiField("new_name", tr("Nombre"), section = newCustomer),
            UiField("new_email", tr("Correo"), type = "email", section = newCustomer),
            UiField("new_phone", tr("Teléfono"), type = "phone", section = newCustomer),
            UiField("seats", tr("Plazas"), type = "number", section = tr("Inscripción")),
            UiField(
                "price_kind", tr("Tipo de precio"), type = "dropdown", section = tr("Inscripción"),
                options = listOf(AutoPrice, priceLabel(PriceKind.Member), priceLabel(PriceKind.General), priceLabel(PriceKind.Courtesy)),
                help = tr("Automático propone el precio de miembro si el cliente tiene una membresía vigente."),
            ),
            UiField("amount", tr("Monto"), section = tr("Inscripción"), help = tr("Vacío = precio × plazas.")),
            UiField("paid", tr("Pagado"), type = "boolean", section = tr("Inscripción")),
            UiField("note", tr("Nota"), type = "textarea", section = tr("Inscripción")),
        )
        val prices = tr("Precio de miembro: {0} · Precio general: {1}", money(event.priceMember, event.currency), money(event.pricePublic, event.currency))
        return UiCard(
            listOf(
                hint(prices),
                hint(free?.let { tr("Plazas libres: {0}. Si no caben, queda en la lista de espera.", it) } ?: tr("Sin límite de cupo.")),
                UiForm(
                    "register", fields, values,
                    submit = UiButton(tr("Inscribir"), post("evento", "register", mapOf("id" to event.id.toString()), formId = "register")),
                ),
            ),
            title = tr("Inscribir"),
        )
    }

    private fun paymentsCard(context: Context, detail: EventDetail, submitted: Map<String, String>?): UiNode {
        val event = detail.event.event
        val manage = context.caps.manage
        val values = mapOf("pay_date" to context.today.toString()) + submitted.orEmpty().filterKeys { it in PaymentKeys }
        val table = UiTable(
            columns = listOf(UiTableColumn("date", tr("Fecha")), UiTableColumn("amount", tr("Monto")), UiTableColumn("method", tr("Método")), UiTableColumn("note", tr("Nota"))),
            rows = detail.payments.map { mapOf("date" to it.paidOn.toString(), "amount" to money(it.amount, event.currency), "method" to it.method, "note" to it.note) },
            emptyText = tr("Sin abonos."),
        )
        val children = buildList<UiNode> {
            add(table)
            if (manage) {
                if (detail.payments.isNotEmpty()) {
                    add(
                        UiColumn(
                            detail.payments.map { payment ->
                                UiButton(
                                    tr("Borrar abono {0} · {1}", payment.paidOn, money(payment.amount, event.currency)),
                                    post("evento", "payment-delete", mapOf("id" to event.id.toString(), "payment" to payment.id.toString()),
                                        confirm = "¿Borrar este abono de ${money(payment.amount, event.currency)}? No se puede deshacer."),
                                    style = "danger",
                                )
                            },
                            spacing = 8,
                        ),
                    )
                }
                add(
                    UiForm(
                        "payment",
                        listOf(
                            UiField("pay_date", tr("Fecha"), type = "date", required = true),
                            UiField("pay_amount", tr("Monto"), required = true),
                            UiField("pay_method", tr("Método"), placeholder = tr("Efectivo, transferencia, tarjeta...")),
                            UiField("pay_note", tr("Nota")),
                        ),
                        values,
                        submit = UiButton(tr("Registrar abono"), post("evento", "payment-add", mapOf("id" to event.id.toString()), formId = "payment")),
                    ),
                )
            }
        }
        return UiCard(children, title = tr("Abonos"))
    }

    private fun clientCard(context: Context, detail: EventDetail): UiNode {
        val item = detail.event
        val event = item.event
        val balance = EventosRules.balance(event.agreedAmount, listOf(item.stats.paymentsTotal))
        val balanceText = if (balance.signum() < 0) tr("Pagado de más: {0}", money(balance.negate(), event.currency)) else tr("Saldo: {0}", money(balance, event.currency))
        val clientId = event.clientCustomerId
        return UiCard(
            listOfNotNull(
                UiText(event.clientName.ifBlank { if (clientId == null) tr("Sin contratante") else tr("Cliente no disponible") }, UiTextStyle.Subtitle),
                event.guests?.let { hint(tr("Invitados: {0}", it)) },
                UiText(tr("Monto acordado: {0}", money(event.agreedAmount, event.currency))),
                UiText(tr("Pagado: {0}", money(item.stats.paymentsTotal, event.currency))),
                UiText(balanceText, tone = if (balance.signum() > 0) UiTone.Warning else UiTone.Success),
                UiButton(tr("Abrir ficha del cliente"), UiOpenModule("clientes", "customer", mapOf("id" to clientId.toString())), style = "secondary").takeIf { clientId != null },
            ),
            title = tr("Contratante"),
        )
    }

    /** The event card. [submitted] refills the registration and payment forms after an error. */
    fun evento(context: Context, detail: EventDetail, notice: UiNotice? = null, submitted: Map<String, String>? = null): UiScreen {
        val item = detail.event
        val event = item.event
        val caps = context.caps
        val isPublic = event.kind == EventKind.Public
        val registered = detail.registrations.filter { it.status == RegistrationStatus.Registered }
        val waiting = detail.registrations.filter { it.status == RegistrationStatus.Waitlist }
        val actions = UiRow(
            listOfNotNull(
                UiButton(tr("Editar"), UiNavigate("editar", mapOf("id" to event.id.toString())), style = "secondary").takeIf { caps.manage },
                UiButton(tr("Duplicar"), UiNavigate("duplicar", mapOf("id" to event.id.toString())), style = "secondary").takeIf { caps.manage },
            ) + statusActions(caps, event).map { action ->
                UiButton(
                    action.label,
                    post("evento", "status", mapOf("id" to event.id.toString(), "status" to action.target.code), confirm = action.confirm),
                    style = action.style,
                )
            } + listOfNotNull(
                UiButton(tr("Asistencia"), UiNavigate("asistencia", mapOf("id" to event.id.toString()))).takeIf { canTakeAttendance(caps, event, context.now, context.zone) },
                UiButton(tr("Volver"), UiNavigate("proximos"), style = "secondary"),
            ),
        )
        val metrics = if (isPublic) {
            listOf(
                UiMetric(tr("Inscritos"), item.stats.occupied.toString() + (event.capacity?.let { "/$it" } ?: "")),
                UiMetric(tr("En espera"), item.stats.waiting.toString()),
                UiMetric(tr("Asistieron"), "${item.stats.attended}/${item.stats.registrations}"),
                UiMetric(tr("Cobrado"), money(item.stats.collected, event.currency), caption = tr("Pendiente: {0}", money(item.stats.pending, event.currency))),
            )
        } else {
            val balance = EventosRules.balance(event.agreedAmount, listOf(item.stats.paymentsTotal))
            listOf(
                UiMetric(tr("Monto acordado"), money(event.agreedAmount, event.currency)),
                UiMetric(tr("Pagado"), money(item.stats.paymentsTotal, event.currency)),
                UiMetric(tr("Saldo"), money(balance, event.currency), captionTone = if (balance.signum() > 0) UiTone.Warning else UiTone.Success),
            )
        }
        val body = buildList<UiNode> {
            add(actions)
            add(
                UiCard(
                    listOfNotNull(
                        UiStatus(tr(EventosRules.statusLabel(event.kind, event.status)), statusTone(event.status)),
                        UiText(whenLabel(event, context.zone), UiTextStyle.Subtitle),
                        fact(tr("Espacio"), event.space),
                        fact(tr("Categoría"), event.category),
                        fact(tr("Serie"), event.series),
                        fact(tr("Anfitrión"), event.hostName),
                        fact(tr("Responsable"), context.userName(event.responsibleUserId)),
                        fact(tr("Motivo de la cancelación"), event.cancelReason.takeIf { event.status == EventStatus.Cancelled }),
                        event.description.takeIf { it.isNotBlank() }?.let { UiText(it) },
                    ),
                    title = event.title,
                ),
            )
            add(UiGrid(metrics.map { UiCard(listOf(it)) }, minItemWidth = 180))
            if (isPublic) {
                if (canRegister(caps, event)) add(registerCard(context, detail, submitted))
                add(UiCard(listOf(UiList(registered.map { registrationItem(it, event.currency, context.zone, waiting = false) }, emptyText = tr("Aún no hay inscritos."))), title = tr("Inscritos ({0})", registered.size)))
                if (waiting.isNotEmpty()) {
                    add(UiCard(listOf(UiList(waiting.map { registrationItem(it, event.currency, context.zone, waiting = true) })), title = tr("Lista de espera ({0})", waiting.size)))
                }
            } else {
                add(clientCard(context, detail))
                add(paymentsCard(context, detail, submitted))
            }
            if (event.notes.isNotBlank()) add(UiCard(listOf(UiText(event.notes)), title = tr("Notas")))
            if (canCancel(caps, event)) {
                add(
                    UiCard(
                        listOf(
                            UiForm(
                                "cancel",
                                listOf(UiField("reason", tr("Motivo de la cancelación"), required = true)),
                                submitted.orEmpty().filterKeys { it == "reason" },
                                submit = UiButton(
                                    tr("Cancelar evento"),
                                    post("evento", "status", mapOf("id" to event.id.toString(), "status" to EventStatus.Cancelled.code),
                                        confirm = "¿Cancelar este evento? Podrás reabrirlo como borrador.", formId = "cancel"),
                                    style = "danger",
                                ),
                            ),
                        ),
                        title = tr("Cancelar evento"), collapsible = true, expanded = false,
                    ),
                )
            }
        }
        return screen(context, event.title, "proximos", notice, body)
    }

    // ---- Inscripción ----

    fun inscripcion(context: Context, detail: RegistrationDetail, notice: UiNotice? = null): UiScreen {
        val registration = detail.registration
        val event = detail.event
        val stateLabel = when (registration.status) {
            RegistrationStatus.Registered -> tr("Inscrito")
            RegistrationStatus.Waitlist -> tr("En lista de espera")
            RegistrationStatus.Cancelled -> tr("Cancelada")
        }
        val tone = when (registration.status) {
            RegistrationStatus.Registered -> UiTone.Success
            RegistrationStatus.Waitlist -> UiTone.Warning
            RegistrationStatus.Cancelled -> UiTone.Danger
        }
        val buttons = registrationActions(context.caps, registration, event, context.now, context.zone).map { action ->
            UiButton(action.label, post("inscripcion", action.op, mapOf("id" to registration.id.toString()), confirm = action.confirm), style = action.style)
        }
        val body = buildList<UiNode> {
            add(
                UiRow(
                    buttons + listOfNotNull(
                        UiButton(tr("Abrir ficha del cliente"), UiOpenModule("clientes", "customer", mapOf("id" to registration.customerId.toString())), style = "secondary")
                            .takeIf { context.caps.register },
                        UiButton(tr("Volver al evento"), open("evento", event.id), style = "secondary"),
                    ),
                ),
            )
            add(
                UiCard(
                    listOfNotNull(
                        UiStatus(stateLabel, tone),
                        UiText(event.title, UiTextStyle.Subtitle),
                        hint(whenLabel(event, context.zone)),
                        fact(tr("Correo"), detail.email.takeIf { context.caps.register }),
                        detail.membership?.let { hint(EventosMembership.label(it)) },
                        UiText(tr("{0} · {1} · {2}", EventosRules.seatsLabel(registration.seats), tr(priceLabel(registration.priceKind)), money(registration.amount, registration.eventCurrency))),
                        UiText(
                            if (registration.paid) tr("Pagado") + (registration.paidAt?.let { " " + tr("el {0}", EventosRules.stamp(it, context.zone)) } ?: "") else tr("Sin pagar"),
                            tone = if (registration.paid) UiTone.Success else UiTone.Warning,
                        ),
                        fact(tr("Nota del pago"), registration.paymentNote),
                        registration.attendedAt?.let { hint(tr("Asistió: {0}", EventosRules.stamp(it, context.zone)) + registration.attendedBy.takeIf { by -> by.isNotBlank() }?.let { by -> " " + tr("({0})", by) }.orEmpty()) },
                        registration.waitlistedAt?.takeIf { registration.status == RegistrationStatus.Waitlist }?.let { hint(tr("En espera desde {0}", EventosRules.stamp(it, context.zone))) },
                        fact(tr("Nota"), registration.note),
                        fact(tr("Inscrito por"), registration.createdBy),
                    ),
                    title = registration.customerName,
                ),
            )
        }
        return screen(context, registration.customerName, "proximos", notice, body)
    }

    // ---- Asistencia (tablet) ----

    fun asistencia(context: Context, event: Event, registrations: List<Registration>, notice: UiNotice? = null): UiScreen {
        val people = registrations.filter { it.status == RegistrationStatus.Registered }
        val arrived = people.count { it.attendedAt != null }
        val error = EventosRules.attendanceError(RegistrationStatus.Registered, event.status, event.startsAt, context.now, context.zone)
        val body = buildList<UiNode> {
            add(UiRow(listOf(UiButton(tr("Volver al evento"), open("evento", event.id), style = "secondary"))))
            add(UiCard(listOf(UiMetric(tr("Asistieron"), "$arrived/${people.size}", caption = event.title))))
            if (error != null) add(hint(error, UiTone.Warning))
            if (people.isEmpty()) {
                add(UiText(tr("No hay personas inscritas.")))
            } else {
                add(
                    UiGrid(
                        people.map { person ->
                            val button = if (person.attendedAt != null) {
                                UiButton(tr("Quitar"), post("asistencia", "unattend", mapOf("id" to event.id.toString(), "registration" to person.id.toString())), style = "secondary")
                            } else {
                                UiButton(tr("Llegó"), post("asistencia", "attend", mapOf("id" to event.id.toString(), "registration" to person.id.toString())))
                            }
                            UiCard(
                                listOfNotNull(
                                    hint(EventosRules.seatsLabel(person.seats)),
                                    UiStatus(tr("Llegó"), UiTone.Success).takeIf { person.attendedAt != null },
                                ),
                                title = person.customerName,
                                actions = listOf(button).takeIf { error == null && context.caps.register }.orEmpty(),
                            )
                        },
                        minItemWidth = 280,
                    ),
                )
            }
        }
        return screen(context, tr("Asistencia: {0}", event.title), "proximos", notice, body)
    }

    // ---- notices ----

    /** The text and tone of the notice after registering somebody. */
    fun registeredNotice(result: RegistrationResult): Pair<String, String> {
        val registration = result.registration
        if (result.waitlisted) {
            return tr("{0} quedó en la lista de espera: no hay plazas para {1}.", registration.customerName, EventosRules.seatsLabel(registration.seats)) to UiTone.Warning
        }
        val text = buildString {
            append(tr("{0} inscrito: {1} · {2}.", registration.customerName, EventosRules.seatsLabel(registration.seats), money(registration.amount, result.currency)))
            result.membership?.let { append(' ').append(EventosMembership.label(it)).append('.') }
            if (result.eventBecameFull) append(" " + tr("Evento lleno."))
        }
        return text to UiTone.Success
    }
}
