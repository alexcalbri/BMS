package com.ideasdeveloper.bms.server.platform.coreupdate

import com.ideasdeveloper.bms.server.modules.GitHubAuth
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.time.Duration

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
