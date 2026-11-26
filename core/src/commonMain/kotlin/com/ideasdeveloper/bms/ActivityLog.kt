package com.ideasdeveloper.bms

import kotlinx.serialization.Serializable

/**
 * Contract of the company activity log (ACTIVITY_LOG.md, Core 0.12.0): the JSON of
 * GET /companies/me/activity and GET /server/activity. Texts (actorDisplay, summary, labels) are stored
 * in Spanish and returned as stored.
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
)

@Serializable
data class ActivityLogPage(
    val items: List<ActivityLogRow>,
    val page: Int,
    val pageSize: Int,
    val hasMore: Boolean,
    // GET /server/activity without company: companies whose log could not be read this time.
    val unavailableCompanies: List<String> = emptyList(),
)
