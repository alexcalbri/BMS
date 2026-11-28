package com.ideasdeveloper.bms.server.company.application

import com.ideasdeveloper.bms.server.i18n.tr
import com.ideasdeveloper.bms.server.modules.install.ServerModuleInstaller
import com.ideasdeveloper.bms.server.admins.AuditActor
import com.ideasdeveloper.bms.server.auth.application.EmailChangeRule
import com.ideasdeveloper.bms.server.modules.PackageInstallQueue
import com.ideasdeveloper.bms.server.modules.CompanyFiles
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

// [code] opcional: las rutas lo devuelven en lugar del codigo generico (p. ej. MODULE_NEEDS_CORE).
class CompanyProvisioningException(message: String, val code: String? = null) : Exception(message)

class CompanyProvisioningService(
    private val central: DataSource,
    private val config: CompanyProvisioningConfig,
    private val loginDatabases: LoginDatabases,
    private val moduleRegistry: ModuleRegistry,
    private val remoteModuleCatalog: RemoteModuleCatalog,
    private val defaultCatalogUrl: String,
    private val modulePackagesRoot: Path,
    // Null outside an installed distribution (e.g. :server:run), where modules cannot be built.
    private val modulePackageBuilder: ModulePackageBuilder?,
    private val requestRestart: () -> Unit,
    // Files the modules stored for the company; removed with the company.
    private val companyFiles: CompanyFiles? = null,
    // Installs that survive restarts and the single restart once every build ended; null keeps the restart per package.
    private val installQueue: PackageInstallQueue? = null,
    // Re-checks who asked for a queued module install when it resumes (their current rights).
    private val queuedActors: com.ideasdeveloper.bms.server.admins.QueuedInstallActors = com.ideasdeveloper.bms.server.admins.QueuedInstallActors.Unchecked,
    // Anchors of the company activity logs (ACTIVITY_LOG.md §9): at deactivation and before a deletion.
    private val activityIntegrity: com.ideasdeveloper.bms.server.activity.ActivityIntegrityService? = null,
) {
    private val log = LoggerFactory.getLogger(CompanyProvisioningService::class.java)
    private val tenantSchema = TenantSchemaProvisioner(central, config, loginDatabases, moduleRegistry)
    private val installer = ServerModuleInstaller(
        central = central,
        loginDatabases = loginDatabases,
        moduleRegistry = moduleRegistry,
        remoteModuleCatalog = remoteModuleCatalog,
        defaultCatalogUrl = defaultCatalogUrl,
        modulePackagesRoot = modulePackagesRoot,
        modulePackageBuilder = modulePackageBuilder,
        requestRestart = requestRestart,
        installQueue = installQueue,
        queuedActors = queuedActors,
        tenantSchema = tenantSchema,
        serverWorkspaceModules = { serverWorkspaceModules() },
        ensureServerWorkspace = { role, actor -> ensureServerWorkspace(role, actor) },
        serverWorkspaceCode = { serverWorkspaceRow()?.code },
        setModuleEnabled = { company, module, enabled, role, actor -> setModuleEnabled(company, module, enabled, role, actor) },
        countCompaniesWithModuleEnabled = { moduleId -> countCompaniesWithModuleEnabled(moduleId) },
    )
    private val companyCodePattern = Regex("[a-z][a-z0-9_]{0,62}")
    private val postgresRolePattern = Regex("[a-z][a-z0-9_]{0,62}")
    private val colorPattern = Regex("#[0-9A-Fa-f]{6}")
    private val modules: List<ModuleDefinition>
        get() = moduleRegistry.definitions

    fun createCompany(request: CreateCompanyRequest, provisioningRole: String, actor: AuditActor): CreateCompanyResponse {
        val code = request.code.trim()
        val companyName = request.name.trim()
        val ownerUsername = request.businessOwnerUsername.trim()
        val ownerPassword = request.businessOwnerPassword
        val ownerName = request.businessOwnerName.trim()
        val ownerEmail = request.businessOwnerEmail.trim()
        val databaseName = "bms_$code"
        val companyId = UUID.randomUUID()

        if (code == ServerWorkspace.Code) {
            throw CompanyProvisioningException(tr("El codigo {0} esta reservado para el espacio del servidor.", ServerWorkspace.Code))
        }
        validate(code, companyName, ownerUsername, ownerPassword, request)
        UserProfileRules.problem(ownerName, ownerEmail)?.let { throw CompanyProvisioningException(tr("Business owner: {0}", it)) }

        central.connection.use { connection ->
            if (connection.exists("companies", "code", code)) {
                throw CompanyProvisioningException(tr("Ya existe una empresa con ese codigo."))
            }
            if (connection.exists("companies", "database_name", databaseName)) {
                throw CompanyProvisioningException(tr("Ya existe una base de datos registrada para ese codigo."))
            }
        }

        // Lo que este intento creo, para borrarlo si algo falla antes de registrar la empresa.
        var databaseCreated = false
        var roleCreated = false
        var registered = false
        val tenantConfig = tenantSchema.tenantConfig(databaseName)
        try {
            tenantSchema.adminConnection(provisioningRole).use { admin ->
                admin.autoCommit = true
                if (admin.existsDatabase(databaseName)) {
                    throw CompanyProvisioningException(tr("La base de datos de la empresa ya existe."))
                }
                if (admin.existsRole(ownerUsername) || roleUsedInAnyCompany(ownerUsername) || centralLoginTaken(ownerUsername)) {
                    throw CompanyProvisioningException(tr("El usuario PostgreSQL del business owner ya existe."))
                }

                admin.createStatement().use { statement ->
                    silenceStatementLog(statement)
                    statement.executeUpdate("CREATE DATABASE ${quoteIdentifier(databaseName)}")
                    databaseCreated = true
                    // Por defecto PUBLIC puede conectarse a cualquier base: solo el runtime y el owner.
                    statement.executeUpdate("REVOKE CONNECT ON DATABASE ${quoteIdentifier(databaseName)} FROM PUBLIC")
                    statement.executeUpdate(
                        "CREATE ROLE ${quoteIdentifier(ownerUsername)} LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE PASSWORD ${quoteLiteral(ScramSha256.verifier(ownerPassword))}"
                    )
                    roleCreated = true
                    statement.executeUpdate("GRANT CONNECT ON DATABASE ${quoteIdentifier(databaseName)} TO ${quoteIdentifier(config.runtimeUser)}")
                    statement.executeUpdate("GRANT CONNECT ON DATABASE ${quoteIdentifier(databaseName)} TO ${quoteIdentifier(ownerUsername)}")
                    tenantSchema.grantProvisioningConnect(statement, databaseName)
                }
            }

            tenantSchema.tenantAdminConnection(tenantConfig.jdbcUrl, provisioningRole).use { tenant ->
                tenantSchema.applyTenantCoreMigrations(tenant)
                tenantSchema.applyBaseModuleMigrations(tenant)
                tenantSchema.seedBusinessOwner(tenant, ownerUsername, ownerName, ownerEmail)
                tenantSchema.grantTenantPermissions(tenant, ownerUsername)
            }

            central.connection.use { connection ->
                connection.autoCommit = false
                try {
                    insertCompany(connection, companyId, request, databaseName)
                    insertAudit(
                        connection = connection,
                        actor = actor,
                        action = "company.created",
                        companyCode = code,
                        moduleId = null,
                        details = auditDetails("databaseName" to databaseName, "businessOwnerUsername" to ownerUsername),
                    )
                    connection.commit()
                    // Confirmado: desde aqui la empresa existe y no se debe borrar nada, aunque el cierre falle.
                    registered = true
                } catch (failure: Exception) {
                    connection.rollback()
                    throw failure
                }
            }
        } catch (failure: Throwable) {
            // Nada apunta aun a una base a medias: se borra para poder reutilizar el codigo (sin ocultar el error original).
            if (!registered && (databaseCreated || roleCreated)) {
                // Si un commit incierto si dejo la empresa registrada (o no se puede comprobar), no se borra nada.
                val alreadyRegistered = runCatching { central.connection.use { it.exists("companies", "code", code) } }
                    .getOrElse { check ->
                        log.error("No se pudo comprobar si la empresa {} quedo registrada; no se borra su base ni su rol", code, check)
                        true
                    }
                if (alreadyRegistered) {
                    log.error("La empresa {} esta registrada pese al fallo; se conservan su base {} y su rol", code, databaseName)
                } else runCatching {
                    tenantSchema.adminConnection(provisioningRole).use { admin ->
                        admin.autoCommit = true
                        admin.createStatement().use { statement ->
                            if (databaseCreated) statement.executeUpdate("DROP DATABASE IF EXISTS ${quoteIdentifier(databaseName)}")
                            if (roleCreated) statement.executeUpdate("DROP ROLE IF EXISTS ${quoteIdentifier(ownerUsername)}")
                        }
                    }
                }.onFailure { log.warn("No se pudo borrar la base incompleta {}", databaseName, it) }
            }
            throw failure
        }

        loginDatabases.registerTenant(tenantConfig)

        return CreateCompanyResponse(
            id = companyId.toString(),
            code = code,
            name = companyName,
            databaseName = databaseName,
            businessOwnerUsername = ownerUsername,
            enabledModules = listOf("clientes"),
        )
    }

    // [scope] null: every company; otherwise only those (a scoped server administrator), and linked
    // companies outside it are not named.
    fun listCompanies(scope: Set<String>? = null): List<CompanySummaryResponse> {
        val companies = central.connection.use { connection ->
            connection.prepareStatement(
                "SELECT code, name, logo_url, primary_color, secondary_color, accent_color, database_name, is_active, owner_group FROM companies WHERE NOT is_server_workspace ORDER BY code"
            ).use { query ->
                query.executeQuery().use { rows ->
                    buildList {
                        while (rows.next()) {
                            add(
                                CompanyRow(
                                    code = rows.getString("code"),
                                    name = rows.getString("name"),
                                    logoUrl = rows.getString("logo_url"),
                                    primaryColor = rows.getString("primary_color"),
                                    secondaryColor = rows.getString("secondary_color"),
                                    accentColor = rows.getString("accent_color"),
                                    databaseName = rows.getString("database_name"),
                                    isActive = rows.getBoolean("is_active"),
                                ) to rows.getString("owner_group")
                            )
                        }
                    }
                }
            }
        }
        val groups = companies.groupBy({ it.second }, { it.first.code })
            .mapValues { (_, codes) -> if (scope == null) codes else codes.filter { it in scope } }

        return companies.filter { scope == null || it.first.code in scope }.map { (company, group) ->
            CompanySummaryResponse(
                code = company.code,
                name = company.name,
                databaseName = company.databaseName,
                isActive = company.isActive,
                modules = loadCompanyModules(company.databaseName),
                logoUrl = company.logoUrl,
                primaryColor = company.primaryColor,
                secondaryColor = company.secondaryColor,
                accentColor = company.accentColor,
                linkedCompanies = groups[group].orEmpty() - company.code,
            )
        }
    }


    fun updateCompany(companyCode: String, request: UpdateCompanyRequest, provisioningRole: String, actor: AuditActor): CompanySummaryResponse {
        val normalizedCompanyCode = companyCode.trim()
        rejectServerWorkspace(normalizedCompanyCode)
        val companyName = request.name.trim()
        val logoUrl = request.logoUrl?.trim()?.takeIf { it.isNotBlank() }
        if (companyName.isBlank()) {
            throw CompanyProvisioningException(tr("El nombre de la empresa es obligatorio."))
        }
        if (UserProfileRules.hasControlCharacters(companyName)) {
            throw CompanyProvisioningException(tr("El nombre no puede tener caracteres de control."))
        }
        if (!colorPattern.matches(request.primaryColor) ||
            !colorPattern.matches(request.secondaryColor) ||
            !colorPattern.matches(request.accentColor)
        ) {
            throw CompanyProvisioningException(tr("Los colores deben usar formato hexadecimal #RRGGBB."))
        }

        val company = central.connection.use { connection ->
            findCompanyAnyState(connection, normalizedCompanyCode)
                ?: throw CompanyProvisioningException(tr("Empresa no encontrada."))
        }

        val oldLogo = central.connection.use { previousLogo(it, normalizedCompanyCode, null) }
        // The company's own log row goes first, uncommitted, on its database; it commits only after the central
        // change did, so a failed central commit never leaves a row for a change that did not happen.
        withCompanyActivity(
            company.databaseName, actor, "company", normalizedCompanyCode, companyName,
            com.ideasdeveloper.bms.server.activity.ActivityActions.SettingsChanged, "Cambio desde el servidor los datos de la empresa.",
            com.ideasdeveloper.bms.server.activity.ActivityChanges.diff(
                mapOf("name" to company.name, "logoUrl" to oldLogo),
                mapOf("name" to companyName, "logoUrl" to logoUrl),
                mapOf("name" to "Nombre", "logoUrl" to "Logo"),
            ),
        ) {
        central.connection.use { connection ->
            connection.autoCommit = false
            try {
                connection.prepareStatement(
                    """
                    UPDATE companies
                    SET name = ?, logo_url = ?, primary_color = ?, secondary_color = ?, accent_color = ?
                    WHERE code = ?
                    """.trimIndent()
                ).use { update ->
                    update.setString(1, companyName)
                    update.setString(2, logoUrl)
                    update.setString(3, request.primaryColor)
                    update.setString(4, request.secondaryColor)
                    update.setString(5, request.accentColor)
                    update.setString(6, normalizedCompanyCode)
                    if (update.executeUpdate() != 1) {
                        throw CompanyProvisioningException(tr("No se pudo actualizar la empresa."))
                    }
                }
                insertAudit(
                    connection = connection,
                    actor = actor,
                    action = "company.updated",
                    companyCode = normalizedCompanyCode,
                    moduleId = null,
                    details = auditDetails("previousName" to company.name, "name" to companyName, "databaseName" to company.databaseName),
                )
                connection.commit()
            } catch (failure: Exception) {
                connection.rollback()
                throw failure
            } finally {
                connection.autoCommit = true
            }
        }
        }

        return CompanySummaryResponse(
            code = company.code,
            name = companyName,
            databaseName = company.databaseName,
            isActive = company.isActive,
            modules = loadCompanyModules(company.databaseName),
            logoUrl = logoUrl,
            primaryColor = request.primaryColor,
            secondaryColor = request.secondaryColor,
            accentColor = request.accentColor,
        )
    }


    fun setCompanyActive(companyCode: String, active: Boolean, provisioningRole: String, actor: AuditActor): CompanySummaryResponse {
        val normalizedCompanyCode = companyCode.trim()
        rejectServerWorkspace(normalizedCompanyCode)
        val company = central.connection.use { connection ->
            findCompanyAnyState(connection, normalizedCompanyCode)
                ?: throw CompanyProvisioningException(tr("Empresa no encontrada."))
        }

        central.connection.use { connection ->
            connection.autoCommit = false
            try {
                connection.prepareStatement("UPDATE companies SET is_active = ? WHERE code = ?").use { update ->
                    update.setBoolean(1, active)
                    update.setString(2, normalizedCompanyCode)
                    update.executeUpdate()
                }
                insertAudit(
                    connection = connection,
                    actor = actor,
                    action = if (active) "company.activated" else "company.deactivated",
                    companyCode = normalizedCompanyCode,
                    moduleId = null,
                    details = auditDetails("databaseName" to company.databaseName),
                )
                connection.commit()
            } catch (failure: Exception) {
                connection.rollback()
                throw failure
            } finally {
                connection.autoCommit = true
            }
        }

        if (active) {
            loginDatabases.registerTenant(tenantSchema.tenantConfig(company.databaseName))
        } else {
            // Where its log reached when it stopped being used (never fails the deactivation).
            activityIntegrity?.anchor(normalizedCompanyCode, "deactivation")
            loginDatabases.unregisterTenant(company.databaseName)
        }

        return CompanySummaryResponse(
            code = company.code,
            name = company.name,
            databaseName = company.databaseName,
            isActive = active,
            modules = loadCompanyModules(company.databaseName),
            logoUrl = company.logoUrl,
            primaryColor = company.primaryColor,
            secondaryColor = company.secondaryColor,
            accentColor = company.accentColor,
        )
    }

    /**
     * Creates [newCode] as a copy of [sourceCode]: same profile, the same enabled modules and each
     * module's settings (ServerModule.duplicatedSettings), without data, staff users or integration
     * credentials. Only the server owner duplicates. With [newOwner] null the copy keeps the business
     * owner (same user and password) and joins the source's owner group, so the owner switches
     * between both; with a new owner the copy gets its own user and is not linked.
     */
    fun duplicateCompany(sourceCode: String, newCode: String, provisioningRole: String, actor: AuditActor, newOwner: DuplicateOwnerRequest? = null): CreateCompanyResponse {
        val code = newCode.trim()
        val source = sourceCode.trim()
        rejectServerWorkspace(source)
        if (code == ServerWorkspace.Code) throw CompanyProvisioningException(tr("El codigo {0} esta reservado para el espacio del servidor.", ServerWorkspace.Code))
        if (!companyCodePattern.matches(code)) {
            throw CompanyProvisioningException(tr("El codigo debe iniciar con una letra minuscula y usar solo letras minusculas, numeros o guion bajo (hasta 63)."))
        }
        val databaseName = "bms_$code"
        val (sourceCompany, ownerGroup) = central.connection.use { connection ->
            if (connection.exists("companies", "code", code)) throw CompanyProvisioningException(tr("Ya existe una empresa con ese codigo."))
            if (connection.exists("companies", "database_name", databaseName)) throw CompanyProvisioningException(tr("Ya existe una base de datos registrada para ese codigo."))
            val row = findCompany(connection, source) ?: throw CompanyProvisioningException(tr("La empresa a duplicar no existe o esta desactivada."))
            row to ownerGroup(connection, source)
        }
        val sourceTenant = loginDatabases.tenantConfig(sourceCompany.databaseName)
            ?: throw CompanyProvisioningException(tr("La empresa a duplicar no tiene conexion tenant configurada."))
        val newOwnerInput = newOwner?.let { input ->
            val username = input.username.trim()
            validate(code, sourceCompany.name, username, input.password, CreateCompanyRequest(code, sourceCompany.name, username, input.password))
            UserProfileRules.problem(input.name.trim(), input.email.trim())?.let { throw CompanyProvisioningException(tr("Business owner: {0}", it)) }
            input.copy(username = username, name = input.name.trim(), email = input.email.trim())
        }

        // The owner of the copy is the same person: same PostgreSQL role, name, email and password.
        data class Owner(val role: String, val name: String?, val email: String?, val credentials: List<Any?>?)
        val (sourceOwner, enabledModules) = tenantRuntimeConnection(sourceTenant).use { tenant ->
            val owner = tenant.prepareStatement(
                """
                SELECT users.id, users.postgres_role, users.display_name, users.email
                FROM business_owner owner JOIN application_users users ON users.id = owner.user_id
                LIMIT 1
                """.trimIndent()
            ).use { query ->
                query.executeQuery().use { rows ->
                    if (!rows.next()) throw CompanyProvisioningException(tr("La empresa a duplicar no tiene business owner."))
                    val userId = rows.getObject(1, UUID::class.java)
                    val credentials = tenant.prepareStatement(
                        "SELECT password_salt, password_hash, iterations FROM application_user_credentials WHERE user_id = ?"
                    ).use { credentialQuery ->
                        credentialQuery.setObject(1, userId)
                        credentialQuery.executeQuery().use { found -> if (found.next()) listOf(found.getString(1), found.getString(2), found.getInt(3)) else null }
                    }
                    Owner(rows.getString(2), rows.getString(3), rows.getString(4), credentials)
                }
            }
            val modules = tenant.prepareStatement("SELECT module_id FROM tenant_modules WHERE status = 'enabled'").use { query ->
                query.executeQuery().use { rows -> buildSet { while (rows.next()) add(rows.getString(1)) } }
            }
            owner to modules
        }

        val owner = newOwnerInput?.let { Owner(it.username, it.name, it.email, null) } ?: sourceOwner
        val tenantConfig = tenantSchema.tenantConfig(databaseName)
        var registered = false
        var roleCreated = false
        try {
            tenantSchema.adminConnection(provisioningRole).use { admin ->
                admin.autoCommit = true
                if (admin.existsDatabase(databaseName)) throw CompanyProvisioningException(tr("La base de datos de la empresa ya existe."))
                if (newOwnerInput != null) {
                    if (admin.existsRole(owner.role) || roleUsedInAnyCompany(owner.role) || centralLoginTaken(owner.role)) {
                        throw CompanyProvisioningException(tr("El usuario PostgreSQL del business owner ya existe."))
                    }
                } else if (!admin.existsRole(owner.role)) {
                    throw CompanyProvisioningException(tr("No existe el usuario PostgreSQL del business owner."))
                }
                admin.createStatement().use { statement ->
                    silenceStatementLog(statement)
                    statement.executeUpdate("CREATE DATABASE ${quoteIdentifier(databaseName)}")
                    // Por defecto PUBLIC puede conectarse a cualquier base: solo el runtime y el owner.
                    statement.executeUpdate("REVOKE CONNECT ON DATABASE ${quoteIdentifier(databaseName)} FROM PUBLIC")
                    if (newOwnerInput != null) {
                        statement.executeUpdate(
                            "CREATE ROLE ${quoteIdentifier(owner.role)} LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE PASSWORD ${quoteLiteral(ScramSha256.verifier(newOwnerInput.password))}"
                        )
                        roleCreated = true
                    }
                    statement.executeUpdate("GRANT CONNECT ON DATABASE ${quoteIdentifier(databaseName)} TO ${quoteIdentifier(config.runtimeUser)}")
                    statement.executeUpdate("GRANT CONNECT ON DATABASE ${quoteIdentifier(databaseName)} TO ${quoteIdentifier(owner.role)}")
                    tenantSchema.grantProvisioningConnect(statement, databaseName)
                }
            }
            tenantSchema.tenantAdminConnection(tenantConfig.jdbcUrl, provisioningRole).use { tenant ->
                tenantSchema.applyTenantCoreMigrations(tenant)
                tenantSchema.applyBaseModuleMigrations(tenant)
                tenantSchema.seedBusinessOwner(tenant, owner.role, owner.name.orEmpty(), owner.email.orEmpty())
                owner.credentials?.let { (salt, hash, iterations) ->
                    tenant.prepareStatement(
                        """
                        INSERT INTO application_user_credentials (user_id, password_salt, password_hash, iterations)
                        SELECT user_id, ?, ?, ? FROM business_owner
                        """.trimIndent()
                    ).use { insert ->
                        insert.setString(1, salt as String)
                        insert.setString(2, hash as String)
                        insert.setInt(3, iterations as Int)
                        insert.executeUpdate()
                    }
                }
                tenantSchema.grantTenantPermissions(tenant, owner.role)
            }
            val request = CreateCompanyRequest(
                code = code,
                name = sourceCompany.name,
                businessOwnerUsername = owner.role,
                businessOwnerPassword = "",
                businessOwnerName = owner.name.orEmpty(),
                businessOwnerEmail = owner.email.orEmpty(),
                logoUrl = sourceCompany.logoUrl,
                primaryColor = sourceCompany.primaryColor,
                secondaryColor = sourceCompany.secondaryColor,
                accentColor = sourceCompany.accentColor,
            )
            central.connection.use { connection ->
                connection.autoCommit = false
                try {
                    val companyId = UUID.randomUUID()
                    // Same owner: same group. A new owner starts its own group.
                    insertCompany(connection, companyId, request, databaseName, if (newOwnerInput == null) ownerGroup else companyId)
                    insertAudit(
                        connection, actor, "company.duplicated", code, null,
                        auditDetails("source" to source, "databaseName" to databaseName, "businessOwnerUsername" to owner.role, "newBusinessOwner" to (newOwnerInput != null)),
                    )
                    connection.commit()
                } catch (failure: Exception) {
                    connection.rollback()
                    throw failure
                }
            }
            registered = true
        } catch (failure: Exception) {
            // Nothing points to a half-built database yet: remove it so the code can be used again.
            if (!registered) {
                runCatching {
                    tenantSchema.adminConnection(provisioningRole).use { admin ->
                        admin.autoCommit = true
                        admin.createStatement().use { statement ->
                            statement.executeUpdate("DROP DATABASE IF EXISTS ${quoteIdentifier(databaseName)}")
                            if (roleCreated) statement.executeUpdate("DROP ROLE IF EXISTS ${quoteIdentifier(owner.role)}")
                        }
                    }
                }.onFailure { log.warn("No se pudo borrar la base incompleta {}", databaseName, it) }
            }
            throw failure
        }
        loginDatabases.registerTenant(tenantConfig)

        // Same optional modules, then the settings of every enabled module (base ones included).
        val copied = modules.filter { it.id in enabledModules || it.locked }
        copied.filterNot { it.locked }.forEach { definition -> setModuleEnabled(code, definition.id, true, provisioningRole, actor) }
        val failures = mutableListOf<String>()
        tenantRuntimeConnection(sourceTenant).use { from ->
            tenantSchema.tenantAdminConnection(tenantConfig.jdbcUrl, provisioningRole).use { to ->
                copied.forEach { definition ->
                    val tables = moduleRegistry.module(definition.id)?.duplicatedSettings.orEmpty()
                    if (tables.isEmpty()) return@forEach
                    runCatching { SettingsCopy.copy(from, to, tables) }.onFailure { failure ->
                        log.warn("No se copio la configuracion de {} de {} a {}", definition.id, source, code, failure)
                        failures += definition.displayName
                    }
                }
            }
        }
        if (failures.isNotEmpty()) {
            central.connection.use { connection ->
                insertAudit(connection, actor, "company.duplicate_settings_failed", code, null, auditDetails("modules" to failures.joinToString()))
            }
        }
        return CreateCompanyResponse(
            id = code,
            code = code,
            name = sourceCompany.name,
            databaseName = databaseName,
            businessOwnerUsername = owner.role,
            enabledModules = loadCompanyModules(databaseName).filter { it.enabled }.map { it.moduleId },
            settingsNotCopied = failures.map { tr(it) },
        )
    }

    private fun ownerGroup(connection: Connection, companyCode: String): UUID =
        connection.prepareStatement("SELECT owner_group FROM companies WHERE code = ?").use { query ->
            query.setString(1, companyCode)
            query.executeQuery().use { rows -> if (rows.next()) rows.getObject(1, UUID::class.java) else throw CompanyProvisioningException(tr("Empresa no encontrada.")) }
        }

    /**
     * Links [companyCode] to the owner group of [targetCode] (the server owner says both have the
     * same business owner, who then switches between them), or with [targetCode] null takes it out
     * of its group.
     */
    fun linkCompany(companyCode: String, targetCode: String?, provisioningRole: String, actor: AuditActor): List<CompanySummaryResponse> {
        val code = companyCode.trim()
        rejectServerWorkspace(code)
        targetCode?.trim()?.let { rejectServerWorkspace(it) }
        central.connection.use { connection ->
            findCompanyAnyState(connection, code) ?: throw CompanyProvisioningException(tr("Empresa no encontrada."))
            val updated = if (targetCode == null) {
                connection.prepareStatement("UPDATE companies SET owner_group = id WHERE code = ?").use { update ->
                    update.setString(1, code)
                    update.executeUpdate()
                }
            } else {
                val target = targetCode.trim()
                if (target == code) throw CompanyProvisioningException(tr("Elige otra empresa para vincular."))
                findCompanyAnyState(connection, target) ?: throw CompanyProvisioningException(tr("La empresa a vincular no existe."))
                connection.prepareStatement(
                    "UPDATE companies SET owner_group = (SELECT owner_group FROM companies WHERE code = ?) WHERE code = ?"
                ).use { update ->
                    update.setString(1, target)
                    update.setString(2, code)
                    update.executeUpdate()
                }
            }
            if (updated != 1) throw CompanyProvisioningException(tr("No se pudo actualizar la empresa."))
            insertAudit(
                connection, actor, if (targetCode == null) "company.unlinked" else "company.linked", code, null,
                auditDetails("linkedTo" to targetCode.orEmpty()),
            )
        }
        return listCompanies()
    }

    // The role also owns another company when it was granted CONNECT on another database (duplicates).
    private fun ownerRoleUsedElsewhere(admin: Connection, role: String, databaseName: String): Boolean =
        admin.prepareStatement(
            """
            SELECT 1 FROM pg_database database, aclexplode(database.datacl) acl
            WHERE database.datname <> ? AND acl.grantee = (SELECT oid FROM pg_roles WHERE rolname = ?)
            LIMIT 1
            """.trimIndent()
        ).use { query ->
            query.setString(1, databaseName)
            query.setString(2, role)
            query.executeQuery().use { it.next() }
        }

    fun deleteInactiveCompany(companyCode: String, provisioningRole: String, actor: AuditActor) {
        val normalizedCompanyCode = companyCode.trim()
        rejectServerWorkspace(normalizedCompanyCode)
        val company = central.connection.use { connection ->
            findCompanyAnyState(connection, normalizedCompanyCode)
                ?: throw CompanyProvisioningException(tr("Empresa no encontrada."))
        }
        if (company.isActive) {
            throw CompanyProvisioningException(tr("Primero debes desactivar la empresa antes de eliminarla."))
        }

        val tenantConfig = loginDatabases.tenantConfig(company.databaseName)
        var ownerRole: String? = null
        var tenantDatabaseDropped = false
        var ownerRoleDropped = false
        // The log goes with the database (it is not archived): its final anchor and a summary stay in the server audit.
        val finalLog = activityIntegrity?.finalAnchor(normalizedCompanyCode)

        tenantSchema.adminConnection(provisioningRole).use { admin ->
            admin.autoCommit = true
            val tenantDatabaseExists = admin.existsDatabase(company.databaseName)
            if (tenantDatabaseExists && tenantConfig != null) {
                ownerRole = tenantRuntimeConnection(tenantConfig).use { tenant ->
                    tenant.prepareStatement(
                        """
                        SELECT users.postgres_role
                        FROM business_owner owner
                        JOIN application_users users ON users.id = owner.user_id
                        LIMIT 1
                        """.trimIndent()
                    ).use { query ->
                        query.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
                    }
                }
            }

            loginDatabases.unregisterTenant(company.databaseName)

            admin.createStatement().use { statement ->
                if (tenantDatabaseExists) {
                    statement.executeUpdate("REVOKE CONNECT ON DATABASE ${quoteIdentifier(company.databaseName)} FROM PUBLIC")
                    dropDatabaseForced(statement, company.databaseName, config.runtimeUser)
                    tenantDatabaseDropped = true
                }
                // Without its database nobody can reach these files any more.
                runCatching { companyFiles?.deleteCompany(company.code) }
                    .onFailure { log.warn("No se pudieron borrar los archivos de la empresa {}", company.code, it) }
                val ownerRoleToDrop = ownerRole
                if (!ownerRoleToDrop.isNullOrBlank() && admin.existsRole(ownerRoleToDrop) && !ownerRoleUsedElsewhere(admin, ownerRoleToDrop, company.databaseName)) {
                    statement.executeUpdate("DROP ROLE IF EXISTS ${quoteIdentifier(ownerRoleToDrop)}")
                    ownerRoleDropped = true
                }
            }
        }

        central.connection.use { connection ->
            connection.autoCommit = false
            try {
                insertAudit(
                    connection = connection,
                    actor = actor,
                    action = "company.deleted",
                    companyCode = normalizedCompanyCode,
                    moduleId = null,
                    details = auditDetails(
                        "databaseName" to company.databaseName, "ownerRole" to ownerRole.orEmpty(), "tenantDatabaseDropped" to tenantDatabaseDropped,
                        "ownerRoleDropped" to ownerRoleDropped, "activityRows" to finalLog?.rows, "activityLastId" to finalLog?.lastId,
                        "activityLastHash" to finalLog?.lastHash, "activityIntact" to finalLog?.ok,
                    ),
                )
                connection.prepareStatement("DELETE FROM companies WHERE code = ? AND is_active = FALSE").use { delete ->
                    delete.setString(1, normalizedCompanyCode)
                    if (delete.executeUpdate() != 1) {
                        throw CompanyProvisioningException(tr("No se pudo eliminar la empresa desactivada."))
                    }
                }
                connection.commit()
            } catch (failure: Exception) {
                connection.rollback()
                throw failure
            } finally {
                connection.autoCommit = true
            }
        }
    }

    /**
     * Sets a new password for the company's business owner and closes their open sessions. Only the
     * server owner does this, from the app: companies created by an AI agent get a random password
     * nobody knows, because credentials never go through the MCP.
     */
    fun resetBusinessOwnerPassword(companyCode: String, password: String, provisioningRole: String, actor: AuditActor) =
        changeBusinessOwnerPassword(companyCode, password, provisioningRole, "company.owner_password_reset", actor)

    /**
     * New password of a business owner who proved, with a code sent to their email, that they own the
     * account (AccountService). Runs as the first server owner's role, which can change company roles.
     */
    fun setBusinessOwnerPasswordByEmailCode(companyCode: String, password: String) {
        val role = installer.firstServerOwnerRole() ?: throw CompanyProvisioningException(tr("El servidor no tiene un dueno activo."))
        changeBusinessOwnerPassword(companyCode, password, role, "company.owner_password_recovered", null)
    }

    /** New password of a server owner who proved with an email code that they own the account. */
    fun setServerOwnerPasswordByEmailCode(userId: UUID, password: String) {
        val role = serverOwnerRole(userId) ?: throw CompanyProvisioningException(tr("El usuario ya no es dueno del servidor."))
        if (password.length < 16 || password.any { it !in ' '..'~' }) {
            throw CompanyProvisioningException(tr("La contrasena del dueno del servidor debe tener al menos 16 caracteres ASCII."))
        }
        // Any role may change its own password.
        tenantSchema.adminConnection(role).use { admin ->
            admin.autoCommit = true
            admin.createStatement().use { statement ->
                silenceStatementLog(statement)
                statement.executeUpdate("ALTER ROLE ${quoteIdentifier(role)} PASSWORD ${quoteLiteral(ScramSha256.verifier(password))}")
            }
        }
        central.connection.use { connection ->
            connection.prepareStatement(
                "UPDATE application_sessions SET revoked_at = CURRENT_TIMESTAMP WHERE user_id = ? AND revoked_at IS NULL"
            ).use { revoke ->
                revoke.setObject(1, userId)
                revoke.executeUpdate()
            }
            insertAudit(connection, AuditActor.owner(role, userId), "server_owner.password_recovered", null, null, "{}")
        }
    }

    // [actor] null: the business owner recovering their own password (email code).
    private fun changeBusinessOwnerPassword(companyCode: String, password: String, provisioningRole: String, auditAction: String, actor: AuditActor?) {
        val normalizedCompanyCode = companyCode.trim()
        rejectServerWorkspace(normalizedCompanyCode)
        if (password.length < 12) {
            throw CompanyProvisioningException(tr("La contrasena del business owner debe tener al menos 12 caracteres."))
        }
        val company = central.connection.use { connection ->
            findCompanyAnyState(connection, normalizedCompanyCode)
                ?: throw CompanyProvisioningException(tr("Empresa no encontrada."))
        }
        val tenantConfig = loginDatabases.tenantConfig(company.databaseName)
            ?: throw CompanyProvisioningException(tr("La empresa no tiene conexion tenant configurada."))
        val (ownerId, ownerRole) = tenantRuntimeConnection(tenantConfig).use { tenant ->
            tenant.prepareStatement(
                """
                SELECT users.id, users.postgres_role
                FROM business_owner owner
                JOIN application_users users ON users.id = owner.user_id
                LIMIT 1
                """.trimIndent()
            ).use { query ->
                query.executeQuery().use { rows -> if (rows.next()) rows.getObject(1, UUID::class.java) to rows.getString(2) else null }
            }
        } ?: throw CompanyProvisioningException(tr("La empresa no tiene business owner."))

        // Audit row first, in an open central transaction committed after the change: a failed insert stops it.
        inCentralTransaction { connection ->
            insertAudit(connection, actor ?: AuditActor.businessOwner(ownerRole), auditAction, normalizedCompanyCode, null, auditDetails("businessOwnerUsername" to ownerRole))
            tenantSchema.adminConnection(provisioningRole).use { admin ->
                admin.autoCommit = true
                admin.createStatement().use { statement ->
                    silenceStatementLog(statement)
                    statement.executeUpdate("ALTER ROLE ${quoteIdentifier(ownerRole)} PASSWORD ${quoteLiteral(ScramSha256.verifier(password))}")
                }
            }
            tenantRuntimeConnection(tenantConfig).use { tenant -> com.ideasdeveloper.bms.server.activity.ActivityLog.atomically(tenant) {
                // A reset from the server is recorded in the company (recovery by email code is recorded by AccountService),
                // in one tenant transaction with the credential and session changes.
                if (actor != null) {
                    com.ideasdeveloper.bms.server.activity.CoreActivity.record(
                        tenant, com.ideasdeveloper.bms.server.activity.ActivityActor.fromAudit(actor, actorName(actor)), "user", ownerId.toString(), ownerRole,
                        com.ideasdeveloper.bms.server.activity.ActivityActions.PasswordChanged, "Puso desde el servidor una contrasena nueva al business owner $ownerRole.",
                        listOf(com.ideasdeveloper.bms.server.activity.ActivityChange.secret("password", "Contrasena")),
                    )
                }
                // An application password would keep the old one working; owners sign in with their role.
                tenant.prepareStatement("DELETE FROM application_user_credentials WHERE user_id = ?").use { delete ->
                    delete.setObject(1, ownerId)
                    delete.executeUpdate()
                }
                tenant.prepareStatement(
                    "UPDATE application_sessions SET revoked_at = CURRENT_TIMESTAMP WHERE user_id = ? AND revoked_at IS NULL"
                ).use { revoke ->
                    revoke.setObject(1, ownerId)
                    revoke.executeUpdate()
                }
            } }
        }
    }

    /** A central user (server owner or administrator, also a removed one) already uses [username] as login. */
    private fun centralLoginTaken(username: String): Boolean = central.connection.use { it.exists("application_users", "postgres_role", username) }

    /**
     * Whether [login] can be the login of a new server administrator: not a PostgreSQL role, not a
     * central user and not a user of any company. Fails closed when a company cannot be checked.
     */
    fun loginNameTaken(login: String): Boolean =
        central.connection.use { it.existsRole(login) } || centralLoginTaken(login) || roleUsedInAnyCompany(login)

    /**
     * Codes of every company whose business owner has the same PostgreSQL role as the one of
     * [companyCode] (duplicated companies share it), [companyCode] included. Changing that owner's
     * user, email or password changes it in all of them.
     */
    fun companiesSharingOwner(companyCode: String): Set<String> {
        val role = ownerOf(companyCode.trim()).role
        val codes = central.connection.use { connection ->
            connection.prepareStatement("SELECT code, database_name FROM companies").use { query ->
                query.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getString(1) to rows.getString(2)) } }
            }
        }
        // Fails closed: a company whose database cannot be read counts as sharing the owner.
        return codes.filter { (_, databaseName) ->
            runCatching {
                tenantRuntimeConnection(loginDatabases.tenantConfig(databaseName) ?: tenantSchema.tenantConfig(databaseName)).use { connection ->
                    connection.prepareStatement(
                        "SELECT 1 FROM business_owner owner JOIN application_users users ON users.id = owner.user_id WHERE users.postgres_role = ?"
                    ).use { query ->
                        query.setString(1, role)
                        query.executeQuery().use { it.next() }
                    }
                }
            }.getOrElse { failure -> !(failure is java.sql.SQLException && failure.sqlState == "42P01") }
        }.map { it.first }.toSet() + companyCode.trim()
    }

    /** Codes of the companies in the owner group of [companyCode], itself included. */
    fun ownerGroupOf(companyCode: String): Set<String> = loginDatabases.ownerGroupOf(companyCode.trim()) + companyCode.trim()

    /** True si [username] ya es `postgres_role` de algun usuario en cualquier empresa. */
    private fun roleUsedInAnyCompany(username: String): Boolean =
        companyTenants().any { tenant -> roleUsedInTenant(tenant, username) }

    /**
     * Falla cerrado: solo una tabla inexistente (SQLState 42P01, empresa aun sin esquema) cuenta como
     * "no usado". Cualquier otro error (base inaccesible, conexion, permisos) impide verificar la
     * unicidad y se rechaza la operacion.
     */
    private fun roleUsedInTenant(tenant: TenantDatabaseConfig, username: String): Boolean =
        try {
            tenantRuntimeConnection(tenant).use { it.exists("application_users", "postgres_role", username) }
        } catch (failure: java.sql.SQLException) {
            if (failure.sqlState == "42P01") false
            else {
                log.warn("No se pudo verificar la unicidad del usuario en una empresa", failure)
                throw CompanyProvisioningException(tr("No se pudo verificar la unicidad del usuario"))
            }
        } catch (failure: Exception) {
            log.warn("No se pudo verificar la unicidad del usuario en una empresa", failure)
            throw CompanyProvisioningException(tr("No se pudo verificar la unicidad del usuario"))
        }

    /**
     * Defensa en profundidad: los CREATE/ALTER ROLE ya envian el verificador SCRAM-SHA-256
     * ([ScramSha256.verifier]) y no la contrasena en texto, asi que el texto plano nunca viaja ni se
     * registra. Aun asi se pide a PostgreSQL no registrar estos DDL en el log. Se hace por sesion (la
     * conexion de aprovisionamiento es de un solo uso); si el rol no puede cambiar el parametro solo
     * se avisa en el log, sin consecuencia para la contrasena.
     */
    private fun silenceStatementLog(statement: java.sql.Statement) {
        try {
            statement.execute("SET log_statement = 'none'")
        } catch (failure: java.sql.SQLException) {
            log.warn("No se pudo desactivar log_statement: las contrasenas del DDL pueden quedar en el log de PostgreSQL", failure)
        }
    }

    private class OwnerRef(val id: UUID, val role: String, val displayName: String?, val email: String?)

    /** User, name and email of the business owner of [companyCode]. */
    fun businessOwner(companyCode: String): BusinessOwnerResponse {
        val owner = ownerOf(companyCode.trim())
        val twoFactor = runCatching {
            loginDatabases.accountSource(companyCode.trim())?.connection?.use { connection ->
                loginDatabases.twoFactor.takeIf { it.available(connection) }?.isEnabled(connection, owner.id)
            }
        }.getOrNull()
        return BusinessOwnerResponse(owner.role, owner.displayName.orEmpty(), owner.email.orEmpty(), twoFactor, owner.id.toString())
    }

    /**
     * New user, name, email and, when given, password of the business owner of [companyCode], by the
     * server owner. The owner's PostgreSQL role is shared by every company it owns (duplicated
     * companies keep it), so the change applies to all of them. Renaming the role clears its password
     * in PostgreSQL, so a new user needs a new password.
     */
    fun updateBusinessOwner(companyCode: String, request: UpdateBusinessOwnerRequest, provisioningRole: String, actor: AuditActor?): BusinessOwnerResponse {
        val code = companyCode.trim()
        val owner = ownerOf(code)
        val username = request.username.trim()
        val displayName = request.displayName.trim()
        val email = request.email.trim()
        val password = request.password
        UserProfileRules.problem(displayName, email)?.let { throw CompanyProvisioningException(tr("Business owner: {0}", it)) }
        if (!postgresRolePattern.matches(username)) {
            throw CompanyProvisioningException(tr("El usuario debe iniciar con una letra minuscula y usar solo letras, numeros o guion bajo."))
        }
        val renamed = username != owner.role
        if (renamed && password.isEmpty()) {
            throw CompanyProvisioningException(tr("Para cambiar el usuario del business owner escribe tambien una contrasena nueva."))
        }
        if (password.isNotEmpty() && password.length < 12) {
            throw CompanyProvisioningException(tr("La contrasena del business owner debe tener al menos 12 caracteres."))
        }
        val tenants = companyTenants()
        val ownerTenants = tenants.filter { tenant -> ownerRoleIn(tenant, owner.role) }
        if (renamed) {
            val taken = tenantSchema.adminConnection(provisioningRole).use { it.existsRole(username) } ||
                centralLoginTaken(username) ||
                tenants.any { tenant -> roleUsedInTenant(tenant, username) }
            if (taken) throw CompanyProvisioningException(tr("Ya existe un usuario con ese nombre."))
        }

        // Audit row first, in an open central transaction committed after the change: a failed insert stops it.
        inCentralTransaction { connection ->
            insertAudit(
                connection, actor ?: AuditActor.businessOwner(owner.role), "company.owner_updated", code, null,
                auditDetails(
                    "businessOwnerUsername" to username,
                    "previousUsername" to owner.role,
                    "usernameChanged" to renamed,
                    "emailChanged" to EmailChangeRule.isChange(owner.email, email),
                    "passwordChanged" to password.isNotEmpty(),
                ),
            )
            tenantSchema.adminConnection(provisioningRole).use { admin ->
                admin.autoCommit = true
                admin.createStatement().use { statement ->
                    silenceStatementLog(statement)
                    if (renamed) statement.executeUpdate("ALTER ROLE ${quoteIdentifier(owner.role)} RENAME TO ${quoteIdentifier(username)}")
                    if (password.isNotEmpty()) statement.executeUpdate("ALTER ROLE ${quoteIdentifier(username)} PASSWORD ${quoteLiteral(ScramSha256.verifier(password))}")
                }
            }
            try {
                ownerTenants.forEach { tenant ->
                    tenantRuntimeConnection(tenant).use { connection ->
                      // The change and (from the server) its activity row in one company transaction.
                      connection.autoCommit = false
                      try {
                        val previous = connection.prepareStatement(
                            "SELECT display_name, email FROM application_users WHERE postgres_role = ? AND id IN (SELECT user_id FROM business_owner)"
                        ).use { query ->
                            query.setString(1, owner.role)
                            query.executeQuery().use { rows -> if (rows.next()) rows.getString(1) to rows.getString(2) else null to null }
                        }
                        val ownerIds = connection.prepareStatement(
                            """
                            UPDATE application_users SET postgres_role = ?, display_name = ?, email = ?
                            WHERE postgres_role = ? AND id IN (SELECT user_id FROM business_owner)
                            RETURNING id
                            """.trimIndent()
                        ).use { update ->
                            update.setString(1, username)
                            update.setString(2, displayName)
                            update.setString(3, email)
                            update.setString(4, owner.role)
                            update.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getObject(1, UUID::class.java)) } }
                        }
                        if (password.isNotEmpty()) {
                            ownerIds.forEach { id ->
                                // Owners sign in with their role; an application password would keep the old one working.
                                connection.prepareStatement("DELETE FROM application_user_credentials WHERE user_id = ?").use { delete ->
                                    delete.setObject(1, id)
                                    delete.executeUpdate()
                                }
                                connection.prepareStatement(
                                    "UPDATE application_sessions SET revoked_at = CURRENT_TIMESTAMP WHERE user_id = ? AND revoked_at IS NULL"
                                ).use { revoke ->
                                    revoke.setObject(1, id)
                                    revoke.executeUpdate()
                                }
                            }
                        }
                        // A change made from the server (the business owner's own edit is logged by CompanySelfService).
                        if (actor != null) {
                            val changes = com.ideasdeveloper.bms.server.activity.ActivityChanges.diff(
                                mapOf("username" to owner.role, "displayName" to previous.first, "email" to previous.second),
                                mapOf("username" to username, "displayName" to displayName, "email" to email),
                                mapOf("username" to "Usuario", "displayName" to "Nombre", "email" to "Correo"),
                            ) + listOfNotNull(if (password.isNotEmpty()) com.ideasdeveloper.bms.server.activity.ActivityChange.secret("password", "Contrasena") else null)
                            ownerIds.forEach { id ->
                                com.ideasdeveloper.bms.server.activity.CoreActivity.record(
                                    connection, com.ideasdeveloper.bms.server.activity.ActivityActor.fromAudit(actor, actorName(actor)), "user", id.toString(), username,
                                    com.ideasdeveloper.bms.server.activity.ActivityActions.Updated, "Modifico desde el servidor los datos del business owner $username.", changes,
                                )
                            }
                        }
                        connection.commit()
                      } catch (failure: Throwable) {
                        runCatching { connection.rollback() }
                        throw failure
                      } finally {
                        runCatching { connection.autoCommit = true }
                      }
                    }
                }
            } catch (failure: Exception) {
                // Without its row the owner could not sign in with the new user: give the role its name back.
                if (renamed) {
                    runCatching {
                        tenantSchema.adminConnection(provisioningRole).use { admin ->
                            admin.autoCommit = true
                            admin.createStatement().use { it.executeUpdate("ALTER ROLE ${quoteIdentifier(username)} RENAME TO ${quoteIdentifier(owner.role)}") }
                        }
                    }
                }
                throw failure
            }
        }
        return BusinessOwnerResponse(username, displayName, email)
    }

    /** The business owner edits their own data; runs as the first server owner's role, like password recovery. */
    fun updateBusinessOwnerBySelf(companyCode: String, request: UpdateBusinessOwnerRequest): BusinessOwnerResponse {
        val role = installer.firstServerOwnerRole() ?: throw CompanyProvisioningException(tr("El servidor no tiene un dueno activo."))
        return updateBusinessOwner(companyCode, request, role, null)
    }

    private fun ownerOf(companyCode: String): OwnerRef {
        rejectServerWorkspace(companyCode)
        val company = central.connection.use { connection -> findCompanyAnyState(connection, companyCode) }
            ?: throw CompanyProvisioningException(tr("Empresa no encontrada."))
        val tenant = loginDatabases.tenantConfig(company.databaseName) ?: tenantSchema.tenantConfig(company.databaseName)
        return tenantRuntimeConnection(tenant).use { connection ->
            connection.prepareStatement(
                """
                SELECT users.id, users.postgres_role, users.display_name, users.email
                FROM business_owner owner
                JOIN application_users users ON users.id = owner.user_id
                LIMIT 1
                """.trimIndent()
            ).use { query ->
                query.executeQuery().use { rows ->
                    if (rows.next()) OwnerRef(rows.getObject(1, UUID::class.java), rows.getString(2), rows.getString(3), rows.getString(4)) else null
                }
            }
        } ?: throw CompanyProvisioningException(tr("La empresa no tiene business owner."))
    }

    // Tenant databases of every company, active or not, and of the server workspace.
    private fun companyTenants(): List<TenantDatabaseConfig> = central.connection.use { connection ->
        connection.prepareStatement("SELECT database_name FROM companies").use { query ->
            query.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) {
                        val name = rows.getString(1)
                        add(loginDatabases.tenantConfig(name) ?: tenantSchema.tenantConfig(name))
                    }
                }
            }
        }
    }

    // Whether [role] is the business owner of [tenant]; a database that cannot be opened counts as no.
    private fun ownerRoleIn(tenant: TenantDatabaseConfig, role: String): Boolean = runCatching {
        tenantRuntimeConnection(tenant).use { connection ->
            connection.prepareStatement(
                "SELECT 1 FROM business_owner owner JOIN application_users users ON users.id = owner.user_id WHERE users.postgres_role = ?"
            ).use { query ->
                query.setString(1, role)
                query.executeQuery().use { it.next() }
            }
        }
    }.getOrDefault(false)

    // Postgres role of an active server owner, the role their changes run as; null when not one.
    fun serverOwnerRole(userId: UUID): String? = central.connection.use { connection ->
        connection.prepareStatement(
            """
            SELECT users.postgres_role
            FROM server_owners owners
            JOIN application_users users ON users.id = owners.user_id
            WHERE owners.user_id = ? AND users.is_active
            """.trimIndent()
        ).use { query ->
            query.setObject(1, userId)
            query.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
        }
    }

    fun setModuleEnabled(companyCode: String, moduleId: String, enabled: Boolean, provisioningRole: String, actor: AuditActor): CompanySummaryResponse {
        val normalizedCompanyCode = companyCode.trim()
        val normalizedModuleId = moduleId.trim()
        val definition = modules.singleOrNull { it.id == normalizedModuleId }
            ?: throw CompanyProvisioningException(tr("Modulo no reconocido o no instalado en el servidor."))
        if (definition.locked && !enabled) {
            throw CompanyProvisioningException(tr("El modulo {0} es base y no puede deshabilitarse.", trIn(I18n.moduleNamespace(definition.id), definition.displayName)))
        }

        val company = central.connection.use { connection ->
            findCompany(connection, normalizedCompanyCode)
                ?: throw CompanyProvisioningException(tr("Empresa no encontrada."))
        }
        val tenantConfig = loginDatabases.tenantConfig(company.databaseName)
            ?: throw CompanyProvisioningException(tr("La empresa no tiene conexion tenant configurada."))

        // The audit row is written first in an open central transaction and committed right after the
        // tenant change: a failed audit insert stops the change (two databases: only a failure of the
        // final central commit, after the tenant commit, could leave the change without its row).
        inCentralTransaction { connection ->
            insertAudit(
                connection = connection,
                actor = actor,
                action = if (enabled) "module.enabled" else "module.disabled",
                companyCode = normalizedCompanyCode,
                moduleId = definition.id,
                details = auditDetails("displayName" to definition.displayName),
            )
            tenantSchema.tenantAdminConnection(tenantConfig.jdbcUrl, provisioningRole).use { tenant ->
                tenantSchema.applyModuleMigrations(tenant, definition)
                installer.clearFailedModuleMigration(definition.id, normalizedCompanyCode)
                tenant.autoCommit = false
                try {
                    tenant.prepareStatement(
                        """
                        INSERT INTO tenant_modules (module_id, display_name, status, enabled_at)
                        VALUES (?, ?, ?, CASE WHEN ? THEN CURRENT_TIMESTAMP ELSE NULL END)
                        ON CONFLICT (module_id)
                        DO UPDATE SET
                            display_name = EXCLUDED.display_name,
                            status = EXCLUDED.status,
                            enabled_at = CASE
                                WHEN EXCLUDED.status = 'enabled' THEN COALESCE(tenant_modules.enabled_at, CURRENT_TIMESTAMP)
                                ELSE NULL
                            END
                        """.trimIndent()
                    ).use { upsert ->
                        upsert.setString(1, definition.id)
                        upsert.setString(2, definition.displayName)
                        upsert.setString(3, if (enabled) "enabled" else "disabled")
                        upsert.setBoolean(4, enabled)
                        upsert.executeUpdate()
                    }
                    // The company's own log, on this same tenant transaction: committed with this change or not at all.
                    recordOnTenant(tenant, actor, "module", definition.id, definition.displayName,
                        if (enabled) com.ideasdeveloper.bms.server.activity.ActivityActions.Enabled else com.ideasdeveloper.bms.server.activity.ActivityActions.Disabled,
                        if (enabled) "Activo el modulo ${definition.displayName} en la empresa." else "Desactivo el modulo ${definition.displayName} en la empresa.")
                    tenant.commit()
                } catch (failure: Exception) {
                    tenant.rollback()
                    throw failure
                } finally {
                    tenant.autoCommit = true
                }
            }
        }

        return CompanySummaryResponse(
            code = company.code,
            name = company.name,
            databaseName = company.databaseName,
            isActive = company.isActive,
            modules = loadCompanyModules(company.databaseName),
            logoUrl = company.logoUrl,
            primaryColor = company.primaryColor,
            secondaryColor = company.secondaryColor,
            accentColor = company.accentColor,
        )
    }

    /**
     * Base modules, installed modules and the modules offered by the catalog URL configured now.
     * Modules of a previously configured catalog that were never installed are not shown.
     */
    fun listServerModules(): List<ServerModuleResponse> = installer.listServerModules()

    fun serverModuleCatalog(): ServerModuleCatalogResponse = installer.serverModuleCatalog()

    fun updateServerModuleCatalog(catalogUrl: String, actor: AuditActor): ServerModuleCatalogResponse =
        installer.updateServerModuleCatalog(catalogUrl, actor)

    /**
     * Installs a module. Modules whose code is already loaded are activated at once. Optional
     * modules are downloaded from the configured catalog branch and built in the background; when
     * the package is ready the server restarts to load it. The response reports the progress.
     */
    fun installServerModule(moduleId: String, actor: AuditActor, appVersion: String? = null): ServerModuleResponse =
        installer.installServerModule(moduleId, actor, appVersion)

    /** Descarga el modulo del catalogo y devuelve commit, URL y hash calculado, para revisarlo antes de autorizar. */
    fun inspectServerModule(moduleId: String): ModulePackageInspection = installer.inspectServerModule(moduleId)

    fun authorizeUnsignedServerModule(
        moduleId: String,
        actor: AuditActor,
        confirmationText: String,
        expectedPackageSha256: String,
        expectedCommit: String,
    ): ModuleUnsignedAuthorizationResponse =
        installer.authorizeUnsignedServerModule(moduleId, actor, confirmationText, expectedPackageSha256, expectedCommit)

    /**
     * Turns a module on or off in the server workspace. Turning on the first module creates the
     * workspace with its own tenant database. Base modules are always on once it exists.
     */
    fun setServerModuleUsage(moduleId: String, enabled: Boolean, provisioningRole: String, actor: AuditActor): ServerModuleResponse =
        installer.setServerModuleUsage(moduleId, enabled, provisioningRole, actor)

    /** Whether the startup catch-up could not apply [moduleId]'s migrations in [companyCode] (its code would run on an old schema). */
    fun moduleMigrationFailed(moduleId: String, companyCode: String): Boolean = installer.moduleMigrationFailed(moduleId, companyCode)

    /**
     * Brings every company up to date with the migrations of the modules it uses: base modules for
     * all companies and optional modules where they are enabled. Runs at startup, so a module
     * version loaded after an installation or update applies its new migrations to the companies
     * that already had the module. Applied migrations are skipped (schema_migrations).
     */
    fun applyPendingModuleMigrations() = installer.applyPendingModuleMigrations()

    /** Whether the module is being installed now or waits for the restart that loads it: its extensions wait for it. */
    fun moduleInstallPending(moduleId: String): Boolean = installer.moduleInstallPending(moduleId)

    /**
     * Module installs that a restart interrupted (asked for while another package was restarting the
     * server, or cut while building) start again. Call it once at startup, off the main thread.
     */
    fun resumePendingInstalls() = installer.resumePendingInstalls()

    fun deleteServerModule(moduleId: String, actor: AuditActor) = installer.deleteServerModule(moduleId, actor)

    // Name of the server shown to server owners instead of a company name.
    fun updateServerName(name: String, provisioningRole: String, actor: AuditActor): ServerNameResponse {
        val normalized = name.trim()
        if (normalized.isEmpty() || normalized.length > 80) throw CompanyProvisioningException(tr("El nombre del servidor debe tener entre 1 y 80 caracteres."))
        if (UserProfileRules.hasControlCharacters(normalized)) throw CompanyProvisioningException(tr("El nombre no puede tener caracteres de control."))
        // The rename and its audit row commit together: a failed audit insert leaves the name as it was.
        inCentralTransaction { connection ->
            connection.prepareStatement(
                """
                INSERT INTO server_settings (setting_key, setting_value, updated_at)
                VALUES (?, ?, CURRENT_TIMESTAMP)
                ON CONFLICT (setting_key)
                DO UPDATE SET setting_value = EXCLUDED.setting_value, updated_at = CURRENT_TIMESTAMP
                """.trimIndent()
            ).use { upsert ->
                upsert.setString(1, ServerNameSetting)
                upsert.setString(2, normalized)
                upsert.executeUpdate()
            }
            insertAudit(connection, actor, "server.renamed", null, null, auditDetails("name" to normalized))
        }
        return ServerNameResponse(normalized)
    }

    // Code of the server workspace, creating it with its own tenant database on first use.
    @Synchronized
    fun ensureServerWorkspace(provisioningRole: String, actor: AuditActor): String {
        serverWorkspaceRow()?.let { return it.code }
        val code = ServerWorkspace.Code
        val databaseName = ServerWorkspace.DatabaseName
        central.connection.use { connection ->
            if (connection.exists("companies", "code", code)) {
                throw CompanyProvisioningException(tr("Ya existe una empresa con el codigo {0}; cambia su codigo para crear el espacio del servidor.", code))
            }
        }
        tenantSchema.adminConnection(provisioningRole).use { admin ->
            admin.autoCommit = true
            // A database left by an interrupted attempt is reused; its migrations are idempotent.
            if (!admin.existsDatabase(databaseName)) {
                admin.createStatement().use { statement ->
                    statement.executeUpdate("CREATE DATABASE ${quoteIdentifier(databaseName)}")
                    statement.executeUpdate("REVOKE CONNECT ON DATABASE ${quoteIdentifier(databaseName)} FROM PUBLIC")
                    statement.executeUpdate("GRANT CONNECT ON DATABASE ${quoteIdentifier(databaseName)} TO ${quoteIdentifier(config.runtimeUser)}")
                    tenantSchema.grantProvisioningConnect(statement, databaseName)
                }
            }
        }
        val tenantConfig = tenantSchema.tenantConfig(databaseName)
        tenantSchema.tenantAdminConnection(tenantConfig.jdbcUrl, provisioningRole).use { tenant ->
            tenantSchema.applyTenantCoreMigrations(tenant)
            tenantSchema.applyBaseModuleMigrations(tenant)
            tenantSchema.grantTenantPermissions(tenant, ownerUsername = null)
        }
        central.connection.use { connection ->
            connection.autoCommit = false
            try {
                connection.prepareStatement(
                    """
                    INSERT INTO companies (
                        id, code, name, logo_url, primary_color, secondary_color, accent_color, database_name, is_active, is_server_workspace
                    ) VALUES (?, ?, ?, NULL, '#667EEA', '#764BA2', '#FFFFFF', ?, TRUE, TRUE)
                    """.trimIndent()
                ).use { insert ->
                    insert.setObject(1, UUID.randomUUID())
                    insert.setString(2, code)
                    insert.setString(3, ServerWorkspace.Name)
                    insert.setString(4, databaseName)
                    insert.executeUpdate()
                }
                insertAudit(connection, actor, "server_workspace.created", code, null, auditDetails("databaseName" to databaseName))
                connection.commit()
            } catch (failure: Exception) {
                connection.rollback()
                throw failure
            } finally {
                connection.autoCommit = true
            }
        }
        loginDatabases.registerTenant(tenantConfig)
        log.info("Server workspace created in database {}", databaseName)
        return code
    }

    private fun serverWorkspaceRow(): CompanyRow? = try {
        central.connection.use { connection ->
            connection.prepareStatement(
                "SELECT code, name, logo_url, primary_color, secondary_color, accent_color, database_name, is_active FROM companies WHERE is_server_workspace"
            ).use { query ->
                query.executeQuery().use { rows ->
                    if (!rows.next()) null else CompanyRow(
                        code = rows.getString("code"),
                        name = rows.getString("name"),
                        logoUrl = rows.getString("logo_url"),
                        primaryColor = rows.getString("primary_color"),
                        secondaryColor = rows.getString("secondary_color"),
                        accentColor = rows.getString("accent_color"),
                        databaseName = rows.getString("database_name"),
                        isActive = rows.getBoolean("is_active"),
                    )
                }
            }
        }
    } catch (_: java.sql.SQLException) {
        // Before control migration V006 there is no workspace column.
        null
    }

    // Modules enabled in the server workspace, or null when it does not exist yet.
    private fun serverWorkspaceModules(): Set<String>? {
        val workspace = serverWorkspaceRow() ?: return null
        val tenant = loginDatabases.tenantConfig(workspace.databaseName) ?: return emptySet()
        return tenantRuntimeConnection(tenant).use { connection ->
            connection.prepareStatement("SELECT module_id FROM tenant_modules WHERE status = 'enabled'").use { query ->
                query.executeQuery().use { rows -> buildSet { while (rows.next()) add(rows.getString(1)) } }
            }
        }
    }

    private fun rejectServerWorkspace(companyCode: String) {
        if (companyCode == ServerWorkspace.Code && serverWorkspaceRow()?.code == companyCode) {
            throw CompanyProvisioningException(tr("El espacio del servidor no se administra como una empresa."))
        }
    }

    private fun validate(
        code: String,
        companyName: String,
        ownerUsername: String,
        ownerPassword: String,
        request: CreateCompanyRequest,
    ) {
        if (!companyCodePattern.matches(code)) {
            throw CompanyProvisioningException(tr("El codigo debe iniciar con una letra minuscula y usar solo letras, numeros o guion bajo."))
        }
        if (companyName.isBlank()) {
            throw CompanyProvisioningException(tr("El nombre de la empresa es obligatorio."))
        }
        if (UserProfileRules.hasControlCharacters(companyName)) {
            throw CompanyProvisioningException(tr("El nombre no puede tener caracteres de control."))
        }
        if (!postgresRolePattern.matches(ownerUsername)) {
            throw CompanyProvisioningException(tr("El usuario debe iniciar con una letra minuscula y usar solo letras, numeros o guion bajo."))
        }
        if (ownerPassword.length < 12) {
            throw CompanyProvisioningException(tr("La contrasena del business owner debe tener al menos 12 caracteres."))
        }
        if (!colorPattern.matches(request.primaryColor) ||
            !colorPattern.matches(request.secondaryColor) ||
            !colorPattern.matches(request.accentColor)
        ) {
            throw CompanyProvisioningException(tr("Los colores deben usar formato hexadecimal #RRGGBB."))
        }
    }

    private fun loadCompanyModules(databaseName: String): List<CompanyModuleResponse> {
        val tenantConfig = loginDatabases.tenantConfig(databaseName) ?: return modules.map { definition ->
            definition.toCompanyModuleResponse(enabled = definition.locked)
        }

        val states = mutableMapOf<String, Boolean>()
        tenantRuntimeConnection(tenantConfig).use { connection ->
            connection.prepareStatement("SELECT module_id, status FROM tenant_modules").use { query ->
                query.executeQuery().use { rows ->
                    while (rows.next()) {
                        states[rows.getString("module_id")] = rows.getString("status") == "enabled"
                    }
                }
            }
        }
        return modules.map { definition ->
            definition.toCompanyModuleResponse(enabled = if (definition.locked) true else states[definition.id] == true)
        }
    }

    private fun countCompaniesWithModuleEnabled(moduleId: String): Int {
        val companies = central.connection.use { connection ->
            connection.prepareStatement("SELECT database_name FROM companies ORDER BY code").use { query ->
                query.executeQuery().use { rows ->
                    buildList {
                        while (rows.next()) {
                            add(rows.getString("database_name"))
                        }
                    }
                }
            }
        }
        return companies.count { databaseName ->
            val tenantConfig = loginDatabases.tenantConfig(databaseName) ?: tenantSchema.tenantConfig(databaseName)
            tenantRuntimeConnection(tenantConfig).use { connection ->
                connection.prepareStatement(
                    "SELECT 1 FROM tenant_modules WHERE module_id = ? AND status = 'enabled'"
                ).use { query ->
                    query.setString(1, moduleId)
                    query.executeQuery().use { rows -> rows.next() }
                }
            }
        }
    }

    private fun tenantRuntimeConnection(tenant: TenantDatabaseConfig): Connection {
        val source = PGSimpleDataSource().apply {
            setURL(tenant.jdbcUrl)
            setUser(tenant.user)
            setPassword(tenant.password)
        }
        return source.connection
    }

    private fun insertCompany(connection: Connection, companyId: UUID, request: CreateCompanyRequest, databaseName: String, ownerGroup: UUID = companyId) {
        connection.prepareStatement(
            """
            INSERT INTO companies (
                id, code, name, logo_url, primary_color, secondary_color, accent_color, database_name, is_active, owner_group
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, TRUE, ?)
            """.trimIndent()
        ).use { insert ->
            insert.setObject(1, companyId)
            insert.setString(2, request.code.trim())
            insert.setString(3, request.name.trim())
            insert.setString(4, request.logoUrl?.trim()?.takeIf { it.isNotBlank() })
            insert.setString(5, request.primaryColor)
            insert.setString(6, request.secondaryColor)
            insert.setString(7, request.accentColor)
            insert.setString(8, databaseName)
            insert.setObject(9, ownerGroup)
            insert.executeUpdate()
        }
    }

    private fun findCompany(connection: Connection, companyCode: String): CompanyRow? {
        connection.prepareStatement(
            "SELECT code, name, logo_url, primary_color, secondary_color, accent_color, database_name, is_active FROM companies WHERE code = ? AND is_active = TRUE"
        ).use { query ->
            query.setString(1, companyCode)
            query.executeQuery().use { rows ->
                return if (rows.next()) {
                    CompanyRow(
                        code = rows.getString("code"),
                        name = rows.getString("name"),
                        logoUrl = rows.getString("logo_url"),
                        primaryColor = rows.getString("primary_color"),
                        secondaryColor = rows.getString("secondary_color"),
                        accentColor = rows.getString("accent_color"),
                        databaseName = rows.getString("database_name"),
                        isActive = rows.getBoolean("is_active"),
                    )
                } else {
                    null
                }
            }
        }
    }


    private fun findCompanyAnyState(connection: Connection, companyCode: String): CompanyRow? {
        connection.prepareStatement(
            "SELECT code, name, logo_url, primary_color, secondary_color, accent_color, database_name, is_active FROM companies WHERE code = ?"
        ).use { query ->
            query.setString(1, companyCode)
            query.executeQuery().use { rows ->
                return if (rows.next()) {
                    CompanyRow(
                        code = rows.getString("code"),
                        name = rows.getString("name"),
                        logoUrl = rows.getString("logo_url"),
                        primaryColor = rows.getString("primary_color"),
                        secondaryColor = rows.getString("secondary_color"),
                        accentColor = rows.getString("accent_color"),
                        databaseName = rows.getString("database_name"),
                        isActive = rows.getBoolean("is_active"),
                    )
                } else {
                    null
                }
            }
        }
    }

    /**
     * Runs [change] (a change in the central database, committed inside it) with the company's activity row for it
     * written first on an open transaction of the company database, committed only once [change] returned: a failed
     * row stops the change, a failed change leaves no row. Only a failed tenant commit after the central one can
     * leave the change without its row; that is logged and audited as activity.row_not_written.
     */
    private fun <T> withCompanyActivity(
        databaseName: String, actor: AuditActor, entityType: String, entityId: String?, label: String, action: String, summary: String,
        changes: List<com.ideasdeveloper.bms.server.activity.ActivityChange> = emptyList(), change: () -> T,
    ): T {
        val tenantConfig = loginDatabases.tenantConfig(databaseName)
            ?: throw CompanyProvisioningException(tr("La empresa no tiene conexion tenant configurada."))
        return tenantRuntimeConnection(tenantConfig).use { tenant ->
            tenant.autoCommit = false
            val result = try {
                com.ideasdeveloper.bms.server.activity.CoreActivity.record(
                    tenant, com.ideasdeveloper.bms.server.activity.ActivityActor.fromAudit(actor, actorName(actor)), entityType, entityId, label, action, summary, changes,
                )
                change()
            } catch (failure: Throwable) {
                runCatching { tenant.rollback() }
                throw failure
            }
            try {
                tenant.commit()
            } catch (failure: Exception) {
                log.error("El cambio {} de {} se confirmo pero su fila del registro de actividad no", action, databaseName, failure)
                runCatching {
                    central.connection.use { insertAudit(it, actor, "activity.row_not_written", null, null, auditDetails("databaseName" to databaseName, "action" to action, "summary" to summary)) }
                }
            }
            result
        }
    }

    /**
     * The company's activity row for a change made from the server by [actor], on [tenant] (the change's own
     * connection, in its transaction). A provisioning connection runs as the server owner's role, which may hold no
     * grant on a log another role owns (an older update.sh applied V011 as the superuser): the row is written as the
     * runtime role, which has them (the provisioning login may SET ROLE to it), and the role is set back.
     */
    private fun recordOnTenant(
        tenant: Connection, actor: AuditActor, entityType: String, entityId: String?, label: String, action: String, summary: String,
        changes: List<com.ideasdeveloper.bms.server.activity.ActivityChange> = emptyList(),
    ) {
        check(!tenant.autoCommit) { "recordOnTenant needs the change's open transaction" }
        val current = tenant.createStatement().use { statement ->
            statement.executeQuery("SELECT current_user, session_user").use { rows -> rows.next(); rows.getString(1) to rows.getString(2) }
        }
        val switched = current.first != config.runtimeUser
        if (switched) tenant.createStatement().use { it.execute("SET LOCAL ROLE ${quoteIdentifier(config.runtimeUser)}") }
        // A failure aborts the transaction, whose rollback also undoes the SET LOCAL.
        com.ideasdeveloper.bms.server.activity.CoreActivity.record(
            tenant, com.ideasdeveloper.bms.server.activity.ActivityActor.fromAudit(actor, actorName(actor)), entityType, entityId, label, action, summary, changes,
        )
        if (switched) tenant.createStatement().use { it.execute("SET LOCAL ROLE ${quoteIdentifier(current.first)}") }
    }

    // Display name of a server person for the activity log (their login when unknown).
    private fun actorName(actor: AuditActor): String? = actor.userId?.let { id -> loginDatabases.serverPerson(id)?.name }

    private fun previousLogo(connection: Connection, code: String, fallback: String?): String? =
        connection.prepareStatement("SELECT logo_url FROM companies WHERE code = ?").use { query ->
            query.setString(1, code)
            query.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else fallback }
        }

    /** Audit entry written by other Core services (extensions); [details] is a JSON object. */
    fun recordAudit(actor: AuditActor, action: String, companyCode: String?, moduleId: String?, details: String) {
        central.connection.use { connection -> insertAudit(connection, actor, action, companyCode, moduleId, details) }
    }

    /**
     * Applies the pending tenant migrations of an extension in one company with the provisioning
     * role and grants its runtime tables (EXTENSIONS.md, install). Applied migrations are skipped.
     */
    fun applyExtensionMigrations(companyCode: String, extension: com.ideasdeveloper.bms.server.extensions.ServerExtension) =
        tenantSchema.applyExtensionMigrations(companyCode, extension) { installer.firstServerOwnerRole() }

    /** Runs [block] in one central transaction (committed at the end, rolled back on any failure). */
    private fun <T> inCentralTransaction(block: (Connection) -> T): T = central.inCentralTransaction(block)

    private fun insertAudit(
        connection: Connection,
        actor: AuditActor,
        action: String,
        companyCode: String?,
        moduleId: String?,
        details: String,
    ) = ProvisioningAudit.insert(connection, actor, action, companyCode, moduleId, details)

    private data class CompanyRow(
        val code: String,
        val name: String,
        val logoUrl: String?,
        val primaryColor: String,
        val secondaryColor: String,
        val accentColor: String,
        val databaseName: String,
        val isActive: Boolean,
    )

    private fun ModuleDefinition.toCompanyModuleResponse(enabled: Boolean): CompanyModuleResponse =
        CompanyModuleResponse(
            moduleId = id,
            displayName = trIn(I18n.moduleNamespace(id), displayName),
            enabled = enabled,
            locked = locked,
        )
}
