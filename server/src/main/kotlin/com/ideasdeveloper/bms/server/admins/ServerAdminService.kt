package com.ideasdeveloper.bms.server.admins

import com.ideasdeveloper.bms.ServerAdminResponse
import com.ideasdeveloper.bms.ServerPermissionIds
import com.ideasdeveloper.bms.server.auth.application.PasswordAttemptLimiter
import com.ideasdeveloper.bms.server.auth.application.UserProfileRules
import com.ideasdeveloper.bms.server.auth.infrastructure.security.SessionTokenGenerator
import com.ideasdeveloper.bms.server.i18n.tr
import java.util.UUID

/** Refusal of an administrator change: [code] for the app, 400 unless [status] says otherwise. */
class ServerAdminException(val code: String, message: String, val status: Int = 400) : Exception(message)

/**
 * Server administrators, managed only by the server owner (every method takes the acting
 * [ServerActor] and refuses anyone else), plus the administrator's own password change. Every change
 * is audited. Deactivating, deleting or setting a password revokes the administrator's server
 * sessions and the company sessions they opened from the switcher.
 */
class ServerAdminService(
    private val store: ServerAdminStore,
    // Whether a login is already a PostgreSQL role, a central user or a company user (fails closed).
    private val loginTaken: (String) -> Boolean,
    private val revokeCompanySessions: (UUID) -> Unit,
    private val audit: (actor: AuditActor, action: String, details: String) -> Unit,
    private val passwordAttempts: PasswordAttemptLimiter = PasswordAttemptLimiter(),
    // Deactivates the workspace staff accounts whose credentials the administrator knows and revokes
    // their sessions (CompanySelfService.deactivateWorkspaceStaffOf), audited as the owner.
    private val deactivateWorkspaceStaff: (adminUserId: UUID, by: AuditActor, reason: String) -> Unit = { _, _, _ -> },
) {
    private val loginPattern = Regex("[a-z][a-z0-9_]{0,62}")
    private val tokens = SessionTokenGenerator()

    fun list(by: ServerActor): List<ServerAdminResponse> {
        by.requireOwner()
        return store.list().map { it.response() }
    }

    fun get(by: ServerActor, id: String): ServerAdminResponse {
        by.requireOwner()
        return require(id).response()
    }

    fun create(by: ServerActor, login: String, displayName: String, email: String, password: String, permissions: List<String>, allCompanies: Boolean, companies: List<String>): ServerAdminResponse {
        by.requireOwner()
        val name = login.trim()
        if (!loginPattern.matches(name)) {
            throw ServerAdminException("INVALID_LOGIN", tr("El usuario debe iniciar con una letra minuscula y usar solo letras, numeros o guion bajo."))
        }
        profileProblem(displayName, email)
        ServerAdminPasswords.problem(password)?.let { throw ServerAdminException("WEAK_PASSWORD", it) }
        val granted = permissionsOf(permissions)
        val scope = companiesOf(allCompanies, companies)
        if (loginTaken(name)) throw ServerAdminException("LOGIN_TAKEN", tr("Ya existe un usuario con ese nombre."), 409)
        val id = UUID.randomUUID()
        store.create(id, name, displayName.trim(), email.trim(), ServerAdminPasswords.encode(password), granted, allCompanies, scope, by.userId)
        audit(by.audit, "server_admin.created", details(id, name, "permissions" to granted.ids(), "allCompanies" to allCompanies.toString(), "companies" to scope.sorted().joinToString(",")))
        return require(id.toString()).response()
    }

    /** Name and email, and the company scope when [allCompanies] is given. */
    fun update(by: ServerActor, id: String, displayName: String, email: String, allCompanies: Boolean?, companies: List<String>?): ServerAdminResponse {
        by.requireOwner()
        val admin = require(id)
        profileProblem(displayName, email)
        store.updateProfile(admin.userId, displayName.trim(), email.trim())
        if (allCompanies != null || companies != null) setScope(by, admin, allCompanies ?: admin.allCompanies, companies ?: admin.companies.toList())
        audit(by.audit, "server_admin.updated", details(admin.userId, admin.login, "emailChanged" to (!email.trim().equals(admin.email.orEmpty(), ignoreCase = true)).toString()))
        return require(id).response()
    }

    fun setPermissions(by: ServerActor, id: String, permissions: List<String>, allCompanies: Boolean?, companies: List<String>?): ServerAdminResponse {
        by.requireOwner()
        val admin = require(id)
        val granted = permissionsOf(permissions)
        // Without server.workspace the staff accounts they created (or gave a password) stop working too.
        // Idempotent and BEFORE saving, and a failure fails the request: a retry sweeps again, so the
        // permission is never saved without the sweep having run.
        if (ServerPermission.Workspace !in granted) {
            deactivateWorkspaceStaff(admin.userId, by.audit, "workspace_permission_removed")
        }
        store.setPermissions(admin.userId, granted)
        if (allCompanies != null || companies != null) setScope(by, admin, allCompanies ?: admin.allCompanies, companies ?: admin.companies.toList())
        audit(by.audit, "server_admin.permissions_updated", details(admin.userId, admin.login, "permissions" to granted.ids(), "previous" to admin.permissions.ids()))
        return require(id).response()
    }

    private fun setScope(by: ServerActor, admin: ServerAdminRow, allCompanies: Boolean, companies: List<String>) {
        val scope = companiesOf(allCompanies, companies)
        store.setScope(admin.userId, allCompanies, scope)
        audit(by.audit, "server_admin.scope_updated", details(admin.userId, admin.login, "allCompanies" to allCompanies.toString(), "companies" to scope.sorted().joinToString(",")))
    }

    /** A new password by the owner: the administrator must change it at the next sign-in; their sessions end. */
    fun resetPassword(by: ServerActor, id: String, password: String) {
        by.requireOwner()
        val admin = require(id)
        ServerAdminPasswords.problem(password)?.let { throw ServerAdminException("WEAK_PASSWORD", it) }
        store.setPassword(admin.userId, ServerAdminPasswords.encode(password), mustChange = true)
        endSessions(admin.userId)
        audit(by.audit, "server_admin.password_reset", details(admin.userId, admin.login))
    }

    fun setActive(by: ServerActor, id: String, active: Boolean): ServerAdminResponse {
        by.requireOwner()
        val admin = require(id)
        store.setActive(admin.userId, active)
        if (!active) endSessions(admin.userId)
        audit(by.audit, if (active) "server_admin.activated" else "server_admin.deactivated", details(admin.userId, admin.login))
        // Reactivating does not bring the staff accounts back: the owner decides that per account.
        if (!active) deactivateWorkspaceStaff(admin.userId, by.audit, "admin_deactivated")
        return require(id).response()
    }

    fun delete(by: ServerActor, id: String) {
        by.requireOwner()
        val admin = require(id)
        // First nothing of theirs may keep working: deactivate, then end every session they hold.
        store.setActive(admin.userId, false)
        endSessions(admin.userId)
        deactivateWorkspaceStaff(admin.userId, by.audit, "admin_deleted")
        store.delete(admin.userId)
        audit(by.audit, "server_admin.deleted", details(admin.userId, admin.login))
    }

    /**
     * The administrator's own new password (also the forced change of the initial one). Needs the
     * current password; clears must_change_password and ends their other sessions, keeping this one.
     */
    fun changeOwnPassword(by: ServerActor, accessToken: String, currentPassword: String, newPassword: String) {
        if (by.isOwner) {
            throw ServerAdminException("SERVER_ADMIN_REQUIRED", tr("El dueno del servidor cambia su contrasena con el codigo por correo."), 403)
        }
        val admin = store.find(by.userId)?.takeIf { it.usable } ?: throw ServerAdminException("INVALID_SESSION", tr("La sesion no es valida."), 401)
        val key = "/server-admin/${admin.login}"
        if (passwordAttempts.isBlocked(key)) {
            throw ServerAdminException("TOO_MANY_ATTEMPTS", tr("Demasiados intentos. Espera unos minutos e intentalo de nuevo."), 429)
        }
        if (!ServerAdminPasswords.verify(currentPassword, admin.passwordHash)) {
            passwordAttempts.recordFailure(key)
            throw ServerAdminException("INVALID_PASSWORD", tr("La contrasena actual no es correcta."))
        }
        ServerAdminPasswords.problem(newPassword)?.let { throw ServerAdminException("WEAK_PASSWORD", it) }
        if (newPassword == currentPassword) throw ServerAdminException("SAME_PASSWORD", tr("La contrasena nueva debe ser distinta de la actual."))
        store.setPassword(admin.userId, ServerAdminPasswords.encode(newPassword), mustChange = false)
        store.revokeSessions(admin.userId, keepTokenHash = tokens.hash(accessToken))
        revokeCompanySessions(admin.userId)
        audit(by.audit, "server_admin.password_changed", details(admin.userId, admin.login))
    }

    /** Password recovery with an email code (AccountService): sets it and ends every session. */
    fun recoverPassword(userId: UUID, password: String) {
        val admin = store.find(userId)?.takeIf { it.usable } ?: throw ServerAdminException("INVALID_CODE", tr("El codigo no es valido o ya vencio. Pide uno nuevo."))
        ServerAdminPasswords.problem(password)?.let { throw ServerAdminException("WEAK_PASSWORD", it) }
        store.setPassword(userId, ServerAdminPasswords.encode(password), mustChange = false)
        endSessions(userId)
        audit(AuditActor(admin.login, userId, AuditActor.ServerAdminKind), "server_admin.password_recovered", details(userId, admin.login))
    }

    private fun endSessions(userId: UUID) {
        store.revokeSessions(userId)
        revokeCompanySessions(userId)
    }

    private fun require(id: String): ServerAdminRow {
        val userId = runCatching { UUID.fromString(id.trim()) }.getOrNull()
            ?: throw ServerAdminException("NOT_FOUND", tr("Administrador no encontrado."), 404)
        return store.find(userId) ?: throw ServerAdminException("NOT_FOUND", tr("Administrador no encontrado."), 404)
    }

    private fun profileProblem(displayName: String, email: String) {
        UserProfileRules.problem(displayName, email)?.let { throw ServerAdminException("INVALID_PROFILE", it.replaceFirstChar(Char::uppercase)) }
    }

    private fun permissionsOf(ids: List<String>): Set<ServerPermission> = ids.map { it.trim() }.toSet().map { id ->
        ServerPermission.of(id) ?: throw ServerAdminException("INVALID_PERMISSION", tr("Permiso desconocido: {0}", id))
    }.toSet()

    private fun companiesOf(allCompanies: Boolean, codes: List<String>): Set<String> {
        if (allCompanies) return emptySet()
        val wanted = codes.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        val existing = store.existingCompanies(wanted)
        (wanted - existing).firstOrNull()?.let { throw ServerAdminException("INVALID_COMPANY", tr("La empresa {0} no existe.", it)) }
        return wanted
    }

    private fun Set<ServerPermission>.ids(): String = map { it.id }.sorted().joinToString(",")

    private fun details(userId: UUID, login: String, vararg pairs: Pair<String, String>): String =
        kotlinx.serialization.json.buildJsonObject {
            put("adminUserId", kotlinx.serialization.json.JsonPrimitive(userId.toString()))
            put("login", kotlinx.serialization.json.JsonPrimitive(login))
            pairs.forEach { (key, value) -> put(key, kotlinx.serialization.json.JsonPrimitive(value)) }
        }.toString()

    private fun ServerAdminRow.response() = ServerAdminResponse(
        id = userId.toString(),
        login = login,
        displayName = displayName.orEmpty(),
        email = email.orEmpty(),
        active = active,
        mustChangePassword = mustChangePassword,
        permissions = permissions.map { it.id }.sortedBy { ServerPermissionIds.All.indexOf(it) },
        allCompanies = allCompanies,
        companies = companies.sorted(),
        createdAt = createdAt.toString(),
        updatedAt = updatedAt.toString(),
    )
}
