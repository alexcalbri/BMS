package com.ideasdeveloper.bms.server.company.application

import com.ideasdeveloper.bms.server.i18n.tr
import com.ideasdeveloper.bms.server.admins.AuditActor
import com.ideasdeveloper.bms.server.auth.application.EmailChangeRule
import com.ideasdeveloper.bms.server.modules.PackageInstallQueue
import com.ideasdeveloper.bms.server.storage.CompanyFiles
import com.ideasdeveloper.bms.server.security.ScramSha256
import com.ideasdeveloper.bms.server.company.api.ServerNameResponse
import com.ideasdeveloper.bms.server.auth.infrastructure.database.ServerNameSetting
import com.ideasdeveloper.bms.server.company.api.DuplicateOwnerRequest
import com.ideasdeveloper.bms.server.company.api.BusinessOwnerResponse
import com.ideasdeveloper.bms.server.company.api.UpdateBusinessOwnerRequest
import com.ideasdeveloper.bms.server.modules.SettingsCopy
import com.ideasdeveloper.bms.server.auth.infrastructure.database.TenantDatabaseConfig
import com.ideasdeveloper.bms.server.auth.application.UserProfileRules
import com.ideasdeveloper.bms.server.company.api.CreateCompanyRequest
import com.ideasdeveloper.bms.server.company.api.CreateCompanyResponse
import com.ideasdeveloper.bms.server.auth.infrastructure.database.LoginDatabases
import com.ideasdeveloper.bms.server.company.api.CompanyModuleResponse
import com.ideasdeveloper.bms.server.company.api.CompanySummaryResponse
import com.ideasdeveloper.bms.server.company.api.ServerModuleCatalogResponse
import com.ideasdeveloper.bms.server.company.api.ServerModuleResponse
import com.ideasdeveloper.bms.server.company.api.UpdateCompanyRequest
import com.ideasdeveloper.bms.server.modules.ModuleDefinition
import com.ideasdeveloper.bms.server.modules.ModuleRequirementGate
import com.ideasdeveloper.bms.server.app.BMSVersion
import com.ideasdeveloper.bms.server.modules.ModuleRegistry
import com.ideasdeveloper.bms.server.modules.RemoteModuleCatalog
import com.ideasdeveloper.bms.server.modules.RemoteModulePackage
import com.ideasdeveloper.bms.ModuleVersion
import com.ideasdeveloper.bms.server.i18n.I18n
import com.ideasdeveloper.bms.server.i18n.trIn
import com.ideasdeveloper.bms.server.modules.GitHubLocation
import com.ideasdeveloper.bms.server.modules.ModuleBuildException
import com.ideasdeveloper.bms.server.modules.ModulePackageBuilder
import com.ideasdeveloper.bms.server.modules.ModulePackageInspection
import com.ideasdeveloper.bms.server.modules.ModuleUnsignedAuthorizationResponse
import com.ideasdeveloper.bms.server.modules.ServerModule
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import org.postgresql.ds.PGSimpleDataSource
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.sql.Connection
import java.util.HexFormat
import java.util.UUID
import javax.sql.DataSource
import kotlin.io.path.name

internal class TenantSchemaProvisioner(
    private val central: DataSource,
    private val config: CompanyProvisioningConfig,
    private val loginDatabases: LoginDatabases,
    private val moduleRegistry: ModuleRegistry,
) {
    private val log = LoggerFactory.getLogger(CompanyProvisioningService::class.java)

    internal fun tenantConfig(databaseName: String): TenantDatabaseConfig = TenantDatabaseConfig(
        databaseName = databaseName,
        jdbcUrl = config.tenantJdbcUrlPrefix + databaseName,
        user = config.runtimeUser,
        password = config.runtimePassword,
    )

    /**
     * A short-lived connection for provisioning, as the provisioning login (not the runtime role of the pool), switched
     * to [provisioningRole] (the server owner's role, which owns the company databases and may create databases and
     * roles). The pooled runtime connections never hold those rights (SELF_HOSTING.md, Roles de PostgreSQL).
     */
    internal fun adminConnection(provisioningRole: String): Connection {
        val source = PGSimpleDataSource().apply {
            setURL(config.administrationJdbcUrl)
            setUser(config.adminUser)
            setPassword(config.adminPassword)
        }
        return source.connection.apply {
            setProvisioningRole(provisioningRole)
        }
    }

    internal fun tenantAdminConnection(jdbcUrl: String, provisioningRole: String): Connection {
        val source = PGSimpleDataSource().apply {
            setURL(jdbcUrl)
            setUser(config.adminUser)
            setPassword(config.adminPassword)
        }
        return source.connection.apply {
            setProvisioningRole(provisioningRole)
        }
    }

    internal fun applyTenantCoreMigrations(connection: Connection) {
        listOf(
            "database/core/migrations/V001__create_application_users.sql" to "core",
            "database/core/migrations/V002__create_application_sessions.sql" to "core",
            "database/tenant/migrations/V001__create_business_owner.sql" to "tenant",
            "database/tenant/migrations/V003__create_tenant_modules.sql" to "tenant",
            "database/tenant/migrations/V004__create_application_permissions.sql" to "tenant",
            "database/tenant/migrations/V005__create_application_user_credentials.sql" to "tenant",
        ).forEach { (relativePath, module) ->
            applyMigration(connection, module, relativePath, Files.readString(Path.of(config.migrationsRoot).resolve(relativePath)))
        }
        applyUserProfileMigration(connection)
        applyAccountThrottleMigration(connection)
        applyOutboundMigration(connection)
        applyNotificationsMigration(connection)
        applyExtensionsMigration(connection)
        applyCompanyMailMigration(connection)
        applyLanguageMigrations(connection)
        applyActivityLogMigration(connection)
        applyTwoFactorMigration(connection)
        applyAssistantMigration(connection)
    }

    /**
     * The in-app assistant (tenant V018, GLOBAL_AGENT.md); also brought to existing companies at startup. The runtime
     * role gets every DML right on its tables except the messages, which it only reads and appends (they go with their
     * conversation, by cascade).
     */
    internal fun applyAssistantMigration(connection: Connection) {
        val relativePath = "database/tenant/migrations/V018__create_assistant.sql"
        applyMigration(connection, "tenant", relativePath, Files.readString(Path.of(config.migrationsRoot).resolve(relativePath)))
        listOf("assistant_settings", "assistant_conversations", "assistant_pending_calls", "assistant_usage").forEach { table ->
            grantIfMissing(connection, config.runtimeUser, table, listOf("SELECT", "INSERT", "UPDATE", "DELETE"))
        }
        // Lease tokens, closed conversations and the confirmation preview (tenant V019); no new grants.
        val safety = "database/tenant/migrations/V019__assistant_turn_safety.sql"
        applyMigration(connection, "tenant", safety, Files.readString(Path.of(config.migrationsRoot).resolve(safety)))
        // The fingerprint of the confirmation card an approved call is checked against (tenant V020); no new grants.
        val previewHash = "database/tenant/migrations/V020__assistant_preview_hash.sql"
        applyMigration(connection, "tenant", previewHash, Files.readString(Path.of(config.migrationsRoot).resolve(previewHash)))
        grantIfMissing(connection, config.runtimeUser, "assistant_messages", listOf("SELECT", "INSERT"))
        grantSequenceIfMissing(connection, config.runtimeUser, "assistant_messages", "assistant_messages_id_seq", listOf("USAGE", "SELECT"))
        try {
            revokeIfPresent(connection, config.runtimeUser, "assistant_messages", listOf("UPDATE", "DELETE", "TRUNCATE", "REFERENCES", "TRIGGER"))
        } catch (failure: Exception) {
            log.error("No se pudieron quitar al runtime {} los privilegios de mas sobre assistant_messages", config.runtimeUser, failure)
        }
    }

    // Two-step verification of the company's users (tenant V012, TWO_FACTOR.md); also brought to existing companies at startup.
    internal fun applyTwoFactorMigration(connection: Connection) {
        val relativePath = "database/tenant/migrations/V012__create_two_factor.sql"
        applyMigration(connection, "tenant", relativePath, Files.readString(Path.of(config.migrationsRoot).resolve(relativePath)))
        listOf("application_user_two_factor", "application_user_recovery_codes").forEach { table ->
            grantIfMissing(connection, config.runtimeUser, table, listOf("SELECT", "INSERT", "UPDATE", "DELETE"))
        }
        // Delayed recovery by email (tenant V013, TWO_FACTOR.md §7): the runtime starts, cancels and completes processes.
        val emailRecovery = "database/tenant/migrations/V013__create_two_factor_email_recovery.sql"
        applyMigration(connection, "tenant", emailRecovery, Files.readString(Path.of(config.migrationsRoot).resolve(emailRecovery)))
        grantIfMissing(connection, config.runtimeUser, "application_user_2fa_email_recovery", listOf("SELECT", "INSERT", "UPDATE"))
        // The processes are the record of every recovery: the runtime never deletes them.
        try {
            revokeIfPresent(connection, config.runtimeUser, "application_user_2fa_email_recovery", listOf("DELETE", "TRUNCATE", "REFERENCES", "TRIGGER"))
        } catch (failure: Exception) {
            log.error("No se pudieron quitar al runtime {} los privilegios de mas sobre application_user_2fa_email_recovery", config.runtimeUser, failure)
        }
        // Sessions that passed a second step, and the resend of a lost recovery warning (tenant V014); the runtime
        // already holds its rights on both tables.
        val secondFactorMark = "database/tenant/migrations/V014__session_second_factor_mark.sql"
        applyMigration(connection, "tenant", secondFactorMark, Files.readString(Path.of(config.migrationsRoot).resolve(secondFactorMark)))
        // Who is behind a business-owner switch session (tenant V015), for the agents opened from it.
        val switchOrigin = "database/tenant/migrations/V015__session_switch_origin.sql"
        applyMigration(connection, "tenant", switchOrigin, Files.readString(Path.of(config.migrationsRoot).resolve(switchOrigin)))
        // How a session passed its second step and the authenticator being replaced (tenant V016, TWO_FACTOR.md §9).
        val replace = "database/tenant/migrations/V016__two_factor_replace.sql"
        applyMigration(connection, "tenant", replace, Files.readString(Path.of(config.migrationsRoot).resolve(replace)))
    }

    /**
     * Company activity log (tenant V011, ACTIVITY_LOG.md): append-only, so the runtime role gets only
     * SELECT and INSERT on the log and its chain (the sealing trigger runs as the writer and appends to the
     * chain), plus the id sequence, and loses anything else it might hold on the log. Also brought to
     * existing companies at startup.
     */
    internal fun applyActivityLogMigration(connection: Connection) {
        val relativePath = "database/tenant/migrations/V011__create_activity_log.sql"
        applyMigration(connection, "tenant", relativePath, Files.readString(Path.of(config.migrationsRoot).resolve(relativePath)))
        grantIfMissing(connection, config.runtimeUser, ActivityLogTable, listOf("SELECT", "INSERT"))
        grantIfMissing(connection, config.runtimeUser, "activity_log_chain", listOf("SELECT", "INSERT"))
        grantSequenceIfMissing(connection, config.runtimeUser, ActivityLogTable, "activity_log_id_seq", listOf("USAGE"))
        revokeIfPresent(connection, config.runtimeUser, ActivityLogTable, listOf("UPDATE", "DELETE", "TRUNCATE", "REFERENCES", "TRIGGER"))
    }

    /** Takes [privileges] on [table] away from [runtimeUser] when it holds any of them (as the table owner). */
    internal fun revokeIfPresent(connection: Connection, runtimeUser: String, table: String, privileges: List<String>) {
        val held = privileges.filter { privilege ->
            connection.prepareStatement("SELECT has_table_privilege(?, ?, ?)").use { query ->
                query.setString(1, runtimeUser)
                query.setString(2, quoteIdentifier(table))
                query.setString(3, privilege)
                query.executeQuery().use { rows -> rows.next() && rows.getBoolean(1) }
            }
        }
        if (held.isEmpty()) return
        runAsTableOwner(connection, table) { statement ->
            statement.executeUpdate("REVOKE ${held.joinToString(", ")} ON ${quoteIdentifier(table)} FROM ${quoteIdentifier(runtimeUser)}")
        }
    }

    // Language of each user (core V006) and the cache of translated user texts (tenant V010), I18N.md;
    // also brought to existing companies at startup.
    internal fun applyLanguageMigrations(connection: Connection) {
        val userLanguage = "database/core/migrations/V006__add_application_user_language.sql"
        applyMigration(connection, "core", userLanguage, Files.readString(Path.of(config.migrationsRoot).resolve(userLanguage)))
        grantColumnsIfMissing(connection, config.runtimeUser, "application_users", "UPDATE", listOf("language"))
        val translations = "database/tenant/migrations/V010__create_text_translations.sql"
        applyMigration(connection, "tenant", translations, Files.readString(Path.of(config.migrationsRoot).resolve(translations)))
        grantIfMissing(connection, config.runtimeUser, "text_translations", listOf("SELECT", "INSERT", "UPDATE", "DELETE"))
    }

    // SMTP de la empresa (tenant V007); tambien se aplica a las empresas existentes al arrancar.
    internal fun applyCompanyMailMigration(connection: Connection) {
        val relativePath = "database/tenant/migrations/V007__create_company_mail_settings.sql"
        applyMigration(connection, "tenant", relativePath, Files.readString(Path.of(config.migrationsRoot).resolve(relativePath)))
        connection.createStatement().use { statement ->
            statement.executeUpdate("GRANT SELECT, INSERT, UPDATE ON company_mail_settings TO ${quoteIdentifier(config.runtimeUser)}")
        }
    }

    // Encrypted secrets (SecretBox) and per-company extensions (tenant V006, EXTENSIONS.md); also
    // brought to existing companies at startup.
    internal fun applyExtensionsMigration(connection: Connection) {
        val relativePath = "database/tenant/migrations/V006__create_extensions.sql"
        applyMigration(connection, "tenant", relativePath, Files.readString(Path.of(config.migrationsRoot).resolve(relativePath)))
        grantIfMissing(connection, config.runtimeUser, "tenant_data_keys", listOf("SELECT", "INSERT"))
        // The data key is only read and created: replacing or deleting it would make every sealed secret unreadable.
        try {
            revokeIfPresent(connection, config.runtimeUser, "tenant_data_keys", listOf("UPDATE", "DELETE", "TRUNCATE", "REFERENCES", "TRIGGER"))
        } catch (failure: Exception) {
            log.error("No se pudieron quitar al runtime {} los privilegios de mas sobre tenant_data_keys", config.runtimeUser, failure)
        }
        // Schema state of each extension in the company (tenant V017): the error and previous status of a suspended one.
        val schemaState = "database/tenant/migrations/V017__extension_schema_state.sql"
        applyMigration(connection, "tenant", schemaState, Files.readString(Path.of(config.migrationsRoot).resolve(schemaState)))
        grantIfMissing(connection, config.runtimeUser, "tenant_extensions", listOf("SELECT", "INSERT", "UPDATE"))
        listOf("tenant_extension_settings", "tenant_extension_objects").forEach { table ->
            grantIfMissing(connection, config.runtimeUser, table, listOf("SELECT", "INSERT", "UPDATE", "DELETE"))
        }
    }

    /**
     * GRANT idempotente para la puesta al dia de empresas existentes: en ellas el rol de aprovisionamiento
     * no es dueno de la tabla y un GRANT directo fallaria con "permission denied". Si [runtimeUser] ya tiene
     * todos los privilegios no hace nada; si faltan, ejecuta el GRANT como dueno de la tabla (SET ROLE) y
     * vuelve al rol anterior. Nunca lanza: ante un fallo solo avisa en el log para no abortar la puesta al dia.
     */
    internal fun grantIfMissing(connection: Connection, runtimeUser: String, table: String, privileges: List<String>) {
        try {
            val missing = privileges.filterNot { privilege ->
                connection.prepareStatement("SELECT has_table_privilege(?, ?, ?)").use { query ->
                    query.setString(1, runtimeUser)
                    query.setString(2, quoteIdentifier(table))
                    query.setString(3, privilege)
                    query.executeQuery().use { rows -> rows.next() && rows.getBoolean(1) }
                }
            }
            if (missing.isEmpty()) return
            runAsTableOwner(connection, table) { statement ->
                statement.executeUpdate("GRANT ${missing.joinToString(", ")} ON ${quoteIdentifier(table)} TO ${quoteIdentifier(runtimeUser)}")
            }
        } catch (failure: Exception) {
            log.error("No se pudo conceder {} sobre {} a {}; se continua sin abortar la puesta al dia, pero el runtime podria fallar despues con 'permission denied'", privileges, table, runtimeUser, failure)
        }
    }

    /** Variante de [grantIfMissing] para privilegios por columna, p. ej. UPDATE (read_at). */
    internal fun grantColumnsIfMissing(connection: Connection, runtimeUser: String, table: String, privilege: String, columns: List<String>) {
        try {
            val missing = columns.filterNot { column ->
                connection.prepareStatement("SELECT has_column_privilege(?, ?, ?, ?)").use { query ->
                    query.setString(1, runtimeUser)
                    query.setString(2, quoteIdentifier(table))
                    query.setString(3, column)
                    query.setString(4, privilege)
                    query.executeQuery().use { rows -> rows.next() && rows.getBoolean(1) }
                }
            }
            if (missing.isEmpty()) return
            runAsTableOwner(connection, table) { statement ->
                val columnList = missing.joinToString(", ") { quoteIdentifier(it) }
                statement.executeUpdate("GRANT $privilege ($columnList) ON ${quoteIdentifier(table)} TO ${quoteIdentifier(runtimeUser)}")
            }
        } catch (failure: Exception) {
            log.error("No se pudo conceder {} ({}) sobre {} a {}; se continua sin abortar la puesta al dia, pero el runtime podria fallar despues con 'permission denied'", privilege, columns, table, runtimeUser, failure)
        }
    }

    // Ejecuta [action] como dueno de la tabla (si el rol actual no lo es) y restaura el rol anterior.
    internal fun runAsTableOwner(connection: Connection, table: String, action: (java.sql.Statement) -> Unit) {
        val owner = connection.prepareStatement("SELECT tableowner FROM pg_tables WHERE schemaname = current_schema() AND tablename = ?").use { query ->
            query.setString(1, table)
            query.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
        } ?: throw CompanyProvisioningException(tr("La tabla {0} no existe", table))
        val current = connection.createStatement().use { statement ->
            statement.executeQuery("SELECT current_user").use { rows -> rows.next(); rows.getString(1) }
        }
        val needsRoleSwitch = owner != current
        connection.createStatement().use { statement ->
            if (needsRoleSwitch) statement.execute("SET ROLE ${quoteIdentifier(owner)}")
            try {
                action(statement)
            } finally {
                // Restaura el rol anterior (el de aprovisionamiento, no RESET ROLE que volveria al rol de login).
                // Un fallo aqui se registra pero no enmascara la excepcion de [action] ni deja la conexion en el
                // rol del dueno para las operaciones siguientes.
                if (needsRoleSwitch) {
                    try {
                        statement.execute("SET ROLE ${quoteIdentifier(current)}")
                    } catch (restore: Exception) {
                        log.error("No se pudo restaurar el rol {} tras el GRANT sobre {}", current, table, restore)
                    }
                }
            }
        }
    }

    // Notification center (core V005); also brought to existing companies at startup.
    internal fun applyNotificationsMigration(connection: Connection) {
        val relativePath = "database/core/migrations/V005__create_notifications.sql"
        applyMigration(connection, "core", relativePath, Files.readString(Path.of(config.migrationsRoot).resolve(relativePath)))
        grantIfMissing(connection, config.runtimeUser, "notifications", listOf("SELECT", "INSERT", "DELETE"))
        grantColumnsIfMissing(connection, config.runtimeUser, "notifications", "UPDATE", listOf("read_at"))
    }

    // Name, email and password reset codes of users (core V003); also brought to existing companies at startup.
    internal fun applyUserProfileMigration(connection: Connection) {
        val relativePath = "database/core/migrations/V003__add_application_user_profile.sql"
        applyMigration(connection, "core", relativePath, Files.readString(Path.of(config.migrationsRoot).resolve(relativePath)))
        val userCodeMigration = "database/core/migrations/V004__add_application_user_code.sql"
        applyMigration(connection, "core", userCodeMigration, Files.readString(Path.of(config.migrationsRoot).resolve(userCodeMigration)))
        grantIfMissing(connection, config.runtimeUser, "password_reset_codes", listOf("SELECT", "INSERT", "UPDATE", "DELETE"))
        grantIfMissing(connection, config.runtimeUser, "application_users", listOf("SELECT"))
        grantColumnsIfMissing(connection, config.runtimeUser, "application_users", "UPDATE", listOf("user_code"))
    }

    // Contadores de fuerza bruta de reseteo de contrasena persistidos por empresa (tenant V008);
    // tambien se trae a las empresas existentes al arrancar.
    internal fun applyAccountThrottleMigration(connection: Connection) {
        val relativePath = "database/tenant/migrations/V008__account_reset_throttle.sql"
        applyMigration(connection, "tenant", relativePath, Files.readString(Path.of(config.migrationsRoot).resolve(relativePath)))
        grantIfMissing(connection, config.runtimeUser, "account_reset_throttle", listOf("SELECT", "INSERT", "UPDATE", "DELETE"))
    }

    // Cola de salida a servicios externos (tenant V009); tambien se trae a las empresas existentes al arrancar.
    internal fun applyOutboundMigration(connection: Connection) {
        val relativePath = "database/tenant/migrations/V009__outbound.sql"
        applyMigration(connection, "tenant", relativePath, Files.readString(Path.of(config.migrationsRoot).resolve(relativePath)))
        listOf("outbound_events", "outbound_links", "integration_connections").forEach { table ->
            grantIfMissing(connection, config.runtimeUser, table, listOf("SELECT", "INSERT", "UPDATE", "DELETE"))
        }
        grantSequenceIfMissing(connection, config.runtimeUser, "outbound_events", "outbound_events_id_seq", listOf("USAGE", "SELECT"))
    }

    /** Variante de [grantIfMissing] para una secuencia; el dueno de la secuencia es el de su tabla. */
    internal fun grantSequenceIfMissing(connection: Connection, runtimeUser: String, ownerTable: String, sequence: String, privileges: List<String>) {
        try {
            val missing = privileges.filterNot { privilege ->
                connection.prepareStatement("SELECT has_sequence_privilege(?, ?, ?)").use { query ->
                    query.setString(1, runtimeUser)
                    query.setString(2, quoteIdentifier(sequence))
                    query.setString(3, privilege)
                    query.executeQuery().use { rows -> rows.next() && rows.getBoolean(1) }
                }
            }
            if (missing.isEmpty()) return
            runAsTableOwner(connection, ownerTable) { statement ->
                statement.executeUpdate("GRANT ${missing.joinToString(", ")} ON SEQUENCE ${quoteIdentifier(sequence)} TO ${quoteIdentifier(runtimeUser)}")
            }
        } catch (failure: Exception) {
            log.error("No se pudo conceder {} sobre la secuencia {} a {}; se continua sin abortar la puesta al dia", privileges, sequence, runtimeUser, failure)
        }
    }

    internal fun applyBaseModuleMigrations(connection: Connection) {
        moduleRegistry.definitions
            .filter { it.locked }
            .forEach { definition -> applyModuleMigrations(connection, definition) }
    }

    internal fun applyModuleMigrations(connection: Connection, definition: ModuleDefinition) {
        val module = moduleRegistry.module(definition.id) ?: return
        if (!definition.locked) {
            // Optional modules may build on base modules (e.g. Clientes); bring those schemas up to date first.
            applyBaseModuleMigrations(connection)
        }
        module.migrationPaths.forEach { relativePath ->
            applyMigration(connection, module.migrationModule, relativePath, migrationSql(module, relativePath))
        }
        // activity_log is append-only: a module listing it never gets UPDATE/DELETE on it.
        val moduleTables = module.runtimeTables.filterNot { it.equals(ActivityLogTable, ignoreCase = true) }
        if (moduleTables.isNotEmpty()) {
            val tables = moduleTables.joinToString(", ") { quoteIdentifier(it) }
            connection.createStatement().use { statement ->
                statement.executeUpdate("GRANT SELECT, INSERT, UPDATE, DELETE ON $tables TO ${quoteIdentifier(config.runtimeUser)}")
            }
        }
        grantRuntimeDefaults(connection)
    }

    // Packaged modules carry their migrations; modules compiled into the server read the source tree.
    internal fun migrationSql(module: ServerModule, relativePath: String): String =
        module.javaClass.classLoader.getResourceAsStream(relativePath)?.use { String(it.readAllBytes(), Charsets.UTF_8) }
            ?: Path.of(config.migrationsRoot).resolve(relativePath).takeIf { Files.isRegularFile(it) }?.let(Files::readString)
            ?: throw CompanyProvisioningException(tr("No se encontro la migracion {0} del modulo {1}.", relativePath, module.definition.id))

    internal fun applyMigration(connection: Connection, module: String, relativePath: String, content: String) {
        val sql = content.trim()
        require(sql.startsWith("BEGIN;") && sql.endsWith("COMMIT;")) {
            "Migration $relativePath must have outer BEGIN/COMMIT"
        }
        val version = relativePath.substringAfterLast('/').removeSuffix(".sql")
        // scripts/ubuntu/migrations.sh hashes the whole file; earlier servers hashed the trimmed text.
        // Either one identifies an unchanged migration, so both installers can share tenants.
        val checksum = sha256(content)
        val acceptedChecksums = setOf(checksum, sha256(sql))
        connection.autoCommit = false
        try {
            connection.createStatement().use { statement ->
                statement.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS schema_migrations (
                        module TEXT NOT NULL,
                        version TEXT NOT NULL,
                        checksum TEXT NOT NULL,
                        applied_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        PRIMARY KEY (module, version)
                    )
                    """.trimIndent()
                )
            }
            connection.prepareStatement("SELECT checksum FROM schema_migrations WHERE module = ? AND version = ?").use { query ->
                query.setString(1, module)
                query.setString(2, version)
                query.executeQuery().use { rows ->
                    if (rows.next()) {
                        if (rows.getString(1) !in acceptedChecksums) {
                            throw CompanyProvisioningException(tr("La migracion {0} ya fue aplicada con otro checksum.", version))
                        }
                        connection.commit()
                        return
                    }
                }
            }
            val migrationSql = sql.removePrefix("BEGIN;").removeSuffix("COMMIT;").trim()
            connection.createStatement().use { statement ->
                statement.execute(migrationSql)
            }
            connection.prepareStatement("INSERT INTO schema_migrations (module, version, checksum) VALUES (?, ?, ?)").use { insert ->
                insert.setString(1, module)
                insert.setString(2, version)
                insert.setString(3, checksum)
                insert.executeUpdate()
            }
            connection.commit()
        } catch (failure: Exception) {
            connection.rollback()
            throw failure
        } finally {
            connection.autoCommit = true
        }
    }

    internal fun seedBusinessOwner(connection: Connection, ownerUsername: String, ownerName: String, ownerEmail: String) {
        val userId = UUID.randomUUID()
        connection.autoCommit = false
        try {
            connection.prepareStatement(
                "INSERT INTO application_users (id, postgres_role, is_active, display_name, email) VALUES (?, ?, TRUE, ?, ?)"
            ).use { insert ->
                insert.setObject(1, userId)
                insert.setString(2, ownerUsername)
                insert.setString(3, ownerName)
                insert.setString(4, ownerEmail)
                insert.executeUpdate()
            }
            connection.prepareStatement("INSERT INTO business_owner (user_id) VALUES (?)").use { insert ->
                insert.setObject(1, userId)
                insert.executeUpdate()
            }
            connection.commit()
        } catch (failure: Exception) {
            connection.rollback()
            throw failure
        } finally {
            connection.autoCommit = true
        }
    }

    // The server workspace has no business owner (ownerUsername = null).
    internal fun grantTenantPermissions(connection: Connection, ownerUsername: String?) {
        connection.createStatement().use { statement ->
            statement.executeUpdate("REVOKE CREATE ON SCHEMA public FROM PUBLIC")
            statement.executeUpdate("GRANT USAGE ON SCHEMA public TO ${quoteIdentifier(config.runtimeUser)}")
            statement.executeUpdate("GRANT SELECT, INSERT, UPDATE ON application_users TO ${quoteIdentifier(config.runtimeUser)}")
            statement.executeUpdate("GRANT SELECT ON business_owner, tenant_modules TO ${quoteIdentifier(config.runtimeUser)}")
            statement.executeUpdate("GRANT SELECT, INSERT, UPDATE, DELETE ON application_sessions TO ${quoteIdentifier(config.runtimeUser)}")
            statement.executeUpdate("GRANT SELECT, INSERT, UPDATE, DELETE ON application_permissions, application_user_permissions TO ${quoteIdentifier(config.runtimeUser)}")
            statement.executeUpdate("GRANT SELECT, INSERT, UPDATE, DELETE ON application_user_credentials TO ${quoteIdentifier(config.runtimeUser)}")
            statement.executeUpdate("GRANT SELECT, INSERT, UPDATE, DELETE ON customers, customer_field_definitions TO ${quoteIdentifier(config.runtimeUser)}")
            if (ownerUsername != null) {
                statement.executeUpdate("GRANT USAGE ON SCHEMA public TO ${quoteIdentifier(ownerUsername)}")
                statement.executeUpdate("GRANT SELECT ON application_users, business_owner, tenant_modules, application_permissions, application_user_permissions TO ${quoteIdentifier(ownerUsername)}")
                statement.executeUpdate("GRANT SELECT, INSERT, UPDATE, DELETE ON application_sessions TO ${quoteIdentifier(ownerUsername)}")
            }
        }
        grantRuntimeDefaults(connection)
    }

    /**
     * The runtime role owns nothing and is a member of nothing (SELF_HOSTING.md, Roles de PostgreSQL): it reaches the
     * company's tables only through grants. Every table and view of schema public owned by the current role (the
     * server owner's) gets SELECT, INSERT, UPDATE, DELETE for it, schema_migrations SELECT, tenant_data_keys SELECT and
     * INSERT, application_user_2fa_email_recovery SELECT, INSERT and UPDATE, assistant_messages SELECT and INSERT, and every sequence USAGE, SELECT; the activity log and its sequence keep the narrower grants of
     * [applyActivityLogMigration]. Only missing
     * privileges are granted, so running it after every migration costs one catalog query. Never throws.
     */
    internal fun grantRuntimeDefaults(connection: Connection) {
        try {
            val statements = connection.prepareStatement(RuntimeDefaultGrantsSql).use { query ->
                query.setString(1, config.runtimeUser)
                query.setString(2, config.runtimeUser)
                query.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getString(1)) } }
            }
            if (statements.isEmpty()) return
            connection.createStatement().use { statement -> statements.forEach { statement.execute(it) } }
        } catch (failure: Exception) {
            log.error("No se pudieron conceder al runtime {} los privilegios de las tablas de la empresa", config.runtimeUser, failure)
        }
    }

    // CONNECT for the provisioning login on a database it just created (it inherits nothing from the owner's role).
    internal fun grantProvisioningConnect(statement: java.sql.Statement, databaseName: String) {
        if (!config.separateProvisioningRole) return
        statement.executeUpdate("GRANT CONNECT ON DATABASE ${quoteIdentifier(databaseName)} TO ${quoteIdentifier(config.adminUser)}")
    }

    /**
     * Applies the pending tenant migrations of an extension in one company with the provisioning
     * role and grants its runtime tables (EXTENSIONS.md, install). Applied migrations are skipped.
     */
    fun applyExtensionMigrations(
        companyCode: String,
        extension: com.ideasdeveloper.bms.server.extensions.ServerExtension,
        firstServerOwnerRole: () -> String?,
    ) {
        if (extension.migrationPaths.isEmpty() && extension.runtimeTables.isEmpty()) return
        val provisioningRole = firstServerOwnerRole() ?: throw CompanyProvisioningException(tr("No hay un server owner activo para aplicar migraciones."))
        val databaseName = central.connection.use { connection ->
            connection.prepareStatement("SELECT database_name FROM companies WHERE code = ?").use { query ->
                query.setString(1, companyCode)
                query.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
            }
        } ?: throw CompanyProvisioningException(tr("La empresa {0} no existe.", companyCode))
        val tenantConfig = loginDatabases.tenantConfig(databaseName) ?: throw CompanyProvisioningException(tr("La base de datos de {0} no esta configurada.", companyCode))
        val migrationModule = "ext:${extension.manifest.id}"
        tenantAdminConnection(tenantConfig.jdbcUrl, provisioningRole).use { tenant ->
            // A migration of a package must not hang installs or startup (pg_sleep, long locks).
            tenant.createStatement().use { statement ->
                statement.execute("SET statement_timeout = '60s'")
                statement.execute("SET lock_timeout = '10s'")
            }
            extension.migrationPaths.forEach { path ->
                val sql = extension.javaClass.classLoader.getResourceAsStream(path)?.use { String(it.readBytes(), Charsets.UTF_8) }
                    ?: throw CompanyProvisioningException(tr("Falta la migracion {0} de la extension {1}.", path, extension.manifest.id))
                applyMigration(tenant, migrationModule, path, sql)
            }
            val extensionTables = extension.runtimeTables.filterNot { it.equals(ActivityLogTable, ignoreCase = true) }
            if (extensionTables.isNotEmpty()) {
                val tables = extensionTables.joinToString(", ") { quoteIdentifier(it) }
                tenant.createStatement().use { statement ->
                    statement.executeUpdate("GRANT SELECT, INSERT, UPDATE, DELETE ON $tables TO ${quoteIdentifier(config.runtimeUser)}")
                }
            }
            grantRuntimeDefaults(tenant)
        }
    }
}

// Tenant table of the company activity log (tenant V011); never granted beyond SELECT/INSERT.
private const val ActivityLogTable = "activity_log"

// Privileges of the runtime role missing on what the current role owns in schema public (see grantRuntimeDefaults):
// one GRANT statement per object. Parameters: the runtime role twice.
private val RuntimeDefaultGrantsSql = """
    WITH runtime AS (SELECT oid FROM pg_catalog.pg_roles WHERE rolname = ?),
    owned AS (
        SELECT c.oid, c.relname, c.relkind, c.relacl FROM pg_catalog.pg_class c
        WHERE c.relnamespace = 'public'::pg_catalog.regnamespace
          AND c.relowner = (SELECT oid FROM pg_catalog.pg_roles WHERE rolname = current_user)
          AND c.relkind IN ('r', 'p', 'v', 'S')
          AND c.relname NOT IN ('activity_log', 'activity_log_chain', 'activity_log_id_seq')
    ),
    wanted AS (
        SELECT o.relname, o.relacl, CASE o.relkind WHEN 'S' THEN 'SEQUENCE' ELSE 'TABLE' END AS kind, p.privilege
        FROM owned o
        CROSS JOIN (VALUES ('SELECT'), ('INSERT'), ('UPDATE'), ('DELETE'), ('USAGE')) AS p (privilege)
        WHERE CASE
            WHEN o.relkind = 'S' THEN p.privilege IN ('USAGE', 'SELECT')
            WHEN o.relname = 'schema_migrations' THEN p.privilege = 'SELECT'
            WHEN o.relname = 'tenant_data_keys' THEN p.privilege IN ('SELECT', 'INSERT')
            WHEN o.relname = 'application_user_2fa_email_recovery' THEN p.privilege IN ('SELECT', 'INSERT', 'UPDATE')
            WHEN o.relname = 'assistant_messages' THEN p.privilege IN ('SELECT', 'INSERT')
            ELSE p.privilege IN ('SELECT', 'INSERT', 'UPDATE', 'DELETE')
        END
    )
    SELECT pg_catalog.format('GRANT %s ON %s public.%I TO %I', pg_catalog.string_agg(w.privilege, ', ' ORDER BY w.privilege), w.kind, w.relname, ?)
    FROM wanted w
    WHERE NOT EXISTS (
        SELECT 1 FROM pg_catalog.aclexplode(w.relacl) acl
        WHERE acl.grantee = (SELECT oid FROM runtime) AND acl.privilege_type = w.privilege
    )
    GROUP BY w.kind, w.relname
    ORDER BY w.relname
""".trimIndent()
