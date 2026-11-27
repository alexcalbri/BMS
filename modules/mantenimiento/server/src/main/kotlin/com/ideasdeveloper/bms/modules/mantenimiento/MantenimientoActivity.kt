package com.ideasdeveloper.bms.modules.mantenimiento

import com.ideasdeveloper.bms.server.activity.ActivityActor
import com.ideasdeveloper.bms.server.activity.ActivityChange
import com.ideasdeveloper.bms.server.activity.ActivityEntry
import com.ideasdeveloper.bms.server.activity.ActivityLog
import java.sql.Connection

/**
 * Registro de actividad de la empresa (ACTIVITY_LOG.md) para mantenimiento: cada cambio de ordenes, lista de comprobacion,
 * adjuntos, propiedades, activos, planes y ajustes deja una fila con quien lo hizo, en la misma transaccion del cambio.
 * Los textos guardados son castellano fijo.
 */
internal object MantenimientoActivity {
    const val ModuleId = "mantenimiento"

    val OrderLabels = mapOf(
        "kind" to "Tipo", "status" to "Estado", "title" to "Titulo", "description" to "Descripcion", "property" to "Propiedad", "asset" to "Activo", "priority" to "Prioridad",
        "dueDate" to "Fecha limite", "assignee" to "Responsable", "department" to "Departamento", "quoteAmount" to "Monto cotizado",
        "quoteCurrency" to "Moneda de la cotizacion", "quotedBy" to "Cotizado por", "costAmount" to "Costo real",
        "costCurrency" to "Moneda del costo", "resolution" to "Resolucion",
    )
    val WorkLabels = mapOf("costAmount" to "Costo real", "costCurrency" to "Moneda del costo", "resolution" to "Resolucion")
    val ChecklistLabels = mapOf("text" to "Elemento", "done" to "Hecho")
    val PropertyLabels = mapOf("name" to "Nombre", "position" to "Orden")
    val AssetLabels = mapOf("name" to "Nombre", "property" to "Propiedad", "category" to "Categoria", "location" to "Ubicacion", "notes" to "Notas")
    val PlanLabels = mapOf(
        "title" to "Titulo", "description" to "Descripcion", "property" to "Propiedad", "asset" to "Activo", "frequency" to "Frecuencia",
        "intervalDays" to "Cada (dias)", "leadDays" to "Aviso (dias antes)", "priority" to "Prioridad", "nextDue" to "Proxima fecha",
        "checklist" to "Lista de comprobacion", "active" to "Activo",
    )
    val SettingsLabels = mapOf("dueSoonDays" to "Dias de aviso", "tareasBoard" to "Tablero de Tareas")

    fun write(
        tenant: Connection,
        activity: ActivityActor,
        entityType: String,
        entityId: String?,
        label: String,
        action: String,
        summary: String,
        changes: List<ActivityChange> = emptyList(),
    ) {
        ActivityLog.record(tenant, activity, ActivityEntry(ModuleId, entityType, entityId, label, action, summary, changes))
    }

    fun folio(number: Long): String = MantenimientoFolio.format(number)

    /** Tamano para el historial ("24 KB", "900 B"): nunca el contenido. */
    fun sizeLabel(bytes: Long): String = if (bytes < 1024) "$bytes B" else "${bytes / 1024} KB"

    fun assetValues(name: String, property: String?, category: String, location: String, notes: String): Map<String, Any?> = mapOf(
        "name" to text(name), "property" to text(property), "category" to text(category), "location" to text(location), "notes" to text(notes),
    )

    fun planValues(
        title: String, description: String, property: String?, asset: String?, frequency: String, intervalDays: Int?, leadDays: Int,
        priority: String, nextDue: java.time.LocalDate?, checklist: List<String>,
    ): Map<String, Any?> = mapOf(
        "title" to text(title), "description" to text(description), "property" to text(property), "asset" to text(asset),
        "frequency" to frequency, "intervalDays" to intervalDays, "leadDays" to leadDays, "priority" to priority, "nextDue" to nextDue,
        "checklist" to text(checklist.joinToString(" · ")),
    )

    /** Campos de un plan tal como se muestran, para comparar (diff) o registrar (created). */
    fun planValues(plan: Plan, checklist: List<String>): Map<String, Any?> = planValues(
        title = plan.title, description = plan.description, property = plan.propertyName, asset = plan.assetName,
        frequency = plan.frequency.label, intervalDays = plan.intervalDays, leadDays = plan.leadDays,
        priority = plan.priority.label, nextDue = plan.nextDue, checklist = checklist,
    )

    /** Texto vacio = ausente: el mismo valor a ambos lados de una comparacion. */
    fun text(value: String?): String? = value?.trim()?.ifEmpty { null }
}
