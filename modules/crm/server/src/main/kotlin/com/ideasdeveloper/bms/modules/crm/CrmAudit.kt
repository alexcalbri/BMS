package com.ideasdeveloper.bms.modules.crm

import com.ideasdeveloper.bms.server.activity.ActivityActions
import com.ideasdeveloper.bms.server.activity.ActivityActor
import com.ideasdeveloper.bms.server.activity.ActivityChange
import com.ideasdeveloper.bms.server.activity.ActivityChanges
import com.ideasdeveloper.bms.server.activity.ActivityEntry
import com.ideasdeveloper.bms.server.activity.ActivityLog
import java.sql.Connection
import java.util.UUID

/**
 * What the CRM writes in the company activity log (ACTIVITY_LOG.md). Every function records in the SAME
 * connection and transaction as the change and never catches the failure of the log. Stored texts are plain
 * Spanish (never `tr`). The per-lead history (crm_activity) keeps being written as before by [CrmRepository.addActivity].
 */
object CrmAudit {
    const val Module = "crm"
    const val Lead = "lead"
    const val Customer = "customer"
    const val Message = "message"
    const val Settings = "settings"

    val LeadLabels = linkedMapOf(
        "name" to "Nombre", "email" to "Correo", "phone" to "Telefono", "stage" to "Etapa", "interest" to "Interes",
        "source" to "Origen", "language" to "Idioma", "message" to "Mensaje", "details" to "Detalles",
        "estimatedValue" to "Valor estimado", "followUpOn" to "Seguimiento", "owners" to "Asignados",
    )

    val CustomerLabels = linkedMapOf("name" to "Nombre", "email" to "Correo", "phone" to "Telefono")

    /** Writes one row. The only door to [ActivityLog] of the CRM (extensions use it too). */
    fun record(
        tenant: Connection, actor: ActivityActor, entityType: String, entityId: String?, label: String, action: String,
        summary: String, changes: List<ActivityChange> = emptyList(), origin: String? = null,
    ) {
        ActivityLog.record(tenant, actor, ActivityEntry(Module, entityType, entityId, label, action, summary, changes, origin))
    }

    /**
     * Runs [block] inside the open transaction of [tenant], or inside a new one when there is none, so the change and its
     * log row are saved (or refused) together.
     */
    fun <T> atomically(tenant: Connection, block: () -> T): T = if (!tenant.autoCommit) block() else CrmRepository.transaction(tenant, block)

    // ---- Leads ----

    fun leadLabel(tenant: Connection, leadId: UUID): String = runCatching {
        tenant.prepareStatement("SELECT c.display_name FROM crm_leads l JOIN customers c ON c.id = l.customer_id WHERE l.id = ?").use { query ->
            query.setObject(1, leadId)
            query.executeQuery().use { rows -> if (rows.next()) rows.getString(1).orEmpty() else "" }
        }
    }.getOrDefault("")

    fun lead(
        tenant: Connection, actor: ActivityActor, leadId: UUID, label: String?, action: String, summary: (String) -> String,
        changes: List<ActivityChange> = emptyList(), origin: String? = null,
    ) {
        val name = label ?: leadLabel(tenant, leadId)
        record(tenant, actor, Lead, leadId.toString(), name, action, summary(name), changes, origin)
    }

    fun customer(
        tenant: Connection, actor: ActivityActor, customerId: UUID, label: String, action: String, summary: String, changes: List<ActivityChange> = emptyList(),
    ) = record(tenant, actor, Customer, customerId.toString(), label, action, summary, changes)

    /** The row that mirrors one history entry of a lead ([CrmRepository.addActivity]) when the caller gives no better one. */
    internal fun fromHistory(kind: String, body: String, label: String): Triple<String, String, List<ActivityChange>> {
        val first = body.lineSequence().firstOrNull().orEmpty()
        return when (kind) {
            "created" -> Triple(ActivityActions.Created, "Creo el lead $label.", listOf(ActivityChange("history", "Detalle", null, body)))
            "stage" -> {
                val (from, to) = body.split(" -> ", limit = 2).let { it.first() to it.getOrElse(1) { "" } }
                Triple(ActivityActions.StatusChanged, "Movio el lead $label a la etapa $to.", listOf(ActivityChange("stage", "Etapa", from, to)))
            }
            "owner" -> Triple(ActivityActions.Assigned, "Cambio los asignados del lead $label.", listOf(ActivityChange("owners", "Asignados", null, body)))
            "note" -> Triple(ActivityActions.Commented, "Agrego una nota al lead $label.", listOf(ActivityChange("note", "Nota", null, body)))
            "interest" -> Triple(ActivityActions.Updated, "Cambio el interes del lead $label.", listOf(ActivityChange("interest", "Interes", null, body)))
            else -> Triple(ActivityActions.Updated, "Lead $label: ${ActivityChanges.cut(first, 200)}", listOf(ActivityChange("history", "Detalle", null, body)))
        }
    }

    /** Old → new of the fields of the lead form (the stage and the assigned users have their own rows). */
    fun leadChanges(lead: CrmLead, input: CrmLeadInput, fields: List<CrmField>, names: CrmNames?): List<ActivityChange> {
        val before = mapOf(
            "name" to lead.name, "email" to lead.email, "phone" to lead.phone, "interest" to names?.interest(lead.interestId),
            "source" to lead.source, "language" to lead.language, "message" to lead.message, "details" to lead.details,
            "estimatedValue" to lead.estimatedValue, "followUpOn" to lead.followUpOn,
        )
        val after = mapOf(
            "name" to input.name, "email" to input.email, "phone" to input.phone, "interest" to names?.interest(input.interestId),
            "source" to input.source, "language" to input.language, "message" to input.message, "details" to input.details,
            "estimatedValue" to input.estimatedValue, "followUpOn" to input.followUpOn,
        )
        val labels = LeadLabels.filterKeys { it != "stage" && it != "owners" && (names != null || it != "interest") }
        return ActivityChanges.diff(before, after, labels) + CrmFields.activityChanges(fields, lead.customFields, CrmFields.merge(lead.customFields, input.custom))
    }

    /** Contact data of a customer as the log shows it. */
    fun customerValues(name: String, email: String, phone: String): Map<String, Any?> = mapOf("name" to name, "email" to email, "phone" to phone)

    // ---- Configuration ----

    /**
     * A configuration entity [type] ("etapa", "campo"...) that was saved: "created" with its values, or "updated" with the
     * old → new of the fields that changed (nothing is recorded when nothing changed). Secrets only say "changed".
     */
    fun saved(
        tenant: Connection, actor: ActivityActor, type: String, id: String?, label: String, noun: String,
        before: Map<String, Any?>?, after: Map<String, Any?>, labels: Map<String, String>, secrets: Set<String> = emptySet(),
    ) {
        if (before == null) {
            record(tenant, actor, type, id, label, ActivityActions.Created, "Creo $noun $label.", ActivityChanges.created(after, labels, secrets))
        } else {
            val changes = ActivityChanges.diff(before, after, labels, secrets)
            if (changes.isNotEmpty()) record(tenant, actor, type, id, label, ActivityActions.Updated, "Modifico $noun $label.", changes)
        }
    }

    fun removed(
        tenant: Connection, actor: ActivityActor, type: String, id: String?, label: String, noun: String,
        values: Map<String, Any?>, labels: Map<String, String>, secrets: Set<String> = emptySet(),
    ) = record(tenant, actor, type, id, label, ActivityActions.Deleted, "Elimino $noun $label.", ActivityChanges.deleted(values, labels, secrets))

    /** A settings block (one row of crm_settings, the mail, the spam rules...) that was saved: "settings_changed" with the diff, only when something changed. */
    fun settingsChanged(
        tenant: Connection, actor: ActivityActor, area: String, summary: String,
        before: Map<String, Any?>, after: Map<String, Any?>, labels: Map<String, String>, secrets: Set<String> = emptySet(),
    ) {
        val changes = ActivityChanges.diff(before, after, labels, secrets)
        if (changes.isNotEmpty()) record(tenant, actor, Settings, area, area, ActivityActions.SettingsChanged, summary, changes)
    }

    /** Text of a list of ids/names as the log shows it. */
    fun list(values: Collection<Any?>): String = values.joinToString(", ") { it.toString() }
}

/** What [CrmRepository.addActivity] records in the company activity log next to the history entry. */
sealed interface CrmLeadAudit {
    /** The row that mirrors the history entry ([CrmAudit.fromHistory]). */
    data object Auto : CrmLeadAudit

    /** The caller records its own, richer row(s). */
    data object None : CrmLeadAudit

    /** This row, with the lead's name when the caller knows it. */
    data class Row(val action: String, val summary: String, val changes: List<ActivityChange> = emptyList(), val label: String? = null) : CrmLeadAudit
}
