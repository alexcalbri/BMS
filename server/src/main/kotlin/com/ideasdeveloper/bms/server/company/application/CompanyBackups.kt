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

// Copias de seguridad de la empresa (ZIP con manifest y perfil): listar, crear, descargar, borrar e importar.
// Colaborador interno de CompanySelfService, que conserva la API publica y la autorizacion.
internal class CompanyBackups(
    private val owner: CompanySelfService,
    private val backupsRoot: Path,
    // Quota, free-disk floor and uploads in progress of the company (STORAGE_QUOTA.md); imported backups count too.
    private val storage: com.ideasdeveloper.bms.server.storage.StorageGuard?,
) {
    // Restringe nombres de ZIP para evitar rutas arbitrarias o archivos ajenos al tenant.
    private val backupNamePattern = Regex("[a-z][a-z0-9_]{0,62}-backup-[0-9]{8}T[0-9]{6}Z\\.zip")
    // Serializador usado para manifest y metadata del ZIP.
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
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
    private fun backupRow(context: CompanySelfService.BusinessOwnerContext, file: Path, action: String, summary: String) {
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
    fun storeImportedBackup(companyCode: String, accessToken: String, fileName: String, bytes: ByteArray): CompanyBackupResponse =
        storeImportedBackup(companyCode, accessToken, fileName, bytes.inputStream())

    /**
     * Stores an uploaded backup ZIP streaming it to disk (SECURITY_FIXES.md B4): at most
     * [BackupImportLimits.MaxBytes] are read, then the ZIP is checked entry by entry (at most
     * [BackupImportLimits.MaxEntries] entries and [BackupImportLimits.MaxUncompressedBytes]
     * uncompressed, so a ZIP bomb is refused without expanding it) before it takes its name.
     */
    fun storeImportedBackup(companyCode: String, accessToken: String, fileName: String, content: java.io.InputStream, expectedBytes: Long? = null): CompanyBackupResponse {
        val context = authorizeBusinessOwner(companyCode, accessToken)
        validateBackupName(fileName)
        val directory = backupDirectory(context.companyCode).also { it.createDirectories() }
        val output = directory.resolve(fileName)
        BackupImportLimits.importInto(directory, output, content, expectedBytes, context.companyCode, storage)
        backupRow(context, output, ActivityActions.Imported, "Importo la copia de seguridad ${output.name}.")
        return CompanyBackupResponse(
            fileName = output.name,
            sizeBytes = output.fileSize(),
            createdAt = Files.getLastModifiedTime(output).toInstant().toString(),
            downloadUrl = "/companies/me/backups/${output.name}/download",
        )
    }

    // Agrupa los ZIPs por codigo de empresa bajo el directorio configurado.
    private fun backupDirectory(companyCode: String): Path = backupsRoot.resolve(companyCode).normalize()

    // Rechaza nombres que no sigan el formato generado por el servidor.
    private fun validateBackupName(fileName: String) {
        if (!backupNamePattern.matches(fileName)) {
            throw CompanySelfException(tr("Nombre de backup invalido."))
        }
    }

    private fun authorizeBusinessOwner(companyCode: String, accessToken: String) = owner.authorizeBusinessOwner(companyCode, accessToken)

    private fun tenantConnection(tenant: com.ideasdeveloper.bms.server.auth.infrastructure.database.TenantDatabaseConfig) = owner.tenantConnection(tenant)

    private fun <T> CompanySelfService.BusinessOwnerContext.logged(rows: (java.sql.Connection) -> Unit, change: (java.sql.Connection) -> T): T =
        owner.loggedFor(this, rows, change)

    private fun CompanySelfService.BusinessOwnerContext.record(
        connection: java.sql.Connection, entityType: String, entityId: String?, label: String, action: String, summary: String,
        changes: List<ActivityChange> = emptyList(),
    ) = owner.recordFor(this, connection, entityType, entityId, label, action, summary, changes)
}

@Serializable
// Manifest minimo incluido dentro de cada ZIP generado por Empresa.
private data class BackupManifest(
    val companyCode: String,
    val createdAt: String,
    val destructiveRestore: Boolean,
)

/** Limits of an imported backup ZIP (SECURITY_FIXES.md B4). */
object BackupImportLimits {
    const val MaxBytes = 512L * 1024 * 1024
    const val MaxEntries = 10_000
    const val MaxUncompressedBytes = 4L * 1024 * 1024 * 1024

    // Backups a company keeps (generated and imported): at most this many and this much in total.
    const val MaxStoredBackups = 20
    const val MaxStoredBytes = 5L * 1024 * 1024 * 1024

    /**
     * Writes [content] as [output] in the company's backup [directory] (review item 3). Every check comes before
     * anything is written: the number and total size of the backups the company keeps, its quota, the free-disk
     * floor and its uploads in progress ([storage]); while streaming, each chunk is charged to the quota through
     * the same counting stream and the size limits apply; the ZIP is checked before it takes its name.
     */
    fun importInto(directory: Path, output: Path, content: java.io.InputStream, expectedBytes: Long?, companyCode: String, storage: com.ideasdeveloper.bms.server.storage.StorageGuard?) {
        val stored = Files.list(directory).use { files -> files.filter { it.fileName.toString().endsWith(".zip") && it != output }.toList() }
        val storedBytes = stored.sumOf { runCatching { Files.size(it) }.getOrDefault(0L) }
        if (stored.size >= MaxStoredBackups) throw CompanySelfException(tr("La empresa ya guarda {0} copias de seguridad; elimina alguna antes de importar otra.", MaxStoredBackups))
        if (expectedBytes != null && storedBytes + expectedBytes > MaxStoredBytes) throw storedTooLarge()
        val upload = try {
            storage?.checkRoom(companyCode, expectedBytes)
            storage?.begin(companyCode)
        } catch (refused: com.ideasdeveloper.bms.server.modules.StorageQuotaException) {
            throw CompanySelfException(refused.message.orEmpty())
        }
        try {
            val partial = Files.createTempFile(directory, ".import-", ".part")
            try {
                val written = try {
                    Files.newOutputStream(partial).use { out ->
                        copyLimited(content, out, maxBytes = minOf(MaxBytes, MaxStoredBytes - storedBytes)) { bytes -> upload?.charge(bytes) }
                    }
                } catch (refused: com.ideasdeveloper.bms.server.modules.StorageQuotaException) {
                    throw CompanySelfException(refused.message.orEmpty())
                }
                validateZip(partial)
                Files.move(partial, output, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
                upload?.stored(written)
            } finally {
                Files.deleteIfExists(partial)
            }
        } finally {
            upload?.close()
        }
    }

    fun tooLarge() = CompanySelfException(tr("El backup supera el maximo de {0} MB.", MaxBytes / (1024 * 1024)))

    fun storedTooLarge() = CompanySelfException(tr("Las copias de seguridad de la empresa superarian {0} GB; elimina alguna antes de importar otra.", MaxStoredBytes / (1024 * 1024 * 1024)))

    /** Copies at most [maxBytes] (one byte more and the import is refused); each chunk goes through [charge] first. */
    fun copyLimited(input: java.io.InputStream, output: java.io.OutputStream, maxBytes: Long = MaxBytes, charge: (Long) -> Unit = {}): Long {
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) return total
            total += read
            if (total > maxBytes) throw if (maxBytes < MaxBytes) storedTooLarge() else tooLarge()
            charge(read.toLong())
            output.write(buffer, 0, read)
        }
    }

    /** A readable, non-empty ZIP within the entry and uncompressed-size limits (read as a stream, nothing is kept). */
    fun validateZip(file: Path, maxEntries: Int = MaxEntries, maxUncompressed: Long = MaxUncompressedBytes) {
        var entries = 0
        var expanded = 0L
        val buffer = ByteArray(64 * 1024)
        try {
            ZipInputStream(Files.newInputStream(file)).use { zip ->
                while (true) {
                    zip.nextEntry ?: break
                    entries++
                    if (entries > maxEntries) throw CompanySelfException(tr("El backup tiene demasiados archivos (maximo {0}).", maxEntries))
                    while (true) {
                        val read = zip.read(buffer)
                        if (read < 0) break
                        expanded += read
                        if (expanded > maxUncompressed) throw CompanySelfException(tr("El backup descomprimido supera el maximo permitido."))
                    }
                }
            }
        } catch (failure: java.util.zip.ZipException) {
            throw CompanySelfException(tr("El archivo no es un ZIP valido."))
        }
        if (entries == 0) throw CompanySelfException(tr("El archivo ZIP esta vacio."))
    }
}
