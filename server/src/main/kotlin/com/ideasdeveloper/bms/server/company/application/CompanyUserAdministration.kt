package com.ideasdeveloper.bms.server.company.application

import com.ideasdeveloper.bms.server.activity.ActivityActions
import com.ideasdeveloper.bms.server.activity.ActivityActor
import com.ideasdeveloper.bms.server.activity.ActivityChange
import com.ideasdeveloper.bms.server.activity.ActivityChanges
import com.ideasdeveloper.bms.server.activity.CoreActivity
import com.ideasdeveloper.bms.server.admins.ServerOwnerAuthorizer
import com.ideasdeveloper.bms.server.i18n.I18n
import com.ideasdeveloper.bms.server.i18n.companyLanguage
import com.ideasdeveloper.bms.server.i18n.setCompanyLanguage
import com.ideasdeveloper.bms.server.i18n.tr
import com.ideasdeveloper.bms.server.auth.infrastructure.database.LoginDatabases
import com.ideasdeveloper.bms.server.auth.application.UserProfileRules
import com.ideasdeveloper.bms.server.auth.application.ApplicationUserCodes
import com.ideasdeveloper.bms.server.auth.infrastructure.security.ApplicationPasswordHasher
import com.ideasdeveloper.bms.server.auth.infrastructure.security.SessionTokenGenerator
import com.ideasdeveloper.bms.server.company.api.CompanyBackupResponse
import com.ideasdeveloper.bms.server.company.api.CompanyProfileResponse
import com.ideasdeveloper.bms.server.company.api.CompanyPermissionResponse
import com.ideasdeveloper.bms.server.company.api.CompanyUserResponse
import com.ideasdeveloper.bms.server.company.api.CompanyUsersResponse
import com.ideasdeveloper.bms.server.company.api.CreateCompanyUserRequest
import com.ideasdeveloper.bms.server.company.api.UpdateCompanyUserPermissionsRequest
import com.ideasdeveloper.bms.server.auth.application.PasswordAttemptLimiter
import com.ideasdeveloper.bms.server.auth.application.PasswordConfirmation
import com.ideasdeveloper.bms.server.company.api.UpdateCompanyUserRequest
import com.ideasdeveloper.bms.server.company.api.UpdateBusinessOwnerRequest
import com.ideasdeveloper.bms.server.company.api.BusinessOwnerResponse
import com.ideasdeveloper.bms.server.company.api.UpdateCompanyProfileRequest
import com.ideasdeveloper.bms.server.i18n.trIn
import com.ideasdeveloper.bms.server.modules.CompanyMail
import com.ideasdeveloper.bms.server.modules.CompanyMailForm
import com.ideasdeveloper.bms.server.modules.CompanyMailInput
import com.ideasdeveloper.bms.server.modules.MailException
import com.ideasdeveloper.bms.server.modules.ModuleRegistry
import com.ideasdeveloper.bms.server.admins.AuditActor
import com.ideasdeveloper.bms.server.admins.ServerActorKind
import com.ideasdeveloper.bms.server.admins.ServerActorLookup
import com.ideasdeveloper.bms.server.admins.ServerActorResolver
import com.ideasdeveloper.bms.server.admins.ServerPermission
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import com.ideasdeveloper.bms.server.security.MasterKeySecretBox
import com.ideasdeveloper.bms.server.security.SecretBox
import com.ideasdeveloper.bms.server.security.SecretsUnavailableException
import org.postgresql.ds.PGSimpleDataSource
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.sql.SQLException
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.sql.DataSource
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.fileSize
import kotlin.io.path.inputStream
import kotlin.io.path.name
import kotlin.io.path.outputStream

// Administracion de los usuarios de la empresa (alta, edicion, permisos, doble verificacion) y del personal del espacio del servidor.
// Colaborador interno de CompanySelfService, que conserva la API publica y la autorizacion.
internal class CompanyUserAdministration(
    private val owner: CompanySelfService,
    private val central: DataSource,
    private val loginDatabases: LoginDatabases,
    private val catalog: CompanyPermissionCatalog,
    private val ownerEditor: ((companyCode: String, request: UpdateBusinessOwnerRequest) -> BusinessOwnerResponse)?,
    private val verifyOwnerPassword: ((companyCode: String, username: String, password: String) -> Boolean)?,
    private val passwordAttempts: PasswordAttemptLimiter,
    private val twoFactorResets: com.ideasdeveloper.bms.server.auth.twofactor.TwoFactorResetService?,
    private val tokenGenerator: SessionTokenGenerator,
) {
    private val postgresRolePattern = Regex("[a-z][a-z0-9_]{0,62}")

    /**
     * The business owner turns off the two-step verification of another user of the company (TWO_FACTOR.md §8):
     * only with their own session (never one opened from the server switcher, nor in the server workspace) and
     * never for themself.
     */
    fun disableUserTwoFactor(companyCode: String, accessToken: String, userId: UUID, currentPassword: String?): com.ideasdeveloper.bms.server.auth.twofactor.TwoFactorResetResponse {
        val resets = twoFactorResets ?: throw CompanySelfException(tr("La verificacion en dos pasos no esta disponible en este servidor."))
        if (companyCode.trim() == ServerWorkspace.Code) throw CompanySelfException(tr("Debes iniciar sesion como business_owner."))
        val context = authorizeBusinessOwner(companyCode, accessToken)
        val authority = resetAuthority(context, accessToken)
            ?: throw CompanySelfException(tr("Necesitas el permiso {0}.", ServerPermission.UsersTwoFactorReset.id))
        val isOwnerRow = userId == context.businessOwnerUserId
        if (!authority.mayTarget(isOwnerRow)) {
            throw CompanySelfException(if (isOwnerRow && !authority.server) tr("No puedes desactivar asi tu propia verificacion en dos pasos.") else tr("Una empresa de este business owner no esta en tu alcance."))
        }
        resets.checkActor(authority.actor, accessToken, currentPassword)
        return resets.reset(authority.actor, context.companyCode, userId)
    }

    /**
     * Who may reset two-step verification through a company session (TWO_FACTOR.md §8.1), or null:
     * - the business owner's own session: acts as that owner, never on the owner row;
     * - a session of the business-owner switcher (owner of another company of the group): the switcher treats
     *   both owners as one person, so it acts as that owner too, but with its OWN account (password, second-step
     *   mark of this session) and never on the owner row of either company;
     * - a session of the server switcher: acts as the server person (owner, or administrator holding
     *   server.users.two_factor_reset with this company in scope), password of the server account; it may target
     *   the business owner when the whole owner group is in scope.
     */
    private fun resetAuthority(context: CompanySelfService.BusinessOwnerContext, accessToken: String): ResetAuthority? {
        val ownerId = context.businessOwnerUserId ?: return null
        val role = context.businessOwnerRole.orEmpty()
        val code = context.companyCode
        val opener = loginDatabases.sessionOpener(code, accessToken)
        return when (opener?.kind) {
            null -> ResetAuthority(
                com.ideasdeveloper.bms.server.auth.twofactor.TwoFactorResetService.Actor(AuditActor.businessOwner(role), ownerId, role, context.activity.name, code, context.activity),
                server = false,
            ) { isOwnerRow -> !isOwnerRow }
            com.ideasdeveloper.bms.server.auth.infrastructure.database.SessionOpener.Company -> {
                val origin = opener.companyCode ?: return null
                val login = loginDatabases.loginOf(origin, opener.userId) ?: return null
                ResetAuthority(
                    com.ideasdeveloper.bms.server.auth.twofactor.TwoFactorResetService.Actor(
                        AuditActor.businessOwner(login), opener.userId, login, context.activity.name, origin, context.activity, sessionCompanyCode = code,
                    ),
                    server = false,
                ) { isOwnerRow -> !isOwnerRow }
            }
            com.ideasdeveloper.bms.server.auth.infrastructure.database.SessionOpener.Server -> {
                val actor = (ServerOwnerAuthorizer.liveActor(loginDatabases.admins, central, opener.userId) as? ServerActorLookup.Found)?.actor ?: return null
                if (!actor.isOwner && !(actor.has(ServerPermission.UsersTwoFactorReset) && actor.mayActOn(code))) return null
                val groupInScope = actor.isOwner || actor.companyScope == null || (loginDatabases.ownerGroupOf(code) + code).all { actor.mayActOn(it) }
                val name = actor.displayName?.takeIf { it.isNotBlank() } ?: actor.login
                ResetAuthority(
                    com.ideasdeveloper.bms.server.auth.twofactor.TwoFactorResetService.Actor(actor.audit, actor.userId, actor.login, name, null, context.activity, sessionCompanyCode = code),
                    server = true,
                ) { isOwnerRow -> !isOwnerRow || groupInScope }
            }
            else -> null
        }
    }

    private class ResetAuthority(
        val actor: com.ideasdeveloper.bms.server.auth.twofactor.TwoFactorResetService.Actor,
        val server: Boolean,
        val mayTarget: (isOwnerRow: Boolean) -> Boolean,
    )

    fun users(companyCode: String, accessToken: String): CompanyUsersResponse {
        val context = authorizeUserAdministrator(companyCode, accessToken)
        // What this caller may reset (TWO_FACTOR.md §8.4): the same rules as the reset itself.
        val authority = if (context.businessOwnerUserId == null || twoFactorResets == null) null else runCatching { resetAuthority(context, accessToken) }.getOrNull()
        tenantConnection(context.tenant).use { connection ->
            catalog.syncPermissionCatalog(connection)
            return CompanyUsersResponse(
                users = catalog.loadUsers(connection).map { user ->
                    user.copy(canResetTwoFactor = user.twoFactorEnabled?.let { enabled ->
                        enabled && authority?.mayTarget(user.userId == context.businessOwnerUserId.toString()) == true
                    })
                },
                permissions = catalog.loadPermissions(connection),
            )
        }
    }

    fun createUser(companyCode: String, accessToken: String, request: CreateCompanyUserRequest): CompanyUserResponse {
        val context = authorizeUserAdministrator(companyCode, accessToken)
        val username = request.username.trim()
        if (!postgresRolePattern.matches(username)) {
            throw CompanySelfException(tr("El usuario debe iniciar con una letra minuscula y usar solo letras, numeros o guion bajo."))
        }
        if (request.password.length < 12) {
            throw CompanySelfException(tr("La contrasena del usuario debe tener al menos 12 caracteres."))
        }
        UserProfileRules.problem(request.displayName, request.email)?.let { throw CompanySelfException(tr("Usuario: {0}", it)) }

        val userId = UUID.randomUUID()
        val passwordHash = ApplicationPasswordHasher.hash(request.password)
        val details = auditDetails("username" to username, "userId" to userId.toString(), "permissions" to request.permissions.sorted())
        // In the workspace: audit row and who created the account (server_workspace_staff) go in the
        // central transaction, written before the account exists and committed right after it.
        return context.audited("server_workspace.user_created", details) { audit ->
            audit?.let { recordStaffCredentials(it, userId, context.serverActor) }
            tenantConnection(context.tenant).use { connection ->
                catalog.syncPermissionCatalog(connection)
                catalog.validatePermissions(connection, request.permissions)
                connection.autoCommit = false
                try {
                    if (connection.existsUser(username)) {
                        throw CompanySelfException(tr("Ya existe un usuario con ese nombre."))
                    }
                    // Sign-in also accepts a PostgreSQL role's password for the same name.
                    if (connection.existsRole(username)) {
                        throw CompanySelfException(tr("Ese nombre de usuario esta reservado; elige otro."))
                    }
                    connection.prepareStatement(
                        "INSERT INTO application_users (id, postgres_role, is_active, display_name, email) VALUES (?, ?, TRUE, ?, ?)"
                    ).use { insert ->
                        insert.setObject(1, userId)
                        insert.setString(2, username)
                        insert.setString(3, request.displayName.trim())
                        insert.setString(4, request.email.trim())
                        insert.executeUpdate()
                    }
                    connection.prepareStatement(
                        """
                        INSERT INTO application_user_credentials (user_id, password_salt, password_hash, iterations)
                        VALUES (?, ?, ?, ?)
                        """.trimIndent()
                    ).use { insert ->
                        insert.setObject(1, userId)
                        insert.setString(2, passwordHash.salt)
                        insert.setString(3, passwordHash.hash)
                        insert.setInt(4, passwordHash.iterations)
                        insert.executeUpdate()
                    }
                    catalog.replaceUserPermissions(connection, userId, request.permissions)
                    context.record(
                        connection, "user", userId.toString(), username, ActivityActions.Created, "Creo el usuario $username.",
                        ActivityChanges.created(
                            mapOf("username" to username, "displayName" to request.displayName.trim(), "email" to request.email.trim(), "active" to true),
                            UserLabels,
                        ),
                    )
                    catalog.permissionRows(connection, context, userId, username, emptySet(), request.permissions.toSet())
                    connection.commit()
                } catch (failure: Exception) {
                    connection.rollback()
                    throw failure
                } finally {
                    connection.autoCommit = true
                }
                catalog.loadUser(connection, userId) ?: throw CompanySelfException(tr("No se pudo cargar el usuario creado."))
            }
        }
    }

    fun updateUserPermissions(
        companyCode: String,
        accessToken: String,
        userId: String,
        request: UpdateCompanyUserPermissionsRequest,
    ): CompanyUserResponse {
        val context = authorizeUserAdministrator(companyCode, accessToken)
        val targetUserId = try {
            UUID.fromString(userId)
        } catch (_: IllegalArgumentException) {
            throw CompanySelfException(tr("Usuario invalido."))
        }
        if (targetUserId == context.businessOwnerUserId) {
            throw CompanySelfException(tr("No puedes quitar permisos del business_owner desde este flujo."))
        }
        val details = auditDetails("userId" to targetUserId.toString(), "permissions" to request.permissions.sorted())
        return context.audited("server_workspace.user_permissions_updated", details) {
            tenantConnection(context.tenant).use { connection ->
                catalog.syncPermissionCatalog(connection)
                catalog.validatePermissions(connection, request.permissions)
                connection.autoCommit = false
                try {
                    if (!connection.userExists(targetUserId)) {
                        throw CompanySelfException(tr("Usuario no encontrado."))
                    }
                    val before = catalog.loadUserPermissions(connection, targetUserId).toSet()
                    catalog.replaceUserPermissions(connection, targetUserId, request.permissions)
                    catalog.permissionRows(connection, context, targetUserId, connection.loginOf(targetUserId), before, request.permissions.toSet())
                    connection.commit()
                } catch (failure: Exception) {
                    connection.rollback()
                    throw failure
                } finally {
                    connection.autoCommit = true
                }
                catalog.loadUser(connection, targetUserId) ?: throw CompanySelfException(tr("Usuario no encontrado."))
            }
        }
    }

    /**
     * Every field of a user: user, name, email, active, permissions and, when given, a new password.
     * The business owner edits themself here too (their user and password are a PostgreSQL role, so
     * that goes through [ownerEditor]); their permissions and active state do not change.
     */
    fun updateUser(companyCode: String, accessToken: String, userId: String, request: UpdateCompanyUserRequest): CompanyUserResponse {
        val context = authorizeUserAdministrator(companyCode, accessToken)
        val targetUserId = try {
            UUID.fromString(userId)
        } catch (_: IllegalArgumentException) {
            throw CompanySelfException(tr("Usuario invalido."))
        }
        val username = request.username.trim()
        val displayName = request.displayName.trim()
        val email = request.email.trim()
        val password = request.password
        if (!postgresRolePattern.matches(username)) {
            throw CompanySelfException(tr("El usuario debe iniciar con una letra minuscula y usar solo letras, numeros o guion bajo."))
        }
        UserProfileRules.problem(displayName, email)?.let { throw CompanySelfException(tr("Usuario: {0}", it)) }
        if (password.isNotEmpty() && password.length < 12) {
            throw CompanySelfException(tr("La contrasena del usuario debe tener al menos 12 caracteres."))
        }
        if (targetUserId == context.businessOwnerUserId) {
            // The owner's user, email and password are shared by every company of that owner: a server
            // administrator who entered this company from the switcher cannot change them from here.
            if (loginDatabases.openedByServerAdmin(context.companyCode, accessToken)) {
                throw CompanySelfException(tr("Un administrador del servidor no puede editar al business owner desde la empresa."))
            }
            val editor = ownerEditor ?: throw CompanySelfException(tr("El business owner no se puede editar en este servidor."))
            // El business owner cambia SU PROPIO correo: exige la contrasena actual (misma regla que /auth/profile).
            val storedEmail = tenantConnection(context.tenant).use { connection ->
                connection.prepareStatement("SELECT email FROM application_users WHERE id = ?").use { query ->
                    query.setObject(1, targetUserId)
                    query.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
                }
            }
            // Tambien al poner una contrasena nueva: con una sesion robada no se debe poder tomar la cuenta.
            // Solo aqui (quien firma es el propio business owner); un administrador que edita a otro usuario no pasa por esta rama.
            val sensitiveChange = PasswordConfirmation.ownerChangeNeedsPassword(storedEmail, email, password)
            if (sensitiveChange) {
                val role = context.businessOwnerRole.orEmpty()
                PasswordConfirmation.require(
                    request.currentPassword, passwordAttempts, "${context.companyCode}/$role",
                    tr("Escribe tu contrasena actual para cambiar el correo o la contrasena."),
                ) { current -> verifyOwnerPassword?.invoke(context.companyCode, role, current) ?: false }
            }
            if (sensitiveChange) {
                // Se cierran las demas sesiones del business owner (la actual se conserva) ANTES de editar: el editor
                // usa su propia transaccion, y revocar de mas si la edicion falla es seguro; no revocar, no.
                tenantConnection(context.tenant).use { connection ->
                    connection.prepareStatement(
                        "UPDATE application_sessions SET revoked_at = CURRENT_TIMESTAMP WHERE user_id = ? AND revoked_at IS NULL AND token_hash <> ?"
                    ).use { revoke ->
                        revoke.setObject(1, targetUserId)
                        revoke.setString(2, tokenGenerator.hash(accessToken))
                        revoke.executeUpdate()
                    }
                }
            }
            // The owner's row is written first in an open company transaction, committed once the editor finished.
            context.logged({ tenant ->
                val before = tenant.prepareStatement("SELECT postgres_role, display_name, email FROM application_users WHERE id = ?").use { query ->
                    query.setObject(1, targetUserId)
                    query.executeQuery().use { rows -> if (rows.next()) Triple(rows.getString(1), rows.getString(2), rows.getString(3)) else null }
                }
                val edited = ActivityChanges.diff(
                    mapOf("username" to before?.first, "displayName" to before?.second, "email" to before?.third),
                    mapOf("username" to username, "displayName" to displayName, "email" to email),
                    UserLabels,
                )
                if (edited.isNotEmpty()) {
                    context.record(tenant, "user", targetUserId.toString(), username, ActivityActions.Updated, "Modifico los datos del business owner $username.", edited)
                }
                if (password.isNotEmpty()) {
                    context.record(tenant, "user", targetUserId.toString(), username, ActivityActions.PasswordChanged, "Cambio la contrasena del business owner $username.",
                        listOf(ActivityChange.secret("password", "Contrasena")))
                }
            }) { editor(context.companyCode, UpdateBusinessOwnerRequest(username, displayName, email, password)) }
            return tenantConnection(context.tenant).use { connection -> catalog.loadUser(connection, targetUserId) }
                ?: throw CompanySelfException(tr("Usuario no encontrado."))
        }
        // Whoever sets a password, or the email that password recovery goes to, controls the account
        // from now on: in the workspace it follows that server user (closed with them).
        val emailChanged = tenantConnection(context.tenant).use { connection ->
            connection.prepareStatement("SELECT email FROM application_users WHERE id = ?").use { query ->
                query.setObject(1, targetUserId)
                query.executeQuery().use { rows -> rows.next() && com.ideasdeveloper.bms.server.auth.application.EmailChangeRule.isChange(rows.getString(1), email) }
            }
        }
        val takesCredentials = password.isNotEmpty() || emailChanged
        // A swept account (its creator lost the workspace or was closed) comes back only with a new
        // password, so whoever knew the old credentials does not.
        val reactivatingSwept = request.isActive && context.serverActor != null &&
            isInactiveUser(context.tenant, targetUserId) && staffRowIsStale(targetUserId)
        if (reactivatingSwept && password.isEmpty()) {
            throw CompanySelfException(tr("Esta cuenta se desactivo porque el administrador que la creo perdio el acceso; para reactivarla escribe una contrasena nueva."))
        }
        val reassigning = reactivatingSwept
        val details = auditDetails(
            "userId" to targetUserId.toString(), "username" to username, "active" to request.isActive,
            "passwordChanged" to password.isNotEmpty(), "emailChanged" to emailChanged, "permissions" to request.permissions.sorted(),
        )
        return context.audited("server_workspace.user_updated", details) { audit ->
            if (audit != null && takesCredentials) recordStaffCredentials(audit, targetUserId, context.serverActor)
            // The owner resetting a swept account takes it over: nobody else knows its credentials.
            if (audit != null && reassigning && context.serverActor?.kind == AuditActor.ServerOwnerKind) {
                audit.prepareStatement("DELETE FROM server_workspace_staff WHERE user_id = ?").use { delete ->
                    delete.setObject(1, targetUserId)
                    delete.executeUpdate()
                }
            }
            tenantConnection(context.tenant).use { connection ->
                catalog.syncPermissionCatalog(connection)
                catalog.validatePermissions(connection, request.permissions)
                connection.autoCommit = false
                try {
                    val current = connection.prepareStatement("SELECT postgres_role, is_active FROM application_users WHERE id = ?").use { query ->
                        query.setObject(1, targetUserId)
                        query.executeQuery().use { rows -> if (rows.next()) rows.getString(1) to rows.getBoolean(2) else null }
                    } ?: throw CompanySelfException(tr("Usuario no encontrado."))
                    val beforeProfile = connection.prepareStatement("SELECT display_name, email FROM application_users WHERE id = ?").use { query ->
                        query.setObject(1, targetUserId)
                        query.executeQuery().use { rows -> if (rows.next()) rows.getString(1) to rows.getString(2) else null to null }
                    }
                    val beforePermissions = catalog.loadUserPermissions(connection, targetUserId).toSet()
                    if (connection.isBusinessOwner(targetUserId)) {
                        throw CompanySelfException(tr("Solo el business owner o el dueno del servidor pueden editar al business owner."))
                    }
                    if (username != current.first) {
                        if (connection.existsUser(username)) throw CompanySelfException(tr("Ya existe un usuario con ese nombre."))
                        // Sign-in also accepts a PostgreSQL role's password for the same name.
                        if (connection.existsRole(username)) throw CompanySelfException(tr("Ese nombre de usuario esta reservado; elige otro."))
                    }
                    connection.prepareStatement(
                        "UPDATE application_users SET postgres_role = ?, display_name = ?, email = ?, is_active = ? WHERE id = ?"
                    ).use { update ->
                        update.setString(1, username)
                        update.setString(2, displayName)
                        update.setString(3, email)
                        update.setBoolean(4, request.isActive)
                        update.setObject(5, targetUserId)
                        update.executeUpdate()
                    }
                    catalog.replaceUserPermissions(connection, targetUserId, request.permissions)
                    if (password.isNotEmpty()) {
                        val hash = ApplicationPasswordHasher.hash(password)
                        connection.prepareStatement(
                            """
                            INSERT INTO application_user_credentials (user_id, password_salt, password_hash, iterations)
                            VALUES (?, ?, ?, ?)
                            ON CONFLICT (user_id) DO UPDATE SET password_salt = EXCLUDED.password_salt,
                                password_hash = EXCLUDED.password_hash, iterations = EXCLUDED.iterations
                            """.trimIndent()
                        ).use { upsert ->
                            upsert.setObject(1, targetUserId)
                            upsert.setString(2, hash.salt)
                            upsert.setString(3, hash.hash)
                            upsert.setInt(4, hash.iterations)
                            upsert.executeUpdate()
                        }
                    }
                    val edited = ActivityChanges.diff(
                        mapOf("username" to current.first, "displayName" to beforeProfile.first, "email" to beforeProfile.second),
                        mapOf("username" to username, "displayName" to displayName, "email" to email),
                        UserLabels,
                    )
                    if (edited.isNotEmpty()) {
                        context.record(connection, "user", targetUserId.toString(), username, ActivityActions.Updated, "Modifico el usuario $username.", edited)
                    }
                    if (current.second != request.isActive) {
                        context.record(
                            connection, "user", targetUserId.toString(), username, ActivityActions.StatusChanged,
                            if (request.isActive) "Activo al usuario $username." else "Desactivo al usuario $username.",
                            listOf(ActivityChange.of("active", "Activo", current.second, request.isActive)),
                        )
                    }
                    if (password.isNotEmpty()) {
                        context.record(connection, "user", targetUserId.toString(), username, ActivityActions.PasswordChanged, "Puso una contrasena nueva al usuario $username.",
                            listOf(ActivityChange.secret("password", "Contrasena")))
                    }
                    catalog.permissionRows(connection, context, targetUserId, username, beforePermissions, request.permissions.toSet())
                    // A new password or a deactivated user closes the user's open sessions.
                    if (password.isNotEmpty() || (current.second && !request.isActive)) {
                        connection.prepareStatement(
                            "UPDATE application_sessions SET revoked_at = CURRENT_TIMESTAMP WHERE user_id = ? AND revoked_at IS NULL"
                        ).use { revoke ->
                            revoke.setObject(1, targetUserId)
                            revoke.executeUpdate()
                        }
                    }
                    connection.commit()
                } catch (failure: Exception) {
                    connection.rollback()
                    throw failure
                } finally {
                    connection.autoCommit = true
                }
                catalog.loadUser(connection, targetUserId) ?: throw CompanySelfException(tr("Usuario no encontrado."))
            }
        }
    }

    /**
     * Records that [by] knows the credentials of workspace staff account [userId] (it created the
     * account or set its password), on the central transaction [connection]. A server user without
     * a central id (cannot happen for administrators) clears the row instead.
     */
    private fun recordStaffCredentials(connection: java.sql.Connection, userId: UUID, by: AuditActor?) {
        val creator = by?.userId
        if (creator == null || by.kind !in setOf(AuditActor.ServerOwnerKind, AuditActor.ServerAdminKind)) {
            connection.prepareStatement("DELETE FROM server_workspace_staff WHERE user_id = ?").use { delete ->
                delete.setObject(1, userId)
                delete.executeUpdate()
            }
            return
        }
        connection.prepareStatement(
            """
            INSERT INTO server_workspace_staff (user_id, created_by, created_by_kind) VALUES (?, ?, ?)
            ON CONFLICT (user_id) DO UPDATE SET created_by = EXCLUDED.created_by, created_by_kind = EXCLUDED.created_by_kind, updated_at = CURRENT_TIMESTAMP
            """.trimIndent()
        ).use { upsert ->
            upsert.setObject(1, userId)
            upsert.setObject(2, creator)
            upsert.setString(3, by.kind)
            upsert.executeUpdate()
        }
    }

    private fun isInactiveUser(tenant: com.ideasdeveloper.bms.server.auth.infrastructure.database.TenantDatabaseConfig, userId: UUID): Boolean =
        tenantConnection(tenant).use { connection ->
            connection.prepareStatement("SELECT is_active FROM application_users WHERE id = ?").use { query ->
                query.setObject(1, userId)
                query.executeQuery().use { rows -> rows.next() && !rows.getBoolean(1) }
            }
        }

    /**
     * Whether workspace staff account [userId] has a tracking row whose creator no longer holds the
     * right to know its credentials: not an active server owner and not a usable administrator with
     * server.workspace. Without the table (before control V015) there is no row.
     */
    private fun staffRowIsStale(userId: UUID): Boolean = try {
        central.connection.use { connection ->
            connection.prepareStatement(
                """
                SELECT 1 FROM server_workspace_staff staff
                WHERE staff.user_id = ?
                  AND NOT EXISTS (
                      SELECT 1 FROM server_owners owners JOIN application_users users ON users.id = owners.user_id
                      WHERE owners.user_id = staff.created_by AND users.is_active)
                  AND NOT EXISTS (
                      SELECT 1 FROM server_admins admins JOIN application_users users ON users.id = admins.user_id
                      JOIN server_admin_permissions permissions ON permissions.user_id = admins.user_id
                      WHERE admins.user_id = staff.created_by AND admins.is_active AND users.is_active
                        AND permissions.permission = ?)
                """.trimIndent()
            ).use { query ->
                query.setObject(1, userId)
                query.setString(2, com.ideasdeveloper.bms.ServerPermissionIds.Workspace)
                query.executeQuery().use { it.next() }
            }
        }
    } catch (failure: SQLException) {
        if (failure.sqlState == "42P01") false else throw failure
    }

    /**
     * An administrator was deactivated, deleted or lost server.workspace: the workspace staff
     * accounts whose credentials they know (server_workspace_staff) are deactivated and their
     * sessions revoked, audited as [by] (the owner). Returns how many accounts it closed.
     */
    fun deactivateWorkspaceStaffOf(adminUserId: UUID, by: AuditActor, reason: String): Int {
        val staff: List<UUID> = try {
            central.connection.use { connection ->
                connection.prepareStatement("SELECT user_id FROM server_workspace_staff WHERE created_by = ? ORDER BY user_id").use { query ->
                    query.setObject(1, adminUserId)
                    query.executeQuery().use { rows -> buildList<UUID> { while (rows.next()) add(rows.getObject(1, UUID::class.java)) } }
                }
            }
        } catch (failure: SQLException) {
            // Before control V015 there is no such table: nobody to close.
            if (failure.sqlState == "42P01") return 0 else throw failure
        }
        if (staff.isEmpty()) return 0
        val workspace = central.connection.use { connection ->
            connection.prepareStatement("SELECT code, database_name FROM companies WHERE is_server_workspace").use { query ->
                query.executeQuery().use { rows -> if (rows.next()) rows.getString(1) to rows.getString(2) else null }
            }
        } ?: return 0
        val tenant = loginDatabases.tenantConfig(workspace.second)
            ?: throw CompanySelfException(tr("El espacio del servidor no tiene conexion configurada."))
        val context = CompanySelfService.BusinessOwnerContext(workspace.first, workspace.second, tenant, CompanyProfileResponse(workspace.first, workspace.first, null, "#000000", "#000000", "#000000"), null, null, by)
        val details = auditDetails("adminUserId" to adminUserId.toString(), "userIds" to staff.map { it.toString() }, "reason" to reason)
        return context.audited("server_workspace.staff_deactivated", details) {
            tenantConnection(tenant).use { connection ->
                connection.autoCommit = false
                try {
                    val ids = connection.createArrayOf("uuid", staff.toTypedArray())
                    val closed = connection.prepareStatement("UPDATE application_users SET is_active = FALSE WHERE id = ANY (?) AND is_active RETURNING id, postgres_role").use { update ->
                        update.setArray(1, ids)
                        update.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getObject(1, UUID::class.java) to rows.getString(2)) } }
                    }
                    val revoked = connection.prepareStatement("UPDATE application_sessions SET revoked_at = CURRENT_TIMESTAMP WHERE user_id = ANY (?) AND revoked_at IS NULL RETURNING user_id").use { revoke ->
                        revoke.setArray(1, ids)
                        revoke.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getObject(1, UUID::class.java)) } }
                    }
                    // The workspace's own log, in the same transaction: one row per account closed, with its sessions.
                    val actor = ActivityActor.fromAudit(by, by.userId?.let { loginDatabases.serverPerson(it)?.name })
                    closed.forEach { (userId, username) ->
                        val sessions = revoked.count { it == userId }
                        CoreActivity.record(
                            connection, actor, "user", userId.toString(), username, ActivityActions.StatusChanged,
                            "Desactivo al usuario $username y cerro sus sesiones abiertas ($sessions): el administrador del servidor que conoce su contrasena ya no tiene acceso ($reason).",
                            listOf(ActivityChange.of("active", "Activo", true, false)),
                        )
                    }
                    connection.commit()
                    closed.size
                } catch (failure: Exception) {
                    connection.rollback()
                    throw failure
                } finally {
                    connection.autoCommit = true
                }
            }
        }
    }

    private fun authorizeBusinessOwner(companyCode: String, accessToken: String) = owner.authorizeBusinessOwner(companyCode, accessToken)

    private fun authorizeUserAdministrator(companyCode: String, accessToken: String) = owner.authorizeUserAdministrator(companyCode, accessToken)

    private fun tenantConnection(tenant: com.ideasdeveloper.bms.server.auth.infrastructure.database.TenantDatabaseConfig) = owner.tenantConnection(tenant)

    private fun <T> CompanySelfService.BusinessOwnerContext.audited(action: String, details: String, change: (java.sql.Connection?) -> T): T =
        owner.auditedFor(this, action, details, change)

    private fun <T> CompanySelfService.BusinessOwnerContext.logged(rows: (java.sql.Connection) -> Unit, change: (java.sql.Connection) -> T): T =
        owner.loggedFor(this, rows, change)

    private fun CompanySelfService.BusinessOwnerContext.record(
        connection: java.sql.Connection, entityType: String, entityId: String?, label: String, action: String, summary: String,
        changes: List<ActivityChange> = emptyList(),
    ) = owner.recordFor(this, connection, entityType, entityId, label, action, summary, changes)
}

private val UserLabels = mapOf("username" to "Usuario", "displayName" to "Nombre", "email" to "Correo", "active" to "Activo")
