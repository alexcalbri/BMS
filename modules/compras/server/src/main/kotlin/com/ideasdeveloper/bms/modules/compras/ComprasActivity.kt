package com.ideasdeveloper.bms.modules.compras

import com.ideasdeveloper.bms.server.activity.ActivityActions
import com.ideasdeveloper.bms.server.activity.ActivityActor
import com.ideasdeveloper.bms.server.activity.ActivityChange
import com.ideasdeveloper.bms.server.activity.ActivityChanges
import com.ideasdeveloper.bms.server.activity.ActivityEntry
import com.ideasdeveloper.bms.server.activity.ActivityLog
import java.sql.Connection
import java.util.UUID

/**
 * Registro de actividad de la empresa (ACTIVITY_LOG.md) para compras: cada escritura deja una fila con quien la
 * hizo (`actor.activity`), en la misma transaccion del cambio. Los textos guardados son castellano fijo.
 */
internal object ComprasActivity {
    const val ModuleId = "compras"

    val RequestLabels = mapOf(
        "title" to "Titulo", "description" to "Descripcion", "department" to "Departamento", "estimatedAmount" to "Monto estimado",
        "currency" to "Moneda", "requiredDate" to "Fecha requerida", "priority" to "Prioridad", "status" to "Estado", "requester" to "Solicitante",
    )
    val QuoteLabels = mapOf("supplier" to "Proveedor", "amount" to "Monto", "currency" to "Moneda", "notes" to "Notas")
    val DepartmentLabels = mapOf("name" to "Nombre", "active" to "Activo")
    val ApproverLabels = mapOf("name" to "Aprobador", "maxAmount" to "Monto maximo", "limitCurrency" to "Moneda del limite", "active" to "Activo")
    val SettingsLabels = mapOf("defaultCurrency" to "Moneda por defecto")

    /** Campos de una solicitud tal como se muestran, para comparar (diff) o registrar (created/deleted). */
    fun requestValues(request: PurchaseRequest): Map<String, Any?> = mapOf(
        "title" to request.title,
        "description" to request.description,
        "department" to request.departmentName,
        "estimatedAmount" to request.estimatedAmount,
        "currency" to request.currency,
        "requiredDate" to request.requiredDate,
        "priority" to request.priority.label,
    )

    fun quoteValues(quote: Quote): Map<String, Any?> = mapOf(
        "supplier" to quote.supplier,
        "amount" to quote.amount,
        "currency" to quote.currency,
        "notes" to quote.notes,
    )

    /** Una fila de compras con quien [activity] la hizo (ActivityLog.record en la misma transaccion). */
    fun write(tenant: Connection, activity: ActivityActor, entityType: String, entityId: String?, label: String, action: String, summary: String, changes: List<ActivityChange> = emptyList()) {
        ActivityLog.record(tenant, activity, ActivityEntry(ModuleId, entityType, entityId, label, action, summary, changes))
    }

    /** Una fila sobre la solicitud [request] (entidad "solicitud"). */
    fun request(tenant: Connection, actor: Actor, request: PurchaseRequest, action: String, summary: String, changes: List<ActivityChange> = emptyList()) {
        write(tenant, actor.activity, "solicitud", request.id.toString(), request.title, action, summary, changes)
    }

    /** Una fila sobre una cotizacion de [request] (entidad "cotizacion"). */
    fun quote(tenant: Connection, actor: Actor, request: PurchaseRequest, quoteId: UUID, supplier: String, action: String, summary: String, changes: List<ActivityChange>) {
        write(tenant, actor.activity, "cotizacion", quoteId.toString(), supplier, action, summary, changes)
    }

    /** Una fila de ajustes del modulo (entidad "settings" o la de su tipo). */
    fun settings(tenant: Connection, actor: Actor, entityType: String, entityId: String?, label: String, summary: String, changes: List<ActivityChange>) {
        ActivityLog.record(
            tenant, actor.activity,
            ActivityEntry(ModuleId, entityType, entityId, label, ActivityActions.SettingsChanged, summary, changes),
        )
    }

    fun folio(request: PurchaseRequest): String = ComprasFolio.format(request.number)

    /** Lo que cambio en un registro al editarlo: campo, etiqueta y valores antes y despues. */
    fun changes(before: Map<String, Any?>, after: Map<String, Any?>, labels: Map<String, String>): List<ActivityChange> =
        ActivityChanges.diff(before, after, labels)
}
