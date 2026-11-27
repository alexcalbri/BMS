package com.ideasdeveloper.bms.modules.crm

import com.ideasdeveloper.bms.server.i18n.msg
import com.ideasdeveloper.bms.server.activity.ActivityActions
import com.ideasdeveloper.bms.server.activity.ActivityChange
import com.ideasdeveloper.bms.server.i18n.tr
import com.ideasdeveloper.bms.server.modules.ModuleNotifier
import com.ideasdeveloper.bms.server.outbound.OutboundOrigin
import java.util.UUID

/** Actions on many leads at once, shared by the bulk bar of the list view of "Leads" and the agent tools. */
object CrmBulkActions {
    // Most checkboxes read from one request.
    const val MaxSelected = 500

    /**
     * Ids the bulk form selected, always inside [pageIds] (the leads of the page the form was drawn for):
     * with "all" on, the whole page; otherwise the pages leads whose checkbox is "true". Ids that are not
     * on the page are ignored, so a stale or forged form never reaches other leads.
     */
    fun selectedIds(values: Map<String, String>, pageIds: List<UUID>): List<UUID> {
        if (values["all"] == "true") return pageIds.take(MaxSelected)
        val checked = values.filter { it.key.startsWith("lead:") && it.value == "true" }.keys.take(MaxSelected)
            .mapNotNull { runCatching { UUID.fromString(it.removePrefix("lead:")) }.getOrNull() }.toSet()
        return pageIds.filter { it in checked }
    }

    /** Why a lead seen only through a mention is left out of any bulk action. */
    val ReadOnlyReason = msg("solo los ves por una mencion (lectura y notas)")

    /**
     * Splits [leads] into the ones the user may act on (inside their scope) and the ones they only see through a mention,
     * which no bulk action touches. A defense on top of the screens, which never offer those leads to the bulk bar.
     */
    fun writable(scope: CrmScope, leads: List<CrmLead>): Pair<List<CrmLead>, List<CrmLead>> =
        leads.partition { scope.canSee(it.interestId, it.ownerUserIds) }

    /** The user [value] names to add or remove; an empty, unknown or "Sin asignar" value is an error, never a silent change. */
    fun userFor(users: List<CrmUser>, value: String): UUID =
        users.firstOrNull { it.name == value }?.id ?: throw CrmActionNotice(tr("Elige el usuario."))

    /** The assigned users of [leads] that no longer have access to the CRM, named "<usuario> (sin acceso)". */
    fun legacyOf(context: CrmScreens.Context, leads: List<CrmLead>): List<CrmUser> =
        CrmRepository.legacyUsers(context.tenant, context.users, leads.flatMap { it.ownerUserIds })

    /** Whether [target] (the users a lead would end up with) differs from the [current] ones: a lead that would not change is skipped. */
    fun changesAssignees(current: List<UUID>, target: Collection<UUID>): Boolean = target.toSet() != current.toSet()

    /** "3 lead(s) movidos a X. 2 omitidos: motivo (2)." ; nothing done is a warning. */
    fun summary(done: Int, doneText: String, skipped: Map<String, Int>): String {
        val omitted = skipped.values.sum()
        if (omitted == 0) return "$done $doneText"
        val reasons = skipped.entries.joinToString("; ") { "${it.key} (${it.value})" }
        val text = tr("{0} actualizados, {1} omitidos: {2}.", done, omitted, reasons)
        if (done == 0) throw CrmActionNotice(text, warning = true)
        return "$done $doneText $text"
    }

    /**
     * Adds, removes or replaces assigned users in each of [leads] (already filtered to the user's scope). A lead that
     * would not change is skipped and counted, so repeating the action changes nothing. The users really added get
     * "Te asignaron un lead" through [notifier] (never the user doing it). Throws [CrmActionNotice]; returns the message.
     */
    fun assign(
        context: CrmScreens.Context, requested: List<CrmLead>, mode: CrmAssignees.Mode, ids: List<UUID>, actor: CrmActor, notifier: ModuleNotifier? = null,
        origin: String = OutboundOrigin.User,
    ): String {
        if (!context.canAssign) throw CrmActionNotice(tr("No tienes permiso para asignar leads."))
        if (requested.isEmpty()) throw CrmActionNotice(tr("Marca al menos un lead."), warning = true)
        if (mode != CrmAssignees.Mode.Replace && ids.isEmpty()) throw CrmActionNotice(tr("Elige el usuario."))
        // Users without access can be taken out but never added.
        if (!CrmAssignees.canApply(mode, ids, context.users.map { it.id })) throw CrmActionNotice(tr("Ese usuario no tiene acceso al CRM."))
        val (leads, readOnly) = writable(context.scope, requested)
        val skipped = linkedMapOf<String, Int>()
        if (readOnly.isNotEmpty()) skipped[tr(ReadOnlyReason)] = readOnly.size
        var done = 0
        val doneLeads = mutableListOf<CrmLead>()
        leads.forEach { lead ->
            // "Sin asignar" takes everybody out; a replacement keeps the assigned users that can no longer be chosen.
            val target = if (mode == CrmAssignees.Mode.Replace && ids.isEmpty()) emptyList()
            else CrmAssignees.combineKeepingInactive(lead.ownerUserIds, mode, ids, context.users.map { it.id })
            if (!changesAssignees(lead.ownerUserIds, target)) {
                val reason = when (mode) {
                    CrmAssignees.Mode.Add -> tr("ya lo tenian asignado")
                    CrmAssignees.Mode.Remove -> tr("no lo tenian asignado")
                    CrmAssignees.Mode.Replace -> if (ids.isEmpty()) tr("ya estaban sin asignar") else tr("ya tenian esos asignados")
                }
                skipped[reason] = (skipped[reason] ?: 0) + 1
                return@forEach
            }
            try {
                // The change and its history entry go together.
                val change = CrmRepository.assign(context.tenant, lead, mode, if (mode == CrmAssignees.Mode.Replace) target else ids, context.names, actor, " (en lote)", origin)
                CrmNotify.assigned(context.tenant, notifier, lead.id, change.added, actor.userId)
                done++
                doneLeads += lead
            } catch (failure: CrmValidationException) {
                val reason = failure.message.orEmpty().ifBlank { tr("no se pudieron asignar") }
                skipped[reason] = (skipped[reason] ?: 0) + 1
            }
        }
        val who = CrmAssignees.names(context.names, ids)
        val doneText = when (mode) {
            CrmAssignees.Mode.Add -> tr("lead(s) con {0} anadido a los asignados.", who)
            CrmAssignees.Mode.Remove -> tr("lead(s) sin {0} entre los asignados.", who)
            CrmAssignees.Mode.Replace -> if (ids.isEmpty()) tr("lead(s) sin asignados.") else tr("lead(s) asignados a {0}.", who)
        }
        // One row per lead was written by CrmRepository.assign; this is the one of the whole operation.
        val whoStored = CrmAssignees.names(context.names, ids)
        recordBulk(
            context, actor, ActivityActions.Assigned, doneLeads, requested.size,
            when (mode) {
                CrmAssignees.Mode.Add -> "Anadio a $whoStored a los asignados de ${doneLeads.size} lead(s) en lote."
                CrmAssignees.Mode.Remove -> "Quito a $whoStored de los asignados de ${doneLeads.size} lead(s) en lote."
                CrmAssignees.Mode.Replace -> if (ids.isEmpty()) "Dejo sin asignados a ${doneLeads.size} lead(s) en lote." else "Asigno ${doneLeads.size} lead(s) a $whoStored en lote."
            },
            "Asignados", when (mode) { CrmAssignees.Mode.Add -> "Anadir: $whoStored"; CrmAssignees.Mode.Remove -> "Quitar: $whoStored"; CrmAssignees.Mode.Replace -> if (ids.isEmpty()) "Sin asignar" else "Reemplazar por: $whoStored" },
        )
        return summary(done, doneText, skipped)
    }

    /**
     * Applies [action] (one of the CrmImportScreens.Action* labels) to [leads], which must already be
     * filtered to the user's scope. [value] is the stage name or the user name (add / remove an assigned user;
     * "Sin asignar" takes no value and removes everybody). A lead that cannot take the action is skipped and
     * counted. Users added get "Te asignaron un lead" through [notifier]. Throws [CrmActionNotice]; returns the success message.
     */
    fun apply(
        context: CrmScreens.Context, requested: List<CrmLead>, action: String, value: String, actor: CrmActor, notifier: ModuleNotifier? = null,
        origin: String = OutboundOrigin.User,
    ): String {
        val tenant = context.tenant
        if (requested.isEmpty()) throw CrmActionNotice(tr("Marca al menos un lead."), warning = true)
        // Leads seen only through a mention are read-only: they never reach an action (not even delete).
        val (leads, readOnly) = writable(context.scope, requested)
        val skipped = linkedMapOf<String, Int>()
        if (readOnly.isNotEmpty()) skipped[tr(ReadOnlyReason)] = readOnly.size
        // Nothing left to act on: the message says why (they were all read-only).
        if (leads.isEmpty()) return summary(0, "", skipped)
        fun skip(reason: String, count: Int = 1) { skipped[reason] = (skipped[reason] ?: 0) + count }
        return when (action) {
            CrmImportScreens.ActionStage -> {
                val stage = context.stages.firstOrNull { it.name == value } ?: throw CrmActionNotice(tr("Elige la etapa."))
                val doneLeads = mutableListOf<CrmLead>()
                leads.forEach { lead ->
                    if (CrmRepository.wonNeedsReservation(tenant, lead.id, stage)) {
                        skip(tr("tienen habitaciones elegidas sin reserva: confirma la reserva desde su ficha"))
                        return@forEach
                    }
                    try {
                        CrmRepository.moveLead(tenant, lead, stage, context.stages, actor, origin)
                        doneLeads += lead
                    } catch (failure: CrmValidationException) {
                        skip(failure.message.orEmpty().ifBlank { tr("no se pudieron mover") })
                    }
                }
                recordBulk(context, actor, ActivityActions.StatusChanged, doneLeads, leads.size, "Movio ${doneLeads.size} lead(s) a la etapa ${stage.name} en lote.", "Etapa", stage.name)
                summary(doneLeads.size, tr("lead(s) movidos a {0}.", stage.name), skipped)
            }
            CrmImportScreens.ActionAddOwner, CrmImportScreens.ActionRemoveOwner ->
                assign(
                    context, leads, if (action == CrmImportScreens.ActionAddOwner) CrmAssignees.Mode.Add else CrmAssignees.Mode.Remove,
                    // "Quitar" also accepts assigned users that no longer have access ("<usuario> (sin acceso)").
                    listOf(userFor(context.users + legacyOf(context, leads), value)), actor, notifier, origin,
                )
            // "Sin asignar": takes everybody out.
            CrmImportScreens.ActionClearOwners -> assign(context, leads, CrmAssignees.Mode.Replace, emptyList(), actor, notifier, origin)
            CrmImportScreens.ActionArchive -> {
                val doneLeads = leads.filter { CrmRepository.archiveLead(tenant, it.id, actor) }
                val archived = doneLeads.size
                if (archived < leads.size) skip(tr("ya estaban archivados"), leads.size - archived)
                recordBulk(context, actor, ActivityActions.Archived, doneLeads, leads.size, "Archivo $archived lead(s) en lote.", "Accion", "Archivar")
                summary(archived, tr("lead(s) archivados."), skipped)
            }
            CrmImportScreens.ActionRestore -> {
                val doneLeads = leads.filter { CrmRepository.restoreLead(tenant, it.id, actor) }
                val restored = doneLeads.size
                if (restored < leads.size) skip(tr("ya estaban abiertos"), leads.size - restored)
                recordBulk(context, actor, ActivityActions.Restored, doneLeads, leads.size, "Restauro $restored lead(s) en lote.", "Accion", "Restaurar")
                summary(restored, tr("lead(s) restaurados."), skipped)
            }
            CrmImportScreens.ActionDelete -> {
                if (!context.permissions.has(CrmScreens.Delete)) throw CrmActionNotice(tr("No tienes permiso para eliminar leads."))
                leads.forEach { CrmRepository.deleteLead(tenant, it, actor, context.stage(it.stageId)?.name) }
                recordBulk(context, actor, ActivityActions.Deleted, leads, leads.size, "Elimino ${leads.size} lead(s) en lote.", "Accion", "Eliminar")
                summary(leads.size, tr("lead(s) eliminados (los clientes quedan en Clientes)."), skipped)
            }
            else -> throw CrmActionNotice(tr("Elige la accion."))
        }
    }

    /** The one row of a whole bulk operation (spec §4): the leads it reached are in `changes`; each of them has its own row. */
    private fun recordBulk(
        context: CrmScreens.Context, actor: CrmActor, action: String, leads: List<CrmLead>, selected: Int, summary: String, detailLabel: String, detail: String,
    ) {
        if (leads.isEmpty()) return
        CrmAudit.record(
            context.tenant, actor.activity, "bulk_action", null, "Accion en lote: ${leads.size} lead(s)", action, summary,
            listOf(
                ActivityChange("count", "Leads afectados", null, "${leads.size} de $selected"),
                ActivityChange("detail", detailLabel, null, detail),
                ActivityChange("leads", "Leads", null, leads.joinToString(", ") { it.name }),
            ),
        )
    }
}
