package com.ideasdeveloper.bms.modules.mantenimiento

import com.ideasdeveloper.bms.server.i18n.msg
import com.ideasdeveloper.bms.server.i18n.tr
import com.ideasdeveloper.bms.ui.UiAction
import com.ideasdeveloper.bms.ui.UiBadge
import com.ideasdeveloper.bms.ui.UiBoard
import com.ideasdeveloper.bms.ui.UiBoardCard
import com.ideasdeveloper.bms.ui.UiBoardColumn
import com.ideasdeveloper.bms.ui.UiButton
import com.ideasdeveloper.bms.ui.UiCard
import com.ideasdeveloper.bms.ui.UiDivider
import com.ideasdeveloper.bms.ui.UiField
import com.ideasdeveloper.bms.ui.UiFieldCondition
import com.ideasdeveloper.bms.ui.UiFieldValues
import com.ideasdeveloper.bms.ui.UiFileRef
import com.ideasdeveloper.bms.ui.UiFiles
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
import com.ideasdeveloper.bms.ui.UiRequest
import com.ideasdeveloper.bms.ui.UiRow
import com.ideasdeveloper.bms.ui.UiScreen
import com.ideasdeveloper.bms.ui.UiStatus
import com.ideasdeveloper.bms.ui.UiText
import com.ideasdeveloper.bms.ui.UiTextStyle
import com.ideasdeveloper.bms.ui.UiTimeline
import com.ideasdeveloper.bms.ui.UiTimelineEntry
import com.ideasdeveloper.bms.ui.UiTone
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

/** Un botón que pasa la orden a [status]; [confirm] se pregunta antes cuando existe. */
data class StatusAction(val label: String, val status: OrderStatus, val style: String = "primary", val confirm: String? = null)

/**
 * Pantallas nativas del módulo. Cada constructor es puro: recibe datos ya cargados (y filtrados por el scope) y devuelve un
 * [UiScreen]; MantenimientoRoutes carga los datos y MantenimientoActions lee lo que envían los formularios.
 */
object MantenimientoScreens {
    const val Route: String = MantenimientoModule.UiRoute
    const val ListLimit = 200
    const val KanbanLimit = 50
    const val HistoryLimit = 50
    const val ViewList = "list"
    const val ViewBoard = "board"
    private val Views = listOf(ViewList to msg("Lista"), ViewBoard to msg("Tablero"))
    private val StampFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    /** Etiquetas de los usuarios asignables por id. */
    class UserNames(users: List<MantenimientoUser>) {
        private val byId = users.associate { it.id to it.label }
        fun label(id: UUID): String = byId[id] ?: MantenimientoActions.RetiredUser
    }

    /** Lo que toda pantalla necesita: quién mira ([scope]), los usuarios asignables, las propiedades y hoy. */
    data class Context(
        val scope: MantenimientoScope,
        val users: List<MantenimientoUser>,
        val properties: List<Property>,
        val today: LocalDate,
        // El usuario tiene mantenimiento.import y alguna fuente está activa: aparece "Importar".
        val importEnabled: Boolean = false,
        val zone: ZoneId = ZoneId.systemDefault(),
    ) {
        val names: UserNames = UserNames(users)
    }

    /** Datos de la pantalla Órdenes: [orders] para la lista (con una fila de más sobre [ListLimit]) o [columns] para el tablero. */
    data class OrdersData(val orders: List<Order> = emptyList(), val columns: Map<OrderStatus, List<Order>> = emptyMap())

    data class OrderDetail(
        val order: Order,
        val checklist: List<OrderCheck>,
        val notes: List<OrderNote>,
        val attachments: List<Attachment>,
        val assets: AssetOptions,
        val plan: Plan? = null,
        // Compras está activo y el usuario puede usar su herramienta ahora mismo.
        val purchaseAvailable: Boolean = false,
    )

    // ---- tonos e insignias ----

    fun statusTone(status: OrderStatus): String = when (status) {
        OrderStatus.Done -> UiTone.Success
        OrderStatus.Rejected -> UiTone.Danger
        OrderStatus.Requested, OrderStatus.Quoting, OrderStatus.InProgress -> UiTone.Info
        OrderStatus.OnHold -> UiTone.Warning
        OrderStatus.Open, OrderStatus.Cancelled -> UiTone.Neutral
    }

    fun priorityTone(priority: OrderPriority): String = when (priority) {
        OrderPriority.Urgent -> UiTone.Danger
        OrderPriority.High -> UiTone.Warning
        else -> UiTone.Neutral
    }

    fun orderBadges(order: Order, today: LocalDate, withStatus: Boolean = true): List<UiBadge> = buildList {
        if (order.isOverdue(today)) add(UiBadge(tr("Vencida"), UiTone.Danger))
        if (order.priority != OrderPriority.Medium) add(UiBadge(tr(order.priority.label), priorityTone(order.priority)))
        if (order.kind == OrderKind.Project) add(UiBadge(tr(OrderKind.Project.label), UiTone.Neutral))
        if (withStatus) add(UiBadge(tr(order.status.label), statusTone(order.status)))
    }

    // ---- navegación ----

    fun navigation(scope: MantenimientoScope, selected: String, import: Boolean = false): UiNavigation = UiNavigation(
        title = tr(MantenimientoModule.displayName),
        items = listOfNotNull(
            UiNavItem("upcoming", tr("Próximos"), UiNavigate("upcoming")),
            UiNavItem("mine", tr("Mis órdenes"), UiNavigate("mine")),
            UiNavItem("orders", tr("Órdenes"), UiNavigate("orders")).takeIf { scope.seesAll },
            UiNavItem("assets", tr("Activos"), UiNavigate("assets")),
            UiNavItem("plans", tr("Planes"), UiNavigate("plans")),
            UiNavItem("admin", tr("Ajustes"), UiNavigate("admin")).takeIf { scope.canManage },
            UiNavItem("importar", tr("Importar"), UiNavigate("importar")).takeIf { import },
        ),
        selected = selected,
    )

    private fun screen(context: Context, title: String, selected: String, notice: UiNotice?, body: List<UiNode>) =
        UiScreen(title = title, navigation = navigation(context.scope, selected, context.importEnabled), notice = notice, body = body)

    private fun openOrder(order: Order): UiAction = UiNavigate("order", mapOf("id" to order.id.toString()))

    private fun hint(text: String, tone: String? = null) = UiText(text, UiTextStyle.Caption, tone)

    private fun sectionTitle(text: String) = UiText(text, UiTextStyle.Subtitle)

    fun stamp(instant: Instant, zone: ZoneId): String = StampFormat.withZone(zone).format(instant)

    /** Los botones para reportar una avería o solicitar un proyecto, a mano de todo el personal. */
    fun quickActions(): UiNode = UiRow(
        listOf(
            UiButton(tr("Reportar avería"), UiNavigate("new-order", mapOf("kind" to OrderKind.Corrective.id))),
            UiButton(tr("Solicitar proyecto"), UiNavigate("new-order", mapOf("kind" to OrderKind.Project.id)), style = "secondary"),
        ),
    )

    private fun orderItem(context: Context, order: Order): UiListItem = UiListItem(
        title = tr("{0} · {1}", order.displayNumber, order.title),
        subtitle = listOfNotNull(
            order.propertyName?.takeIf { it.isNotBlank() },
            order.assetName?.takeIf { it.isNotBlank() },
            order.assigneeId?.let { context.names.label(it) },
        ).joinToString(" · "),
        trailing = order.dueDate?.toString().orEmpty(),
        badges = orderBadges(order, context.today),
        action = openOrder(order),
    )

    private fun cutNotice(count: Int): UiNode? =
        if (count > ListLimit) hint(tr("Se muestran las primeras {0} órdenes: usa los filtros para acotar.", ListLimit), UiTone.Warning) else null

    // ---- Próximos ----

    /** Órdenes abiertas agrupadas por fecha límite; los grupos vacíos no aparecen. */
    fun upcomingBuckets(orders: List<Order>, today: LocalDate): List<Pair<String, List<Order>>> {
        val open = orders.filter { it.status.isActive }
        val week = today.plusDays(7)
        val month = today.plusDays(30)
        fun dueBetween(order: Order, from: LocalDate, to: LocalDate): Boolean {
            val due = order.dueDate ?: return false
            return !due.isBefore(from) && !due.isAfter(to)
        }
        return listOf(
            tr("Vencidas") to open.filter { it.isOverdue(today) },
            tr("Próximos 7 días") to open.filter { dueBetween(it, today, week) },
            tr("Próximos 30 días") to open.filter { dueBetween(it, week.plusDays(1), month) },
            tr("Más adelante") to open.filter { it.dueDate?.isAfter(month) == true },
            tr("Sin fecha") to open.filter { it.dueDate == null },
        ).filter { it.second.isNotEmpty() }
    }

    private fun planItem(plan: Plan): UiListItem = UiListItem(
        title = plan.title,
        subtitle = listOfNotNull(plan.propertyName, plan.assetName, if (plan.frequency != PlanFrequency.EveryNDays) tr(plan.frequency.label) else tr("Cada {0} días", plan.intervalDays)).joinToString(" · "),
        trailing = plan.nextDue?.toString().orEmpty(),
        badges = listOfNotNull(UiBadge(tr("Inactivo"), UiTone.Neutral).takeIf { !plan.active }),
        action = UiNavigate("plan", mapOf("id" to plan.id.toString())),
    )

    fun upcoming(
        context: Context,
        metrics: OrderMetrics,
        orders: List<Order>,
        plansSoon: List<Plan>,
        property: Property?,
        notice: UiNotice? = null,
    ): UiScreen {
        val shown = orders.take(ListLimit)
        val filter = UiCard(
            listOf(
                UiForm(
                    "filters",
                    listOf(UiField("property", tr("Propiedad"), type = "dropdown", options = listOf(MantenimientoActions.AllOption) + context.properties.map { it.name })),
                    mapOf("property" to (property?.name ?: MantenimientoActions.AllOption)),
                    submit = UiButton(tr("Filtrar"), UiNavigate("upcoming", formId = "filters"), style = "secondary"),
                    minFieldWidth = 220,
                ),
            ),
        )
        val body = buildList<UiNode> {
            add(quickActions())
            if (context.properties.isNotEmpty()) add(filter)
            add(
                UiGrid(
                    listOf(
                        UiCard(listOf(UiMetric(tr("Abiertas"), metrics.open.toString()))),
                        UiCard(
                            listOf(
                                UiMetric(
                                    tr("Vencidas"), metrics.overdue.toString(),
                                    caption = if (metrics.overdue > 0) tr("Atiéndelas primero") else tr("Al día"),
                                    captionTone = if (metrics.overdue > 0) UiTone.Danger else UiTone.Success,
                                ),
                            ),
                        ),
                        UiCard(listOf(UiMetric(tr("Por aprobar"), metrics.pendingApproval.toString(), captionTone = if (metrics.pendingApproval > 0) UiTone.Info else UiTone.Neutral))),
                        UiCard(listOf(UiMetric(tr("Completadas este mes"), metrics.completedThisMonth.toString()))),
                    ),
                    minItemWidth = 180,
                ),
            )
            cutNotice(orders.size)?.let { add(it) }
            val buckets = upcomingBuckets(shown, context.today)
            if (buckets.isEmpty()) {
                add(UiCard(listOf(UiText(tr("No hay órdenes abiertas.")))))
            } else {
                buckets.forEach { (title, group) -> add(UiCard(listOf(UiList(group.map { orderItem(context, it) })), title = tr("{0} ({1})", title, group.size))) }
            }
            if (plansSoon.isNotEmpty()) {
                add(UiCard(listOf(UiList(plansSoon.map { planItem(it) })), title = tr("Planes que generan pronto ({0})", plansSoon.size)))
            }
        }
        return screen(context, tr("Próximos"), "upcoming", notice, body)
    }

    // ---- Mis órdenes ----

    fun mine(context: Context, orders: List<Order>, notice: UiNotice? = null): UiScreen {
        val shown = orders.distinctBy { it.id }.take(ListLimit)
        val me = context.scope.userId
        val assigned = shown.filter { it.assigneeId == me && me != null }
        val reported = shown.filter { it.requestedBy == me && me != null && it.assigneeId != me }
        val body = buildList<UiNode> {
            add(quickActions())
            cutNotice(orders.size)?.let { add(it) }
            if (assigned.isEmpty() && reported.isEmpty()) {
                add(UiCard(listOf(UiText(if (me == null) tr("Esta cuenta no recibe órdenes: abre Órdenes para verlas.") else tr("No tienes órdenes abiertas.")))))
            }
            if (assigned.isNotEmpty()) add(UiCard(listOf(UiList(assigned.map { orderItem(context, it) })), title = tr("Asignadas a mí ({0})", assigned.size)))
            if (reported.isNotEmpty()) add(UiCard(listOf(UiList(reported.map { orderItem(context, it) })), title = tr("Reportadas por mí ({0})", reported.size)))
        }
        return screen(context, tr("Mis órdenes"), "mine", notice, body)
    }

    // ---- Órdenes ----

    fun viewOf(params: Map<String, String>): String = Views.firstOrNull { it.first == params["view"] }?.first ?: ViewList

    private fun filterForm(context: Context, view: String, params: Map<String, String>): UiNode {
        val all = MantenimientoActions.AllOption
        val fields = buildList {
            add(UiField("q", tr("Buscar"), placeholder = tr("Folio, título o descripción")))
            if (view != ViewBoard) add(UiField("status", tr("Estado"), type = "dropdown", options = listOf(all, MantenimientoActions.ClosedOption) + OrderStatus.entries.map { it.label }))
            add(UiField("kind", tr("Tipo"), type = "dropdown", options = listOf(all) + OrderKind.entries.map { it.label }))
            add(UiField("property", tr("Propiedad"), type = "dropdown", options = listOf(all) + context.properties.map { it.name }))
            add(UiField("assignee", tr("Responsable"), type = "dropdown", options = listOf(all) + context.users.map { it.label }))
        }
        val values = fields.associate { field ->
            field.key to (params[field.key]?.takeIf { it.isNotBlank() } ?: if (field.type == "dropdown") all else "")
        }
        return UiCard(
            listOf(
                UiForm(
                    "filters", fields, values,
                    submit = UiButton(tr("Filtrar"), UiNavigate("orders", mapOf("view" to view), formId = "filters"), style = "secondary"),
                    minFieldWidth = 200,
                ),
            ),
        )
    }

    fun orders(context: Context, data: OrdersData, params: Map<String, String>, notice: UiNotice? = null): UiScreen {
        val view = viewOf(params)
        val selector = UiRow(
            Views.map { (id, label) -> UiButton(tr(label), UiNavigate("orders", mapOf("view" to id), formId = "filters"), style = if (id == view) "primary" else "secondary") } +
                listOf(
                    UiButton(tr("Reportar avería"), UiNavigate("new-order", mapOf("kind" to OrderKind.Corrective.id)), style = "secondary"),
                    UiButton(tr("Solicitar proyecto"), UiNavigate("new-order", mapOf("kind" to OrderKind.Project.id)), style = "secondary"),
                ),
        )
        val body = buildList<UiNode> {
            add(selector)
            add(filterForm(context, view, params))
            if (view == ViewBoard) {
                addAll(boardView(context, data, params))
            } else {
                val mode = MantenimientoActions.listingMode(params)
                when (mode) {
                    MantenimientoActions.ListingMode.Active ->
                        add(hint(tr("Se muestran solo las órdenes activas. Las cerradas son una vista aparte: elige «{0}» en Estado.", tr(MantenimientoActions.ClosedOption))))
                    MantenimientoActions.ListingMode.ClosedWindow ->
                        add(hint(tr("Órdenes cerradas (completadas, rechazadas o canceladas) en los últimos {0} días, la más reciente primero.", MantenimientoActions.ClosedWindowDays)))
                    MantenimientoActions.ListingMode.Search ->
                        add(hint(tr("La búsqueda incluye también las órdenes cerradas.")))
                    MantenimientoActions.ListingMode.Explicit -> Unit
                }
                cutNotice(data.orders.size)?.let { add(it) }
                val title = when (mode) {
                    MantenimientoActions.ListingMode.Active -> tr("Órdenes activas")
                    MantenimientoActions.ListingMode.ClosedWindow -> tr("Órdenes cerradas")
                    MantenimientoActions.ListingMode.Search -> tr("Resultados de la búsqueda")
                    MantenimientoActions.ListingMode.Explicit -> tr("Órdenes")
                }
                add(UiCard(listOf(UiList(data.orders.take(ListLimit).map { orderItem(context, it) }, emptyText = tr("No hay órdenes con estos filtros."))), title = title))
            }
        }
        return screen(context, tr("Órdenes"), "orders", notice, body)
    }

    /** Estados que muestra el tablero: los de decisión y el rechazo solo aparecen si tienen tarjetas. */
    fun boardStatuses(columns: Map<OrderStatus, List<Order>>): List<OrderStatus> = OrderStatus.entries.filter { status ->
        status !in setOf(OrderStatus.Requested, OrderStatus.Quoting, OrderStatus.Rejected) || columns[status].orEmpty().isNotEmpty()
    }

    private fun boardView(context: Context, data: OrdersData, params: Map<String, String>): List<UiNode> {
        val keep = MantenimientoActions.filterParams(params) - "status"
        val move = UiRequest("$Route/orders", params = keep + mapOf("view" to ViewBoard, "op" to "move"))
        val columns = boardStatuses(data.columns).map { status ->
            val orders = data.columns[status].orEmpty()
            UiBoardColumn(
                id = status.id,
                title = tr(status.label),
                tone = statusTone(status),
                caption = when {
                    orders.size > KanbanLimit -> tr("Mostrando {0}: usa los filtros para ver el resto", KanbanLimit)
                    !status.isActive -> tr("Cerradas en los últimos {0} días", MantenimientoActions.ClosedWindowDays)
                    else -> ""
                },
                cards = orders.take(KanbanLimit).map { order ->
                    UiBoardCard(
                        id = order.id.toString(),
                        title = tr("{0} · {1}", order.displayNumber, order.title),
                        subtitle = listOfNotNull(order.propertyName, order.assetName).joinToString(" · "),
                        details = listOfNotNull(
                            order.assigneeId?.let { tr("Responsable: {0}", context.names.label(it)) },
                            order.dueDate?.let { tr("Vence: {0}", it) },
                            order.checklistTotal.takeIf { it > 0 }?.let { tr("Lista: {0}/{1}", order.checklistDone, it) },
                        ),
                        badges = orderBadges(order, context.today, withStatus = false),
                        action = openOrder(order),
                    )
                },
            )
        }
        return listOf(UiBoard(columns, move = move, emptyText = tr("Sin órdenes.")))
    }

    // ---- Orden ----

    private fun fileRefs(attachments: List<Attachment>): List<UiFileRef> = attachments.map { UiFileRef(it.id.toString(), it.name, it.path, it.size) }

    private fun filesField(readOnly: Boolean) = UiField(
        "files", tr("Adjuntos"), type = "files", readOnly = readOnly,
        files = UiFiles(upload = "$Route/files", download = "$Route/files"),
    )

    /** Los botones de estado que [scope] puede usar en [order]; rechazar va aparte (pide un motivo). El repositorio vuelve a aplicar las reglas. */
    fun statusActions(scope: MantenimientoScope, order: Order): List<StatusAction> {
        if (order.archivedAt != null) return emptyList()
        return OrderTransitions.targets(order.kind, order.status, TransitionRole.of(scope, order)).mapNotNull { to ->
            when {
                OrderTransitions.requiresReason(to) -> null
                OrderTransitions.isApproval(order.kind, order.status, to) -> StatusAction(tr("Aprobar"), to)
                to == OrderStatus.Quoting -> StatusAction(tr("Pasar a cotización"), to, "secondary")
                to == OrderStatus.Requested -> StatusAction(tr("Volver a pendiente"), to, "secondary")
                to == OrderStatus.InProgress && order.status == OrderStatus.Done -> StatusAction(tr("Reabrir"), to, "secondary")
                to == OrderStatus.InProgress && order.status == OrderStatus.OnHold -> StatusAction(tr("Reanudar"), to)
                to == OrderStatus.InProgress -> StatusAction(tr("Empezar"), to)
                to == OrderStatus.OnHold -> StatusAction(tr("Poner en espera"), to, "secondary")
                to == OrderStatus.Open -> StatusAction(tr("Volver a abierta"), to, "secondary")
                to == OrderStatus.Done -> StatusAction(tr("Completar"), to)
                to == OrderStatus.Cancelled -> StatusAction(tr("Cancelar orden"), to, "danger", "¿Cancelar esta orden?")
                else -> null
            }
        }
    }

    /** Se puede rechazar (decidir un proyecto) y hay que pedir el motivo. */
    fun canReject(scope: MantenimientoScope, order: Order): Boolean =
        order.archivedAt == null && OrderTransitions.allowed(order.kind, order.status, OrderStatus.Rejected, TransitionRole.of(scope, order))

    private fun orderRequest(op: String, order: Order, extra: Map<String, String> = emptyMap(), confirm: String? = null, formId: String? = null) =
        UiRequest("$Route/order", params = mapOf("op" to op, "id" to order.id.toString()) + extra, confirm = confirm, formId = formId)

    /** Los botones de compra: solo si Compras está disponible para este usuario. */
    fun purchaseButtons(scope: MantenimientoScope, detail: OrderDetail): List<UiButton> {
        if (!detail.purchaseAvailable) return emptyList()
        val order = detail.order
        MantenimientoPurchases.openRequestAction(order)?.let { return listOf(UiButton(tr("Ver solicitud de compra"), it, style = "secondary")) }
        if (scope.canWork(order) && order.status.isActive) {
            return listOf(
                UiButton(
                    tr("Solicitar compra"),
                    orderRequest("purchase", order, confirm = "¿Crear en Compras una solicitud borrador con los datos de esta orden?"),
                    style = "secondary",
                ),
            )
        }
        return emptyList()
    }

    private fun propertyOptions(context: Context, order: Order?): List<String> =
        listOf(MantenimientoActions.NoProperty) + (context.properties.map { it.name } + listOfNotNull(order?.propertyName)).distinctBy { it.lowercase() }

    private fun assigneeOptions(context: Context, order: Order?): List<String> {
        val retired = order?.assigneeId?.let { id -> context.users.none { it.id == id } } == true
        return listOf(MantenimientoActions.NoAssignee) + context.users.map { it.label } + listOfNotNull(MantenimientoActions.RetiredUser.takeIf { retired })
    }

    /** Campos del formulario de la orden: [canEdit] (gestionar) abre los datos; [canWork] abre costo real y resolución. */
    fun orderFields(context: Context, detail: OrderDetail, canEdit: Boolean, canWork: Boolean, withFiles: Boolean): List<UiField> = buildList {
        val order = detail.order
        add(UiField("title", tr("Título"), required = true, readOnly = !canEdit, section = tr("Datos")))
        add(UiField("description", tr("Descripción"), type = "textarea", readOnly = !canEdit, section = tr("Datos")))
        add(UiField("kind", tr("Tipo"), readOnly = true, section = tr("Datos")))
        add(UiField("priority", tr("Prioridad"), type = "dropdown", options = OrderPriority.entries.map { it.label }, readOnly = !canEdit, section = tr("Datos")))
        add(UiField("property", tr("Propiedad"), type = "dropdown", options = propertyOptions(context, order), readOnly = !canEdit, section = tr("Datos")))
        add(UiField("asset", tr("Activo"), type = "dropdown", options = listOf(MantenimientoActions.NoAsset) + detail.assets.labels, readOnly = !canEdit, help = detail.assets.truncatedHelp, section = tr("Datos")))
        add(UiField("due", tr("Fecha límite"), type = "date", readOnly = !canEdit, section = tr("Datos")))
        add(UiField("assignee", tr("Responsable"), type = "dropdown", options = assigneeOptions(context, order), readOnly = !canEdit, section = tr("Datos")))
        add(UiField("department", tr("Departamento"), readOnly = !canEdit, section = tr("Datos")))
        if (order.kind == OrderKind.Project) {
            add(UiField("quote", tr("Cotización"), type = "number", readOnly = !canEdit, section = tr("Cotización")))
            add(UiField("quote_currency", tr("Moneda de la cotización"), readOnly = !canEdit, help = tr("Tres letras, por ejemplo USD."), section = tr("Cotización")))
            add(UiField("quoted_by", tr("Cotizado por"), readOnly = !canEdit, section = tr("Cotización")))
        }
        if (showsWork(order)) {
            add(UiField("cost", tr("Costo real"), type = "number", readOnly = !canWork, section = tr("Cierre")))
            add(UiField("cost_currency", tr("Moneda del costo"), readOnly = !canWork, help = tr("Tres letras, por ejemplo USD."), section = tr("Cierre")))
            add(UiField("resolution", tr("Resolución"), type = "textarea", readOnly = !canWork, help = tr("Al completar la orden se guardan el costo y la resolución escritos aquí."), section = tr("Cierre")))
        }
        if (withFiles) add(filesField(readOnly = !canEdit))
    }

    /** Costo real y resolución solo tienen sentido cuando la orden está en trabajo o ya se completó. */
    fun showsWork(order: Order): Boolean = order.status.isWorking || order.status == OrderStatus.Done

    private fun orderValues(context: Context, detail: OrderDetail, withFiles: Boolean): Map<String, String> = buildMap {
        val order = detail.order
        put("title", order.title)
        put("description", order.description)
        put("kind", tr(order.kind.label))
        put("priority", order.priority.label)
        put("property", order.propertyName ?: MantenimientoActions.NoProperty)
        put("asset", detail.assets.label(order.assetId) ?: MantenimientoActions.NoAsset)
        put("due", order.dueDate?.toString().orEmpty())
        put("assignee", order.assigneeId?.let { context.names.label(it) } ?: MantenimientoActions.NoAssignee)
        put("department", order.department)
        put("quote", order.quoteAmount?.toPlainString().orEmpty())
        put("quote_currency", order.quoteCurrency)
        put("quoted_by", order.quotedBy)
        put("cost", order.costAmount?.toPlainString().orEmpty())
        put("cost_currency", order.costCurrency)
        put("resolution", order.resolution)
        if (withFiles) put("files", UiFieldValues.files(fileRefs(detail.attachments)))
    }

    fun order(context: Context, detail: OrderDetail, notice: UiNotice? = null, submitted: Map<String, String>? = null): UiScreen {
        val order = detail.order
        val scope = context.scope
        val canEdit = scope.canEdit(order)
        val canWork = MantenimientoActions.canWorkNow(scope, order)
        val hasForm = canEdit || canWork
        val canAttach = MantenimientoActions.canAttach(scope, order)
        val statusButtons = statusActions(scope, order).map { action ->
            UiButton(
                action.label,
                // Completar lleva el costo y la resolución del formulario.
                orderRequest("status", order, mapOf("status" to action.status.id), confirm = action.confirm, formId = "order".takeIf { action.status == OrderStatus.Done && hasForm }),
                style = action.style,
            )
        }
        val actions = UiRow(
            statusButtons + listOfNotNull(UiButton(tr("Tomar orden"), orderRequest("take", order)).takeIf { scope.canTake(order) }) +
                purchaseButtons(scope, detail) +
                listOf(UiButton(tr("Volver"), UiNavigate(if (scope.seesAll) "orders" else "mine"), style = "secondary")),
        )
        val header = UiRow(
            listOfNotNull(
                UiStatus(tr(order.status.label), statusTone(order.status)),
                UiStatus(tr(order.priority.label), priorityTone(order.priority)),
                UiStatus(tr("Vencida"), UiTone.Danger).takeIf { order.isOverdue(context.today) },
                hint(
                    listOfNotNull(
                        order.displayNumber,
                        tr(order.kind.label),
                        order.requestedByName.takeIf { it.isNotBlank() }?.let { tr("Solicitada por {0}", it) },
                        order.dueDate?.let { tr("Vence {0}", it) },
                        order.purchaseFolio.takeIf { it.isNotBlank() }?.let { tr("Compra {0}", it) },
                    ).joinToString(" · "),
                ),
            ),
            spacing = 8,
        )
        // Quien no gestiona adjunta en un formulario aparte: el principal no lleva el campo de archivos.
        val filesInMain = canEdit
        val form = UiForm(
            id = "order",
            fields = orderFields(context, detail, canEdit, canWork, withFiles = filesInMain),
            values = orderValues(context, detail, filesInMain) + submitted.orEmpty(),
            submit = UiButton(tr("Guardar"), orderRequest("save", order, formId = "order")).takeIf { hasForm },
        )
        val body = buildList<UiNode> {
            add(header)
            if (order.status == OrderStatus.Rejected && order.rejectedReason.isNotBlank()) add(hint(tr("Motivo del rechazo: {0}", order.rejectedReason), UiTone.Warning))
            add(actions)
            if (canReject(scope, order)) add(decisionCard(order))
            add(UiCard(listOf(form), title = tr("Detalles")))
            if (!filesInMain) add(attachmentsCard(order, detail.attachments, canAttach))
            add(checklistCard(order, detail.checklist, MantenimientoActions.canWorkChecklist(scope, order), canEdit))
            add(notesCard(context, order, detail.notes, MantenimientoActions.canNote(scope, order)))
            detail.plan?.let { plan ->
                add(
                    UiCard(
                        listOf(
                            hint(tr("Esta orden la generó el plan \"{0}\" ({1}).", plan.title, tr(plan.frequency.label).lowercase())),
                            UiButton(tr("Ver plan"), UiNavigate("plan", mapOf("id" to plan.id.toString())), style = "secondary"),
                        ),
                        title = tr("Plan de origen"),
                    ),
                )
            }
        }
        return screen(context, tr("{0} · {1}", order.displayNumber, order.title), if (scope.seesAll) "orders" else "mine", notice, body)
    }

    private fun decisionCard(order: Order): UiNode = UiCard(
        listOf(
            UiForm(
                id = "decision",
                fields = listOf(UiField("reason", tr("Motivo del rechazo"), type = "textarea", help = tr("Obligatorio solo al rechazar."))),
            ),
            UiRow(
                listOf(
                    UiButton(
                        tr("Rechazar proyecto"),
                        orderRequest("status", order, mapOf("status" to OrderStatus.Rejected.id), confirm = "¿Rechazar esta solicitud?", formId = "decision"),
                        style = "danger",
                    ),
                ),
            ),
        ),
        title = tr("Decisión"),
    )

    private fun attachmentsCard(order: Order, attachments: List<Attachment>, canAttach: Boolean): UiNode {
        if (!canAttach && attachments.isEmpty()) return UiCard(listOf(hint(tr("Sin adjuntos."))), title = tr("Adjuntos"))
        return UiCard(
            listOf(
                UiForm(
                    id = "attachments",
                    fields = listOf(filesField(readOnly = !canAttach)),
                    values = mapOf("files" to UiFieldValues.files(fileRefs(attachments))),
                    submit = UiButton(tr("Guardar adjuntos"), orderRequest("files", order, formId = "attachments"), style = "secondary").takeIf { canAttach },
                ),
            ),
            title = tr("Adjuntos"),
        )
    }

    private fun checklistCard(order: Order, items: List<OrderCheck>, canWork: Boolean, canEdit: Boolean): UiNode {
        val rows = items.map { item ->
            UiRow(
                listOfNotNull(
                    UiButton(
                        (if (item.done) "[x] " else "[ ] ") + item.text,
                        orderRequest("check-toggle", order, mapOf("item" to item.id.toString(), "done" to (!item.done).toString())),
                        style = "secondary",
                        enabled = canWork,
                    ),
                    UiButton(tr("Quitar"), orderRequest("check-remove", order, mapOf("item" to item.id.toString())), style = "danger").takeIf { canEdit },
                ),
                spacing = 8,
            )
        }
        val children = buildList<UiNode> {
            if (rows.isEmpty()) add(hint(tr("Sin elementos.")))
            addAll(rows)
            if (canWork) {
                add(
                    UiForm(
                        id = "check",
                        fields = listOf(UiField("text", tr("Nuevo elemento"), required = true)),
                        submit = UiButton(tr("Añadir"), orderRequest("check-add", order, formId = "check"), style = "secondary"),
                    ),
                )
            }
        }
        val title = if (order.checklistTotal > 0) tr("Lista de comprobación ({0}/{1})", order.checklistDone, order.checklistTotal) else tr("Lista de comprobación")
        return UiCard(children, title = title)
    }

    private fun notesCard(context: Context, order: Order, notes: List<OrderNote>, canNote: Boolean): UiNode {
        val children = buildList<UiNode> {
            add(
                UiTimeline(
                    notes.map { note ->
                        val caption = listOf(note.authorName, stamp(note.createdAt, context.zone)).filter { it.isNotBlank() }.joinToString(" - ")
                        if (note.kind == "note") UiTimelineEntry(note.authorName.ifBlank { tr("Nota") }, note.body, stamp(note.createdAt, context.zone))
                        else UiTimelineEntry(note.body, caption = caption)
                    },
                    emptyText = tr("Aún no hay notas."),
                ),
            )
            if (canNote) {
                add(
                    UiForm(
                        id = "note",
                        fields = listOf(UiField("body", tr("Nota"), type = "textarea", required = true)),
                        submit = UiButton(tr("Anotar"), orderRequest("note", order, formId = "note"), style = "secondary"),
                    ),
                )
            }
        }
        return UiCard(children, title = tr("Notas e historial ({0})", notes.size))
    }

    // ---- Nueva orden (avería o proyecto) ----

    /** Los tipos que se pueden crear a mano: las preventivas las genera un plan. */
    fun creatableKind(text: String?): OrderKind = OrderKind.fromId(text)?.takeIf { it != OrderKind.Preventive } ?: OrderKind.Corrective

    fun newOrderFields(context: Context, kind: OrderKind, assets: AssetOptions): List<UiField> = buildList {
        add(UiField("title", tr("Título"), required = true))
        add(UiField("description", tr("Descripción"), type = "textarea"))
        if (kind == OrderKind.Project) add(UiField("department", tr("Departamento")))
        add(UiField("property", tr("Propiedad"), type = "dropdown", options = propertyOptions(context, null)))
        add(UiField("asset", tr("Activo"), type = "dropdown", options = listOf(MantenimientoActions.NoAsset) + assets.labels, help = assets.truncatedHelp))
        add(UiField("priority", tr("Prioridad"), type = "dropdown", options = OrderPriority.entries.map { it.label }))
        if (kind == OrderKind.Corrective && context.scope.canManage) {
            add(UiField("assignee", tr("Responsable"), type = "dropdown", options = assigneeOptions(context, null)))
            add(UiField("due", tr("Fecha límite"), type = "date"))
        }
        if (kind == OrderKind.Project) {
            add(UiField("quote", tr("Cotización (opcional)"), type = "number"))
            add(UiField("quote_currency", tr("Moneda de la cotización"), help = tr("Tres letras, por ejemplo USD.")))
        }
        add(filesField(readOnly = false))
    }

    fun newOrder(context: Context, kind: OrderKind, assets: AssetOptions, notice: UiNotice? = null, submitted: Map<String, String>? = null): UiScreen {
        val base = mapOf(
            "priority" to OrderPriority.Medium.label,
            "property" to MantenimientoActions.NoProperty,
            "asset" to MantenimientoActions.NoAsset,
            "assignee" to MantenimientoActions.NoAssignee,
            "quote_currency" to "USD",
        )
        val title = if (kind == OrderKind.Project) tr("Solicitar proyecto") else tr("Reportar avería")
        val form = UiForm(
            id = "order",
            fields = newOrderFields(context, kind, assets),
            values = base + submitted.orEmpty(),
            submit = UiButton(
                if (kind == OrderKind.Project) tr("Enviar solicitud") else tr("Reportar"),
                UiRequest("$Route/new-order", params = mapOf("op" to "create", "kind" to kind.id), formId = "order"),
            ),
        )
        val body = listOf(
            UiRow(
                listOf(
                    UiButton(tr("Reportar avería"), UiNavigate("new-order", mapOf("kind" to OrderKind.Corrective.id)), style = if (kind == OrderKind.Corrective) "primary" else "secondary"),
                    UiButton(tr("Solicitar proyecto"), UiNavigate("new-order", mapOf("kind" to OrderKind.Project.id)), style = if (kind == OrderKind.Project) "primary" else "secondary"),
                ),
            ),
            if (kind == OrderKind.Project) hint(tr("Un proyecto espera la aprobación de quien gestiona mantenimiento.")) else hint(tr("Cuenta qué falla y dónde: avisamos a quien gestiona mantenimiento.")),
            UiCard(listOf(form), title = title),
            UiRow(listOf(UiButton(tr("Cancelar"), UiNavigate("mine"), style = "secondary"))),
        )
        return screen(context, title, "upcoming", notice, body)
    }

    // ---- Activos ----

    fun assets(context: Context, assets: List<Asset>, property: Property?, query: String, notice: UiNotice? = null): UiScreen {
        val filter = UiCard(
            listOf(
                UiForm(
                    "filters",
                    listOf(
                        UiField("q", tr("Buscar"), placeholder = tr("Nombre, categoría o ubicación")),
                        UiField("property", tr("Propiedad"), type = "dropdown", options = listOf(MantenimientoActions.AllOption) + context.properties.map { it.name }),
                    ),
                    mapOf("q" to query, "property" to (property?.name ?: MantenimientoActions.AllOption)),
                    submit = UiButton(tr("Filtrar"), UiNavigate("assets", formId = "filters"), style = "secondary"),
                    minFieldWidth = 220,
                ),
            ),
        )
        val items = assets.take(ListLimit).map { asset ->
            UiListItem(
                title = asset.name,
                subtitle = listOf(asset.category, asset.location).filter { it.isNotBlank() }.joinToString(" · "),
                trailing = asset.propertyName.orEmpty(),
                badges = listOfNotNull(UiBadge(tr("Archivado"), UiTone.Neutral).takeIf { asset.archived }),
                action = UiNavigate("asset", mapOf("id" to asset.id.toString())),
            )
        }
        val body = buildList<UiNode> {
            if (context.scope.canManage) add(UiRow(listOf(UiButton(tr("Nuevo activo"), UiNavigate("asset")))))
            add(filter)
            if (assets.size > ListLimit) add(hint(tr("Se muestran los primeros {0} activos: usa los filtros para acotar.", ListLimit), UiTone.Warning))
            add(UiCard(listOf(UiList(items, emptyText = tr("No hay activos con estos filtros."))), title = tr("Activos")))
        }
        return screen(context, tr("Activos"), "assets", notice, body)
    }

    /** [asset] es null para un activo nuevo. */
    fun asset(
        context: Context,
        asset: Asset?,
        history: List<Order>,
        plans: List<Plan>,
        notice: UiNotice? = null,
        submitted: Map<String, String>? = null,
    ): UiScreen {
        val canManage = context.scope.canManage
        fun request(op: String, confirm: String? = null, formId: String? = null) =
            UiRequest("$Route/asset", params = mapOf("op" to op) + asset?.let { mapOf("id" to it.id.toString()) }.orEmpty(), confirm = confirm, formId = formId)

        val values = mapOf(
            "name" to asset?.name.orEmpty(),
            "property" to (asset?.propertyName ?: MantenimientoActions.NoProperty),
            "category" to asset?.category.orEmpty(),
            "location" to asset?.location.orEmpty(),
            "notes" to asset?.notes.orEmpty(),
        ) + submitted.orEmpty()
        val propertyOptions = listOf(MantenimientoActions.NoProperty) + (context.properties.map { it.name } + listOfNotNull(asset?.propertyName)).distinctBy { it.lowercase() }
        val form = UiForm(
            id = "asset",
            fields = listOf(
                UiField("name", tr("Nombre"), required = true, readOnly = !canManage),
                UiField("property", tr("Propiedad"), type = "dropdown", options = propertyOptions, readOnly = !canManage),
                UiField("category", tr("Categoría"), readOnly = !canManage),
                UiField("location", tr("Ubicación"), readOnly = !canManage),
                UiField("notes", tr("Notas"), type = "textarea", readOnly = !canManage),
            ),
            values = values,
            submit = UiButton(if (asset == null) tr("Crear activo") else tr("Guardar"), request("save", formId = "asset")).takeIf { canManage },
        )
        val body = buildList<UiNode> {
            if (asset?.archived == true) add(hint(tr("Activo archivado."), UiTone.Warning))
            add(UiCard(listOf(form), title = tr("Activo")))
            if (asset != null) {
                if (canManage) {
                    add(
                        UiRow(
                            listOf(
                                if (asset.archived) UiButton(tr("Restaurar activo"), request("restore"), style = "secondary")
                                else UiButton(tr("Archivar activo"), request("archive", confirm = "¿Archivar este activo? Deja de ofrecerse en los formularios."), style = "danger"),
                            ),
                        ),
                    )
                }
                if (history.size > HistoryLimit) add(hint(tr("Se muestran las últimas {0} órdenes del activo.", HistoryLimit), UiTone.Warning))
                add(UiCard(listOf(UiList(history.take(HistoryLimit).map { orderItem(context, it) }, emptyText = tr("Este activo no tiene órdenes."))), title = tr("Historial")))
                add(UiCard(listOf(UiList(plans.map { planItem(it) }, emptyText = tr("Este activo no tiene planes."))), title = tr("Planes")))
            }
            add(UiRow(listOf(UiButton(tr("Volver a activos"), UiNavigate("assets"), style = "secondary"))))
        }
        return screen(context, asset?.name ?: tr("Nuevo activo"), "assets", notice, body)
    }

    // ---- Planes ----

    fun plans(context: Context, plans: List<Plan>, notice: UiNotice? = null): UiScreen {
        val active = plans.filter { it.active }
        val inactive = plans.filterNot { it.active }
        val body = buildList<UiNode> {
            if (context.scope.canManage) add(UiRow(listOf(UiButton(tr("Nuevo plan"), UiNavigate("plan")))))
            add(UiCard(listOf(UiList(active.map { planItem(it) }, emptyText = tr("Aún no hay planes preventivos."))), title = tr("Planes activos ({0})", active.size)))
            if (inactive.isNotEmpty()) add(UiCard(listOf(UiList(inactive.map { planItem(it) })), title = tr("Planes inactivos ({0})", inactive.size), collapsible = true, expanded = false))
        }
        return screen(context, tr("Planes"), "plans", notice, body)
    }

    /** [plan] es null para un plan nuevo. [checklist] son los elementos actuales y [generated] las últimas órdenes que generó. */
    fun plan(
        context: Context,
        plan: Plan?,
        checklist: List<PlanCheck>,
        generated: List<Order>,
        assets: AssetOptions,
        notice: UiNotice? = null,
        submitted: Map<String, String>? = null,
    ): UiScreen {
        val canManage = context.scope.canManage
        fun request(op: String, extra: Map<String, String> = emptyMap(), confirm: String? = null, formId: String? = null) =
            UiRequest("$Route/plan", params = mapOf("op" to op) + plan?.let { mapOf("id" to it.id.toString()) }.orEmpty() + extra, confirm = confirm, formId = formId)

        val propertyOptions = listOf(MantenimientoActions.NoProperty) + (context.properties.map { it.name } + listOfNotNull(plan?.propertyName)).distinctBy { it.lowercase() }
        val retired = plan?.defaultAssignee?.let { id -> context.users.none { it.id == id } } == true
        val assigneeOptions = listOf(MantenimientoActions.NoAssignee) + context.users.map { it.label } + listOfNotNull(MantenimientoActions.RetiredUser.takeIf { retired })
        val values = mapOf(
            "title" to plan?.title.orEmpty(),
            "description" to plan?.description.orEmpty(),
            "property" to (plan?.propertyName ?: MantenimientoActions.NoProperty),
            "asset" to (assets.label(plan?.assetId) ?: MantenimientoActions.NoAsset),
            "frequency" to (plan?.frequency ?: PlanFrequency.Monthly).label,
            "interval" to plan?.intervalDays?.toString().orEmpty(),
            "schedule_mode" to (plan?.scheduleMode ?: ScheduleMode.Fixed).label,
            "next_due" to plan?.nextDue?.toString().orEmpty(),
            "lead_days" to (plan?.leadDays ?: 7).toString(),
            "assignee" to (plan?.defaultAssignee?.let { context.names.label(it) } ?: MantenimientoActions.NoAssignee),
            "priority" to (plan?.priority ?: OrderPriority.Medium).label,
        ) + submitted.orEmpty()
        val form = UiForm(
            id = "plan",
            fields = listOf(
                UiField("title", tr("Título"), required = true, readOnly = !canManage),
                UiField("description", tr("Descripción"), type = "textarea", readOnly = !canManage),
                UiField("property", tr("Propiedad"), type = "dropdown", options = propertyOptions, readOnly = !canManage),
                UiField("asset", tr("Activo"), type = "dropdown", options = listOf(MantenimientoActions.NoAsset) + assets.labels, readOnly = !canManage, help = assets.truncatedHelp),
                UiField("frequency", tr("Frecuencia"), type = "dropdown", options = PlanFrequency.entries.map { it.label }, readOnly = !canManage),
                UiField(
                    "interval", tr("Cada cuántos días"), type = "number", readOnly = !canManage,
                    visibleWhen = UiFieldCondition("frequency", listOf(PlanFrequency.EveryNDays.label)),
                ),
                UiField(
                    "schedule_mode", tr("Calendario"), type = "dropdown", options = ScheduleMode.entries.map { it.label }, readOnly = !canManage,
                    help = tr("Calendario fijo: la fecha avanza sola. Desde el último cierre: la próxima fecha sale de completar la orden."),
                ),
                UiField("next_due", tr("Próxima fecha"), type = "date", readOnly = !canManage),
                UiField("lead_days", tr("Días de anticipación"), type = "number", readOnly = !canManage, help = tr("La orden se crea tantos días antes de la fecha.")),
                UiField("assignee", tr("Responsable por defecto"), type = "dropdown", options = assigneeOptions, readOnly = !canManage),
                UiField("priority", tr("Prioridad"), type = "dropdown", options = OrderPriority.entries.map { it.label }, readOnly = !canManage),
            ),
            values = values,
            submit = UiButton(if (plan == null) tr("Crear plan") else tr("Guardar"), request("save", formId = "plan")).takeIf { canManage },
        )
        val body = buildList<UiNode> {
            if (plan != null && !plan.active) add(hint(tr("Plan inactivo: no genera órdenes."), UiTone.Warning))
            if (plan != null && plan.nextDue == null && plan.scheduleMode == ScheduleMode.Floating) {
                add(hint(tr("Espera a que se complete su orden abierta para calcular la próxima fecha.")))
            }
            add(UiCard(listOf(form), title = tr("Plan")))
            if (plan != null && canManage) {
                add(
                    UiRow(
                        listOf(
                            if (plan.active) UiButton(tr("Desactivar plan"), request("active", mapOf("active" to "false"), confirm = "¿Desactivar este plan? Deja de generar órdenes."), style = "danger")
                            else UiButton(tr("Activar plan"), request("active", mapOf("active" to "true")), style = "secondary"),
                        ),
                    ),
                )
            }
            if (plan != null) {
                add(planChecklistCard(checklist, canManage, ::request))
                if (generated.size > HistoryLimit) add(hint(tr("Se muestran las últimas {0} órdenes del plan.", HistoryLimit), UiTone.Warning))
                add(UiCard(listOf(UiList(generated.take(HistoryLimit).map { orderItem(context, it) }, emptyText = tr("Este plan aún no generó órdenes."))), title = tr("Órdenes generadas")))
            } else {
                add(hint(tr("Guarda el plan para añadir su lista de comprobación.")))
            }
            add(UiRow(listOf(UiButton(tr("Volver a planes"), UiNavigate("plans"), style = "secondary"))))
        }
        return screen(context, plan?.title ?: tr("Nuevo plan"), "plans", notice, body)
    }

    private fun planChecklistCard(
        items: List<PlanCheck>,
        canManage: Boolean,
        request: (String, Map<String, String>, String?, String?) -> UiRequest,
    ): UiNode {
        val children = buildList<UiNode> {
            if (items.isEmpty()) add(hint(tr("Sin elementos: la orden se crea sin lista de comprobación.")))
            items.forEachIndexed { index, item ->
                if (index > 0) add(UiDivider())
                val params = mapOf("item" to item.id.toString())
                add(
                    UiRow(
                        listOfNotNull(
                            UiText(item.text),
                            UiButton(tr("Subir"), request("plan-check-up", params, null, null), style = "secondary").takeIf { canManage && index > 0 },
                            UiButton(tr("Bajar"), request("plan-check-down", params, null, null), style = "secondary").takeIf { canManage && index < items.lastIndex },
                            UiButton(tr("Quitar"), request("plan-check-remove", params, null, null), style = "danger").takeIf { canManage },
                        ),
                        spacing = 8,
                    ),
                )
            }
            if (canManage) {
                add(UiDivider())
                add(sectionTitle(tr("Nuevo elemento")))
                add(
                    UiForm(
                        id = "plan-check",
                        fields = listOf(UiField("text", tr("Texto"), required = true)),
                        submit = UiButton(tr("Añadir"), request("plan-check-add", emptyMap(), null, "plan-check"), style = "secondary"),
                    ),
                )
            }
        }
        return UiCard(children, title = tr("Lista de comprobación del plan"))
    }
}
