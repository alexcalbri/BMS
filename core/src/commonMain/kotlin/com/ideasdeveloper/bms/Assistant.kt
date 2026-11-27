package com.ideasdeveloper.bms

import kotlinx.serialization.Serializable

/**
 * Contract of the in-app assistant (GLOBAL_AGENT.md, "Contract", Core 0.14.0): permission ids, reason and event codes
 * and the JSON of the /companies/me/assistant/... and /server/assistant/... routes. Shared by the server and the app.
 */
object AssistantIds {
    // Company permission (module empresa): see and use the assistant. The business owner always has it.
    const val UsePermission = "empresa.assistant.use"
    // Server administrator permission: the server's assistant settings, key, allowance and usage.
    const val ManagePermission = "server.assistant.manage"

    // Why the assistant is not available (AssistantStatusResponse.reason), in the order they are checked.
    const val ReasonServerWorkspace = "SERVER_WORKSPACE"
    const val ReasonServerDisabled = "SERVER_DISABLED"
    const val ReasonCompanyNotAllowed = "COMPANY_NOT_ALLOWED"
    const val ReasonCompanyDisabled = "COMPANY_DISABLED"
    const val ReasonNoApiKey = "NO_API_KEY"
    const val ReasonSecretsUnavailable = "SECRETS_UNAVAILABLE"
    const val ReasonPermissionRequired = "PERMISSION_REQUIRED"
    const val ReasonLimitReached = "LIMIT_REACHED"

    // SSE event names.
    const val EventTurnStarted = "turn_started"
    const val EventTextDelta = "text_delta"
    const val EventToolStarted = "tool_started"
    const val EventToolFinished = "tool_finished"
    const val EventConfirmationRequired = "confirmation_required"
    const val EventUsage = "usage"
    const val EventNotice = "notice"
    const val EventError = "error"
    const val EventDone = "done"

    // Models the server owner may choose (the first is the default) and efforts.
    val Models: List<String> = listOf("claude-opus-5-5", "claude-sonnet-5-5", "claude-haiku-5-5")
    val Efforts: List<String> = listOf("low", "medium", "high", "xhigh", "max")
    const val DefaultModel = "claude-opus-5-5"
    const val DefaultEffort = "medium"
}

@Serializable
data class AssistantStatusResponse(
    // Show the assistant entry (the business owner always sees it, to configure it).
    val visible: Boolean,
    // The user may send a message now.
    val available: Boolean,
    val reason: String? = null,
    val message: String? = null,
    val canManage: Boolean = false,
    val model: String? = null,
    // "company" | "server" | null
    val keySource: String? = null,
    val monthlyLimitTokens: Long? = null,
    val monthUsedTokens: Long = 0,
    val limitApplies: Boolean = true,
    val confirmationHours: Int = 24,
    val maxMessageChars: Int = 20_000,
    val disclosure: String = "",
)

@Serializable
data class AssistantConversationResponse(
    val id: String,
    val title: String,
    val createdAt: String,
    val updatedAt: String,
    val archived: Boolean,
    // idle | running | awaiting_confirmation
    val state: String,
    val pendingConfirmations: Int = 0,
)

@Serializable
data class AssistantConversationListResponse(val conversations: List<AssistantConversationResponse>, val nextBefore: String? = null)

@Serializable
data class AssistantCreateConversationRequest(val title: String? = null)

@Serializable
data class AssistantUpdateConversationRequest(val title: String? = null, val archived: Boolean? = null)

@Serializable
data class AssistantSendMessageRequest(val text: String)

@Serializable
data class AssistantDecisionRequest(
    // "approve" | "reject"
    val decision: String,
)

@Serializable
data class AssistantToolView(
    val toolUseId: String,
    val name: String,
    val label: String,
    // ok | error | rejected | expired | pending | not_run
    val status: String,
)

@Serializable
data class AssistantItem(
    val id: String,
    // user | assistant | notice
    val role: String,
    // text | tool | notice
    val kind: String,
    val text: String? = null,
    val tool: AssistantToolView? = null,
    val code: String? = null,
    val createdAt: String,
)

@Serializable
data class AssistantSummaryRow(val label: String, val value: String)

/** An open confirmation: the `confirmation_required` event and the `pending` list of a conversation. */
@Serializable
data class AssistantConfirmation(
    val confirmationId: String,
    val toolUseId: String,
    val name: String,
    val label: String,
    val module: String,
    val destructive: Boolean,
    val summary: List<AssistantSummaryRow>,
    val expiresAt: String,
)

@Serializable
data class AssistantConversationDetailResponse(
    val conversation: AssistantConversationResponse,
    val items: List<AssistantItem>,
    val pending: List<AssistantConfirmation>,
)

@Serializable
data class AssistantCompanySettingsResponse(
    val enabled: Boolean,
    val serverEnabled: Boolean,
    val companyAllowed: Boolean,
    val ownKeySet: Boolean,
    val ownKeyHint: String? = null,
    val serverKeySet: Boolean,
    val monthlyLimitTokens: Long? = null,
    val limitAppliesToOwnKey: Boolean,
    val model: String,
    val retentionDays: Int,
    val disclosure: String,
    val permissionId: String = AssistantIds.UsePermission,
)

@Serializable
data class AssistantCompanySettingsRequest(
    val enabled: Boolean? = null,
    val acceptDisclosure: Boolean? = null,
    val apiKey: String? = null,
    val removeApiKey: Boolean? = null,
)

@Serializable
data class AssistantUserUsage(
    val userId: String,
    val name: String,
    val requests: Long,
    val inputTokens: Long,
    val outputTokens: Long,
    val cacheReadInputTokens: Long,
    val cacheCreationInputTokens: Long,
    val countedTokens: Long,
    val keySource: String,
)

@Serializable
data class AssistantDayUsage(val day: String, val countedTokens: Long, val requests: Long)

@Serializable
data class AssistantCompanyUsageResponse(
    val month: String,
    val monthlyLimitTokens: Long? = null,
    val usedTokens: Long,
    val users: List<AssistantUserUsage>,
    val days: List<AssistantDayUsage>,
)

@Serializable
data class AssistantServerSettingsResponse(
    val enabled: Boolean,
    val keySet: Boolean,
    val keyHint: String? = null,
    val model: String,
    val models: List<String> = AssistantIds.Models,
    val effort: String,
    val efforts: List<String> = AssistantIds.Efforts,
    val defaultMonthlyTokens: Long,
    val limitOwnKeyCompanies: Boolean,
    val retentionDays: Int,
    val secretsAvailable: Boolean,
    val disclosure: String,
)

@Serializable
data class AssistantServerSettingsRequest(
    val enabled: Boolean? = null,
    val acceptDisclosure: Boolean? = null,
    val model: String? = null,
    val effort: String? = null,
    val defaultMonthlyTokens: Long? = null,
    val limitOwnKeyCompanies: Boolean? = null,
    val retentionDays: Int? = null,
)

@Serializable
data class AssistantKeyRequest(val apiKey: String)

@Serializable
data class AssistantServerCompany(
    val code: String,
    val name: String,
    val allowed: Boolean,
    val monthlyTokens: Long? = null,
    val effectiveMonthlyTokens: Long? = null,
    val enabled: Boolean,
    val ownKeySet: Boolean,
    val monthUsedTokens: Long,
)

@Serializable
data class AssistantServerCompaniesResponse(val companies: List<AssistantServerCompany>)

@Serializable
data class AssistantCompanyAllowanceRequest(val allowed: Boolean, val monthlyTokens: Long? = null)

@Serializable
data class AssistantServerCompanyUsage(
    val code: String,
    val name: String,
    val requests: Long,
    val countedTokens: Long,
    val serverKeyTokens: Long,
    val ownKeyTokens: Long,
)

@Serializable
data class AssistantServerUsageResponse(val month: String, val companies: List<AssistantServerCompanyUsage>)
