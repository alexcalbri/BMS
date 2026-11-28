package com.ideasdeveloper.bms.server.company.application

import com.ideasdeveloper.bms.server.i18n.tr
import com.ideasdeveloper.bms.server.modules.install.ServerModuleInstaller
import com.ideasdeveloper.bms.server.admins.AuditActor
import com.ideasdeveloper.bms.server.modules.PackageInstallQueue
import com.ideasdeveloper.bms.server.modules.CompanyFiles
import com.ideasdeveloper.bms.server.security.ScramSha256
import com.ideasdeveloper.bms.server.company.api.ServerNameResponse
import com.ideasdeveloper.bms.server.auth.infrastructure.database.ServerNameSetting
import com.ideasdeveloper.bms.server.company.api.DuplicateOwnerRequest
import com.ideasdeveloper.bms.server.company.api.BusinessOwnerResponse
import com.ideasdeveloper.bms.server.company.api.UpdateBusinessOwnerRequest
import com.ideasdeveloper.bms.server.auth.application.UserProfileRules
import com.ideasdeveloper.bms.server.company.api.CreateCompanyRequest
import com.ideasdeveloper.bms.server.company.api.CreateCompanyResponse
import com.ideasdeveloper.bms.server.auth.infrastructure.database.LoginDatabases
import com.ideasdeveloper.bms.server.company.api.CompanySummaryResponse
import com.ideasdeveloper.bms.server.company.api.ServerModuleCatalogResponse
import com.ideasdeveloper.bms.server.company.api.ServerModuleResponse
import com.ideasdeveloper.bms.server.company.api.UpdateCompanyRequest
import com.ideasdeveloper.bms.server.modules.ModuleDefinition
import com.ideasdeveloper.bms.server.modules.ModuleRegistry
import com.ideasdeveloper.bms.server.modules.RemoteModuleCatalog
import com.ideasdeveloper.bms.server.i18n.I18n
import com.ideasdeveloper.bms.server.i18n.trIn
import com.ideasdeveloper.bms.server.modules.ModulePackageBuilder
import com.ideasdeveloper.bms.server.modules.ModulePackageInspection
import com.ideasdeveloper.bms.server.modules.ModuleUnsignedAuthorizationResponse
import com.ideasdeveloper.bms.server.modules.ServerModule
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
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
    private val companyCodePattern = Regex("[a-z][a-z0-9_]{0,62}")
    private val postgresRolePattern = Regex("[a-z][a-z0-9_]{0,62}")
    private val registry = CompanyRegistryQueries(central, loginDatabases, moduleRegistry, tenantSchema)
    private val activityRecorder = CompanyActivityRecorder(central, config, loginDatabases, registry)
    private val deletion = CompanyDeletion(
        central = central,
        config = config,
        loginDatabases = loginDatabases,
        tenantSchema = tenantSchema,
        registry = registry,
        companyFiles = companyFiles,
        activityIntegrity = activityIntegrity,
        rejectServerWorkspace = { code -> rejectServerWorkspace(code) },
    )
    private val workspace = ServerWorkspaceProvisioner(
        central = central,
        config = config,
        loginDatabases = loginDatabases,
        tenantSchema = tenantSchema,
        tenantRuntimeConnection = { tenant -> registry.tenantRuntimeConnection(tenant) },
    )
    private val owners = BusinessOwnerAccounts(
        central = central,
        loginDatabases = loginDatabases,
        tenantSchema = tenantSchema,
        postgresRolePattern = postgresRolePattern,
        firstServerOwnerRole = { installer.firstServerOwnerRole() },
        rejectServerWorkspace = { code -> rejectServerWorkspace(code) },
        findCompanyAnyState = { connection, code -> registry.findCompanyAnyState(connection, code) },
        tenantRuntimeConnection = { tenant -> registry.tenantRuntimeConnection(tenant) },
        actorName = { actor -> activityRecorder.actorName(actor) },
    )
    private val duplication = CompanyDuplication(
        central = central,
        config = config,
        loginDatabases = loginDatabases,
        moduleRegistry = moduleRegistry,
        tenantSchema = tenantSchema,
        owners = owners,
        companyCodePattern = companyCodePattern,
        rejectServerWorkspace = { code -> rejectServerWorkspace(code) },
        findCompany = { connection, code -> registry.findCompany(connection, code) },
        tenantRuntimeConnection = { tenant -> registry.tenantRuntimeConnection(tenant) },
        validate = { code, name, username, password, request -> validate(code, name, username, password, request) },
        insertCompany = { connection, id, request, database, group -> registry.insertCompany(connection, id, request, database, group) },
        loadCompanyModules = { database -> registry.loadCompanyModules(database) },
        setModuleEnabled = { company, module, enabled, role, actor -> setModuleEnabled(company, module, enabled, role, actor) },
    )
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
        countCompaniesWithModuleEnabled = { moduleId -> registry.countCompaniesWithModuleEnabled(moduleId) },
    )
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
                if (admin.existsRole(ownerUsername) || owners.roleUsedInAnyCompany(ownerUsername) || owners.centralLoginTaken(ownerUsername)) {
                    throw CompanyProvisioningException(tr("El usuario PostgreSQL del business owner ya existe."))
                }

                admin.createStatement().use { statement ->
                    owners.silenceStatementLog(statement)
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
                    registry.insertCompany(connection, companyId, request, databaseName)
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
                modules = registry.loadCompanyModules(company.databaseName),
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
            registry.findCompanyAnyState(connection, normalizedCompanyCode)
                ?: throw CompanyProvisioningException(tr("Empresa no encontrada."))
        }

        val oldLogo = central.connection.use { activityRecorder.previousLogo(it, normalizedCompanyCode, null) }
        // The company's own log row goes first, uncommitted, on its database; it commits only after the central
        // change did, so a failed central commit never leaves a row for a change that did not happen.
        activityRecorder.withCompanyActivity(
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
            modules = registry.loadCompanyModules(company.databaseName),
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
            registry.findCompanyAnyState(connection, normalizedCompanyCode)
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
            modules = registry.loadCompanyModules(company.databaseName),
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
    fun duplicateCompany(sourceCode: String, newCode: String, provisioningRole: String, actor: AuditActor, newOwner: DuplicateOwnerRequest? = null): CreateCompanyResponse =
        duplication.duplicateCompany(sourceCode, newCode, provisioningRole, actor, newOwner)

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
            registry.findCompanyAnyState(connection, code) ?: throw CompanyProvisioningException(tr("Empresa no encontrada."))
            val updated = if (targetCode == null) {
                connection.prepareStatement("UPDATE companies SET owner_group = id WHERE code = ?").use { update ->
                    update.setString(1, code)
                    update.executeUpdate()
                }
            } else {
                val target = targetCode.trim()
                if (target == code) throw CompanyProvisioningException(tr("Elige otra empresa para vincular."))
                registry.findCompanyAnyState(connection, target) ?: throw CompanyProvisioningException(tr("La empresa a vincular no existe."))
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

    fun deleteInactiveCompany(companyCode: String, provisioningRole: String, actor: AuditActor) =
        deletion.deleteInactiveCompany(companyCode, provisioningRole, actor)

    /**
     * Sets a new password for the company's business owner and closes their open sessions. Only the
     * server owner does this, from the app: companies created by an AI agent get a random password
     * nobody knows, because credentials never go through the MCP.
     */
    fun resetBusinessOwnerPassword(companyCode: String, password: String, provisioningRole: String, actor: AuditActor) =
        owners.resetBusinessOwnerPassword(companyCode, password, provisioningRole, actor)

    /**
     * New password of a business owner who proved, with a code sent to their email, that they own the
     * account (AccountService). Runs as the first server owner's role, which can change company roles.
     */
    fun setBusinessOwnerPasswordByEmailCode(companyCode: String, password: String) =
        owners.setBusinessOwnerPasswordByEmailCode(companyCode, password)

    /** New password of a server owner who proved with an email code that they own the account. */
    fun setServerOwnerPasswordByEmailCode(userId: UUID, password: String) =
        owners.setServerOwnerPasswordByEmailCode(userId, password)

    /**
     * Whether [login] can be the login of a new server administrator: not a PostgreSQL role, not a
     * central user and not a user of any company. Fails closed when a company cannot be checked.
     */
    fun loginNameTaken(login: String): Boolean = owners.loginNameTaken(login)

    /**
     * Codes of every company whose business owner has the same PostgreSQL role as the one of
     * [companyCode] (duplicated companies share it), [companyCode] included. Changing that owner's
     * user, email or password changes it in all of them.
     */
    fun companiesSharingOwner(companyCode: String): Set<String> = owners.companiesSharingOwner(companyCode)

    /** Codes of the companies in the owner group of [companyCode], itself included. */
    fun ownerGroupOf(companyCode: String): Set<String> = owners.ownerGroupOf(companyCode)

    /** User, name and email of the business owner of [companyCode]. */
    fun businessOwner(companyCode: String): BusinessOwnerResponse = owners.businessOwner(companyCode)

    /**
     * New user, name, email and, when given, password of the business owner of [companyCode], by the
     * server owner. The owner's PostgreSQL role is shared by every company it owns (duplicated
     * companies keep it), so the change applies to all of them. Renaming the role clears its password
     * in PostgreSQL, so a new user needs a new password.
     */
    fun updateBusinessOwner(companyCode: String, request: UpdateBusinessOwnerRequest, provisioningRole: String, actor: AuditActor?): BusinessOwnerResponse =
        owners.updateBusinessOwner(companyCode, request, provisioningRole, actor)

    /** The business owner edits their own data; runs as the first server owner's role, like password recovery. */
    fun updateBusinessOwnerBySelf(companyCode: String, request: UpdateBusinessOwnerRequest): BusinessOwnerResponse =
        owners.updateBusinessOwnerBySelf(companyCode, request)

    // Postgres role of an active server owner, the role their changes run as; null when not one.
    fun serverOwnerRole(userId: UUID): String? = owners.serverOwnerRole(userId)

    fun setModuleEnabled(companyCode: String, moduleId: String, enabled: Boolean, provisioningRole: String, actor: AuditActor): CompanySummaryResponse {
        val normalizedCompanyCode = companyCode.trim()
        val normalizedModuleId = moduleId.trim()
        val definition = modules.singleOrNull { it.id == normalizedModuleId }
            ?: throw CompanyProvisioningException(tr("Modulo no reconocido o no instalado en el servidor."))
        if (definition.locked && !enabled) {
            throw CompanyProvisioningException(tr("El modulo {0} es base y no puede deshabilitarse.", trIn(I18n.moduleNamespace(definition.id), definition.displayName)))
        }

        val company = central.connection.use { connection ->
            registry.findCompany(connection, normalizedCompanyCode)
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
                    activityRecorder.recordOnTenant(tenant, actor, "module", definition.id, definition.displayName,
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
            modules = registry.loadCompanyModules(company.databaseName),
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
    fun ensureServerWorkspace(provisioningRole: String, actor: AuditActor): String =
        workspace.ensureServerWorkspace(provisioningRole, actor)

    private fun serverWorkspaceRow(): CompanyRow? = workspace.serverWorkspaceRow()

    // Modules enabled in the server workspace, or null when it does not exist yet.
    private fun serverWorkspaceModules(): Set<String>? = workspace.serverWorkspaceModules()

    private fun rejectServerWorkspace(companyCode: String) = workspace.rejectServerWorkspace(companyCode)

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


}
