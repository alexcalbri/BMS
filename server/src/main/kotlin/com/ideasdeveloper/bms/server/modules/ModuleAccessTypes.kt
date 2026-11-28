package com.ideasdeveloper.bms.server.modules

import com.ideasdeveloper.bms.server.activity.ActivityActor
import com.ideasdeveloper.bms.server.activity.ActivityLog
import com.ideasdeveloper.bms.server.auth.domain.LoginCredentials
import com.ideasdeveloper.bms.server.auth.domain.LoginSuccessResponse
import com.ideasdeveloper.bms.server.auth.infrastructure.database.LoginDatabases
import com.ideasdeveloper.bms.server.company.application.CompanyLinks
import com.ideasdeveloper.bms.server.company.application.ServerWorkspace
import com.ideasdeveloper.bms.server.auth.infrastructure.security.SessionTokenGenerator
import com.ideasdeveloper.bms.server.i18n.CompanyLanguages
import com.ideasdeveloper.bms.server.i18n.I18n
import com.ideasdeveloper.bms.server.i18n.SessionLanguages
import com.ideasdeveloper.bms.server.i18n.TextTranslation
import com.ideasdeveloper.bms.server.i18n.TextTranslator
import com.ideasdeveloper.bms.server.i18n.tr
import com.ideasdeveloper.bms.server.i18n.trIn
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.header
import io.ktor.server.response.respond
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.sql.Connection
import java.sql.SQLException
import java.util.UUID
import javax.sql.DataSource

/**
 * Which companies the AI agent of a business owner may act in besides its own: the ones the server
 * owner put in the same owner group (control V008). Plain SQL over a connection so the rule can be
 * tested against a real database.
 */
object OwnerGroupScope {
    /**
     * [companyCode] first and then the other companies of its owner group by name: all active, not
     * suspended in the group and never the server workspace. Empty when [companyCode] itself is not
     * active, is suspended in its group, is the server workspace or does not exist.
     */
    fun companies(central: Connection, companyCode: String): List<GroupCompany> =
        central.prepareStatement(
            """
            SELECT target.code, target.name
            FROM companies origin
            JOIN companies target ON target.owner_group = origin.owner_group
            WHERE origin.code = ?
              AND origin.is_active AND NOT origin.is_server_workspace AND NOT origin.group_suspended
              AND target.is_active AND NOT target.is_server_workspace AND NOT target.group_suspended
            ORDER BY CASE WHEN target.code = origin.code THEN 0 ELSE 1 END, lower(target.name), target.code
            """.trimIndent()
        ).use { query ->
            query.setString(1, companyCode)
            query.executeQuery().use { rows ->
                buildList { while (rows.next()) add(GroupCompany(rows.getString(1), rows.getString(2))) }
            }
        }

    /** The active business owner of a company: its user and its email as stored. */
    data class Owner(val id: UUID, val email: String?)

    /** The active business owner of the company behind [tenant], or null when it has none. */
    fun activeBusinessOwner(tenant: Connection): Owner? =
        tenant.prepareStatement(
            """
            SELECT users.id, users.email
            FROM business_owner owner
            JOIN application_users users ON users.id = owner.user_id
            WHERE users.is_active = TRUE
            ORDER BY users.id
            LIMIT 1
            """.trimIndent()
        ).use { query ->
            query.executeQuery().use { rows -> if (rows.next()) Owner(rows.getObject(1, UUID::class.java), rows.getString(2)) else null }
        }

    /** Whether two business owners are the same person: the same email, and neither without one. */
    fun sameOwner(one: Owner, other: Owner): Boolean {
        val email = one.email?.trim().orEmpty()
        return email.isNotEmpty() && email.equals(other.email?.trim().orEmpty(), ignoreCase = true)
    }
}

/** A company link rule was broken (different owner, inactive, server workspace...); the message is for people. */
class CompanyLinkException(message: String) : Exception(message)

// Who a user is, for activity logs: [name] is the display name (or the username when missing).
data class UserIdentity(val userId: UUID, val name: String, val email: String?, val username: String)

// A user whose password was checked: a tenant user, or a server owner acting in the server workspace.
// [serverAdmin]: a server administrator connecting an audit-only agent (see serverAuditAgent); never serverOwner.
/** Outcome of [ModuleAccessService.signInForAgent]. */
sealed interface AgentSignIn {
    data class Verified(val user: VerifiedUser) : AgentSignIn
    /** Right password; the account uses two-step verification: ask for the code and send both again. */
    data object SecondFactorRequired : AgentSignIn
    data object InvalidCode : AgentSignIn
    data object TooManyAttempts : AgentSignIn
    /** Wrong user, password or company, or the account may not connect agents. */
    data object Rejected : AgentSignIn
}

data class VerifiedUser(val userId: UUID, val username: String, val companyCode: String, val serverOwner: Boolean, val serverAdmin: Boolean = false)

/**
 * What a server administrator's AI agent may use (ModuleAccessService.serverAuditAgent): exactly the
 * audit read tools, with [permissions] that hold nothing (no module permission, no business owner,
 * no server owner) in the server workspace.
 */
class ServerAuditAgent(val userId: UUID, val login: String, val name: String, val tools: List<RegisteredMcpTool>, val permissions: ModulePermissions)

// Who may read a company's activity log (see ModuleAccessService.activityReader).
sealed class ActivityReaderLookup {
    class Granted(val companyCode: String, val userId: UUID, private val resolve: () -> ActivityActor) : ActivityReaderLookup() {
        val actor: ActivityActor by lazy(resolve)
    }
    data class Denied(val decision: ModuleAccessDecision) : ActivityReaderLookup()
}

// Who a Core request belongs to (see ModuleAccessService.userSession).
sealed class UserSessionLookup {
    data class Granted(val companyCode: String, val userId: UUID) : UserSessionLookup()
    data class Denied(val decision: ModuleAccessDecision) : UserSessionLookup()
}

sealed class ModulePermissionLookup {
    data class Granted(val permissions: ModulePermissions) : ModulePermissionLookup()
    data class Denied(val decision: ModuleAccessDecision) : ModulePermissionLookup()
}

sealed class ModuleAccessDecision {
    data object Allowed : ModuleAccessDecision()
    data class Unauthorized(val code: String, val message: String) : ModuleAccessDecision()
    data class Forbidden(val code: String, val message: String) : ModuleAccessDecision()
}

@Serializable
data class ModuleAccessErrorResponse(val code: String, val message: String)

internal data class ModuleUser(val id: UUID, val businessOwner: Boolean)
