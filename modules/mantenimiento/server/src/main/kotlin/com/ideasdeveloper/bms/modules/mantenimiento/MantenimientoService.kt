package com.ideasdeveloper.bms.modules.mantenimiento

import com.ideasdeveloper.bms.server.activity.ActivityActions
import com.ideasdeveloper.bms.server.activity.ActivityChange
import com.ideasdeveloper.bms.server.activity.ActivityChanges
import com.ideasdeveloper.bms.server.i18n.msg
import com.ideasdeveloper.bms.server.i18n.tr
import com.ideasdeveloper.bms.server.modules.ModuleAccessService
import com.ideasdeveloper.bms.server.modules.ModulePermissions
import com.ideasdeveloper.bms.ui.UiFieldValues
import java.sql.Connection
import java.util.UUID

/**
 * Operaciones de las pantallas: comprueban el permiso y la visibilidad con el scope del usuario, escriben dentro de una transacción
 * (con su nota de historial) y, después de confirmar, mandan los avisos sin que un fallo de aviso rompa la operación. Una orden
 * que el usuario no ve se informa como inexistente. Todo es síncrono: quien llama lo ejecuta dentro de withContext(Dispatchers.IO).
 */
object MantenimientoService {
    private val NotFound = msg("Orden no encontrada.")
    private val NotManager = msg("Solo quien gestiona mantenimiento puede hacer esto.")

    fun scope(permissions: ModulePermissions): MantenimientoScope = MantenimientoScope.from(permissions)

    fun actor(tenant: Connection, access: ModuleAccessService, permissions: ModulePermissions, scope: MantenimientoScope): MantenimientoActor =
        MantenimientoActor(scope.userId, access.userIdentity(tenant, permissions)?.name ?: "Usuario", permissions.activityActor)

    fun visibleOrder(tenant: Connection, scope: MantenimientoScope, id: UUID): Order =
        MantenimientoRepository.getOrderVisible(tenant, scope, id) ?: throw MantenimientoValidationException(tr(NotFound))

    private fun requireManage(scope: MantenimientoScope) {
        if (!scope.canManage) throw MantenimientoValidationException(tr(NotManager))
    }

    private fun missing(what: String): Nothing = throw MantenimientoValidationException(what)

    const val AssetOptionsLimit = 500

    /** Los activos como opciones: todos los activos si [full] (quien edita) y siempre el [current], aunque esté archivado. */
    fun assetOptions(tenant: Connection, full: Boolean, current: UUID?): AssetOptions {
        val list = if (full) MantenimientoRepository.listAssets(tenant, limit = AssetOptionsLimit) else emptyList()
        val extra = current?.takeIf { id -> list.none { it.id == id } }?.let { MantenimientoRepository.getAsset(tenant, it) }
        // Con el tope alcanzado el desplegable queda recortado: la pantalla lo avisa en lugar de callarlo.
        return AssetOptions(list + listOfNotNull(extra), truncated = list.size >= AssetOptionsLimit)
    }

    private fun lookups(tenant: Connection, current: UUID?): Lookups =
        Lookups(MantenimientoRepository.assignableUsers(tenant), MantenimientoRepository.listProperties(tenant), assetOptions(tenant, full = true, current = current))

    private fun fileIds(values: Map<String, String>): List<UUID> =
        UiFieldValues.files(values["files"].orEmpty()).mapNotNull { MantenimientoActions.uuid(it.id) }

    // ---- órdenes ----

    /** Crea una avería o un proyecto. Responsable, fecha límite y "cotizado por" solo cuentan si quien crea gestiona. */
    fun createOrder(tenant: Connection, access: ModuleAccessService, permissions: ModulePermissions, kind: OrderKind, values: Map<String, String>): Order {
        if (kind == OrderKind.Preventive) missing(tr("Las órdenes preventivas las genera un plan."))
        val scope = scope(permissions)
        val actor = actor(tenant, access, permissions, scope)
        val input = MantenimientoActions.orderInput(kind, values, lookups(tenant, null), scope.canManage)
        val created = MantenimientoRepository.transaction(tenant) {
            val result = MantenimientoRepository.createOrder(tenant, input, actor)
            // El autor enlaza sus propios archivos al reportar.
            MantenimientoRepository.attach(tenant, result.id, fileIds(values), permissions.userId, actor.activity)
            result
        }
        val order = MantenimientoRepository.getOrder(tenant, created.id) ?: missing(tr(NotFound))
        MantenimientoNotify.created(tenant, access.notificationsFor(MantenimientoModule.id), order, scope.userId)
        return order
    }

    /**
     * Guarda el formulario: quien gestiona edita todo; quien trabaja la orden solo costo real y resolución. Quien solo la ve no guarda nada.
     */
    fun saveOrder(tenant: Connection, access: ModuleAccessService, permissions: ModulePermissions, id: UUID, values: Map<String, String>): Order {
        val scope = scope(permissions)
        val before = visibleOrder(tenant, scope, id)
        val actor = actor(tenant, access, permissions, scope)
        val updated = when {
            scope.canEdit(before) -> {
                val input = MantenimientoActions.orderInput(before.kind, values, lookups(tenant, before.assetId), canManage = true, existing = before)
                MantenimientoRepository.transaction(tenant) {
                    val saved = MantenimientoRepository.updateOrder(tenant, id, input, actor) ?: missing(tr(NotFound))
                    applyFiles(tenant, scope, permissions, saved, values)
                    saved
                }
            }
            MantenimientoActions.canWorkNow(scope, before) -> {
                val work = MantenimientoActions.workValues(values, before)
                MantenimientoRepository.updateWork(tenant, id, work.costAmount, work.costCurrency, work.resolution, actor.activity)
                MantenimientoRepository.getOrder(tenant, id) ?: missing(tr(NotFound))
            }
            else -> missing(tr("No tienes permiso para editar esta orden."))
        }
        if (updated.assigneeId != before.assigneeId) MantenimientoNotify.assigned(tenant, access.notificationsFor(MantenimientoModule.id), updated, scope.userId)
        return updated
    }

    /** Enlaza los archivos nuevos y quita los que el usuario sacó del formulario (quien no gestiona solo quita los que subió él). */
    fun applyFiles(tenant: Connection, scope: MantenimientoScope, permissions: ModulePermissions, order: Order, values: Map<String, String>) {
        val current = MantenimientoRepository.listAttachments(tenant, scope, order.id)
        val changes = MantenimientoActions.fileChanges(values, current)
        if (changes.added.isEmpty() && changes.removed.isEmpty()) return
        if (!MantenimientoActions.canAttach(scope, order)) missing(tr("No puedes cambiar los archivos de esta orden."))
        if (changes.added.isNotEmpty()) {
            MantenimientoRepository.attach(tenant, order.id, changes.added.mapNotNull { MantenimientoActions.uuid(it.id) }, permissions.userId, permissions.activityActor)
        }
        val removable = if (scope.canEdit(order)) changes.removed else changes.removed.filter { id -> current.firstOrNull { it.id == id }?.uploadedBy == permissions.userId }
        if (removable.isNotEmpty()) MantenimientoRepository.markDeleted(tenant, order.id, removable, permissions.activityActor)
    }

    fun saveFiles(tenant: Connection, permissions: ModulePermissions, id: UUID, values: Map<String, String>) {
        val scope = scope(permissions)
        val order = visibleOrder(tenant, scope, id)
        MantenimientoRepository.transaction(tenant) { applyFiles(tenant, scope, permissions, order, values) }
    }

    /**
     * Cambia el estado con las reglas de [OrderTransitions]. Al completar se guardan el costo y la resolución del formulario si vienen en
     * [values]; al rechazar [values] trae el motivo.
     */
    fun changeStatus(tenant: Connection, access: ModuleAccessService, permissions: ModulePermissions, id: UUID, to: OrderStatus, values: Map<String, String>): Order {
        val scope = scope(permissions)
        val before = visibleOrder(tenant, scope, id)
        val actor = actor(tenant, access, permissions, scope)
        val after = if (to == OrderStatus.Done) {
            val resolution = values["resolution"]
            val cost = if ("cost" in values) MantenimientoActions.amount(values["cost"], tr("El costo")) else null
            MantenimientoRepository.closeOrder(tenant, id, resolution, cost, actor, scope)
        } else {
            MantenimientoRepository.changeStatus(tenant, id, to, actor, scope, reason = values["reason"].orEmpty())
        }
        MantenimientoNotify.statusChanged(tenant, access.notificationsFor(MantenimientoModule.id), before, after, scope.userId)
        return after
    }

    /** Tomar una orden sin responsable (atómico: si otra persona la tomó antes, avisa). */
    fun take(tenant: Connection, access: ModuleAccessService, permissions: ModulePermissions, id: UUID) {
        val scope = scope(permissions)
        val order = visibleOrder(tenant, scope, id)
        if (!scope.canTake(order)) missing(tr("No puedes tomar esta orden."))
        val actor = actor(tenant, access, permissions, scope)
        if (!MantenimientoRepository.takeOrder(tenant, id, actor)) missing(tr("Otra persona ya tomó la orden."))
    }

    fun addNote(tenant: Connection, access: ModuleAccessService, permissions: ModulePermissions, id: UUID, body: String) {
        val scope = scope(permissions)
        val order = visibleOrder(tenant, scope, id)
        if (!MantenimientoActions.canNote(scope, order)) missing(tr("No puedes anotar en esta orden."))
        MantenimientoRepository.addNote(tenant, id, body, actor(tenant, access, permissions, scope))
    }

    fun addCheck(tenant: Connection, permissions: ModulePermissions, id: UUID, text: String) {
        val scope = scope(permissions)
        val order = visibleOrder(tenant, scope, id)
        if (!MantenimientoActions.canWorkChecklist(scope, order)) missing(tr("No puedes cambiar la lista de esta orden."))
        MantenimientoRepository.addCheckItem(tenant, id, text, permissions.activityActor)
    }

    fun toggleCheck(tenant: Connection, permissions: ModulePermissions, id: UUID, item: UUID, done: Boolean) {
        val scope = scope(permissions)
        val order = visibleOrder(tenant, scope, id)
        if (!MantenimientoActions.canWorkChecklist(scope, order)) missing(tr("No puedes cambiar la lista de esta orden."))
        if (!MantenimientoRepository.setCheckDone(tenant, id, item, done, scope.userId, permissions.activityActor)) missing(tr("Ese elemento ya no existe."))
    }

    fun removeCheck(tenant: Connection, permissions: ModulePermissions, id: UUID, item: UUID) {
        val scope = scope(permissions)
        val order = visibleOrder(tenant, scope, id)
        if (!scope.canEdit(order)) missing(tr(NotManager))
        if (!MantenimientoRepository.removeCheckItem(tenant, id, item, permissions.activityActor)) missing(tr("Ese elemento ya no existe."))
    }

    // ---- Compras ----

    /** Si el botón de compras debe mostrarse: Compras activo y la herramienta usable con los permisos vivos del usuario. */
    fun purchaseAvailable(tenant: Connection, access: ModuleAccessService, permissions: ModulePermissions): Boolean {
        val userId = permissions.userId ?: return false
        return MantenimientoPurchases.available(access, tenant, permissions.companyCode, userId, permissions.serverOwner)
    }

    /** Pide la compra de la orden (solo quien la trabaja). Devuelve el aviso de éxito o lanza el motivo del fallo. */
    fun requestPurchase(tenant: Connection, access: ModuleAccessService, permissions: ModulePermissions, id: UUID): String {
        val scope = scope(permissions)
        val order = visibleOrder(tenant, scope, id)
        if (!scope.canWork(order) || !order.status.isActive) missing(tr("No puedes pedir la compra de esta orden."))
        val userId = permissions.userId ?: missing(tr("No se pudo identificar a tu usuario."))
        return when (val outcome = MantenimientoPurchases.request(access, tenant, permissions.companyCode, userId, permissions.serverOwner, id)) {
            is MantenimientoPurchases.PurchaseOutcome.Created -> tr("Solicitud de compra {0} creada como borrador en Compras.", outcome.folio)
            is MantenimientoPurchases.PurchaseOutcome.Busy -> missing(outcome.message)
            is MantenimientoPurchases.PurchaseOutcome.Failed -> missing(outcome.reason)
        }
    }

    // ---- activos ----

    fun saveAsset(tenant: Connection, permissions: ModulePermissions, id: UUID?, values: Map<String, String>): UUID {
        val scope = scope(permissions)
        requireManage(scope)
        val properties = MantenimientoRepository.listProperties(tenant)
        return MantenimientoRepository.transaction(tenant) {
            if (id == null) {
                val input = MantenimientoActions.assetInput(values, properties)
                val created = MantenimientoRepository.createAsset(tenant, input)
                MantenimientoActivity.write(tenant, permissions.activityActor, "activo", created.toString(), input.name.trim(), ActivityActions.Created,
                    "Creo el activo ${input.name.trim()}.", ActivityChanges.created(assetValuesOf(input, properties), MantenimientoActivity.AssetLabels))
                return@transaction created
            }
            val existing = MantenimientoRepository.getAsset(tenant, id) ?: missing(tr("Activo no encontrado."))
            val input = MantenimientoActions.assetInput(values, properties, existing)
            if (!MantenimientoRepository.updateAsset(tenant, id, input)) missing(tr("Activo no encontrado."))
            val changes = ActivityChanges.diff(
                MantenimientoActivity.assetValues(existing.name, existing.propertyName, existing.category, existing.location, existing.notes),
                assetValuesOf(input, properties), MantenimientoActivity.AssetLabels,
            )
            if (changes.isNotEmpty()) {
                MantenimientoActivity.write(tenant, permissions.activityActor, "activo", id.toString(), input.name.trim(), ActivityActions.Updated,
                    "Modifico el activo ${input.name.trim()}.", changes)
            }
            id
        }
    }

    private fun assetValuesOf(input: AssetInput, properties: List<Property>): Map<String, Any?> =
        MantenimientoActivity.assetValues(input.name, properties.firstOrNull { it.id == input.propertyId }?.name, input.category, input.location, input.notes)

    fun archiveAsset(tenant: Connection, permissions: ModulePermissions, id: UUID, archived: Boolean) {
        requireManage(scope(permissions))
        MantenimientoRepository.transaction(tenant) {
            val name = MantenimientoRepository.getAsset(tenant, id)?.name.orEmpty()
            if (!MantenimientoRepository.archiveAsset(tenant, id, archived)) missing(tr("Activo no encontrado."))
            MantenimientoActivity.write(tenant, permissions.activityActor, "activo", id.toString(), name,
                if (archived) ActivityActions.Archived else ActivityActions.Restored,
                if (archived) "Archivo el activo $name." else "Restauro el activo $name.")
        }
    }

    // ---- planes ----

    fun savePlan(tenant: Connection, permissions: ModulePermissions, id: UUID?, values: Map<String, String>): UUID {
        val scope = scope(permissions)
        requireManage(scope)
        return MantenimientoRepository.transaction(tenant) {
            if (id == null) {
                val input = MantenimientoActions.planInput(values, lookups(tenant, null), emptyList())
                val created = MantenimientoRepository.createPlan(tenant, input, scope.userId)
                MantenimientoActivity.write(tenant, permissions.activityActor, "plan", created.toString(), input.title, ActivityActions.Created,
                    "Creo el plan ${input.title}.", ActivityChanges.created(planValuesOf(tenant, input), MantenimientoActivity.PlanLabels))
                return@transaction created
            }
            val existing = MantenimientoRepository.getPlan(tenant, id) ?: missing(tr("Plan no encontrado."))
            val checklist = MantenimientoRepository.planChecklist(tenant, id).map { it.text }
            val input = MantenimientoActions.planInput(values, lookups(tenant, existing.assetId), checklist, existing)
            if (!MantenimientoRepository.updatePlan(tenant, id, input)) missing(tr("Plan no encontrado."))
            val changes = ActivityChanges.diff(planValuesOfPlan(existing, checklist), planValuesOf(tenant, input), MantenimientoActivity.PlanLabels)
            if (changes.isNotEmpty()) {
                MantenimientoActivity.write(tenant, permissions.activityActor, "plan", id.toString(), input.title, ActivityActions.Updated,
                    "Modifico el plan ${input.title}.", changes)
            }
            id
        }
    }

    private fun planValuesOf(tenant: Connection, input: PlanInput): Map<String, Any?> = MantenimientoActivity.planValues(
        title = input.title, description = input.description,
        property = input.propertyId?.let { id -> MantenimientoRepository.listProperties(tenant).firstOrNull { it.id == id }?.name },
        asset = input.assetId?.let { MantenimientoRepository.getAsset(tenant, it)?.name },
        frequency = input.frequency.label, intervalDays = input.intervalDays, leadDays = input.leadDays,
        priority = input.priority.label, nextDue = input.nextDue, checklist = input.checklist,
    )

    private fun planValuesOfPlan(plan: Plan, checklist: List<String>): Map<String, Any?> = MantenimientoActivity.planValues(
        title = plan.title, description = plan.description, property = plan.propertyName, asset = plan.assetName,
        frequency = plan.frequency.label, intervalDays = plan.intervalDays, leadDays = plan.leadDays,
        priority = plan.priority.label, nextDue = plan.nextDue, checklist = checklist,
    )

    fun setPlanActive(tenant: Connection, permissions: ModulePermissions, id: UUID, active: Boolean) {
        requireManage(scope(permissions))
        MantenimientoRepository.transaction(tenant) {
            val plan = MantenimientoRepository.getPlan(tenant, id)
            if (!MantenimientoRepository.setPlanActive(tenant, id, active)) missing(tr("Plan no encontrado."))
            val title = plan?.title.orEmpty()
            MantenimientoActivity.write(tenant, permissions.activityActor, "plan", id.toString(), title,
                if (active) ActivityActions.Enabled else ActivityActions.Disabled,
                if (active) "Activo el plan $title." else "Desactivo el plan $title.",
                listOf(ActivityChange("active", "Activo", ActivityChanges.format(plan?.active == true), ActivityChanges.format(active))))
        }
    }

    /** Cambia la lista de comprobacion del plan: [change] recibe los textos actuales y el indice del elemento tocado (-1 si no hay). */
    fun changePlanChecklist(tenant: Connection, permissions: ModulePermissions, id: UUID, itemId: UUID?, change: (List<String>, Int) -> List<String>) {
        requireManage(scope(permissions))
        MantenimientoRepository.transaction(tenant) {
            val plan = MantenimientoRepository.getPlan(tenant, id) ?: missing(tr("Plan no encontrado."))
            val items = MantenimientoRepository.planChecklist(tenant, id)
            val index = itemId?.let { wanted -> items.indexOfFirst { it.id == wanted }.also { if (it < 0) missing(tr("Ese elemento ya no existe.")) } } ?: -1
            val before = items.map { it.text }
            val changed = change(before, index)
            if (!MantenimientoRepository.updatePlan(tenant, id, MantenimientoActions.planInputOf(plan, changed))) missing(tr("Plan no encontrado."))
            val diff = ActivityChanges.diff(mapOf("checklist" to before.joinToString(" · ")), mapOf("checklist" to changed.joinToString(" · ")), MantenimientoActivity.PlanLabels)
            if (diff.isNotEmpty()) {
                MantenimientoActivity.write(tenant, permissions.activityActor, "plan", id.toString(), plan.title, ActivityActions.Updated,
                    "Cambio la lista de comprobacion del plan ${plan.title}.", diff)
            }
        }
    }

    // ---- ajustes ----

    fun addProperty(tenant: Connection, permissions: ModulePermissions, name: String) {
        requireManage(scope(permissions))
        MantenimientoRepository.transaction(tenant) {
            val clean = name.trim()
            val id = MantenimientoRepository.addProperty(tenant, name)
            MantenimientoActivity.write(tenant, permissions.activityActor, "propiedad", id.toString(), clean, ActivityActions.Created,
                "Agrego la propiedad $clean.", ActivityChanges.created(mapOf("name" to clean), MantenimientoActivity.PropertyLabels))
        }
    }

    fun renameProperty(tenant: Connection, permissions: ModulePermissions, id: UUID, name: String) {
        requireManage(scope(permissions))
        MantenimientoRepository.transaction(tenant) {
            val before = MantenimientoRepository.getProperty(tenant, id)
            if (!MantenimientoRepository.renameProperty(tenant, id, name)) missing(tr("Propiedad no encontrada."))
            val clean = name.trim()
            val changes = ActivityChanges.diff(mapOf("name" to before?.name), mapOf("name" to clean), MantenimientoActivity.PropertyLabels)
            if (changes.isNotEmpty()) {
                MantenimientoActivity.write(tenant, permissions.activityActor, "propiedad", id.toString(), clean, ActivityActions.Updated,
                    "Renombro la propiedad ${before?.name.orEmpty()} a $clean.", changes)
            }
        }
    }

    fun archiveProperty(tenant: Connection, permissions: ModulePermissions, id: UUID, archived: Boolean) {
        requireManage(scope(permissions))
        MantenimientoRepository.transaction(tenant) {
            val name = MantenimientoRepository.getProperty(tenant, id)?.name.orEmpty()
            if (!MantenimientoRepository.archiveProperty(tenant, id, archived)) missing(tr("Propiedad no encontrada."))
            MantenimientoActivity.write(tenant, permissions.activityActor, "propiedad", id.toString(), name,
                if (archived) ActivityActions.Archived else ActivityActions.Restored,
                if (archived) "Archivo la propiedad $name." else "Restauro la propiedad $name.")
        }
    }

    fun moveProperty(tenant: Connection, permissions: ModulePermissions, id: UUID, up: Boolean) {
        requireManage(scope(permissions))
        MantenimientoRepository.transaction(tenant) {
            val before = MantenimientoRepository.getProperty(tenant, id)
            MantenimientoRepository.moveProperty(tenant, id, up)
            val after = MantenimientoRepository.getProperty(tenant, id)
            if (before != null && after != null && before.position != after.position) {
                MantenimientoActivity.write(tenant, permissions.activityActor, "propiedad", id.toString(), after.name, ActivityActions.Updated,
                    "Cambio el orden de la propiedad ${after.name}.",
                    listOf(ActivityChange("position", "Orden", before.position.toString(), after.position.toString())))
            }
        }
    }

    fun saveDueSoon(tenant: Connection, permissions: ModulePermissions, text: String?) {
        requireManage(scope(permissions))
        val days = MantenimientoActions.int(text, tr("Los días de aviso")) ?: missing(tr("Indica los días de aviso (0 a 30)."))
        MantenimientoRepository.transaction(tenant) {
            val before = MantenimientoRepository.settings(tenant).dueSoonDays
            MantenimientoRepository.saveDueSoonDays(tenant, days)
            val changes = ActivityChanges.diff(mapOf("dueSoonDays" to before), mapOf("dueSoonDays" to days), MantenimientoActivity.SettingsLabels)
            if (changes.isNotEmpty()) {
                MantenimientoActivity.write(tenant, permissions.activityActor, "settings", null, "Ajustes de mantenimiento", ActivityActions.SettingsChanged,
                    "Cambio los días de aviso a $days.", changes)
            }
        }
    }

    /**
     * Los tableros que este usuario puede elegir. Solo un usuario de la empresa puede configurar la integración (las tareas se publican
     * con sus permisos): el dueño del servidor no lo es.
     */
    fun tareasChoice(tenant: Connection, access: ModuleAccessService, permissions: ModulePermissions): TareasChoice {
        val scope = scope(permissions)
        val userId = scope.userId ?: return TareasChoice(emptyList(), false, tr("El dueño del servidor no es usuario de la empresa: configura la integración con un usuario de la empresa."))
        if (!scope.canManage) return TareasChoice(emptyList(), false, tr(NotManager))
        if (!MantenimientoBridge.available(access, tenant, permissions.companyCode, userId, false, MantenimientoTareasSync.ListBoardsTool)) {
            return TareasChoice(emptyList(), false, tr("Tareas no está activo o tu usuario no tiene permiso para usarlo."))
        }
        val boards = MantenimientoTareasSync.boards(access, tenant, permissions.companyCode, userId, false)
            ?: return TareasChoice(emptyList(), false, tr("No se pudieron leer los tableros de Tareas."))
        return TareasChoice(boards, true)
    }

    /** Elige el tablero de Tareas (o apaga la integración con "Sin tablero"). El usuario que configura publica las tareas. */
    fun saveTareasBoard(tenant: Connection, access: ModuleAccessService, permissions: ModulePermissions, label: String?) {
        val scope = scope(permissions)
        requireManage(scope)
        val clean = label?.trim().orEmpty()
        if (clean.isEmpty() || clean.equals(MantenimientoAdminScreens.NoBoard, ignoreCase = true)) {
            saveBoard(tenant, permissions, null, null, "Sin tablero")
            return
        }
        val userId = scope.userId ?: missing(tr("Solo un usuario de la empresa puede configurar la integración."))
        val choice = tareasChoice(tenant, access, permissions)
        if (!choice.editable) missing(choice.reason.ifBlank { tr("Tareas no está disponible para tu usuario.") })
        val board = choice.labels.firstOrNull { it.second.equals(clean, ignoreCase = true) } ?: missing(tr("Tablero no válido: {0}. Elige uno de la lista.", clean))
        saveBoard(tenant, permissions, board.first, userId, board.second)
    }

    fun turnOffTareas(tenant: Connection, permissions: ModulePermissions) {
        requireManage(scope(permissions))
        saveBoard(tenant, permissions, null, null, "Sin tablero")
    }

    // Guarda el tablero de Tareas y, si cambio, lo deja en el registro de actividad (sin el id del publicador).
    private fun saveBoard(tenant: Connection, permissions: ModulePermissions, boardId: UUID?, publisherId: UUID?, label: String) {
        MantenimientoRepository.transaction(tenant) {
            val before = MantenimientoRepository.settings(tenant).tareasBoardId
            MantenimientoRepository.saveTareasBoard(tenant, boardId, publisherId)
            if (before != boardId) {
                MantenimientoActivity.write(tenant, permissions.activityActor, "settings", null, "Ajustes de mantenimiento", ActivityActions.SettingsChanged,
                    "Cambio el tablero de Tareas a $label.", listOf(ActivityChange("tareasBoard", "Tablero de Tareas", null, label)))
            }
        }
    }
}
