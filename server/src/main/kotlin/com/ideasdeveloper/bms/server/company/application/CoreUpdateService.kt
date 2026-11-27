package com.ideasdeveloper.bms.server.company.application

import com.ideasdeveloper.bms.server.i18n.tr
import com.ideasdeveloper.bms.server.modules.GitHubAuth
import kotlinx.serialization.Serializable
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.time.Duration
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

@Serializable
data class CoreUpdateRequestDto(
    val id: Long,
    val targetVersion: String,
    val fromVersion: String,
    val status: String,
    val requestedAt: String,
    val startedAt: String? = null,
    val finishedAt: String? = null,
    val message: String? = null,
    val logTail: String? = null,
    val stalePending: Boolean = false,
    /** Commit exacto (40 hex) que se instala; null en solicitudes anteriores a Core con commit fijado. */
    val targetCommit: String? = null,
    /** Lo escribe el ayudante root: "verified" (firma de la llave instalada) o "unsigned"; null si aun no reviso. */
    val signatureStatus: String? = null,
)

@Serializable
data class CoreUpdateStatusResponse(
    val currentVersion: String,
    val latestVersion: String? = null,
    val updateAvailable: Boolean,
    val request: CoreUpdateRequestDto? = null,
    /** Commit (40 hex) de [latestVersion] en la rama [ref]; es el que la app muestra y devuelve al pedir la actualizacion. */
    val latestCommit: String? = null,
    /** Rama de la que se actualiza (CORE_UPDATE_REF). */
    val ref: String? = null,
    /** true si hay una llave de firma instalada en el servidor: sin firma valida no se instala. false = "sin firma". */
    val signatureRequired: Boolean = false,
)

/** Cuerpo opcional de POST /server/core-update: el commit que el dueno vio y aprobo. */
@Serializable
data class CoreUpdateRequestBody(val targetCommit: String? = null)

/** Commit de la rama del Core y la version que declara su server/build.gradle.kts. */
data class CoreCommit(val sha: String, val version: String)

/** Fila de core_update_requests tal como la guarda la base de datos. */
data class CoreUpdateRow(
    val id: Long,
    val targetVersion: String,
    val fromVersion: String,
    val status: String,
    val requestedBy: String,
    val requestedAt: Instant,
    val startedAt: Instant?,
    val finishedAt: Instant?,
    val message: String?,
    val logTail: String?,
    val targetCommit: String? = null,
    val signatureStatus: String? = null,
)

/** Ya hay una solicitud pending o running (indice unico). */
class CoreUpdateActiveException : RuntimeException(tr("Ya hay una actualizacion en curso."))

/** Acceso a datos de las solicitudes; se separa para poder probar la logica sin base de datos. */
interface CoreUpdateStore {
    fun latest(): CoreUpdateRow?

    /** Inserta una solicitud pending y la audita. Lanza [CoreUpdateActiveException] si ya hay una activa. */
    fun insertPending(targetVersion: String, fromVersion: String, requestedBy: String, targetCommit: String): CoreUpdateRow
}

sealed interface CoreUpdateOutcome {
    data class Accepted(val request: CoreUpdateRequestDto) : CoreUpdateOutcome
    data object CatalogUnavailable : CoreUpdateOutcome
    data object UpToDate : CoreUpdateOutcome
    data object InProgress : CoreUpdateOutcome
    /** No se pudo leer el commit de la rama, o ese commit no declara la ultima version del catalogo. */
    data object CommitUnavailable : CoreUpdateOutcome
    /** El commit enviado no es un sha de 40 hex. */
    data object InvalidCommit : CoreUpdateOutcome
    /** La rama avanzo desde que el dueno vio el commit: debe revisar [latestCommit] y pedir de nuevo. */
    data class CommitChanged(val latestCommit: String) : CoreUpdateOutcome
}

class CoreUpdateService(
    private val store: CoreUpdateStore,
    private val currentVersion: String,
    /** Ultima version Core del catalogo remoto, o null si no se pudo leer. */
    private val latestVersion: () -> String?,
    private val now: () -> Instant = Instant::now,
    /** Commit actual de la rama del Core y su version (por defecto, GitHub en CORE_UPDATE_REF). */
    private val latestCommit: () -> CoreCommit? = GitHubCoreCommits.fromEnvironment(),
    /** Si hay llave de firma instalada (la misma que usa el ayudante root). */
    private val signatureRequired: () -> Boolean = { CoreRelease.signatureRequired() },
    private val ref: String? = CoreRelease.ref(),
    /** true: la solicitud debe traer el commit aprobado (apps que ya lo muestran). false: lo fija el servidor. */
    private val requireApprovedCommit: Boolean = false,
) {
    @Volatile private var cachedLatest: String? = null
    @Volatile private var cachedAt: Instant? = null
    @Volatile private var cachedCommit: CoreCommit? = null
    @Volatile private var cachedCommitAt: Instant? = null

    /**
     * Ultima version del catalogo con cache: se refresca cada [CacheRefreshAfter] y, si la lectura
     * falla, se sigue devolviendo la ultima buena hasta [CacheKeepFor]. Solo se guardan lecturas buenas.
     */
    private fun cachedLatestVersion(): String? {
        val current = now()
        val at = cachedAt
        val value = cachedLatest
        if (value != null && at != null && Duration.between(at, current) < CacheRefreshAfter) return value
        val fresh = latestVersion()?.trim()?.takeIf { it.isNotEmpty() }
        if (fresh != null) {
            synchronized(this) {
                cachedLatest = fresh
                cachedAt = current
            }
            return fresh
        }
        return if (value != null && at != null && Duration.between(at, current) < CacheKeepFor) value else null
    }

    // Mismo esquema de cache que la version; solo se guardan lecturas buenas.
    private fun cachedLatestCommit(): CoreCommit? {
        val current = now()
        val at = cachedCommitAt
        val value = cachedCommit
        if (value != null && at != null && Duration.between(at, current) < CacheRefreshAfter) return value
        val fresh = freshCommit()
        if (fresh != null) return fresh
        return if (value != null && at != null && Duration.between(at, current) < CacheKeepFor) value else null
    }

    private fun freshCommit(): CoreCommit? {
        val fresh = runCatching { latestCommit() }.getOrNull()?.takeIf { CommitPattern.matches(it.sha) && semver.matches(it.version) }
        if (fresh != null) {
            synchronized(this) {
                cachedCommit = fresh
                cachedCommitAt = now()
            }
        }
        return fresh
    }

    fun status(): CoreUpdateStatusResponse {
        val last = store.latest()
        // Con una solicitud activa no se consulta el catalogo: el destino ya esta decidido.
        val active = last != null && (last.status == "pending" || last.status == "running")
        val latest = if (active) last?.targetVersion else cachedLatestVersion()
        val commit = when {
            active -> last?.targetCommit
            isUpdateAvailable(currentVersion, latest) -> cachedLatestCommit()?.takeIf { it.version == latest }?.sha
            else -> null
        }
        return CoreUpdateStatusResponse(
            currentVersion = currentVersion,
            latestVersion = latest,
            updateAvailable = isUpdateAvailable(currentVersion, latest),
            request = last?.let { toDto(it, now()) },
            latestCommit = commit,
            ref = ref,
            signatureRequired = runCatching { signatureRequired() }.getOrDefault(false),
        )
    }

    /**
     * Pide instalar la ultima version. El commit que se instala es el que la rama tiene ahora y debe declarar esa
     * version; si la app envia [approvedCommit] (el que mostro al dueno) tiene que ser exactamente ese, o se
     * responde [CoreUpdateOutcome.CommitChanged]. El ayudante root solo instala el commit guardado.
     */
    fun request(requestedBy: String, approvedCommit: String? = null): CoreUpdateOutcome {
        val approved = approvedCommit?.trim()?.takeIf { it.isNotEmpty() }?.lowercase()
        if (approved != null && !CommitPattern.matches(approved)) return CoreUpdateOutcome.InvalidCommit
        if (approved == null && requireApprovedCommit) return CoreUpdateOutcome.InvalidCommit
        // Lectura fresca (sin cache): es la que decide a que version se actualiza.
        val latest = latestVersion()?.trim()?.takeIf { it.isNotEmpty() } ?: return CoreUpdateOutcome.CatalogUnavailable
        synchronized(this) {
            cachedLatest = latest
            cachedAt = now()
        }
        if (!isUpdateAvailable(currentVersion, latest)) return CoreUpdateOutcome.UpToDate
        val commit = freshCommit()?.takeIf { it.version == latest } ?: return CoreUpdateOutcome.CommitUnavailable
        if (approved != null && approved != commit.sha) return CoreUpdateOutcome.CommitChanged(commit.sha)
        return try {
            CoreUpdateOutcome.Accepted(toDto(store.insertPending(latest, currentVersion, requestedBy, commit.sha), now()))
        } catch (_: CoreUpdateActiveException) {
            CoreUpdateOutcome.InProgress
        }
    }

    companion object {
        val StalePendingAfter: Duration = Duration.ofMinutes(3)
        val CacheRefreshAfter: Duration = Duration.ofSeconds(60)
        val CacheKeepFor: Duration = Duration.ofMinutes(10)
        private val semver = Regex("^[0-9]+\\.[0-9]+\\.[0-9]+$")
        val CommitPattern = Regex("^[0-9a-f]{40}$")

        /** Solo se ofrece si la ultima es semver valida y mayor que la actual (nunca un downgrade). */
        fun isUpdateAvailable(current: String, latest: String?): Boolean {
            val candidate = parse(latest) ?: return false
            val installed = parse(current) ?: return false
            return compare(candidate, installed) > 0
        }

        private fun parse(version: String?): List<Long>? {
            val value = version?.trim() ?: return null
            if (!semver.matches(value)) return null
            val parts = value.split('.').map { it.toLongOrNull() }
            return if (parts.any { it == null }) null else parts.filterNotNull()
        }

        private fun compare(left: List<Long>, right: List<Long>): Int {
            for (index in left.indices) {
                val difference = left[index].compareTo(right[index])
                if (difference != 0) return difference
            }
            return 0
        }

        /** pending por mas de 3 minutos: el ayudante root no esta instalado. */
        fun isStalePending(status: String, requestedAt: Instant, now: Instant): Boolean =
            status == "pending" && Duration.between(requestedAt, now) > StalePendingAfter

        fun toDto(row: CoreUpdateRow, now: Instant): CoreUpdateRequestDto = CoreUpdateRequestDto(
            id = row.id,
            targetVersion = row.targetVersion,
            fromVersion = row.fromVersion,
            status = row.status,
            requestedAt = row.requestedAt.toString(),
            startedAt = row.startedAt?.toString(),
            finishedAt = row.finishedAt?.toString(),
            message = row.message,
            logTail = row.logTail,
            stalePending = isStalePending(row.status, row.requestedAt, now),
            targetCommit = row.targetCommit,
            signatureStatus = row.signatureStatus,
        )
    }
}

/** Implementacion JDBC sobre la base central. */
class JdbcCoreUpdateStore(private val central: DataSource) : CoreUpdateStore {
    override fun latest(): CoreUpdateRow? = central.connection.use { connection ->
        connection.prepareStatement("$Select ORDER BY id DESC LIMIT 1").use { query ->
            query.executeQuery().use { rows -> if (rows.next()) rows.toRow() else null }
        }
    }

    override fun insertPending(targetVersion: String, fromVersion: String, requestedBy: String, targetCommit: String): CoreUpdateRow =
        central.connection.use { connection ->
            val previousAutoCommit = connection.autoCommit
            connection.autoCommit = false
            try {
                val row = connection.prepareStatement(
                    """
                    INSERT INTO core_update_requests (target_version, from_version, requested_by, target_commit)
                    VALUES (?, ?, ?, ?)
                    RETURNING $Columns
                    """.trimIndent()
                ).use { insert ->
                    insert.setString(1, targetVersion)
                    insert.setString(2, fromVersion)
                    insert.setString(3, requestedBy)
                    insert.setString(4, targetCommit)
                    insert.executeQuery().use { rows ->
                        rows.next()
                        rows.toRow()
                    }
                }
                audit(connection, requestedBy, row)
                connection.commit()
                row
            } catch (failure: SQLException) {
                runCatching { connection.rollback() }
                // Indice unico: ya hay una solicitud pending o running.
                if (failure.sqlState == UniqueViolation) throw CoreUpdateActiveException()
                throw failure
            } catch (failure: Throwable) {
                runCatching { connection.rollback() }
                throw failure
            } finally {
                runCatching { connection.autoCommit = previousAutoCommit }
            }
        }

    // [requestedBy] is the login of the server owner or administrator who asked (never the executing role).
    private fun audit(connection: Connection, requestedBy: String, row: CoreUpdateRow) {
        val (userId, owner) = connection.prepareStatement(
            "SELECT users.id, EXISTS (SELECT 1 FROM server_owners owners WHERE owners.user_id = users.id) FROM application_users users WHERE users.postgres_role = ?"
        ).use { query ->
            query.setString(1, requestedBy)
            query.executeQuery().use { rows -> if (rows.next()) rows.getObject(1, UUID::class.java) to rows.getBoolean(2) else null to false }
        }
        val kind = when {
            userId == null -> com.ideasdeveloper.bms.server.admins.AuditActor.SystemKind
            owner -> com.ideasdeveloper.bms.server.admins.AuditActor.ServerOwnerKind
            else -> com.ideasdeveloper.bms.server.admins.AuditActor.ServerAdminKind
        }
        ProvisioningAudit.insert(
            connection, com.ideasdeveloper.bms.server.admins.AuditActor(requestedBy, userId, kind), "server.core_update_requested", null, null,
            auditDetails("from" to row.fromVersion, "to" to row.targetVersion, "commit" to row.targetCommit, "requestId" to row.id),
        )
    }

    private fun ResultSet.toRow() = CoreUpdateRow(
        id = getLong("id"),
        targetVersion = getString("target_version"),
        fromVersion = getString("from_version"),
        status = getString("status"),
        requestedBy = getString("requested_by"),
        requestedAt = getTimestamp("requested_at").toInstant(),
        startedAt = getTimestamp("started_at")?.toInstant(),
        finishedAt = getTimestamp("finished_at")?.toInstant(),
        message = getString("message"),
        logTail = getString("log_tail"),
        targetCommit = getString("target_commit"),
        signatureStatus = getString("signature_status"),
    )

    private companion object {
        const val UniqueViolation = "23505"
        const val Columns = "id, target_version, from_version, status, requested_by, requested_at, " +
            "started_at, finished_at, message, log_tail, target_commit, signature_status"
        const val Select = "SELECT $Columns FROM core_update_requests"
    }
}

/** Origen del Core y llave de firma: los mismos valores que usan scripts/ubuntu/mirror.sh y core-update.sh. */
object CoreRelease {
    const val Owner = "alexcalbri"
    const val Repo = "BMS"
    const val DefaultRef = "master"
    /** allowed_signers de ssh-keygen, de root. Si existe, el ayudante exige firma (SIGNING.md). */
    const val AllowedSignersPath = "/usr/local/lib/bms/allowed_signers"
    private val RefPattern = Regex("^[A-Za-z0-9][A-Za-z0-9._/-]{0,100}$")

    /** Rama de CORE_UPDATE_REF validada como en el ayudante; null si no es valida (no se ofrece commit). */
    fun ref(value: String? = System.getenv("CORE_UPDATE_REF")): String? {
        val ref = value?.trim()?.takeIf { it.isNotEmpty() } ?: DefaultRef
        return ref.takeIf { RefPattern.matches(it) && ".." !in it && !it.endsWith(".lock") }
    }

    fun signatureRequired(path: Path = Path.of(AllowedSignersPath)): Boolean =
        Files.exists(path, LinkOption.NOFOLLOW_LINKS)
}

/**
 * Commit actual de la rama del Core en GitHub (API, respuesta de solo el sha) y la version de su
 * server/build.gradle.kts (raw.githubusercontent.com, en ese commit). Hosts fijos por https.
 */
class GitHubCoreCommits(
    private val ref: String?,
    private val githubToken: String? = null,
    /** GET de [url] con [accept]: (estado HTTP, cuerpo), o null si fallo la conexion. */
    private val get: (url: String, accept: String, token: String?) -> Pair<Int, String>? = ::httpGet,
) : () -> CoreCommit? {
    override fun invoke(): CoreCommit? {
        val branch = ref ?: return null
        val commitUrl = "https://api.github.com/repos/${CoreRelease.Owner}/${CoreRelease.Repo}/commits/$branch"
        val (status, body) = get(commitUrl, "application/vnd.github.sha", githubToken) ?: return null
        val sha = body.trim().lowercase()
        if (status != 200 || !CoreUpdateService.CommitPattern.matches(sha)) return null
        val gradleUrl = "https://raw.githubusercontent.com/${CoreRelease.Owner}/${CoreRelease.Repo}/$sha/server/build.gradle.kts"
        val (gradleStatus, gradle) = get(gradleUrl, "text/plain", githubToken) ?: return null
        if (gradleStatus != 200) return null
        val version = versionOf(gradle) ?: return null
        return CoreCommit(sha, version)
    }

    companion object {
        private val VersionLine = Regex("^version = \"([0-9]+\\.[0-9]+\\.[0-9]+)\"$", RegexOption.MULTILINE)
        private const val MaxBytes = 256 * 1024

        /** Misma linea que comprueba core-update.sh con grep -Fx. */
        fun versionOf(gradle: String): String? = VersionLine.find(gradle)?.groupValues?.get(1)

        fun fromEnvironment(): GitHubCoreCommits =
            GitHubCoreCommits(CoreRelease.ref(), System.getenv("MODULE_GITHUB_TOKEN")?.trim()?.takeIf { it.isNotEmpty() })

        private val http: HttpClient by lazy {
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build()
        }

        private fun httpGet(url: String, accept: String, token: String?): Pair<Int, String>? = runCatching {
            val request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10)).GET()
                .header("Accept", accept).header("User-Agent", "BMS-Server")
            GitHubAuth.header(token, url)?.let { request.header("Authorization", it) }
            val response = http.send(request.build(), HttpResponse.BodyHandlers.ofInputStream())
            val bytes = response.body().use { it.readNBytes(MaxBytes + 1) }
            if (bytes.size > MaxBytes) null else response.statusCode() to bytes.decodeToString()
        }.getOrNull()
    }
}
