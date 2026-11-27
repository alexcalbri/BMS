package com.ideasdeveloper.bms.modules.membresia

import com.ideasdeveloper.bms.server.activity.ActivityActions
import com.ideasdeveloper.bms.server.activity.ActivityActor
import com.ideasdeveloper.bms.server.activity.ActivityChange
import com.ideasdeveloper.bms.server.activity.ActivityChanges
import com.ideasdeveloper.bms.server.activity.ActivityEntry
import com.ideasdeveloper.bms.server.activity.ActivityLog
import java.math.BigDecimal
import java.sql.Connection
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * Lo que Membresias escribe en el registro de actividad de la empresa (ACTIVITY_LOG.md §4): planes, membresias vendidas,
 * renovadas, editadas y canceladas, visitas y pases de invitado, ajustes, clientes creados desde el modulo y las
 * importaciones. Los textos son castellano fijo (nunca `tr`): se guardan tal cual. Cada fila se escribe en la misma
 * transaccion que el cambio, antes de confirmarla; un fallo de la fila hace fallar el cambio.
 */
internal object MembresiaActivity {
    const val MembershipType = "membership"
    const val PlanType = "plan"
    const val VisitType = "visit"
    const val GuestPassType = "guest_pass"
    const val SettingsType = "settings"
    const val CustomerType = "customer"
    const val CustomerFieldType = "customer_field"
    const val ImportType = "import"

    val MembershipLabels: Map<String, String> = linkedMapOf(
        "customer" to "Cliente", "plan" to "Plan", "startsOn" to "Inicio", "endsOn" to "Fin", "amount" to "Monto", "status" to "Estado",
        "source" to "Fuente", "host" to "Anfitrión", "unit" to "Unidad", "notes" to "Notas", "includedVisits" to "Visitas incluidas",
        "guestPasses" to "Pases de invitado", "renewedFrom" to "Renueva a", "cancelReason" to "Motivo de cancelación",
        "expiringNotified" to "Aviso de vencimiento",
    )
    val PlanLabels: Map<String, String> = linkedMapOf(
        "name" to "Nombre", "description" to "Descripción", "price" to "Precio", "durationDays" to "Duración (días)",
        "includedVisits" to "Visitas incluidas", "guestPasses" to "Pases de invitado", "wifiAccess" to "WiFi", "displayOrder" to "Orden",
        "active" to "Activo",
    )
    val VisitLabels: Map<String, String> = linkedMapOf(
        "customer" to "Cliente", "date" to "Fecha", "plan" to "Plan", "override" to "Registrada igual con aviso", "note" to "Nota",
        "guest" to "Invitado", "guestPassesLeft" to "Pases de invitado restantes", "count" to "Visitas importadas",
    )
    val SettingsLabels: Map<String, String> = linkedMapOf("currency" to "Moneda", "expiringDays" to "Días de aviso", "sources" to "Fuentes")
    val CustomerLabels: Map<String, String> = linkedMapOf("name" to "Nombre", "email" to "Correo", "phone" to "Teléfono")

    private val DayFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy")

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
        ActivityLog.record(tenant, actor, ActivityEntry(MembresiaModule.Id, type, id?.toString(), label, action, summary, changes))
    }

    fun day(date: LocalDate): String = date.format(DayFormat)

    private fun text(value: String?): String? = value?.trim()?.takeIf { it.isNotEmpty() }

    private fun money(amount: BigDecimal, currency: String): String = amount.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString() + " " + currency

    fun statusName(stored: String): String = when (stored) {
        StoredStatus.Programada -> "Programada"
        StoredStatus.Activa -> "Activa"
        StoredStatus.Vencida -> "Vencida"
        StoredStatus.Cancelada -> "Cancelada"
        else -> stored
    }

    private fun membershipValues(m: Membership): Map<String, Any?> = mapOf(
        "customer" to m.customerName,
        "plan" to m.planName,
        "startsOn" to day(m.startsOn),
        "endsOn" to day(m.endsOn),
        "amount" to money(m.amount, m.currency),
        "status" to statusName(m.status),
        "source" to text(m.source),
        "host" to text(m.hostName),
        "unit" to text(m.unit),
        "notes" to text(m.notes),
        "includedVisits" to (m.includedVisits?.toString() ?: "Ilimitadas"),
        "guestPasses" to m.guestPassesTotal.toString(),
        "cancelReason" to text(m.cancelReason),
    )

    private fun membershipLabel(m: Membership) = "${m.customerName} · ${m.planName}"

    // ---- membresias ----

    /** Membresia vendida (alta). [summary] distingue el alta normal de la importada. */
    fun membershipCreated(tenant: Connection, actor: ActivityActor, m: Membership, summary: String = "Vende la membresía ${m.planName} a ${m.customerName} (${day(m.startsOn)} a ${day(m.endsOn)}).") {
        write(tenant, actor, MembershipType, m.id, membershipLabel(m), ActivityActions.Created, summary, ActivityChanges.created(membershipValues(m), MembershipLabels))
    }

    /** Renovacion: una membresia nueva enlazada a la anterior. */
    fun membershipRenewed(tenant: Connection, actor: ActivityActor, previous: Membership, renewed: Membership) {
        val values = membershipValues(renewed) + ("renewedFrom" to "${previous.planName} ${day(previous.startsOn)} a ${day(previous.endsOn)}")
        write(
            tenant, actor, MembershipType, renewed.id, membershipLabel(renewed), ActivityActions.Created,
            "Renueva la membresía de ${renewed.customerName} con el plan ${renewed.planName} (${day(renewed.startsOn)} a ${day(renewed.endsOn)}).",
            ActivityChanges.created(values, MembershipLabels),
        )
    }

    /** Edicion: solo lo que cambia, antes -> despues. */
    fun membershipEdited(tenant: Connection, actor: ActivityActor, before: Membership, after: Membership) {
        val changes = ActivityChanges.diff(membershipValues(before), membershipValues(after), MembershipLabels)
        if (changes.isEmpty()) return
        write(tenant, actor, MembershipType, after.id, membershipLabel(after), ActivityActions.Updated, "Modifica la membresía de ${after.customerName} (${after.planName}).", changes)
    }

    fun membershipCancelled(tenant: Connection, actor: ActivityActor, before: Membership, reason: String) {
        val changes = buildList {
            add(ActivityChange("status", "Estado", statusName(before.status), statusName(StoredStatus.Cancelada)))
            text(reason)?.let { add(ActivityChange("cancelReason", "Motivo de cancelación", null, it)) }
        }
        write(
            tenant, actor, MembershipType, before.id, membershipLabel(before), ActivityActions.StatusChanged,
            "Cancela la membresía de ${before.customerName} (${before.planName})" + (text(reason)?.let { ": $it." } ?: "."), changes,
        )
    }

    /** Cambio de estado guardado hecho por la tarea diaria (programada -> activa, activa -> vencida). */
    fun membershipStatusByJob(tenant: Connection, actor: ActivityActor, m: Membership, from: String, to: String) {
        write(
            tenant, actor, MembershipType, m.id, membershipLabel(m), ActivityActions.StatusChanged,
            "Pasa a ${statusName(to).lowercase()} la membresía de ${m.customerName} (${m.planName}).",
            listOf(ActivityChange("status", "Estado", statusName(from), statusName(to))),
        )
    }

    /** La tarea de avisos marca que ya avisó del vencimiento. */
    fun expiringNotified(tenant: Connection, actor: ActivityActor, m: Membership, today: LocalDate) {
        write(
            tenant, actor, MembershipType, m.id, membershipLabel(m), ActivityActions.Updated,
            "Avisa del vencimiento de la membresía de ${m.customerName} (${m.planName}, fin ${day(m.endsOn)}).",
            listOf(ActivityChange("expiringNotified", "Aviso de vencimiento", m.expiringNotifiedOn?.let(::day), day(today))),
        )
    }

    // ---- visitas ----

    fun visitRecorded(tenant: Connection, actor: ActivityActor, visitId: UUID, m: Membership, today: LocalDate, overridden: Boolean, note: String) {
        val values = mapOf("customer" to m.customerName, "date" to day(today), "plan" to m.planName, "override" to overridden, "note" to text(note))
        write(
            tenant, actor, VisitType, visitId, m.customerName, ActivityActions.Created,
            "Registra la visita de ${m.customerName}" + (if (overridden) " (registrada igual pese al aviso)." else "."),
            ActivityChanges.created(values, VisitLabels),
        )
    }

    fun guestPassUsed(tenant: Connection, actor: ActivityActor, visitId: UUID, m: Membership, guestName: String, today: LocalDate) {
        val values = mapOf(
            "customer" to m.customerName, "guest" to guestName, "date" to day(today), "plan" to m.planName,
            "guestPassesLeft" to (m.guestPassesTotal - m.guestPassesUsed - 1).coerceAtLeast(0).toString(),
        )
        write(
            tenant, actor, GuestPassType, visitId, guestName, ActivityActions.Created,
            "Registra un pase de invitado de ${m.customerName}: $guestName.", ActivityChanges.created(values, VisitLabels),
        )
    }

    /** Visitas importadas de una membresia (una fila por membresia, no una por visita). */
    fun visitsImported(tenant: Connection, actor: ActivityActor, membershipId: UUID, customerName: String, count: Int) {
        if (count <= 0) return
        write(
            tenant, actor, VisitType, membershipId, customerName, ActivityActions.Created,
            "Importa $count ${if (count == 1) "visita" else "visitas"} de $customerName.",
            listOf(ActivityChange("count", "Visitas importadas", null, count.toString())),
        )
    }

    // ---- planes ----

    private fun planValues(plan: Plan): Map<String, Any?> = mapOf(
        "name" to plan.name,
        "description" to text(plan.description),
        "price" to plan.price.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString(),
        "durationDays" to plan.durationDays.toString(),
        "includedVisits" to (plan.includedVisits?.toString() ?: "Ilimitadas"),
        "guestPasses" to plan.guestPasses.toString(),
        "wifiAccess" to plan.wifiAccess,
        "displayOrder" to plan.displayOrder.toString(),
        "active" to plan.active,
    )

    fun planCreated(tenant: Connection, actor: ActivityActor, plan: Plan) {
        write(tenant, actor, PlanType, plan.id, plan.name, ActivityActions.Created, "Crea el plan ${plan.name}.", ActivityChanges.created(planValues(plan), PlanLabels))
    }

    fun planUpdated(tenant: Connection, actor: ActivityActor, before: Plan, after: Plan) {
        val changes = ActivityChanges.diff(planValues(before), planValues(after), PlanLabels)
        if (changes.isEmpty()) return
        write(tenant, actor, PlanType, after.id, after.name, ActivityActions.Updated, "Modifica el plan ${after.name}.", changes)
    }

    /** Un plan no se borra: se desactiva (archivado) o se vuelve a activar (restaurado). */
    fun planActiveChanged(tenant: Connection, actor: ActivityActor, plan: Plan, active: Boolean) {
        write(
            tenant, actor, PlanType, plan.id, plan.name, if (active) ActivityActions.Restored else ActivityActions.Archived,
            if (active) "Activa el plan ${plan.name}." else "Desactiva el plan ${plan.name}.",
            listOf(ActivityChange("active", "Activo", ActivityChanges.format(!active), ActivityChanges.format(active))),
        )
    }

    // ---- ajustes y clientes ----

    private fun settingsValues(settings: MembresiaSettings): Map<String, Any?> = mapOf(
        "currency" to settings.currency,
        "expiringDays" to settings.expiringDays.toString(),
        "sources" to settings.sources.joinToString(", ").ifEmpty { null },
    )

    fun settingsChanged(tenant: Connection, actor: ActivityActor, before: MembresiaSettings, after: MembresiaSettings) {
        val changes = ActivityChanges.diff(settingsValues(before), settingsValues(after), SettingsLabels)
        if (changes.isEmpty()) return
        write(tenant, actor, SettingsType, null, "Membresías", ActivityActions.SettingsChanged, "Cambia los ajustes de membresías.", changes)
    }

    fun customerCreated(tenant: Connection, actor: ActivityActor, id: UUID, name: String, email: String, phone: String) {
        write(
            tenant, actor, CustomerType, id, name, ActivityActions.Created, "Crea el cliente $name desde membresías.",
            ActivityChanges.created(mapOf("name" to text(name), "email" to text(email), "phone" to text(phone)), CustomerLabels),
        )
    }

    /** Campos personalizados del cliente que la importacion rellena (solo los que estaban vacios). */
    fun customerFieldsFilled(tenant: Connection, actor: ActivityActor, id: UUID, name: String, filled: List<Triple<String, String, String>>) {
        if (filled.isEmpty()) return
        write(
            tenant, actor, CustomerType, id, name, ActivityActions.Updated, "Completa campos del cliente $name desde la importación.",
            filled.map { (key, label, value) -> ActivityChange.of(key, label, null, value) },
        )
    }

    fun customerFieldCreated(tenant: Connection, actor: ActivityActor, key: String, label: String, type: String) {
        write(
            tenant, actor, CustomerFieldType, null, label, ActivityActions.Created, "Crea el campo de cliente $label al importar.",
            listOf(ActivityChange("label", "Nombre", null, label), ActivityChange("type", "Tipo", null, type), ActivityChange("key", "Clave", null, key)),
        )
    }

    // ---- importaciones ----

    /** La fila unica de una importacion: quien la empezo (el actor), el origen y los totales. */
    fun importTotal(tenant: Connection, actor: ActivityActor, entityId: UUID?, source: String, board: String, created: Int, updated: Int, skipped: Int, failed: Int, outcome: String = "") {
        val total = created + updated + skipped + failed
        val from = if (board.isBlank()) source else "$source (tablero $board)"
        val summary = "Importa $total ${if (total == 1) "fila" else "filas"} de membresías desde $from: $created creadas, $updated actualizadas, $skipped omitidas y $failed con error." +
            (if (outcome.isBlank()) "" else " $outcome")
        write(
            tenant, actor, ImportType, entityId, from, ActivityActions.Imported, summary,
            listOf(
                ActivityChange("source", "Origen", null, from),
                ActivityChange("created", "Creadas", null, created.toString()),
                ActivityChange("updated", "Actualizadas", null, updated.toString()),
                ActivityChange("skipped", "Omitidas", null, skipped.toString()),
                ActivityChange("failed", "Con error", null, failed.toString()),
            ),
        )
    }
}
