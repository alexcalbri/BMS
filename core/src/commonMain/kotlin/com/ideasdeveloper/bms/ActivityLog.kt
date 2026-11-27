package com.ideasdeveloper.bms

import kotlinx.serialization.Serializable

/**
 * Contract of the company activity log (ACTIVITY_LOG.md, Core 0.12.0): the JSON of
 * GET /companies/me/activity and GET /server/activity. Texts (summary, labels) are stored in Spanish and
 * returned as stored. [ActivityLogRow.actorDisplay] is rebuilt by the server from the structured actor
 * fields when the row is read (never the stored text): show the kind from [ActivityLogRow.actorKind],
 * [ActivityLogRow.viaAgent] and [ActivityLogRow.fromServer], not from markers inside a name.
 */
object ActivityPermissionIds {
    // Company permission (module empresa): read the company's activity log.
    const val View = "empresa.activity.view"
}

/** One old → new change. [secret]: a secret setting that changed; neither value is stored. */
@Serializable
data class ActivityLogChange(
    val field: String,
    val label: String,
    val old: String? = null,
    val new: String? = null,
    val secret: Boolean = false,
)

@Serializable
data class ActivityLogRow(
    val id: Long,
    val occurredAt: String,
    // Only in GET /server/activity.
    val companyCode: String? = null,
    val actorKind: String,
    val actorUserId: String? = null,
    val actorName: String,
    // Rebuilt at read time from actorKind, actorName, agentName and realActor*; names holding a marker are quoted.
    val actorDisplay: String,
    val agentName: String? = null,
    val realActorKind: String? = null,
    val realActorUserId: String? = null,
    val realActorName: String? = null,
    val moduleId: String,
    val moduleName: String,
    val entityType: String,
    val entityId: String? = null,
    val entityLabel: String = "",
    val action: String,
    val actionLabel: String,
    val summary: String,
    val changes: List<ActivityLogChange> = emptyList(),
    val origin: String? = null,
    // The action came through an AI agent or OAuth connection (agentName is set).
    val viaAgent: Boolean = false,
    // A server owner or administrator did it (directly, or inside the company through a switcher session).
    val fromServer: Boolean = false,
    // Spanish labels of the actor's kind for CSV and agents: "Agente IA", "Desde el servidor", "Importacion"...
    val actorKindLabel: String = "",
)

/** Result of GET /server/activity/verify (owner only): the hash chain of one company's log against its anchors. */
@Serializable
data class ActivityIntegrityReport(
    val companyCode: String,
    val ok: Boolean,
    val rows: Long = 0,
    val lastSeq: Long = 0,
    val lastId: Long? = null,
    val lastHash: String? = null,
    val anchorsChecked: Int = 0,
    // Id of the first row where the chain breaks (changed, unsealed, or right after missing rows).
    val firstBadId: Long? = null,
    val problems: List<String> = emptyList(),
    val checkedAt: String = "",
    // Facts that are not problems (a database restored with the same chain...).
    val notes: List<String> = emptyList(),
)

@Serializable
data class ActivityIntegritySummary(val reports: List<ActivityIntegrityReport>)

@Serializable
data class ActivityLogPage(
    val items: List<ActivityLogRow>,
    val page: Int,
    val pageSize: Int,
    val hasMore: Boolean,
    // GET /server/activity without company: companies whose log could not be read this time.
    val unavailableCompanies: List<String> = emptyList(),
)
