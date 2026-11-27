package com.ideasdeveloper.bms.modules.empleados

import com.ideasdeveloper.bms.server.activity.ActivityActions
import com.ideasdeveloper.bms.server.activity.ActivityActor
import com.ideasdeveloper.bms.server.activity.ActivityChange
import com.ideasdeveloper.bms.server.activity.ActivityChanges
import com.ideasdeveloper.bms.server.activity.ActivityEntry
import com.ideasdeveloper.bms.server.activity.ActivityLog
import java.sql.Connection
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * Lo que Empleados escribe en el registro de actividad de la empresa (ACTIVITY_LOG.md §4): fichas, sedes, departamentos,
 * horario habitual, turnos y ausencias. Los textos son castellano fijo (nunca `tr`): se guardan tal cual. Cada fila se
 * escribe en la misma transaccion que el cambio, antes de confirmarla; un fallo de la fila hace fallar el cambio.
 */
internal object EmpleadosActivity {
    const val EmployeeType = "employee"
    const val SiteType = "site"
    const val DepartmentType = "department"
    const val WeeklyType = "weekly_schedule"
    const val SlotType = "weekly_slot"
    const val ShiftType = "shift"
    const val AbsenceType = "absence"

    /** Campos de una ficha que se registran (la baja se registra como cambio de estado, no como campo). */
    val EmployeeLabels: Map<String, String> = linkedMapOf(
        "fullName" to "Nombre", "jobTitle" to "Puesto", "department" to "Departamento", "site" to "Sede", "status" to "Estado",
        "hireDate" to "Fecha de ingreso", "phone" to "Teléfono", "email" to "Correo", "availability" to "Disponibilidad",
        "notes" to "Notas", "user" to "Usuario enlazado",
    )
    val ShiftLabels: Map<String, String> = linkedMapOf("date" to "Fecha", "kind" to "Tipo", "start" to "Inicio", "end" to "Fin", "site" to "Sede", "note" to "Nota")
    val SlotLabels: Map<String, String> = linkedMapOf("weekday" to "Día", "start" to "Inicio", "end" to "Fin", "site" to "Sede")
    val AbsenceLabels: Map<String, String> = linkedMapOf(
        "kind" to "Tipo", "from" to "Desde", "to" to "Hasta", "status" to "Estado", "reason" to "Motivo", "note" to "Nota de la decisión",
    )
    val CatalogLabels: Map<String, String> = linkedMapOf("name" to "Nombre", "position" to "Orden")
    val WeeklyLabels: Map<String, String> = linkedMapOf("weekly" to "Horario habitual")

    private val Weekdays = listOf("lunes", "martes", "miércoles", "jueves", "viernes", "sábado", "domingo")
    private val DayFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy")

    /** Escribe una fila de actividad de Empleados en [tenant], con la conexion y la transaccion del cambio. */
    fun write(
        tenant: Connection,
        actor: ActivityActor,
        type: String,
        id: UUID?,
        label: String,
        action: String,
        summary: String,
        changes: List<ActivityChange> = emptyList(),
    ) {
        ActivityLog.record(tenant, actor, ActivityEntry(EmpleadosModule.id, type, id?.toString(), label, action, summary, changes))
    }

    fun day(date: LocalDate): String = date.format(DayFormat)

    fun hour(time: LocalTime?): String? = time?.let { "%02d:%02d".format(it.hour, it.minute) }

    fun dayName(index: Int): String = Weekdays.getOrElse(index - 1) { "día $index" }

    private fun text(value: String?): String? = value?.takeIf { it.isNotBlank() }

    private fun statusName(status: EmployeeStatus): String = when (status) {
        EmployeeStatus.Active -> "Activo"
        EmployeeStatus.Inactive -> "Baja"
    }

    private fun absenceKindName(kind: AbsenceKind): String = when (kind) {
        AbsenceKind.Vacation -> "vacaciones"
        AbsenceKind.Leave -> "permiso"
        AbsenceKind.Sick -> "enfermedad"
        AbsenceKind.Other -> "otro motivo"
    }

    private fun absenceStatusName(status: AbsenceStatus): String = when (status) {
        AbsenceStatus.Pending -> "Pendiente"
        AbsenceStatus.Approved -> "Aprobada"
        AbsenceStatus.Rejected -> "Rechazada"
        AbsenceStatus.Cancelled -> "Cancelada"
    }

    private fun catalogWord(catalog: Catalog): String = if (catalog == Catalog.Sites) "sede" else "departamento"

    private fun catalogType(catalog: Catalog): String = if (catalog == Catalog.Sites) SiteType else DepartmentType

    /** Valores de una ficha tal como se guardaria (nombres de sede, departamento y usuario, como se muestran). */
    fun valuesOf(tenant: Connection, input: EmployeeInput): Map<String, Any?> = mapOf(
        "fullName" to text(input.fullName.trim()),
        "jobTitle" to text(input.jobTitle.trim()),
        "department" to input.departmentId?.let { EmpleadosRepository.getCatalog(tenant, Catalog.Departments, it)?.name },
        "site" to input.siteId?.let { EmpleadosRepository.getCatalog(tenant, Catalog.Sites, it)?.name },
        "hireDate" to input.hireDate?.let(::day),
        "phone" to text(input.phone.trim()),
        "email" to text(input.email.trim()),
        "availability" to text(input.availability.trim()),
        "notes" to text(input.notes),
        "user" to EmpleadosRepository.userLabel(tenant, input.userId),
    )

    /** Valores de una ficha ya leida (con su estado). */
    fun valuesOf(employee: Employee, userLabel: String?): Map<String, Any?> = mapOf(
        "fullName" to employee.fullName,
        "jobTitle" to text(employee.jobTitle),
        "department" to employee.department,
        "site" to employee.site,
        "status" to statusName(employee.status),
        "hireDate" to employee.hireDate?.let(::day),
        "phone" to text(employee.phone),
        "email" to text(employee.email),
        "availability" to text(employee.availability),
        "notes" to text(employee.notes),
        "user" to userLabel,
    )

    fun employeeCreated(tenant: Connection, actor: ActivityActor, id: UUID, input: EmployeeInput) {
        val values = valuesOf(tenant, input)
        val name = input.fullName.trim()
        write(tenant, actor, EmployeeType, id, name, ActivityActions.Created, "Creo la ficha de $name.", ActivityChanges.created(values, EmployeeLabels))
    }

    /** Edicion de una ficha: solo se escribe si algun campo cambia. El estado no lo cambia esta accion. */
    fun employeeUpdated(tenant: Connection, actor: ActivityActor, existing: Employee, input: EmployeeInput) {
        val before = valuesOf(existing, EmpleadosRepository.userLabel(tenant, existing.userId))
        val after = valuesOf(tenant, input) + ("status" to before["status"])
        val changes = ActivityChanges.diff(before, after, EmployeeLabels)
        if (changes.isEmpty()) return
        val name = input.fullName.trim()
        write(tenant, actor, EmployeeType, existing.id, name, ActivityActions.Updated, "Modifico la ficha de $name.", changes)
    }

    /** Baja o reactivacion de una ficha: su propia fila de cambio de estado. */
    fun employeeStatusChanged(tenant: Connection, actor: ActivityActor, employee: Employee, status: EmployeeStatus) {
        val old = statusName(employee.status)
        val new = statusName(status)
        if (old == new) return
        val summary = if (status == EmployeeStatus.Inactive) "Da de baja a ${employee.fullName} el ${day(EmpleadosRepository.today())}." else "Reactiva la ficha de ${employee.fullName}."
        write(
            tenant, actor, EmployeeType, employee.id, employee.fullName, ActivityActions.StatusChanged, summary,
            listOf(ActivityChange("status", "Estado", old, new)),
        )
    }

    fun catalogCreated(tenant: Connection, actor: ActivityActor, catalog: Catalog, id: UUID, name: String, restored: Boolean) {
        val action = if (restored) ActivityActions.Restored else ActivityActions.Created
        val verb = if (restored) "Restaura" else "Crea"
        val article = if (catalog == Catalog.Sites) "la" else "el"
        write(
            tenant, actor, catalogType(catalog), id, name, action, "$verb ${article} ${catalogWord(catalog)} $name.",
            ActivityChanges.created(mapOf("name" to name), CatalogLabels),
        )
    }

    fun catalogRenamed(tenant: Connection, actor: ActivityActor, catalog: Catalog, id: UUID, old: String, new: String) {
        write(
            tenant, actor, catalogType(catalog), id, new, ActivityActions.Updated,
            "Renombra la ${catalogWord(catalog)} $old a $new.",
            ActivityChanges.diff(mapOf("name" to old), mapOf("name" to new), CatalogLabels),
        )
    }

    fun catalogMoved(tenant: Connection, actor: ActivityActor, catalog: Catalog, id: UUID, name: String, oldPosition: Int, newPosition: Int) {
        write(
            tenant, actor, catalogType(catalog), id, name, ActivityActions.Updated,
            "Cambia el orden de la ${catalogWord(catalog)} $name.",
            ActivityChanges.diff(mapOf("position" to oldPosition + 1), mapOf("position" to newPosition + 1), CatalogLabels),
        )
    }

    fun catalogArchived(tenant: Connection, actor: ActivityActor, catalog: Catalog, id: UUID, name: String) {
        write(tenant, actor, catalogType(catalog), id, name, ActivityActions.Archived, "Archiva la ${catalogWord(catalog)} $name.")
    }

    fun slotCreated(tenant: Connection, actor: ActivityActor, slotId: UUID, employeeName: String, weekday: Int, start: LocalTime, end: LocalTime, siteName: String?) {
        write(
            tenant, actor, SlotType, slotId, "Horario de $employeeName", ActivityActions.Created,
            "Añade al horario habitual de $employeeName la franja del ${dayName(weekday)} de ${hour(start)} a ${hour(end)}.",
            ActivityChanges.created(slotValues(weekday, start, end, siteName), SlotLabels),
        )
    }

    fun slotDeleted(tenant: Connection, actor: ActivityActor, slotId: UUID, employeeName: String, weekday: Int, start: LocalTime, end: LocalTime, siteName: String?) {
        write(
            tenant, actor, SlotType, slotId, "Horario de $employeeName", ActivityActions.Deleted,
            "Quita del horario habitual de $employeeName la franja del ${dayName(weekday)} de ${hour(start)} a ${hour(end)}.",
            ActivityChanges.deleted(slotValues(weekday, start, end, siteName), SlotLabels),
        )
    }

    private fun slotValues(weekday: Int, start: LocalTime, end: LocalTime, siteName: String?): Map<String, Any?> =
        mapOf("weekday" to dayName(weekday), "start" to hour(start), "end" to hour(end), "site" to siteName)

    /** Un turno concreto que se crea (o un dia libre). */
    fun shiftCreated(tenant: Connection, actor: ActivityActor, shiftId: UUID, employeeName: String, shift: Shift, siteName: String?) {
        val summary = if (shift.kind == ShiftKind.Off) {
            "Marca el ${day(shift.date)} como día libre de $employeeName."
        } else {
            "Crea el turno de $employeeName del ${day(shift.date)} de ${hour(shift.start)} a ${hour(shift.end)}."
        }
        write(tenant, actor, ShiftType, shiftId, "Turno de $employeeName", ActivityActions.Created, summary, ActivityChanges.created(shiftValues(shift, siteName), ShiftLabels))
    }

    /** Un turno que se borra (directamente o porque otro turno del dia lo reemplaza). Se escribe ANTES del DELETE. */
    fun shiftRemoved(tenant: Connection, actor: ActivityActor, shift: Shift, employeeName: String, siteName: String?) {
        val what = if (shift.kind == ShiftKind.Off) "día libre" else "turno"
        write(
            tenant, actor, ShiftType, shift.id, "Turno de $employeeName", ActivityActions.Deleted,
            "Elimina el $what de $employeeName del ${day(shift.date)}.",
            ActivityChanges.deleted(shiftValues(shift, siteName), ShiftLabels),
        )
    }

    /** Un turno modificado: solo se escribe si algun campo cambia. */
    fun shiftUpdated(tenant: Connection, actor: ActivityActor, before: Shift, after: Shift, employeeName: String, beforeSite: String?, afterSite: String?) {
        val changes = ActivityChanges.diff(shiftValues(before, beforeSite), shiftValues(after, afterSite), ShiftLabels)
        if (changes.isEmpty()) return
        write(
            tenant, actor, ShiftType, before.id, "Turno de $employeeName", ActivityActions.Updated,
            "Modifica el turno de $employeeName del ${day(before.date)}.", changes,
        )
    }

    private fun shiftValues(shift: Shift, siteName: String?): Map<String, Any?> = mapOf(
        "date" to day(shift.date),
        "kind" to if (shift.kind == ShiftKind.Off) "Día libre" else "Turno",
        "start" to hour(shift.start),
        "end" to hour(shift.end),
        "site" to siteName,
        "note" to text(shift.note),
    )

    fun absenceRequested(tenant: Connection, actor: ActivityActor, id: UUID, employeeName: String, input: AbsenceInput) {
        write(
            tenant, actor, AbsenceType, id, "Ausencia de $employeeName", ActivityActions.Created,
            "Pide una ausencia de ${absenceKindName(input.kind)} del ${day(input.startDate)} al ${day(input.endDate)}.",
            ActivityChanges.created(absenceValues(input.kind, input.startDate, input.endDate, AbsenceStatus.Pending, input.reason), AbsenceLabels),
        )
    }

    fun absenceRegistered(tenant: Connection, actor: ActivityActor, id: UUID, employeeName: String, input: AbsenceInput) {
        write(
            tenant, actor, AbsenceType, id, "Ausencia de $employeeName", ActivityActions.Created,
            "Registra una ausencia aprobada de ${absenceKindName(input.kind)} de $employeeName del ${day(input.startDate)} al ${day(input.endDate)}.",
            ActivityChanges.created(absenceValues(input.kind, input.startDate, input.endDate, AbsenceStatus.Approved, input.reason), AbsenceLabels),
        )
    }

    fun absenceCancelled(tenant: Connection, actor: ActivityActor, absence: Absence, employeeName: String) {
        write(
            tenant, actor, AbsenceType, absence.id, "Ausencia de $employeeName", ActivityActions.StatusChanged,
            "Cancela su solicitud de ausencia del ${day(absence.startDate)} al ${day(absence.endDate)}.",
            listOf(ActivityChange("status", "Estado", absenceStatusName(AbsenceStatus.Pending), absenceStatusName(AbsenceStatus.Cancelled))),
        )
    }

    /** Aprueba o rechaza una solicitud: la fila dice quien decidio (el actor) y la nota si la hay. */
    fun absenceDecided(tenant: Connection, actor: ActivityActor, absence: Absence, employeeName: String, approved: Boolean, note: String) {
        val status = if (approved) AbsenceStatus.Approved else AbsenceStatus.Rejected
        val verb = if (approved) "Aprueba" else "Rechaza"
        val changes = buildList {
            add(ActivityChange("status", "Estado", absenceStatusName(AbsenceStatus.Pending), absenceStatusName(status)))
            text(note)?.let { add(ActivityChange.of("note", "Nota de la decisión", null, it)) }
        }
        write(
            tenant, actor, AbsenceType, absence.id, "Ausencia de $employeeName", ActivityActions.StatusChanged,
            "$verb la ausencia de $employeeName del ${day(absence.startDate)} al ${day(absence.endDate)}.", changes,
        )
    }

    private fun absenceValues(kind: AbsenceKind, from: LocalDate, to: LocalDate, status: AbsenceStatus, reason: String): Map<String, Any?> = mapOf(
        "kind" to absenceKindName(kind).replaceFirstChar { it.uppercase() },
        "from" to day(from),
        "to" to day(to),
        "status" to absenceStatusName(status),
        "reason" to text(reason),
    )

    /** Un horario habitual reemplazado por una importacion: un solo cambio con el horario anterior y el nuevo. */
    fun weeklyReplaced(tenant: Connection, actor: ActivityActor, employeeId: UUID, employeeName: String, before: List<String>, after: List<String>) {
        if (before == after) return
        write(
            tenant, actor, WeeklyType, employeeId, "Horario de $employeeName", ActivityActions.Updated,
            "Cambia el horario habitual de $employeeName.",
            ActivityChanges.diff(mapOf("weekly" to before.joinToString("; ")), mapOf("weekly" to after.joinToString("; ")), WeeklyLabels),
        )
    }

    /** Texto de las franjas de un horario (para el cambio de horario habitual). */
    fun slotsText(slots: List<Triple<Int, LocalTime, LocalTime>>): List<String> =
        slots.sortedWith(compareBy({ it.first }, { it.second })).map { (weekday, start, end) -> "${dayName(weekday)} ${hour(start)}-${hour(end)}" }

    /** Valores de una ficha creada o cambiada por una importacion: solo lo que la fila trae. */
    fun importedEmployeeChanges(before: Employee, beforeUser: String?, fields: EmpleadosRepository.ImportFields, deptName: String?, siteName: String?, deactivate: Boolean): List<ActivityChange> {
        val old = valuesOf(before, beforeUser)
        val new = old.toMutableMap()
        new["fullName"] = text(fields.fullName)
        text(fields.jobTitle)?.let { new["jobTitle"] = it }
        text(fields.phone)?.let { new["phone"] = it }
        text(fields.email)?.let { new["email"] = it }
        text(fields.availability)?.let { new["availability"] = it }
        text(fields.notes)?.let { new["notes"] = it }
        deptName?.let { new["department"] = it }
        siteName?.let { new["site"] = it }
        if (deactivate) new["status"] = statusName(EmployeeStatus.Inactive)
        return ActivityChanges.diff(old, new, EmployeeLabels)
    }

    /** Un empleado que la importacion crea: sus valores (solo los no vacios). */
    fun importedEmployeeCreated(fields: EmpleadosRepository.ImportFields, deptName: String?, siteName: String?, active: Boolean): Map<String, Any?> = mapOf(
        "fullName" to text(fields.fullName),
        "jobTitle" to text(fields.jobTitle),
        "department" to deptName,
        "site" to siteName,
        "status" to if (active) null else statusName(EmployeeStatus.Inactive),
        "phone" to text(fields.phone),
        "email" to text(fields.email),
        "availability" to text(fields.availability),
        "notes" to text(fields.notes),
    )

}
