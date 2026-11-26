package com.ideasdeveloper.bms.server.admins

import com.ideasdeveloper.bms.ServerRoles
import com.ideasdeveloper.bms.server.i18n.tr
import java.util.UUID

/**
 * Who an audited change is attributed to (provisioning_audit_log actor_role, actor_user_id and
 * actor_kind). It is never derived from the role that executed the change.
 */
data class AuditActor(val login: String, val userId: UUID?, val kind: String) {
    companion object {
        const val ServerOwnerKind = ServerRoles.Owner
        const val ServerAdminKind = ServerRoles.Admin
        const val BusinessOwnerKind = "business_owner"
        const val SystemKind = "system"

        fun owner(role: String, userId: UUID?) = AuditActor(role, userId, ServerOwnerKind)

        /** Background work of the server (no person acted); [login] is the executing role, for reference. */
        fun system(login: String = SystemKind) = AuditActor(login, null, SystemKind)

        /** A business owner acting on their own company (their role; no central user id). */
        fun businessOwner(role: String) = AuditActor(role, null, BusinessOwnerKind)
    }
}

enum class ServerActorKind(val value: String) { Owner(ServerRoles.Owner), Admin(ServerRoles.Admin) }

/**
 * A server-level user behind a session, read from the database on every request (SERVER_ADMINS.md
 * §4): the owner (all power) or an administrator with [permissions] and a company scope.
 * [executorRole] is the owner's PostgreSQL role used for SET ROLE; [audit] is who acted.
 */
class ServerActor(
    val userId: UUID,
    val login: String,
    val kind: ServerActorKind,
    permissions: Set<ServerPermission>,
    val allCompanies: Boolean,
    companies: Set<String>,
    val executorRole: String,
    val mustChangePassword: Boolean = false,
    val displayName: String? = null,
    val email: String? = null,
) {
    val isOwner: Boolean get() = kind == ServerActorKind.Owner
    val permissions: Set<ServerPermission> = if (kind == ServerActorKind.Owner) ServerPermission.entries.toSet() else permissions.toSet()
    val companies: Set<String> = if (kind == ServerActorKind.Owner) emptySet() else companies.toSet()

    fun has(permission: ServerPermission): Boolean = isOwner || permission in permissions

    /** Whether the actor may act on [companyCode] (its scope; the owner and all-company administrators on any). */
    fun mayActOn(companyCode: String): Boolean = isOwner || allCompanies || companyCode in companies

    /** Null: every company. Otherwise the companies of an administrator's scope. */
    val companyScope: Set<String>? get() = if (isOwner || allCompanies) null else companies

    fun require(permission: ServerPermission) {
        if (!has(permission)) throw ServerPermissionException(permission)
    }

    fun requireCompany(permission: ServerPermission, companyCode: String) {
        require(permission)
        if (!mayActOn(companyCode.trim())) throw ServerPermissionException(permission, companyCode.trim())
    }

    fun requireOwner() {
        if (!isOwner) throw ServerPermissionException(null)
    }

    val audit: AuditActor get() = AuditActor(login, userId, kind.value)
}

/** 403: [permission] null means the action is for the server owner only. */
class ServerPermissionException(val permission: ServerPermission?, val companyCode: String? = null) : Exception(
    when {
        permission == null -> tr("Solo el dueno del servidor puede hacerlo.")
        companyCode != null -> tr("La empresa {0} no esta en tu alcance.", companyCode)
        else -> tr("Necesitas el permiso {0}.", permission.id)
    },
) {
    val code: String get() = if (permission == null) "SERVER_OWNER_REQUIRED" else "SERVER_PERMISSION_REQUIRED"
}

sealed interface ServerActorLookup {
    /** No valid server session: not signed in on the server, or no longer an active owner or administrator. */
    data object None : ServerActorLookup

    /** An administrator who must change the initial password before anything else works. */
    data class PasswordChangeRequired(val actor: ServerActor) : ServerActorLookup

    /** An administrator while the server has no active owner whose role could execute the work. */
    data object NoExecutor : ServerActorLookup

    data class Found(val actor: ServerActor) : ServerActorLookup
}

/** Resolves the server actor behind a session token (the central session of an owner or administrator). */
fun interface ServerActorResolver {
    fun resolve(accessToken: String): ServerActorLookup
}
