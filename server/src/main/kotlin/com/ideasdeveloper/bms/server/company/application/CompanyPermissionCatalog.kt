package com.ideasdeveloper.bms.server.company.application

import com.ideasdeveloper.bms.server.activity.ActivityActions
import com.ideasdeveloper.bms.server.activity.ActivityActor
import com.ideasdeveloper.bms.server.activity.ActivityChange
import com.ideasdeveloper.bms.server.activity.ActivityChanges
import com.ideasdeveloper.bms.server.activity.CoreActivity
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

// Catalogo de permisos de la empresa y lectura/escritura de usuarios y sus permisos en la base tenant.
// Colaborador interno de CompanySelfService.
internal class CompanyPermissionCatalog(
    private val owner: CompanySelfService,
    private val moduleRegistry: ModuleRegistry,
    private val loginDatabases: LoginDatabases,
) {
    internal fun syncPermissionCatalog(connection: java.sql.Connection) {
        val enabledModuleIds = connection.prepareStatement(
            "SELECT module_id FROM tenant_modules WHERE status = 'enabled'"
        ).use { query ->
            query.executeQuery().use { rows ->
                buildSet {
                    add("empresa")
                    while (rows.next()) add(rows.getString("module_id"))
                }
            }
        }
        moduleRegistry.definitions.flatMap { definition ->
            definition.permissions.map { permission -> definition.id to permission }
        }.filter { (moduleId, _) -> moduleId in enabledModuleIds }.forEach { (moduleId, permission) ->
            connection.prepareStatement(
                """
                INSERT INTO application_permissions (permission_id, module_id, title, description)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (permission_id)
                DO UPDATE SET module_id = EXCLUDED.module_id, title = EXCLUDED.title, description = EXCLUDED.description
                """.trimIndent()
            ).use { upsert ->
                upsert.setString(1, permission.id)
                upsert.setString(2, moduleId)
                upsert.setString(3, permission.title)
                upsert.setString(4, permission.description)
                upsert.executeUpdate()
            }
        }
    }

    internal fun loadPermissions(connection: java.sql.Connection): List<CompanyPermissionResponse> =
        connection.prepareStatement(
            "SELECT permission_id, module_id, title, description FROM application_permissions ORDER BY module_id, permission_id"
        ).use { query ->
            query.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) {
                        add(
                            CompanyPermissionResponse(
                                permissionId = rows.getString("permission_id"),
                                moduleId = rows.getString("module_id"),
                                // Stored in Spanish from the module definitions; shown in the user's language.
                                title = trIn(I18n.moduleNamespace(rows.getString("module_id")), rows.getString("title")),
                                description = trIn(I18n.moduleNamespace(rows.getString("module_id")), rows.getString("description")),
                            )
                        )
                    }
                }
            }
        }

    internal fun loadUsers(connection: java.sql.Connection): List<CompanyUserResponse> =
        connection.prepareStatement("SELECT id FROM application_users ORDER BY postgres_role").use { query ->
            query.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) {
                        loadUser(connection, rows.getObject("id", UUID::class.java))?.let(::add)
                    }
                }
            }
        }

    internal fun loadUser(connection: java.sql.Connection, userId: UUID): CompanyUserResponse? {
        val user = connection.prepareStatement(
            "SELECT id, postgres_role, is_active, display_name, email FROM application_users WHERE id = ?"
        ).use { query ->
            query.setObject(1, userId)
            query.executeQuery().use { rows ->
                if (!rows.next()) return null
                Triple(rows.getString("postgres_role"), rows.getBoolean("is_active"), rows.getString("display_name") to rows.getString("email"))
            }
        }
        return CompanyUserResponse(
            userId = userId.toString(),
            username = user.first,
            isActive = user.second,
            permissions = loadUserPermissions(connection, userId),
            displayName = user.third.first,
            email = user.third.second,
            userCode = ApplicationUserCodes.ensure(connection, userId),
            isBusinessOwner = connection.isBusinessOwner(userId),
            twoFactorEnabled = runCatching { loginDatabases.twoFactor.takeIf { it.available(connection) }?.isEnabled(connection, userId) }.getOrNull(),
        )
    }

    internal fun loadUserPermissions(connection: java.sql.Connection, userId: UUID): List<String> =
        connection.prepareStatement("SELECT permission_id FROM application_user_permissions WHERE user_id = ? ORDER BY permission_id").use { query ->
            query.setObject(1, userId)
            query.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) add(rows.getString("permission_id"))
                }
            }
        }

    internal fun validatePermissions(connection: java.sql.Connection, permissions: List<String>) {
        val known = loadPermissions(connection).map { it.permissionId }.toSet()
        val unknown = permissions.toSet() - known
        if (unknown.isNotEmpty()) {
            throw CompanySelfException(tr("Permisos no reconocidos: {0}.", unknown.joinToString()))
        }
    }

    internal fun replaceUserPermissions(connection: java.sql.Connection, userId: UUID, permissions: List<String>) {
        connection.prepareStatement("DELETE FROM application_user_permissions WHERE user_id = ?").use { delete ->
            delete.setObject(1, userId)
            delete.executeUpdate()
        }
        permissions.distinct().forEach { permissionId ->
            connection.prepareStatement("INSERT INTO application_user_permissions (user_id, permission_id) VALUES (?, ?)").use { insert ->
                insert.setObject(1, userId)
                insert.setString(2, permissionId)
                insert.executeUpdate()
            }
        }
    }

    // One row per permission granted or removed (ACTIVITY_LOG.md §3), with its title as stored (Spanish).
    internal fun permissionRows(connection: java.sql.Connection, context: CompanySelfService.BusinessOwnerContext, userId: UUID, username: String, before: Set<String>, after: Set<String>) {
        val added = (after - before).sorted()
        val removed = (before - after).sorted()
        if (added.isEmpty() && removed.isEmpty()) return
        val titles = connection.prepareStatement("SELECT permission_id, title FROM application_permissions").use { query ->
            query.executeQuery().use { rows -> buildMap { while (rows.next()) put(rows.getString(1), rows.getString(2)) } }
        }
        added.forEach { permission ->
            val title = titles[permission] ?: permission
            context.record(connection, "user", userId.toString(), username, ActivityActions.PermissionChanged,
                "Dio el permiso \"$title\" al usuario $username.", listOf(ActivityChange(permission, title, "No", "Si")))
        }
        removed.forEach { permission ->
            val title = titles[permission] ?: permission
            context.record(connection, "user", userId.toString(), username, ActivityActions.PermissionChanged,
                "Quito el permiso \"$title\" al usuario $username.", listOf(ActivityChange(permission, title, "Si", "No")))
        }
    }

    private fun CompanySelfService.BusinessOwnerContext.record(
        connection: java.sql.Connection, entityType: String, entityId: String?, label: String, action: String, summary: String,
        changes: List<ActivityChange> = emptyList(),
    ) = owner.recordFor(this, connection, entityType, entityId, label, action, summary, changes)
}

internal fun java.sql.Connection.loginOf(userId: UUID): String =
    prepareStatement("SELECT postgres_role FROM application_users WHERE id = ?").use { query ->
        query.setObject(1, userId)
        query.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else userId.toString() }
    }

internal fun java.sql.Connection.userExists(userId: UUID): Boolean =
    prepareStatement("SELECT 1 FROM application_users WHERE id = ?").use { query ->
        query.setObject(1, userId)
        query.executeQuery().use { rows -> rows.next() }
    }

internal fun java.sql.Connection.isBusinessOwner(userId: UUID): Boolean =
    prepareStatement("SELECT 1 FROM business_owner WHERE user_id = ?").use { query ->
        query.setObject(1, userId)
        query.executeQuery().use { rows -> rows.next() }
    }

internal fun java.sql.Connection.existsRole(name: String): Boolean =
    prepareStatement("SELECT 1 FROM pg_roles WHERE rolname = ?").use { query ->
        query.setString(1, name)
        query.executeQuery().use { rows -> rows.next() }
    }

internal fun java.sql.Connection.existsUser(name: String): Boolean =
    prepareStatement("SELECT 1 FROM application_users WHERE postgres_role = ?").use { query ->
        query.setString(1, name)
        query.executeQuery().use { rows -> rows.next() }
    }
