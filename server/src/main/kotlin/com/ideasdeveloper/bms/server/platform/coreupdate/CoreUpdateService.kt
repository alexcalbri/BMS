package com.ideasdeveloper.bms.server.platform.coreupdate

import com.ideasdeveloper.bms.server.i18n.tr
import kotlinx.serialization.Serializable
import java.time.Duration
import java.time.Instant

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
