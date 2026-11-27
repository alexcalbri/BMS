package com.ideasdeveloper.bms.modules.propietarios

import com.ideasdeveloper.bms.server.activity.ActivityActor
import com.ideasdeveloper.bms.server.activity.ActivityChange
import com.ideasdeveloper.bms.server.activity.ActivityEntry
import com.ideasdeveloper.bms.server.activity.ActivityLog
import java.sql.Connection
import java.time.LocalDate

/**
 * Registro de actividad de la empresa (ACTIVITY_LOG.md) para propietarios: cada cambio de desarrollos, lotes, titulares y
 * adjuntos deja una fila con quien lo hizo ([ActivityActor]), en la misma transaccion. Textos guardados en castellano fijo.
 */
internal object PropietariosActivity {
    const val ModuleId = "propietarios"

    // Acciones propias del modulo (ademas de las de ActivityActions).
    const val OwnershipTransferred = "ownership_transferred"
    const val OwnershipRemoved = "ownership_removed"
    const val OwnerAdded = "owner_added"

    val DevelopmentLabels = mapOf(
        "name" to "Nombre", "location" to "Ubicacion", "mapUrl" to "Enlace del mapa", "latitude" to "Latitud",
        "longitude" to "Longitud", "notes" to "Notas",
    )
    val LotLabels = mapOf(
        "development" to "Desarrollo", "lotNumber" to "Numero de lote", "registerNumber" to "Registro", "propertyName" to "Propiedad",
        "propertyType" to "Tipo", "status" to "Estado", "areaM2" to "Area (m²)", "latitude" to "Latitud", "longitude" to "Longitud",
        "mapUrl" to "Enlace del mapa", "notes" to "Notas",
    )
    val OwnershipLabels = mapOf(
        "customer" to "Cliente", "sharePercent" to "Porcentaje", "startsOn" to "Inicio", "endsOn" to "Fin", "notes" to "Notas",
    )

    /** Tamano para el historial ("24 KB", "900 B"): nunca el contenido. */
    fun sizeLabel(bytes: Long): String = if (bytes < 1024) "$bytes B" else "${bytes / 1024} KB"

    /** Texto vacio = ausente: el mismo valor a ambos lados de una comparacion. */
    private fun text(value: String?): String? = value?.trim()?.ifEmpty { null }

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

    fun developmentValues(input: DevelopmentInput): Map<String, Any?> = mapOf(
        "name" to text(input.name), "location" to text(input.location), "mapUrl" to text(input.mapUrl),
        "latitude" to input.latitude, "longitude" to input.longitude, "notes" to text(input.notes),
    )

    fun developmentValues(development: Development): Map<String, Any?> = mapOf(
        "name" to text(development.name), "location" to text(development.location), "mapUrl" to text(development.mapUrl),
        "latitude" to development.latitude, "longitude" to development.longitude, "notes" to text(development.notes),
    )

    fun lotValues(input: LotInput, developmentName: String?): Map<String, Any?> = mapOf(
        "development" to text(developmentName), "lotNumber" to text(input.lotNumber), "registerNumber" to text(input.registerNumber),
        "propertyName" to text(input.propertyName), "propertyType" to text(input.propertyType), "status" to input.status.label,
        "areaM2" to input.areaM2, "latitude" to input.latitude, "longitude" to input.longitude, "mapUrl" to text(input.mapUrl),
        "notes" to text(input.notes),
    )

    fun lotValues(lot: Lot): Map<String, Any?> = mapOf(
        "development" to text(lot.developmentName), "lotNumber" to text(lot.lotNumber), "registerNumber" to text(lot.registerNumber),
        "propertyName" to text(lot.propertyName), "propertyType" to text(lot.propertyType), "status" to lot.status.label,
        "areaM2" to lot.areaM2, "latitude" to lot.latitude, "longitude" to lot.longitude, "mapUrl" to text(lot.mapUrl),
        "notes" to text(lot.notes),
    )

    fun ownershipValues(ownership: Ownership): Map<String, Any?> = mapOf(
        "customer" to text(ownership.customerName), "sharePercent" to ownership.sharePercent,
        "startsOn" to ownership.startsOn, "endsOn" to ownership.endsOn, "notes" to text(ownership.notes),
    )

    /** Los campos de una titularidad que puede editar el formulario (el cliente no cambia). */
    fun ownershipEdit(customer: String, share: java.math.BigDecimal?, startsOn: LocalDate?, endsOn: LocalDate?, notes: String): Map<String, Any?> = mapOf(
        "customer" to text(customer), "sharePercent" to share, "startsOn" to startsOn, "endsOn" to endsOn, "notes" to text(notes),
    )
}
