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

// Error de dominio para operaciones de autoservicio de empresa.
class CompanySelfException(message: String) : Exception(message)

// Datos del correo de la empresa que no se aceptan (400).
class CompanyMailRejectedException(message: String) : Exception(message)

// Fallo interno al guardar el correo de la empresa (500, mensaje generico).
class CompanyMailStorageException(message: String) : Exception(message)

// Servicio de autoservicio para que el business_owner administre solo su propia empresa
// y, en futuras rutas, usuarios/permisos dentro de su propia base tenant.
// Este servicio no cambia disponibilidad de modulos; esa decision pertenece a server_owner.
class CompanySelfService(
    private val central: DataSource,
    private val loginDatabases: LoginDatabases,
    private val moduleRegistry: ModuleRegistry,
    private val backupsRoot: Path,
    // Edits the business owner (a PostgreSQL role shared by the companies it owns); see
    // CompanyProvisioningService.updateBusinessOwnerBySelf. Null where owners cannot be edited.
    private val ownerEditor: ((companyCode: String, request: UpdateBusinessOwnerRequest) -> BusinessOwnerResponse)? = null,
    // Contrasena actual del business owner, como en el login (LoginDatabases.verifyPassword). Sin ella, el
    // business owner no puede cambiar su propio correo.
    private val verifyOwnerPassword: ((companyCode: String, username: String, password: String) -> Boolean)? = null,
    // Tope de fallos de contrasena actual por usuario; se comparte con AccountService (misma clave "empresa/usuario").
    private val passwordAttempts: PasswordAttemptLimiter = PasswordAttemptLimiter(),
    // Sella la contrasena del SMTP de la empresa; sin llave maestra no se puede guardar una contrasena.
    private val secrets: SecretBox? = null,
    // Server administrators with server.workspace also manage the server workspace (SERVER_ADMINS.md §3).
    private val serverActors: ServerActorResolver? = null,
    // Quota, free-disk floor and uploads in progress of the company (STORAGE_QUOTA.md); imported backups count too.
    private val storage: com.ideasdeveloper.bms.server.storage.StorageGuard? = null,
    // Turning a user's two-step verification off (TWO_FACTOR.md §8).
    private val twoFactorResets: com.ideasdeveloper.bms.server.auth.twofactor.TwoFactorResetService? = null,
) {
    // Reutiliza el hash de sesion para validar tokens sin exponerlos en base de datos.
    private val tokenGenerator = SessionTokenGenerator()
    // Valida colores aceptados por la tabla central companies.
    private val colorPattern = Regex("#[0-9A-Fa-f]{6}")
    private val backups = CompanyBackups(this, backupsRoot, storage)
    private val catalog = CompanyPermissionCatalog(this, moduleRegistry, loginDatabases)
    private val userAdministration = CompanyUserAdministration(
        this, central, loginDatabases, catalog, ownerEditor, verifyOwnerPassword, passwordAttempts, twoFactorResets, tokenGenerator,
    )

    // Carga el perfil editable de la empresa de la sesion business_owner. El server_owner tambien
    // administra asi la empresa del servidor, sin crear una empresa.
    fun profile(companyCode: String, accessToken: String): CompanyProfileResponse {
        val context = authorizeUserAdministrator(companyCode, accessToken)
        val language = central.connection.use { connection -> companyLanguage(connection, context.companyCode) }
        return context.profile.copy(defaultLanguage = language)
    }

    // Actualiza nombre, logo y colores sin tocar datos tenant.
    fun updateProfile(companyCode: String, accessToken: String, request: UpdateCompanyProfileRequest): CompanyProfileResponse {
        val context = authorizeUserAdministrator(companyCode, accessToken)
        val name = request.name.trim()
        val logoUrl = request.logoUrl?.trim()?.takeIf { it.isNotBlank() }
        if (name.isBlank()) {
            throw CompanySelfException(tr("El nombre de la empresa es obligatorio."))
        }
        if (com.ideasdeveloper.bms.server.auth.application.UserProfileRules.hasControlCharacters(name)) {
            throw CompanySelfException(tr("El nombre no puede tener caracteres de control."))
        }
        if (!colorPattern.matches(request.primaryColor) ||
            !colorPattern.matches(request.secondaryColor) ||
            !colorPattern.matches(request.accentColor)
        ) {
            throw CompanySelfException(tr("Los colores deben usar formato hexadecimal #RRGGBB."))
        }
        val language = request.defaultLanguage?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        if (language != null && language !in I18n.Languages) {
            throw CompanySelfException(tr("El idioma no es valido."))
        }

        val before = context.profile
        val beforeLanguage = central.connection.use { connection -> companyLanguage(connection, context.companyCode) }
        val changes = ActivityChanges.diff(
            mapOf("name" to before.name, "logoUrl" to before.logoUrl, "primaryColor" to before.primaryColor, "secondaryColor" to before.secondaryColor,
                "accentColor" to before.accentColor, "defaultLanguage" to beforeLanguage),
            mapOf("name" to name, "logoUrl" to logoUrl, "primaryColor" to request.primaryColor, "secondaryColor" to request.secondaryColor,
                "accentColor" to request.accentColor, "defaultLanguage" to (language ?: beforeLanguage)),
            CompanyProfileLabels,
        )
        // In the workspace the change and its audit row are one central transaction; the company's
        // activity row is written first in its own open transaction (logged).
        context.audited("server_workspace.profile_updated", auditDetails("name" to name, "defaultLanguage" to language)) { audit ->
          context.logged({ tenant ->
              if (changes.isNotEmpty()) {
                  context.record(tenant, "company", context.companyCode, name, ActivityActions.SettingsChanged, "Cambio los datos de la empresa.", changes.filter { it.field != "defaultLanguage" })
              }
              changes.firstOrNull { it.field == "defaultLanguage" }?.let { change ->
                  context.record(tenant, "company", context.companyCode, name, ActivityActions.SettingsChanged, "Cambio el idioma predeterminado de la empresa.", listOf(change))
              }
          }) {
            fun write(connection: java.sql.Connection) {
                connection.prepareStatement(
                    """
                    UPDATE companies
                    SET name = ?, logo_url = ?, primary_color = ?, secondary_color = ?, accent_color = ?
                    WHERE code = ? AND is_active = TRUE
                    """.trimIndent()
                ).use { update ->
                    update.setString(1, name)
                    update.setString(2, logoUrl)
                    update.setString(3, request.primaryColor)
                    update.setString(4, request.secondaryColor)
                    update.setString(5, request.accentColor)
                    update.setString(6, context.companyCode)
                    update.executeUpdate()
                }
                if (language != null) setCompanyLanguage(connection, context.companyCode, language)
            }
            if (audit != null) write(audit) else central.connection.use { write(it) }
          }
        }

        return profile(companyCode, accessToken)
    }

    // Lista ZIPs ya creados para la empresa.
    fun listBackups(companyCode: String, accessToken: String): List<CompanyBackupResponse> = backups.listBackups(companyCode, accessToken)

    // Genera un ZIP no destructivo con manifest y perfil; la restauracion completa de PostgreSQL es operativa y externa a la app.
    fun createProfileBackup(companyCode: String, accessToken: String): CompanyBackupResponse = backups.createProfileBackup(companyCode, accessToken)

    /** The backup to download, after recording the download (a bulk read, ACTIVITY_LOG.md §4). */
    fun downloadBackup(companyCode: String, accessToken: String, fileName: String): Path = backups.downloadBackup(companyCode, accessToken, fileName)

    // Resuelve el archivo ZIP solicitado dentro del directorio seguro de la empresa.
    fun backupPath(companyCode: String, accessToken: String, fileName: String): Path = backups.backupPath(companyCode, accessToken, fileName)

    // Elimina un ZIP existente despues de validar que pertenece a la empresa.
    fun deleteBackup(companyCode: String, accessToken: String, fileName: String) = backups.deleteBackup(companyCode, accessToken, fileName)

    // Guarda un ZIP subido por el usuario sin ejecutar restauracion destructiva.
    fun storeImportedBackup(companyCode: String, accessToken: String, fileName: String, bytes: ByteArray): CompanyBackupResponse =
        backups.storeImportedBackup(companyCode, accessToken, fileName, bytes)

    /**
     * Stores an uploaded backup ZIP streaming it to disk (SECURITY_FIXES.md B4): at most
     * [BackupImportLimits.MaxBytes] are read, then the ZIP is checked entry by entry (at most
     * [BackupImportLimits.MaxEntries] entries and [BackupImportLimits.MaxUncompressedBytes]
     * uncompressed, so a ZIP bomb is refused without expanding it) before it takes its name.
     */
    fun storeImportedBackup(companyCode: String, accessToken: String, fileName: String, content: java.io.InputStream, expectedBytes: Long? = null): CompanyBackupResponse =
        backups.storeImportedBackup(companyCode, accessToken, fileName, content, expectedBytes)

    // SMTP de la empresa para el dueno (o el dueno del servidor en su espacio). Nunca devuelve la contrasena.
    fun mailSettings(companyCode: String, accessToken: String): CompanyMailForm {
        val context = authorizeUserAdministrator(companyCode, accessToken)
        tenantConnection(context.tenant).use { connection -> return CompanyMail.load(connection) }
    }

    // Guarda el SMTP de la empresa. Falla de autorizacion: CompanySelfException; datos no validos: CompanyMailRejectedException;
    // fallo al guardar: CompanyMailStorageException (mensaje generico, el detalle va al log).
    fun updateMailSettings(companyCode: String, accessToken: String, input: CompanyMailInput): CompanyMailForm {
        val context = authorizeUserAdministrator(companyCode, accessToken)
        try {
            CompanyMail.validate(input)
        } catch (failure: MailException) {
            throw CompanyMailRejectedException(failure.message ?: tr("Servidor de correo no valido."))
        }
        val box = secrets
        if (!input.password.isNullOrEmpty() && (box == null || !box.available)) {
            throw CompanyMailRejectedException(tr("No se pueden guardar secretos: falta la llave maestra del servidor (BMS_MASTER_KEY). Avisa al dueno del servidor."))
        }
        try {
            return context.audited("server_workspace.mail_updated", auditDetails("host" to input.host)) {
                context.logged({ }) { connection ->
                    val before = CompanyMail.load(connection)
                    CompanyMail.save(connection, box ?: MasterKeySecretBox(null), input)
                    val after = CompanyMail.load(connection)
                    // The password is never stored in the log: only that it changed.
                    val changes = ActivityChanges.diff(
                        mapOf("host" to before.host, "port" to before.port, "username" to before.username, "from" to before.from, "security" to before.security),
                        mapOf("host" to after.host, "port" to after.port, "username" to after.username, "from" to after.from, "security" to after.security),
                        mapOf("host" to "Servidor", "port" to "Puerto", "username" to "Usuario", "from" to "Remitente", "security" to "Seguridad"),
                    ) + listOfNotNull(if (input.password != null && (input.password.isNotEmpty() || before.passwordSet)) ActivityChange.secret("password", "Contrasena") else null)
                    context.record(connection, "mail_settings", null, "Correo de la empresa", ActivityActions.SettingsChanged, "Cambio el correo saliente de la empresa.", changes)
                    after
                }
            }
        } catch (failure: SecretsUnavailableException) {
            throw CompanyMailRejectedException(tr("No se pueden guardar secretos: falta la llave maestra del servidor (BMS_MASTER_KEY). Avisa al dueno del servidor."))
        } catch (failure: IllegalArgumentException) {
            throw CompanyMailRejectedException(tr("La contrasena no es valida."))
        } catch (failure: SQLException) {
            LoggerFactory.getLogger(CompanySelfService::class.java).error("No se pudo guardar el correo de la empresa {}", companyCode, failure)
            throw CompanyMailStorageException(tr("No se pudo guardar el correo de la empresa."))
        }
    }

    /**
     * The business owner turns off the two-step verification of another user of the company (TWO_FACTOR.md §8):
     * only with their own session (never one opened from the server switcher, nor in the server workspace) and
     * never for themself.
     */
    fun disableUserTwoFactor(companyCode: String, accessToken: String, userId: UUID, currentPassword: String?): com.ideasdeveloper.bms.server.auth.twofactor.TwoFactorResetResponse =
        userAdministration.disableUserTwoFactor(companyCode, accessToken, userId, currentPassword)

    fun users(companyCode: String, accessToken: String): CompanyUsersResponse = userAdministration.users(companyCode, accessToken)

    fun createUser(companyCode: String, accessToken: String, request: CreateCompanyUserRequest): CompanyUserResponse =
        userAdministration.createUser(companyCode, accessToken, request)

    fun updateUserPermissions(
        companyCode: String,
        accessToken: String,
        userId: String,
        request: UpdateCompanyUserPermissionsRequest,
    ): CompanyUserResponse = userAdministration.updateUserPermissions(companyCode, accessToken, userId, request)

    /**
     * Every field of a user: user, name, email, active, permissions and, when given, a new password.
     * The business owner edits themself here too (their user and password are a PostgreSQL role, so
     * that goes through [ownerEditor]); their permissions and active state do not change.
     */
    fun updateUser(companyCode: String, accessToken: String, userId: String, request: UpdateCompanyUserRequest): CompanyUserResponse =
        userAdministration.updateUser(companyCode, accessToken, userId, request)

    /**
     * Users and permissions are managed by the company's business_owner, or, in the server
     * workspace (which has no business owner), by any server_owner with a central session.
     */
    internal fun authorizeUserAdministrator(companyCode: String, accessToken: String): BusinessOwnerContext {
        val code = companyCode.trim()
        val workspaceActor = if (code != ServerWorkspace.Code) null else when {
            isServerOwnerSession(accessToken) -> serverActors?.resolve(accessToken).let { it as? ServerActorLookup.Found }?.actor?.audit
                ?: AuditActor(ServerActorKind.Owner.value, null, AuditActor.ServerOwnerKind)
            else -> when (val lookup = serverActors?.resolve(accessToken)) {
                is ServerActorLookup.Found -> if (lookup.actor.has(ServerPermission.Workspace)) lookup.actor.audit
                    else throw CompanySelfException(tr("Necesitas el permiso {0}.", ServerPermission.Workspace.id))
                is ServerActorLookup.PasswordChangeRequired -> throw CompanySelfException(
                    if (lookup.twoFactorSetup) tr("Activa la verificacion en dos pasos para continuar.") else tr("Cambia tu contrasena antes de continuar."),
                )
                else -> null
            }
        }
        if (workspaceActor != null) {
            val workspace = central.connection.use { connection ->
                connection.prepareStatement(
                    """
                    SELECT code, name, logo_url, primary_color, secondary_color, accent_color, database_name
                    FROM companies
                    WHERE code = ? AND is_server_workspace AND is_active = TRUE
                    """.trimIndent()
                ).use { query ->
                    query.setString(1, code)
                    query.executeQuery().use { rows ->
                        if (!rows.next()) {
                            throw CompanySelfException(tr("El espacio del servidor aun no existe; activa un modulo para el servidor."))
                        }
                        rows.getString("database_name") to CompanyProfileResponse(
                            code = rows.getString("code"),
                            name = rows.getString("name"),
                            logoUrl = rows.getString("logo_url"),
                            primaryColor = rows.getString("primary_color"),
                            secondaryColor = rows.getString("secondary_color"),
                            accentColor = rows.getString("accent_color"),
                        )
                    }
                }
            }
            val tenant = loginDatabases.tenantConfig(workspace.first)
                ?: throw CompanySelfException(tr("El espacio del servidor no tiene conexion configurada."))
            return BusinessOwnerContext(
                companyCode = code,
                databaseName = workspace.first,
                tenant = tenant,
                profile = workspace.second,
                businessOwnerUserId = null,
                businessOwnerRole = null,
                serverActor = workspaceActor,
                activity = workspaceActor.userId?.let { id ->
                    val person = loginDatabases.serverPerson(id)
                    val name = person?.name ?: workspaceActor.login
                    if (workspaceActor.kind == AuditActor.ServerAdminKind) ActivityActor.serverAdmin(id, name) else ActivityActor.serverOwner(id, name)
                } ?: ActivityActor.serverOwner(null, workspaceActor.login),
            )
        }
        return authorizeBusinessOwner(code, accessToken)
    }

    // Central session of an active server_owner (server owners do not sign in to tenant databases).
    private fun isServerOwnerSession(accessToken: String): Boolean = central.connection.use { connection ->
        connection.prepareStatement(
            """
            SELECT 1
            FROM application_sessions sessions
            JOIN server_owners owners ON owners.user_id = sessions.user_id
            JOIN application_users users ON users.id = sessions.user_id
            WHERE sessions.token_hash = ?
              AND sessions.revoked_at IS NULL
              AND sessions.expires_at > CURRENT_TIMESTAMP
              AND users.is_active = TRUE
            """.trimIndent()
        ).use { query ->
            query.setString(1, tokenGenerator.hash(accessToken))
            query.executeQuery().use { rows -> rows.next() }
        }
    }

    // Valida que el token pertenezca al business_owner de la empresa indicada.
    internal fun authorizeBusinessOwner(companyCode: String, accessToken: String): BusinessOwnerContext {
        val code = companyCode.trim()
        val company = central.connection.use { connection ->
            connection.prepareStatement(
                """
                SELECT code, name, logo_url, primary_color, secondary_color, accent_color, database_name
                FROM companies
                WHERE code = ? AND is_active = TRUE
                """.trimIndent()
            ).use { query ->
                query.setString(1, code)
                query.executeQuery().use { rows ->
                    if (!rows.next()) throw CompanySelfException(tr("Empresa no encontrada."))
                    CompanyRow(
                        code = rows.getString("code"),
                        databaseName = rows.getString("database_name"),
                        profile = CompanyProfileResponse(
                            code = rows.getString("code"),
                            name = rows.getString("name"),
                            logoUrl = rows.getString("logo_url"),
                            primaryColor = rows.getString("primary_color"),
                            secondaryColor = rows.getString("secondary_color"),
                            accentColor = rows.getString("accent_color"),
                        ),
                    )
                }
            }
        }
        // Comprueba la sesion dentro de la base tenant, no en la base central.
        val tenant = loginDatabases.tenantConfig(company.databaseName)
            ?: throw CompanySelfException(tr("La empresa no tiene conexion tenant configurada."))
        val tokenHash = tokenGenerator.hash(accessToken)
        val ownerUserId = tenantConnection(tenant).use { connection ->
            connection.prepareStatement(
                """
                SELECT users.id, users.postgres_role, users.display_name
                FROM application_sessions sessions
                JOIN application_users users ON users.id = sessions.user_id
                JOIN business_owner owner ON owner.user_id = users.id
                WHERE sessions.token_hash = ?
                  AND sessions.revoked_at IS NULL
                  AND sessions.expires_at > CURRENT_TIMESTAMP
                  AND users.is_active = TRUE
                """.trimIndent()
            ).use { query ->
                query.setString(1, tokenHash)
                query.executeQuery().use { rows ->
                    if (!rows.next()) throw CompanySelfException(tr("Debes iniciar sesion como business_owner."))
                    Triple(rows.getObject("id", UUID::class.java), rows.getString("postgres_role"), rows.getString("display_name"))
                }
            }
        }
        // A session opened from the switcher keeps working only while its opener may still enter.
        if (!loginDatabases.companySessionAllowed(company.code, accessToken)) {
            throw CompanySelfException(tr("Debes iniciar sesion como business_owner."))
        }
        return BusinessOwnerContext(
            companyCode = company.code,
            databaseName = company.databaseName,
            tenant = tenant,
            profile = company.profile,
            businessOwnerUserId = ownerUserId.first,
            businessOwnerRole = ownerUserId.second,
            activity = run {
                val name = ownerUserId.third?.takeIf { it.isNotBlank() } ?: ownerUserId.second
                loginDatabases.companySessionOpener(company.code, accessToken)
                    ?.let { opener -> ActivityActor.switcher(ownerUserId.first, name, opener.kind, opener.userId, opener.name) }
                    ?: ActivityActor.businessOwner(ownerUserId.first, name)
            },
        )
    }

    // Abre una conexion runtime al tenant ya resuelto por el servidor.
    internal fun tenantConnection(tenant: com.ideasdeveloper.bms.server.auth.infrastructure.database.TenantDatabaseConfig) =
        PGSimpleDataSource().apply {
            setURL(tenant.jdbcUrl)
            setUser(tenant.user)
            setPassword(tenant.password)
        }.connection

    // Fila central de empresa necesaria para operaciones de autoservicio.
    private data class CompanyRow(
        val code: String,
        val databaseName: String,
        val profile: CompanyProfileResponse,
    )

    // Contexto autorizado para ejecutar acciones sobre una empresa tenant.
    internal data class BusinessOwnerContext(
        val companyCode: String,
        val databaseName: String,
        val tenant: com.ideasdeveloper.bms.server.auth.infrastructure.database.TenantDatabaseConfig,
        val profile: CompanyProfileResponse,
        // Null in the server workspace, which has no business owner.
        val businessOwnerUserId: UUID?,
        val businessOwnerRole: String?,
        // Who acts in the server workspace (owner or administrator); null in a company.
        val serverActor: AuditActor? = null,
        // Who acts for the company activity log (switcher sessions name the server person).
        val activity: ActivityActor = ActivityActor.system(),
    )

    /**
     * Runs [change] with its audit row when the owner or an administrator acts in the server
     * workspace: the row is inserted first in an open central transaction (passed to [change] for
     * more central rows), the change commits its own work, then the central transaction commits. A
     * failed audit insert stops the change, and an audit failure is never swallowed. A company's
     * changes by its own business owner are not audited here ([change] gets null).
     */
    private fun <T> BusinessOwnerContext.audited(action: String, details: String, change: (java.sql.Connection?) -> T): T {
        val actor = serverActor ?: return change(null)
        return central.connection.use { connection ->
            connection.autoCommit = false
            try {
                ProvisioningAudit.insert(connection, actor, action, companyCode, null, details)
                change(connection).also { connection.commit() }
            } catch (failure: Throwable) {
                runCatching { connection.rollback() }
                throw failure
            } finally {
                runCatching { connection.autoCommit = true }
            }
        }
    }

    /**
     * Runs [change] with its row in the company's activity log: the row is inserted first in an open
     * tenant transaction ([change] gets that connection and may write more there), the change runs,
     * then the tenant transaction commits. A failed insert stops the change (ACTIVITY_LOG.md §1.2);
     * only a failed final commit after a change in another database can leave it without its row.
     */
    private fun <T> BusinessOwnerContext.logged(rows: (java.sql.Connection) -> Unit, change: (java.sql.Connection) -> T): T =
        tenantConnection(tenant).use { connection ->
            connection.autoCommit = false
            try {
                rows(connection)
                change(connection).also { connection.commit() }
            } catch (failure: Throwable) {
                runCatching { connection.rollback() }
                throw failure
            } finally {
                runCatching { connection.autoCommit = true }
            }
        }

    private fun BusinessOwnerContext.record(
        connection: java.sql.Connection, entityType: String, entityId: String?, label: String, action: String, summary: String,
        changes: List<ActivityChange> = emptyList(),
    ) = CoreActivity.record(connection, activity, entityType, entityId, label, action, summary, changes)

    // Entry points of the internal collaborators (CompanyBackups, ...): the same helpers, given the context explicitly.
    internal fun <T> auditedFor(context: BusinessOwnerContext, action: String, details: String, change: (java.sql.Connection?) -> T): T =
        context.audited(action, details, change)

    internal fun <T> loggedFor(context: BusinessOwnerContext, rows: (java.sql.Connection) -> Unit, change: (java.sql.Connection) -> T): T =
        context.logged(rows, change)

    internal fun recordFor(
        context: BusinessOwnerContext, connection: java.sql.Connection, entityType: String, entityId: String?, label: String, action: String,
        summary: String, changes: List<ActivityChange> = emptyList(),
    ) = context.record(connection, entityType, entityId, label, action, summary, changes)

    /**
     * An administrator was deactivated, deleted or lost server.workspace: the workspace staff
     * accounts whose credentials they know (server_workspace_staff) are deactivated and their
     * sessions revoked, audited as [by] (the owner). Returns how many accounts it closed.
     */
    fun deactivateWorkspaceStaffOf(adminUserId: UUID, by: AuditActor, reason: String): Int =
        userAdministration.deactivateWorkspaceStaffOf(adminUserId, by, reason)

}

// Spanish labels of the activity log rows of Core (stored as written, ACTIVITY_LOG.md §1.5).
private val CompanyProfileLabels = mapOf(
    "name" to "Nombre", "logoUrl" to "Logo", "primaryColor" to "Color primario", "secondaryColor" to "Color secundario",
    "accentColor" to "Color de acento", "defaultLanguage" to "Idioma predeterminado",
)
