package com.ideasdeveloper.bms.server.modules

import com.ideasdeveloper.bms.server.net.PinnedHost
import com.ideasdeveloper.bms.server.net.PinnedHostException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.io.IOException
import java.net.InetAddress
import java.net.URI
import java.util.Base64

/** Respuesta HTTP ya leida (cuerpo limitado a [RemoteModuleCatalog.MaxBodyBytes]). */
class CatalogHttpResponse(val status: Int, val body: String, val location: String? = null)

/** Una peticion GET a [uri] por la IP ya validada [address]. En produccion es [PinnedHost.openPinnedHttps]. */
fun interface CatalogTransport {
    fun get(uri: URI, address: InetAddress, headers: Map<String, String>): CatalogHttpResponse
}

/** La URL del catalogo (o un salto de redireccion) no cumple las reglas anti-SSRF. */
class CatalogUrlException(message: String) : Exception(message)

/**
 * Reglas de la URL del catalogo de modulos: solo https al puerto 443, con servidor y sin usuario ni clave.
 * La IP se valida (publica) y se ancla en cada lectura ([RemoteModuleCatalog]).
 */
object CatalogUrlPolicy {
    const val MaxUrlLength = 2048

    /** Devuelve el URI o lanza [CatalogUrlException]; no toca la red. */
    fun validate(url: String): URI {
        val text = url.trim()
        if (text.isEmpty() || text.length > MaxUrlLength) throw CatalogUrlException("La URL del catalogo no es valida.")
        val uri = try {
            URI(text)
        } catch (_: Exception) {
            throw CatalogUrlException("La URL del catalogo no es valida.")
        }
        if (!uri.scheme.equals("https", ignoreCase = true)) throw CatalogUrlException("La URL del catalogo debe ser https.")
        if (uri.host.isNullOrBlank()) throw CatalogUrlException("La URL del catalogo no tiene servidor.")
        if (uri.rawUserInfo != null) throw CatalogUrlException("La URL del catalogo no puede llevar usuario ni clave.")
        if (uri.port != -1 && uri.port != 443) throw CatalogUrlException("La URL del catalogo debe usar el puerto 443.")
        return uri
    }
}

/**
 * Lee modulos remotos disponibles para instalar.
 *
 * La URL puede apuntar a:
 *
 * - un JSON legado con `{ "modules": [...] }`;
 * - una URL normal de GitHub como
 *   `https://github.com/alexcalbri/BMS/tree/master/modules`;
 * - un directorio de GitHub Contents API, por ejemplo
 *   `https://api.github.com/repos/owner/repo/contents/modules?ref=main`.
 *
 * En modo GitHub la lectura se fija a un commit: si la URL trae una rama se
 * resuelve primero a su commit (40 hex), se lista y se lee cada `module.json`
 * de ese commit, y el paquete se descarga de ese mismo commit
 * ([RemoteModulePackage.source] lleva el sha, no la rama).
 *
 * Anti-SSRF: solo https (puerto 443, sin credenciales); cada host se resuelve
 * una vez, se rechaza si alguna IP es privada o local y la conexion va a esa IP
 * anclada. Las redirecciones se validan y se anclan de nuevo, como maximo
 * [MaxRedirects].
 *
 * En modo GitHub, cada carpeta es instalable solo si contiene `module.json`
 * valido. Si se elimina una carpeta del repositorio remoto, deja de aparecer
 * para nuevas instalaciones; los servidores que ya la instalaron conservan su
 * registro local en `server_modules` y su paquete descargado.
 */
class RemoteModuleCatalog(
    // Optional GitHub token (see GitHubAuth); sent only to GitHub.
    private val githubToken: String? = null,
    private val clock: () -> Long = System::currentTimeMillis,
    // Valida y fija la IP de cada host; las pruebas lo sustituyen.
    private val resolve: (String) -> InetAddress = { host -> PinnedHost.resolveValidated(host) },
    private val transport: CatalogTransport = PinnedCatalogTransport,
) : AutoCloseable {
    companion object {
        private const val CacheMillis = 5 * 60_000L
        const val MaxRedirects = 3
        const val MaxBodyBytes = 2L * 1024 * 1024
        private val log = LoggerFactory.getLogger(RemoteModuleCatalog::class.java)
    }

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val moduleIdPattern = Regex("[a-z][a-z0-9_]{0,62}")
    private val checksumPattern = Regex("[A-Fa-f0-9]{64}")

    private class Cached(val readAt: Long, val modules: List<RemoteModulePackage>)

    // Last good reading per catalog URL. GitHub allows 60 unauthenticated API requests per hour and a
    // reading costs one per module folder plus one; the modules screen asks every few seconds while
    // a module installs, so without this the limit runs out and the catalog looks empty for an hour.
    private val cache = java.util.concurrent.ConcurrentHashMap<String, Cached>()

    fun modules(catalogUrl: String): List<RemoteModulePackage> {
        val key = catalogUrl.trim()
        if (key.isBlank()) return emptyList()
        val now = clock()
        cache[key]?.takeIf { now - it.readAt < CacheMillis }?.let { return it.modules }
        return try {
            // Modules are built from this repository folder, at the commit read here, when the server owner installs them.
            val source = GitHubLocation.parse(key)?.let { location ->
                if (location.pinned) location else location.copy(ref = resolveCommit(location))
            }
            val url = source?.contentsApiUrl() ?: CatalogUrlPolicy.validate(key).toString()
            modulesFrom(url, source)
                .filter(::isValid)
                .map { it.copy(source = source) }
                .distinctBy { it.definition.id }
                .sortedBy { it.definition.id }
                .also { cache[key] = Cached(now, it) }
        } catch (failure: Exception) {
            // GitHub unreachable or rate limited, or the URL breaks the rules: keep offering the last good reading.
            log.warn("No se pudo leer el catalogo de modulos {}: {}", key, failure.message)
            cache[key]?.modules ?: emptyList()
        }
    }

    // Commit de 40 hex al que apunta hoy la rama del catalogo (API de GitHub, respuesta de solo el sha).
    private fun resolveCommit(location: GitHubLocation): String {
        val response = request(
            "https://api.github.com/repos/${location.owner}/${location.repo}/commits/${location.ref}",
            accept = "application/vnd.github.sha",
        )
        val sha = response.body.trim().lowercase()
        if (response.status != 200 || !GitHubLocation.CommitSha.matches(sha)) {
            throw IOException("No se pudo resolver ${location.ref} (HTTP ${response.status}).")
        }
        return sha
    }

    private fun GitHubLocation.contentsApiUrl(): String =
        "https://api.github.com/repos/$owner/$repo/contents/${path.trim('/')}?ref=$ref"

    // Reads the catalog again on the next call (after changing the catalog URL or to check updates now).
    fun forget(catalogUrl: String) {
        cache.remove(catalogUrl.trim())
    }

    private fun modulesFrom(url: String, source: GitHubLocation?): List<RemoteModulePackage> {
        val text = get(url)
        return when (val element = json.parseToJsonElement(text)) {
            is JsonObject -> modulesFromObject(element)
            is JsonArray -> modulesFromGitHubDirectory(element, source)
            else -> emptyList()
        }
    }

    private fun modulesFromObject(element: JsonObject): List<RemoteModulePackage> {
        element["modules"]?.let {
            return json.decodeFromJsonElement(RemoteModuleCatalogDocument.serializer(), element)
                .modules
                .map { definition -> definition.toPackage() }
        }

        return decodeModuleManifest(element, expectedFolder = null)?.let(::listOf).orEmpty()
    }

    // Sin token la API de GitHub admite 60 llamadas por hora: solo el listado la usa. Cada module.json se lee de
    // raw.githubusercontent.com, que no cuenta para ese limite.
    private fun modulesFromGitHubDirectory(entries: JsonArray, source: GitHubLocation?): List<RemoteModulePackage> =
        entries
            .mapNotNull { it as? JsonObject }
            .filter { it.string("type") == "dir" }
            .mapNotNull { directory ->
                val directoryName = directory.string("name") ?: return@mapNotNull null
                val directoryUrl = directory.string("url") ?: return@mapNotNull null
                val manifestUrl = source?.let { "https://raw.githubusercontent.com/${it.owner}/${it.repo}/${it.ref}/${it.path.trim('/')}/$directoryName/module.json" }
                    ?: githubChildUrl(directoryUrl, "module.json")
                loadModuleManifest(manifestUrl, expectedFolder = directoryName)
            }

    private fun loadModuleManifest(url: String, expectedFolder: String): RemoteModulePackage? {
        // Una carpeta sin module.json (404 con texto plano) no es un modulo: se salta sin tumbar el catalogo.
        val text = get(url)
        return when (val element = runCatching { json.parseToJsonElement(text) }.getOrNull()) {
            is JsonObject -> decodeModuleManifest(element, expectedFolder)
            else -> null
        }
    }

    // Cuerpo de la respuesta (cualquier estado, como antes: una carpeta sin module.json responde 404 y se salta).
    private fun get(url: String): String = request(url).body

    /**
     * GET anclado: valida la URL, resuelve y valida la IP del host y conecta a esa IP. Cada redireccion se
     * valida y se ancla de nuevo. El token solo va a GitHub por https ([GitHubAuth]).
     */
    private fun request(url: String, accept: String = "application/json"): CatalogHttpResponse {
        var current = CatalogUrlPolicy.validate(url)
        var redirects = 0
        while (true) {
            val address = try {
                resolve(current.host)
            } catch (failure: PinnedHostException) {
                throw CatalogUrlException("No se puede leer el catalogo de ${current.host}: ${failure.message}")
            }
            val headers = buildMap {
                put("User-Agent", "BMS-Server")
                put("Accept", accept)
                GitHubAuth.header(githubToken, current.toString())?.let { put("Authorization", it) }
            }
            val response = transport.get(current, address, headers)
            val location = response.location
            if (response.status !in 300..399 || location == null) return response
            if (++redirects > MaxRedirects) throw CatalogUrlException("Demasiadas redirecciones al leer el catalogo.")
            current = CatalogUrlPolicy.validate(current.resolve(location).toString())
        }
    }

    private fun decodeModuleManifest(element: JsonObject, expectedFolder: String?): RemoteModulePackage? {
        val manifest = element["content"]?.jsonPrimitive?.contentOrNull
            ?.let { encoded -> String(Base64.getMimeDecoder().decode(encoded)) }
            ?.let { json.parseToJsonElement(it) as? JsonObject }
            ?: element

        val definition = json.decodeFromJsonElement(RemoteModuleDefinition.serializer(), manifest)
        if (expectedFolder != null && definition.id != expectedFolder) return null
        // Base modules ship inside the server; the catalog only offers optional ones.
        if (definition.bundled) return null
        return definition.toPackage()
    }

    private fun RemoteModuleDefinition.toPackage(): RemoteModulePackage =
        RemoteModulePackage(
            definition = toDefinition(),
            packageUrl = packageUrl?.takeIf { value -> value.isNotBlank() },
            packageSha256 = packageSha256?.lowercase()?.takeIf { value -> value.isNotBlank() },
        )

    private fun isValid(remotePackage: RemoteModulePackage): Boolean {
        val definition = remotePackage.definition
        return moduleIdPattern.matches(definition.id) &&
            definition.displayName.isNotBlank() &&
            definition.description.isNotBlank() &&
            definition.version.isNotBlank() &&
            (remotePackage.packageUrl.isNullOrBlank() || remotePackage.packageUrl.startsWith("https://")) &&
            (remotePackage.packageSha256.isNullOrBlank() || checksumPattern.matches(remotePackage.packageSha256))
    }

    private fun JsonObject.string(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }

    private fun githubChildUrl(directoryUrl: String, child: String): String {
        val queryIndex = directoryUrl.indexOf('?')
        return if (queryIndex >= 0) {
            directoryUrl.substring(0, queryIndex).trimEnd('/') + "/$child" + directoryUrl.substring(queryIndex)
        } else {
            directoryUrl.trimEnd('/') + "/$child"
        }
    }

    override fun close() = Unit
}

/** Transporte real: HTTP/1.1 por TLS a la IP anclada, con SNI y certificado del host original. */
object PinnedCatalogTransport : CatalogTransport {
    override fun get(uri: URI, address: InetAddress, headers: Map<String, String>): CatalogHttpResponse =
        PinnedHost.openPinnedHttps(
            uri = uri, validated = address, originalHost = uri.host, headers = headers,
            timeoutMillis = 15_000, connectTimeoutMillis = 5_000, totalMillis = 30_000, maxBodyBytes = RemoteModuleCatalog.MaxBodyBytes,
        ).use { response ->
            CatalogHttpResponse(response.status, response.body.readBytes().decodeToString(), response.header("location"))
        }
}

data class RemoteModulePackage(
    val definition: ModuleDefinition,
    val packageUrl: String?,
    val packageSha256: String?,
    // Repository folder of the catalog; null when the catalog is not a GitHub folder.
    val source: GitHubLocation? = null,
)

@Serializable
data class RemoteModuleCatalogDocument(
    val modules: List<RemoteModuleDefinition> = emptyList(),
)

@Serializable
data class RemoteModuleDefinition(
    val id: String,
    val displayName: String,
    val description: String,
    val version: String = "0.1.0",
    val locked: Boolean = false,
    val views: List<ModuleViewDefinition> = emptyList(),
    val permissions: List<ModulePermissionDefinition> = emptyList(),
    val packageUrl: String? = null,
    val packageSha256: String? = null,
    val bundled: Boolean = false,
    val minCoreVersion: String? = null,
    val minAppVersion: String? = null,
) {
    fun toDefinition(): ModuleDefinition = ModuleDefinition(
        id = id,
        displayName = displayName,
        description = description,
        version = version,
        locked = false,
        views = views,
        permissions = permissions,
        minCoreVersion = minCoreVersion?.trim()?.takeIf { it.isNotEmpty() },
        minAppVersion = minAppVersion?.trim()?.takeIf { it.isNotEmpty() },
    )
}
