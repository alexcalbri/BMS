package com.ideasdeveloper.bms

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Contract of server administrators (SERVER_ADMINS.md, Core 0.11.0): permission ids and the JSON of
 * the /server/admins, /server/me and /server/audit routes. Shared by the server and the app.
 */
object ServerPermissionIds {
    const val CompaniesView = "server.companies.view"
    const val CompaniesCreate = "server.companies.create"
    const val CompaniesEdit = "server.companies.edit"
    const val CompaniesStatus = "server.companies.status"
    const val CompaniesModules = "server.companies.modules"
    const val CompaniesExtensions = "server.companies.extensions"
    const val CompaniesOwnerPassword = "server.companies.owner_password"
    const val CompaniesEnter = "server.companies.enter"
    const val ModulesView = "server.modules.view"
    const val ModulesInstall = "server.modules.install"
    const val ModulesRemove = "server.modules.remove"
    const val ModulesAuthorizeUnsigned = "server.modules.authorize_unsigned"
    const val ExtensionsInstall = "server.extensions.install"
    const val ExtensionsAuthorizeUnsigned = "server.extensions.authorize_unsigned"
    const val CoreUpdate = "server.core.update"
    const val LogsView = "server.logs.view"
    const val AuditView = "server.audit.view"
    const val Settings = "server.settings"
    const val Workspace = "server.workspace"

    val All: List<String> = listOf(
        CompaniesView, CompaniesCreate, CompaniesEdit, CompaniesStatus, CompaniesModules, CompaniesExtensions,
        CompaniesOwnerPassword, CompaniesEnter, ModulesView, ModulesInstall, ModulesRemove, ModulesAuthorizeUnsigned,
        ExtensionsInstall, ExtensionsAuthorizeUnsigned, CoreUpdate, LogsView, AuditView, Settings, Workspace,
    )
}

/** Values of LoginSuccessResponse.role and ServerMeResponse.kind for server sessions. */
object ServerRoles {
    const val Owner = "server_owner"
    const val Admin = "server_admin"
}

/** One server permission as GET /server/admin-permissions returns it (title and description translated). */
@Serializable
data class ServerPermissionInfo(
    val id: String,
    // Heading of the group (Empresas, Modulos, Extensiones, Servidor), already translated; the editor
    // shows the groups in the order they first appear.
    val group: String,
    val title: String,
    val description: String,
    // "high" for permissions that equal taking over a company or running code on the server, else "normal".
    val risk: String,
    // True when the administrator's company scope limits it.
    val scoped: Boolean,
)

@Serializable
data class ServerAdminResponse(
    val id: String,
    val login: String,
    val displayName: String,
    val email: String,
    val active: Boolean,
    val mustChangePassword: Boolean,
    val permissions: List<String>,
    val allCompanies: Boolean,
    val companies: List<String>,
    val createdAt: String,
    val updatedAt: String,
)

@Serializable
data class CreateServerAdminRequest(
    val login: String,
    val displayName: String,
    val email: String,
    // Initial password typed by the owner (16+ characters); the administrator must change it.
    val password: String,
    val permissions: List<String> = emptyList(),
    val allCompanies: Boolean = false,
    val companies: List<String> = emptyList(),
)

/** Name, email and (when given) company scope of an administrator; a scope left out is unchanged. */
@Serializable
data class UpdateServerAdminRequest(
    val displayName: String,
    val email: String,
    val allCompanies: Boolean? = null,
    val companies: List<String>? = null,
)

/** Permissions and (when given) company scope; a scope left out is unchanged. */
@Serializable
data class ServerAdminPermissionsRequest(
    val permissions: List<String>,
    val allCompanies: Boolean? = null,
    val companies: List<String>? = null,
)

/** New password set by the owner; the administrator must change it at the next sign-in. */
@Serializable
data class ServerAdminPasswordRequest(val password: String)

@Serializable
data class ServerAdminStatusRequest(val active: Boolean)

/** PUT /auth/password: the administrator's own password change (the only route besides logout and GET /server/me while mustChangePassword). */
@Serializable
data class ChangeServerPasswordRequest(val currentPassword: String, val newPassword: String)

/** GET /server/me: who the server session is and what it may do. */
@Serializable
data class ServerMeResponse(
    val userId: String,
    val login: String,
    val kind: String,
    val displayName: String? = null,
    val email: String? = null,
    // Every permission id for the owner; the granted ones for an administrator.
    val permissions: List<String>,
    // True for the owner and for administrators without a company limit.
    val allCompanies: Boolean,
    // Companies the administrator may act on (empty when allCompanies).
    val companies: List<String> = emptyList(),
    val mustChangePassword: Boolean = false,
)

@Serializable
data class ServerAuditEntry(
    val id: String,
    val createdAt: String,
    val action: String,
    val companyCode: String? = null,
    val moduleId: String? = null,
    // Login of who acted (an administrator's login, the owner's role, or the system).
    val actorRole: String,
    val actorUserId: String? = null,
    // server_owner, server_admin, business_owner, system; null for rows written before Core 0.11.0.
    val actorKind: String? = null,
    val actorName: String? = null,
    val actorEmail: String? = null,
    val details: JsonObject,
)

@Serializable
data class ServerAuditPage(
    val items: List<ServerAuditEntry>,
    val page: Int,
    val pageSize: Int,
    val hasMore: Boolean,
)
