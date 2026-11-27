package com.ideasdeveloper.bms.modules.clientes

import com.ideasdeveloper.bms.server.activity.ActivityActions
import com.ideasdeveloper.bms.server.activity.ActivityActor
import com.ideasdeveloper.bms.server.activity.ActivityChange
import com.ideasdeveloper.bms.server.activity.ActivityChanges
import com.ideasdeveloper.bms.server.activity.ActivityEntry
import com.ideasdeveloper.bms.server.activity.ActivityLog
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.sql.Connection
import java.util.UUID

/**
 * Who made a change, for both records of it: the module's own history (customer_activity: user id and the
 * name shown there, which keeps "(agente X)") and the company activity log ([actor], resolved by Core).
 */
class ClientesAuthor(val userId: UUID?, val name: String, val actor: ActivityActor) {
    companion object {
        /** What the sharing job does by itself (customer_activity keeps saying "Sistema"). */
        fun system(actor: ActivityActor) = ClientesAuthor(null, "Sistema", actor)
    }
}

/** The activity row could not be written: the change fails with it and callers that tolerate other failures must let this one through. */
class ActivityWriteException(cause: Throwable) : RuntimeException("No se pudo escribir el registro de actividad.", cause)

/**
 * Rows Clientes writes in the company activity log (ACTIVITY_LOG.md), next to each write of the module and in the
 * same transaction. The texts are Spanish plain literals: they are stored, never translated with tr.
 */
internal object ClientesLog {
    const val Module = ClientesModule.id

    // Entity types.
    const val Customer = "customer"
    const val Note = "customer_note"
    const val HistoryEntry = "customer_history_entry"
    const val Field = "customer_field"
    const val CompanyLink = "company_link"
    const val AccountLink = "customer_account_link"
    const val Review = "customer_review"
    const val Sync = "customer_sync"

    val CustomerLabels = linkedMapOf("displayName" to "Nombre", "primaryEmail" to "Correo", "primaryPhone" to "Telefono")
    val FieldLabels = linkedMapOf(
        "fieldKey" to "Clave", "label" to "Etiqueta", "fieldType" to "Tipo", "required" to "Obligatorio", "displayOrder" to "Orden",
    )

    /** Runs [block] in one transaction of [db] (or inside the one the caller already opened), so the change and its rows commit together. */
    fun <T> atomically(db: Connection, block: () -> T): T {
        if (!db.autoCommit) return block()
        db.autoCommit = false
        try {
            val result = block()
            db.commit()
            return result
        } catch (failure: Throwable) {
            runCatching { db.rollback() }
            throw failure
        } finally {
            runCatching { db.autoCommit = true }
        }
    }

    fun record(
        db: Connection,
        actor: ActivityActor,
        entityType: String,
        entityId: Any?,
        label: String,
        action: String,
        summary: String,
        changes: List<ActivityChange> = emptyList(),
    ) {
        try {
            ActivityLog.record(db, actor, ActivityEntry(Module, entityType, entityId?.toString(), label, action, summary, changes))
        } catch (failure: Exception) {
            throw ActivityWriteException(failure)
        }
    }

    private fun text(value: JsonElement?): String? = when (value) {
        null, JsonNull -> null
        is JsonPrimitive -> value.contentOrNull
        else -> value.toString()
    }?.trim()?.ifEmpty { null }

    // Field -> label and field -> value of a customer, custom fields as "campo.<key>" with the company's label.
    private fun labelsFor(keys: Collection<String>, fieldLabels: Map<String, String>): Map<String, String> =
        CustomerLabels + keys.sorted().associate { "campo.$it" to (fieldLabels[it] ?: it) }

    private fun valuesOf(name: String?, email: String?, phone: String?, dynamic: JsonObject?): Map<String, Any?> = buildMap {
        put("displayName", name?.trim()?.ifEmpty { null })
        put("primaryEmail", email?.trim()?.ifEmpty { null })
        put("primaryPhone", phone?.trim()?.ifEmpty { null })
        dynamic?.forEach { (key, value) -> put("campo.$key", text(value)) }
    }

    fun requestValues(request: CreateCustomerRequest): Map<String, Any?> =
        valuesOf(request.displayName, request.primaryEmail, request.primaryPhone, request.dynamicFields)

    fun responseValues(customer: CustomerResponse): Map<String, Any?> =
        valuesOf(customer.displayName, customer.primaryEmail, customer.primaryPhone, customer.dynamicFields)

    private fun labelsOf(vararg values: Map<String, Any?>, fieldLabels: Map<String, String>): Map<String, String> =
        labelsFor(values.flatMap { it.keys }.filter { it.startsWith("campo.") }.map { it.removePrefix("campo.") }, fieldLabels)

    /** Customer created: every filled field as a new value. */
    fun customerCreated(db: Connection, actor: ActivityActor, id: UUID, request: CreateCustomerRequest, fieldLabels: Map<String, String>, via: String = "") {
        val values = requestValues(request)
        record(
            db, actor, Customer, id, request.displayName.trim(), ActivityActions.Created, "Creo el cliente ${request.displayName.trim()}.$via",
            ActivityChanges.created(values, labelsOf(values, fieldLabels = fieldLabels)),
        )
    }

    /** Customer edited: old -> new of each field that changed; nothing is written for an edit that changed nothing. */
    fun customerUpdated(db: Connection, actor: ActivityActor, id: UUID, before: CustomerResponse, request: CreateCustomerRequest, fieldLabels: Map<String, String>) {
        val old = responseValues(before)
        val new = requestValues(request)
        val changes = ActivityChanges.diff(old, new, labelsOf(old, new, fieldLabels = fieldLabels))
        if (changes.isEmpty()) return
        record(db, actor, Customer, id, request.displayName.trim(), ActivityActions.Updated, "Modifico el cliente ${request.displayName.trim()}.", changes)
    }

    /** Name of a customer whatever its status, for labels; empty when the row does not exist. */
    fun customerName(db: Connection, id: UUID): String = runCatching {
        db.prepareStatement("SELECT display_name FROM customers WHERE id = ?").use { query ->
            query.setObject(1, id)
            query.executeQuery().use { rows -> if (rows.next()) rows.getString(1).orEmpty() else "" }
        }
    }.getOrDefault("")

    fun customerStatus(db: Connection, actor: ActivityActor, id: UUID, action: String, summary: (String) -> String) {
        val name = customerName(db, id)
        record(db, actor, Customer, id, name, action, summary(name))
    }

    fun fieldValues(field: CustomerFieldResponse): Map<String, Any?> = mapOf(
        "fieldKey" to field.fieldKey, "label" to field.label, "fieldType" to field.fieldType, "required" to field.required, "displayOrder" to field.displayOrder,
    )

    /** Who a sharing step acts as: the job when nobody asked for it, the person's automation when a change of theirs triggered it. */
    fun sharingActor(by: ActivityActor?): ActivityActor =
        if (by == null || by.kind == ActivityActor.Kinds.Job || by.kind == ActivityActor.Kinds.System || by.kind == ActivityActor.Kinds.Automation) ActivityActor.job("Compartir clientes")
        else ActivityActor.automation("Compartir clientes", startedBy = by)

    // A copied row as old/new values: the fixed fields plus each custom field by its key (the company's labels are not at hand here).
    private fun rowValues(row: ClientesSharing.Row?): Map<String, Any?> {
        if (row == null) return emptyMap()
        val dynamic = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(row.attributes) as? JsonObject }.getOrNull()
        return valuesOf(row.displayName, row.email, row.phone, dynamic) + mapOf("status" to row.status, "customerType" to row.customerType)
    }

    /**
     * A customer's data arrived in [db] by itself (an edit copied from another company): the fields that changed, old -> new,
     * or the new customer when the company had no copy. Nothing when the copy already had the same data.
     */
    fun copyWritten(db: Connection, actor: ActivityActor, old: ClientesSharing.Row?, new: ClientesSharing.Row, fromCompany: String) {
        val before = rowValues(old)
        val after = rowValues(new)
        val labels = labelsFor((before.keys + after.keys).filter { it.startsWith("campo.") }.map { it.removePrefix("campo.") }, emptyMap()) +
            mapOf("status" to "Estado", "customerType" to "Tipo")
        val changes = ActivityChanges.diff(before, after, labels)
        if (old != null && changes.isEmpty()) return
        record(
            db, actor, Customer, new.id, new.displayName,
            if (old == null) ActivityActions.Created else ActivityActions.Updated,
            if (old == null) "Recibio el cliente ${new.displayName} desde la empresa $fromCompany." else "Actualizo el cliente ${new.displayName} con los cambios hechos en la empresa $fromCompany.",
            changes,
        )
    }

    /** A customer left in "Por revisar" (it could not be copied or merged by itself). */
    fun reviewCreated(db: Connection, actor: ActivityActor, customerId: UUID, customerName: String, reason: String) {
        record(
            db, actor, Review, customerId, customerName, ActivityActions.Created, "Dejo por revisar al cliente $customerName.",
            listOf(ActivityChange.of("reason", "Motivo", null, reason)),
        )
    }

    /** What a sharing batch did in [db], a destination company: one row with the totals (not one per customer). */
    fun batchReceived(db: Connection, actor: ActivityActor, fromCompany: String, copied: Int, merged: Int, review: Int) {
        if (copied + merged + review == 0) return
        record(
            db, actor, Sync, null, fromCompany, ActivityActions.Created,
            "Compartio clientes desde la empresa $fromCompany: $copied copiados, $merged fusionados, $review por revisar.",
            listOf(
                ActivityChange.of("copied", "Copiados", null, copied), ActivityChange.of("merged", "Fusionados", null, merged),
                ActivityChange.of("review", "Por revisar", null, review),
            ),
        )
    }
}
