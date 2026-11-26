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
) {
    // Reutiliza el hash de sesion para validar tokens sin exponerlos en base de datos.
    private val tokenGenerator = SessionTokenGenerator()
    // Valida colores aceptados por la tabla central companies.
    private val colorPattern = Regex("#[0-9A-Fa-f]{6}")
    // Restringe nombres de ZIP para evitar rutas arbitrarias o archivos ajenos al tenant.
    private val backupNamePattern = Regex("[a-z][a-z0-9_]{0,62}-backup-[0-9]{8}T[0-9]{6}Z\\.zip")
    private val postgresRolePattern = Regex("[a-z][a-z0-9_]{0,62}")
    // Serializador usado para manifest y metadata del ZIP.
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

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
    fun listBackups(companyCode: String, accessToken: String): List<CompanyBackupResponse> {
        val context = authorizeBusinessOwner(companyCode, accessToken)
        val directory = backupDirectory(context.companyCode)
        if (!directory.exists()) return emptyList()
        return Files.list(directory).use { stream ->
            stream
                .filter { it.extension == "zip" && backupNamePattern.matches(it.name) }
                .map { path ->
                    CompanyBackupResponse(
                        fileName = path.name,
                        sizeBytes = path.fileSize(),
                        createdAt = Files.getLastModifiedTime(path).toInstant().toString(),
                        downloadUrl = "/companies/me/backups/${path.name}/download",
                    )
                }
                .sorted { left, right -> right.createdAt.compareTo(left.createdAt) }
                .toList()
        }
    }

    // Genera un ZIP no destructivo con manifest y perfil; la restauracion completa de PostgreSQL es operativa y externa a la app.
    fun createProfileBackup(companyCode: String, accessToken: String): CompanyBackupResponse {
        val context = authorizeBusinessOwner(companyCode, accessToken)
        val createdAt = OffsetDateTime.now(java.time.ZoneOffset.UTC)
        val fileName = "${context.companyCode}-backup-${DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").format(createdAt)}.zip"
        val output = backupDirectory(context.companyCode).also { it.createDirectories() }.resolve(fileName)

        output.outputStream().use { file ->
            ZipOutputStream(file).use { zip ->
                zip.putNextEntry(ZipEntry("manifest.json"))
                zip.write(json.encodeToString(BackupManifest(context.companyCode, createdAt.toString(), destructiveRestore = false)).toByteArray())
                zip.closeEntry()

                zip.putNextEntry(ZipEntry("company.json"))
                zip.write(json.encodeToString(context.profile).toByteArray())
                zip.closeEntry()
            }
        }

        backupRow(context, output, ActivityActions.Created, "Creo la copia de seguridad ${output.name}.")
        return CompanyBackupResponse(
            fileName = output.name,
            sizeBytes = output.fileSize(),
            createdAt = Files.getLastModifiedTime(output).toInstant().toString(),
            downloadUrl = "/companies/me/backups/${output.name}/download",
        )
    }

    // The file exists already: without its activity row it is removed again (created or imported).
    private fun backupRow(context: BusinessOwnerContext, file: Path, action: String, summary: String) {
        try {
            tenantConnection(context.tenant).use { connection ->
                context.record(connection, "backup", file.name, file.name, action, summary, listOf(ActivityChange("size", "Tamano", null, file.fileSize().toString())))
            }
        } catch (failure: Throwable) {
            runCatching { Files.deleteIfExists(file) }
            throw failure
        }
    }

    /** The backup to download, after recording the download (a bulk read, ACTIVITY_LOG.md §4). */
    fun downloadBackup(companyCode: String, accessToken: String, fileName: String): Path {
        val path = backupPath(companyCode, accessToken, fileName)
        val context = authorizeBusinessOwner(companyCode, accessToken)
        tenantConnection(context.tenant).use { connection ->
            context.record(connection, "backup", path.name, path.name, ActivityActions.Exported, "Descargo la copia de seguridad ${path.name}.")
        }
        return path
    }

    // Resuelve el archivo ZIP solicitado dentro del directorio seguro de la empresa.
    fun backupPath(companyCode: String, accessToken: String, fileName: String): Path {
        val context = authorizeBusinessOwner(companyCode, accessToken)
        validateBackupName(fileName)
        val directory = backupDirectory(context.companyCode)
        val path = directory.resolve(fileName).normalize()
        if (!path.startsWith(directory) || !path.exists()) {
            throw CompanySelfException(tr("Backup no encontrado."))
        }
        return path
    }

    // Elimina un ZIP existente despues de validar que pertenece a la empresa.
    fun deleteBackup(companyCode: String, accessToken: String, fileName: String) {
        val path = backupPath(companyCode, accessToken, fileName)
        val context = authorizeBusinessOwner(companyCode, accessToken)
        // The row first, in a transaction committed only once the file is gone.
        context.logged({ tenant ->
            context.record(tenant, "backup", path.name, path.name, ActivityActions.Deleted, "Elimino la copia de seguridad ${path.name}.",
                listOf(ActivityChange("size", "Tamano", path.fileSize().toString(), null)))
        }) { Files.deleteIfExists(path) }
    }

    // Guarda un ZIP subido por el usuario sin ejecutar restauracion destructiva.
    fun storeImportedBackup(companyCode: String, accessToken: String, fileName: String, bytes: ByteArray): CompanyBackupResponse {
        val context = authorizeBusinessOwner(companyCode, accessToken)
        validateBackupName(fileName)
        validateZip(bytes)
        val output = backupDirectory(context.companyCode).also { it.createDirectories() }.resolve(fileName)
        output.outputStream().use { it.write(bytes) }
        backupRow(context, output, ActivityActions.Imported, "Importo la copia de seguridad ${output.name}.")
        return CompanyBackupResponse(
            fileName = output.name,
            sizeBytes = output.fileSize(),
            createdAt = Files.getLastModifiedTime(output).toInstant().toString(),
            downloadUrl = "/companies/me/backups/${output.name}/download",
        )
    }

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

    fun users(companyCode: String, accessToken: String): CompanyUsersResponse {
        val context = authorizeUserAdministrator(companyCode, accessToken)
        tenantConnection(context.tenant).use { connection ->
            syncPermissionCatalog(connection)
            return CompanyUsersResponse(
                users = loadUsers(connection),
                permissions = loadPermissions(connection),
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
                syncPermissionCatalog(connection)
                validatePermissions(connection, request.permissions)
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
                    replaceUserPermissions(connection, userId, request.permissions)
                    context.record(
                        connection, "user", userId.toString(), username, ActivityActions.Created, "Creo el usuario $username.",
                        ActivityChanges.created(
                            mapOf("username" to username, "displayName" to request.displayName.trim(), "email" to request.email.trim(), "active" to true),
                            UserLabels,
                        ),
                    )
                    permissionRows(connection, context, userId, username, emptySet(), request.permissions.toSet())
                    connection.commit()
                } catch (failure: Exception) {
                    connection.rollback()
                    throw failure
                } finally {
                    connection.autoCommit = true
                }
                loadUser(connection, userId) ?: throw CompanySelfException(tr("No se pudo cargar el usuario creado."))
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
                syncPermissionCatalog(connection)
                validatePermissions(connection, request.permissions)
                connection.autoCommit = false
                try {
                    if (!connection.userExists(targetUserId)) {
                        throw CompanySelfException(tr("Usuario no encontrado."))
                    }
                    val before = loadUserPermissions(connection, targetUserId).toSet()
                    replaceUserPermissions(connection, targetUserId, request.permissions)
                    permissionRows(connection, context, targetUserId, connection.loginOf(targetUserId), before, request.permissions.toSet())
                    connection.commit()
                } catch (failure: Exception) {
                    connection.rollback()
                    throw failure
                } finally {
                    connection.autoCommit = true
                }
                loadUser(connection, targetUserId) ?: throw CompanySelfException(tr("Usuario no encontrado."))
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
            return tenantConnection(context.tenant).use { connection -> loadUser(connection, targetUserId) }
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
                syncPermissionCatalog(connection)
                validatePermissions(connection, request.permissions)
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
                    val beforePermissions = loadUserPermissions(connection, targetUserId).toSet()
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
                    replaceUserPermissions(connection, targetUserId, request.permissions)
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
                    permissionRows(connection, context, targetUserId, username, beforePermissions, request.permissions.toSet())
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
                loadUser(connection, targetUserId) ?: throw CompanySelfException(tr("Usuario no encontrado."))
            }
        }
    }

    /**
     * Users and permissions are managed by the company's business_owner, or, in the server
     * workspace (which has no business owner), by any server_owner with a central session.
     */
    private fun authorizeUserAdministrator(companyCode: String, accessToken: String): BusinessOwnerContext {
        val code = companyCode.trim()
        val workspaceActor = if (code != ServerWorkspace.Code) null else when {
            isServerOwnerSession(accessToken) -> serverActors?.resolve(accessToken).let { it as? ServerActorLookup.Found }?.actor?.audit
                ?: AuditActor(ServerActorKind.Owner.value, null, AuditActor.ServerOwnerKind)
            else -> when (val lookup = serverActors?.resolve(accessToken)) {
                is ServerActorLookup.Found -> if (lookup.actor.has(ServerPermission.Workspace)) lookup.actor.audit
                    else throw CompanySelfException(tr("Necesitas el permiso {0}.", ServerPermission.Workspace.id))
                is ServerActorLookup.PasswordChangeRequired -> throw CompanySelfException(tr("Cambia tu contrasena antes de continuar."))
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
    private fun authorizeBusinessOwner(companyCode: String, accessToken: String): BusinessOwnerContext {
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

    private fun syncPermissionCatalog(connection: java.sql.Connection) {
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

    private fun loadPermissions(connection: java.sql.Connection): List<CompanyPermissionResponse> =
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

    private fun loadUsers(connection: java.sql.Connection): List<CompanyUserResponse> =
        connection.prepareStatement("SELECT id FROM application_users ORDER BY postgres_role").use { query ->
            query.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) {
                        loadUser(connection, rows.getObject("id", UUID::class.java))?.let(::add)
                    }
                }
            }
        }

    private fun loadUser(connection: java.sql.Connection, userId: UUID): CompanyUserResponse? {
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
        )
    }

    private fun loadUserPermissions(connection: java.sql.Connection, userId: UUID): List<String> =
        connection.prepareStatement("SELECT permission_id FROM application_user_permissions WHERE user_id = ? ORDER BY permission_id").use { query ->
            query.setObject(1, userId)
            query.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) add(rows.getString("permission_id"))
                }
            }
        }

    private fun validatePermissions(connection: java.sql.Connection, permissions: List<String>) {
        val known = loadPermissions(connection).map { it.permissionId }.toSet()
        val unknown = permissions.toSet() - known
        if (unknown.isNotEmpty()) {
            throw CompanySelfException(tr("Permisos no reconocidos: {0}.", unknown.joinToString()))
        }
    }

    private fun replaceUserPermissions(connection: java.sql.Connection, userId: UUID, permissions: List<String>) {
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
    private fun permissionRows(connection: java.sql.Connection, context: BusinessOwnerContext, userId: UUID, username: String, before: Set<String>, after: Set<String>) {
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

    private fun java.sql.Connection.loginOf(userId: UUID): String =
        prepareStatement("SELECT postgres_role FROM application_users WHERE id = ?").use { query ->
            query.setObject(1, userId)
            query.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else userId.toString() }
        }

    private fun java.sql.Connection.userExists(userId: UUID): Boolean =
        prepareStatement("SELECT 1 FROM application_users WHERE id = ?").use { query ->
            query.setObject(1, userId)
            query.executeQuery().use { rows -> rows.next() }
        }

    private fun java.sql.Connection.isBusinessOwner(userId: UUID): Boolean =
        prepareStatement("SELECT 1 FROM business_owner WHERE user_id = ?").use { query ->
            query.setObject(1, userId)
            query.executeQuery().use { rows -> rows.next() }
        }

    private fun java.sql.Connection.existsRole(name: String): Boolean =
        prepareStatement("SELECT 1 FROM pg_roles WHERE rolname = ?").use { query ->
            query.setString(1, name)
            query.executeQuery().use { rows -> rows.next() }
        }

    private fun java.sql.Connection.existsUser(name: String): Boolean =
        prepareStatement("SELECT 1 FROM application_users WHERE postgres_role = ?").use { query ->
            query.setString(1, name)
            query.executeQuery().use { rows -> rows.next() }
        }

    // Abre una conexion runtime al tenant ya resuelto por el servidor.
    private fun tenantConnection(tenant: com.ideasdeveloper.bms.server.auth.infrastructure.database.TenantDatabaseConfig) =
        PGSimpleDataSource().apply {
            setURL(tenant.jdbcUrl)
            setUser(tenant.user)
            setPassword(tenant.password)
        }.connection

    // Agrupa los ZIPs por codigo de empresa bajo el directorio configurado.
    private fun backupDirectory(companyCode: String): Path = backupsRoot.resolve(companyCode).normalize()

    // Rechaza nombres que no sigan el formato generado por el servidor.
    private fun validateBackupName(fileName: String) {
        if (!backupNamePattern.matches(fileName)) {
            throw CompanySelfException(tr("Nombre de backup invalido."))
        }
    }

    // Verifica que el archivo subido sea un ZIP legible y no este vacio.
    private fun validateZip(bytes: ByteArray) {
        ZipInputStream(bytes.inputStream()).use { zip ->
            if (zip.nextEntry == null) {
                throw CompanySelfException(tr("El archivo ZIP esta vacio."))
            }
        }
    }

    // Fila central de empresa necesaria para operaciones de autoservicio.
    private data class CompanyRow(
        val code: String,
        val databaseName: String,
        val profile: CompanyProfileResponse,
    )

    // Contexto autorizado para ejecutar acciones sobre una empresa tenant.
    private data class BusinessOwnerContext(
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
        val context = BusinessOwnerContext(workspace.first, workspace.second, tenant, CompanyProfileResponse(workspace.first, workspace.first, null, "#000000", "#000000", "#000000"), null, null, by)
        val details = auditDetails("adminUserId" to adminUserId.toString(), "userIds" to staff.map { it.toString() }, "reason" to reason)
        return context.audited("server_workspace.staff_deactivated", details) {
            tenantConnection(tenant).use { connection ->
                connection.autoCommit = false
                try {
                    val ids = connection.createArrayOf("uuid", staff.toTypedArray())
                    val closed = connection.prepareStatement("UPDATE application_users SET is_active = FALSE WHERE id = ANY (?) AND is_active").use { update ->
                        update.setArray(1, ids)
                        update.executeUpdate()
                    }
                    connection.prepareStatement("UPDATE application_sessions SET revoked_at = CURRENT_TIMESTAMP WHERE user_id = ANY (?) AND revoked_at IS NULL").use { revoke ->
                        revoke.setArray(1, ids)
                        revoke.executeUpdate()
                    }
                    connection.commit()
                    closed
                } catch (failure: Exception) {
                    connection.rollback()
                    throw failure
                } finally {
                    connection.autoCommit = true
                }
            }
        }
    }

}

@Serializable
// Manifest minimo incluido dentro de cada ZIP generado por Empresa.
private data class BackupManifest(
    val companyCode: String,
    val createdAt: String,
    val destructiveRestore: Boolean,
)

// Spanish labels of the activity log rows of Core (stored as written, ACTIVITY_LOG.md §1.5).
private val CompanyProfileLabels = mapOf(
    "name" to "Nombre", "logoUrl" to "Logo", "primaryColor" to "Color primario", "secondaryColor" to "Color secundario",
    "accentColor" to "Color de acento", "defaultLanguage" to "Idioma predeterminado",
)

private val UserLabels = mapOf("username" to "Usuario", "displayName" to "Nombre", "email" to "Correo", "active" to "Activo")
