package com.ideasdeveloper.bms.extensions.wordpress

import com.ideasdeveloper.bms.modules.crm.CrmAudit
import com.ideasdeveloper.bms.server.activity.ActivityChange
import com.ideasdeveloper.bms.server.activity.ActivityActions
import com.ideasdeveloper.bms.server.activity.ActivityActor
import com.ideasdeveloper.bms.modules.crm.CrmActor
import com.ideasdeveloper.bms.modules.crm.CrmExtensionIds
import com.ideasdeveloper.bms.modules.crm.CrmHost
import com.ideasdeveloper.bms.modules.crm.CrmInbound
import com.ideasdeveloper.bms.modules.crm.CrmIntakeResult
import com.ideasdeveloper.bms.modules.crm.CrmUser
import com.ideasdeveloper.bms.modules.crm.CrmWordPressException
import com.ideasdeveloper.bms.modules.crm.CrmWordPressSettings
import com.ideasdeveloper.bms.modules.crm.WordPressPluginInfo
import com.ideasdeveloper.bms.modules.crm.WpAgent
import com.ideasdeveloper.bms.modules.crm.WpListing
import com.ideasdeveloper.bms.modules.crm.WpSearchResult
import com.ideasdeveloper.bms.server.i18n.tr
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.math.BigDecimal
import java.net.URI
import java.net.InetAddress
import com.ideasdeveloper.bms.server.net.PinnedHost
import com.ideasdeveloper.bms.server.net.PinnedHostException
import java.security.MessageDigest
import java.sql.Connection
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Sitios Real Estate 7 con el plugin BMS Connector (cliente y eventos). Los ajustes y los agentes viven en las tablas de
 * la extension ([WordPressSettingsStore]); el resto del CRM (inbox, intereses, actividad, secretos) se usa solo por [CrmHost].
 */
object CrmWordPress {
    const val Source = WordPressPluginInfo.Source
    const val PluginVersion = WordPressPluginInfo.Version
    private const val MaxResponseBytes = 1024 * 1024
    private const val MaxSkewSeconds = 300L
    private val json = Json { ignoreUnknownKeys = true }
    private const val MaxSeenSignatures = 50_000

    // Firmas ya aceptadas (empresa + firma) con su caducidad: una firma valida no se acepta dos veces mientras su fecha siga dentro de la ventana.
    private val seenSignatures = java.util.concurrent.ConcurrentHashMap<String, Long>()

    // ---- Ajustes ----

    fun load(tenant: Connection): CrmWordPressSettings = WordPressSettingsStore.load(CrmHost, tenant)

    // ---- Firmas ----

    fun sign(secret: String, timestamp: String, body: ByteArray): String {
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256")) }
        mac.update("$timestamp.".toByteArray(Charsets.UTF_8))
        return mac.doFinal(body).joinToString("") { "%02x".format(it) }
    }

    /** Valid when the timestamp is within 5 minutes and the signature matches, compared in constant time. */
    fun validSignature(secret: String?, timestamp: String?, signature: String?, body: ByteArray, now: Instant = Instant.now()): Boolean {
        // Un secreto ilegible (texto cifrado) cuenta como "sin secreto": falla cerrado.
        if (secret.isNullOrBlank() || CrmHost.isSecretUnreadable(secret) || timestamp == null || signature == null) return false
        val seconds = timestamp.toLongOrNull() ?: return false
        if (kotlin.math.abs(now.epochSecond - seconds) > MaxSkewSeconds) return false
        return MessageDigest.isEqual(sign(secret, timestamp, body).toByteArray(), signature.lowercase().toByteArray())
    }

    /**
     * Anti-replay: true la primera vez que se ve [signature] de [company] dentro de la ventana, false si ya se uso
     * (un atacante que capture una peticion firmada no puede repetirla durante los 5 minutos de validez de la fecha, en ningun sentido).
     * Solo se guardan firmas validas (las comprueba el llamador antes), asi que no se pueden inventar entradas.
     * Con la tabla llena (50.000 firmas vivas) falla cerrado.
     */
    fun firstUse(company: String, signature: String, now: Instant = Instant.now()): Boolean {
        val nowSeconds = now.epochSecond
        if (seenSignatures.size >= MaxSeenSignatures / 2) seenSignatures.entries.removeIf { it.value <= nowSeconds }
        if (seenSignatures.size >= MaxSeenSignatures) return false
        // La fecha firmada puede ir hasta MaxSkewSeconds por delante: la firma es valida hasta 2 * MaxSkewSeconds desde ahora.
        var first = false
        seenSignatures.compute("$company:${signature.lowercase()}") { _, expires ->
            if (expires == null || expires <= nowSeconds) { first = true; nowSeconds + 2 * MaxSkewSeconds + 1 } else expires
        }
        return first
    }

    /** Olvida una firma (el evento no se pudo procesar y el plugin lo reintentara con la misma firma si coincide el segundo). */
    fun forget(company: String, signature: String) {
        seenSignatures.remove("$company:${signature.lowercase()}")
    }

    // ---- API del plugin (BMS -> sitio) ----

    /** True for addresses a site must never resolve to: loopback, link-local, private (RFC1918), CGNAT 100.64/10, IPv6 ULA fc00::/7, multicast and "this network". */
    fun blockedAddress(address: java.net.InetAddress): Boolean = com.ideasdeveloper.bms.server.modules.MailHostGuard.blockedAddress(address)

    /**
     * The site must be a public host: with a dot, and every address it resolves to public. Checked when saving and before each call
     * (so a name that later points inside the network is still refused). [resolve] is replaceable for tests.
     */
    fun checkSite(url: String, resolve: (String) -> List<java.net.InetAddress> = { host -> java.net.InetAddress.getAllByName(host).toList() }) {
        val host = siteHost(url)
        val addresses = try {
            resolve(host)
        } catch (failure: Exception) {
            throw CrmWordPressException(tr("No se encontro el sitio {0}.", host))
        }
        if (addresses.isEmpty() || addresses.any(::blockedAddress)) throw CrmWordPressException(tr("La direccion del sitio apunta a una red privada o local: no se permite."))
    }

    private fun siteHost(url: String): String {
        val host = runCatching { URI.create(url).host }.getOrNull()?.lowercase()
        if (host.isNullOrBlank() || !host.contains('.') || host.endsWith(".local") || host.endsWith(".internal") || host.endsWith(".localhost")) {
            throw CrmWordPressException(tr("La direccion del sitio debe ser un dominio publico (con punto)."))
        }
        return host
    }

    /** Respuesta de una peticion anclada: estado y cuerpo (acotado). */
    class SiteAnswer(val status: Int, val bytes: ByteArray)

    /** Envia la peticion a la IP ya validada ([address]); sustituible en las pruebas. */
    var fetch: (URI, InetAddress, Map<String, String>, ByteArray) -> SiteAnswer = { uri, address, headers, body ->
        PinnedHost.openPinnedHttps(
            uri, address, uri.host, method = "POST", headers = headers, body = body,
            timeoutMillis = 20_000, totalMillis = 30_000, maxBodyBytes = MaxResponseBytes + 1L,
        ).use { response -> SiteAnswer(response.status, response.body.readNBytes(MaxResponseBytes + 1)) }
    }

    /** Resuelve y valida el host UNA vez; la conexion va a esa misma IP (un DNS que cambie entre la comprobacion y la conexion no sirve). */
    var resolve: (String) -> InetAddress = { host -> PinnedHost.resolveValidated(host) }

    internal fun rpc(settings: CrmWordPressSettings, action: String, extra: JsonObject = JsonObject(emptyMap())): JsonElement {
        val site = settings.siteUrl ?: throw CrmWordPressException(tr("Escribe la direccion del sitio en Integraciones."))
        val secret = settings.secret?.takeIf { !CrmHost.isSecretUnreadable(it) }
            ?: throw CrmWordPressException(if (CrmHost.isSecretUnreadable(settings.secret)) tr("{0}. Genera un secreto nuevo.", CrmHost.UnreadableSecretText) else tr("Genera el secreto y pegalo en el plugin."))
        val host = siteHost(site)
        val body = JsonObject(mapOf("action" to JsonPrimitive(action)) + extra).toString().toByteArray()
        val timestamp = Instant.now().epochSecond.toString()
        val uri = runCatching { URI.create("$site/wp-json/bms/v1/rpc") }.getOrNull()?.takeIf { it.scheme == "https" && it.host != null }
            ?: throw CrmWordPressException(tr("La direccion del sitio debe ser un dominio publico (con punto)."))
        val address = try {
            resolve(host)
        } catch (failure: PinnedHostException) {
            throw CrmWordPressException(if (failure.message.orEmpty().startsWith("No se encontro")) tr("No se encontro el sitio {0}.", host) else tr("La direccion del sitio apunta a una red privada o local: no se permite."))
        }
        val headers = mapOf("Content-Type" to "application/json", "X-BMS-Timestamp" to timestamp, "X-BMS-Signature" to sign(secret, timestamp, body), "Accept" to "application/json", "User-Agent" to "BMS-Server")
        val (status, text) = try {
            // The answer of a site is never read whole beyond MaxResponseBytes.
            val answer = fetch(uri, address, headers, body)
            if (answer.bytes.size > MaxResponseBytes) throw CrmWordPressException(tr("El sitio respondio algo demasiado grande."))
            answer.status to String(answer.bytes, Charsets.UTF_8)
        } catch (failure: CrmWordPressException) {
            throw failure
        } catch (failure: Exception) {
            throw CrmWordPressException(tr("No se pudo contactar el sitio {0}.", site))
        }
        val parsed = runCatching { json.parseToJsonElement(text) }.getOrNull()
        return when (status) {
            in 200..299 -> parsed ?: throw CrmWordPressException(tr("El sitio respondio algo que no es JSON."))
            401, 403 -> throw CrmWordPressException(tr("El sitio rechazo la firma: revisa que el plugin BMS Connector este activo y tenga el mismo secreto."))
            404 -> throw CrmWordPressException((parsed as? JsonObject)?.get("error").str().ifEmpty { tr("El sitio no tiene el plugin BMS Connector (o ese recurso no existe).") })
            else -> throw CrmWordPressException(tr("El sitio respondio HTTP {0}.", status))
        }
    }

    /** Settings for a call to the website; fails when the extension is not active in the company. */
    internal fun checked(tenant: Connection): CrmWordPressSettings {
        CrmHost.extensionInactiveReason(tenant, CrmExtensionIds.WordPress)?.let { throw CrmWordPressException(it) }
        return load(tenant)
    }

    fun ping(tenant: Connection): String {
        CrmHost.extensionInactiveReason(tenant, CrmExtensionIds.WordPress)?.let { throw CrmWordPressException(it) }
        val result = rpc(load(tenant), "ping").obj()
        val name = result?.get("site").str()
        WordPressSettingsStore.record(tenant, name, "")
        return tr("{0} (plugin {1}, tema {2})", name.ifEmpty { tr("Sitio") }, result?.get("version").str(), result?.get("theme").str())
    }

    fun search(tenant: Connection, text: String, filters: Map<String, String>, minPrice: String, maxPrice: String, page: Int): WpSearchResult {
        CrmHost.extensionInactiveReason(tenant, CrmExtensionIds.WordPress)?.let { throw CrmWordPressException(it) }
        val payload = buildJsonObject {
            put("search", text.take(100))
            put("page", page)
            put("per_page", 20)
            put("min_price", minPrice)
            put("max_price", maxPrice)
            putJsonObject("filters") { filters.forEach { (key, value) -> put(key, value) } }
        }
        val result = rpc(load(tenant), "search", payload).obj() ?: throw CrmWordPressException(tr("Respuesta invalida del sitio."))
        return WpSearchResult(result["total"].str().toIntOrNull() ?: 0, result["items"].arr().mapNotNull { listing(it) })
    }

    fun listing(tenant: Connection, id: Long): WpListing =
        listing(rpc(checked(tenant), "listing", buildJsonObject { put("id", id) })) ?: throw CrmWordPressException(tr("Respuesta invalida del sitio."))

    fun taxonomies(tenant: Connection): Map<String, List<String>> =
        rpc(checked(tenant), "taxonomies").obj()?.mapValues { (_, terms) -> terms.arr().mapNotNull { it.obj()?.get("name").str().ifEmpty { null } } }.orEmpty()

    fun markSold(tenant: Connection, id: Long, price: BigDecimal?, date: LocalDate, actor: ActivityActor) {
        rpc(checked(tenant), "sold", buildJsonObject {
            put("id", id)
            put("price", price?.toPlainString().orEmpty())
            put("date", date.toString())
        })
        // The change is made on the website: the log says who asked for it.
        CrmAudit.record(
            tenant, actor, "wp_listing", id.toString(), "Propiedad $id", ActivityActions.StatusChanged, "Marco la propiedad $id como vendida en el sitio de WordPress.",
            listOfNotNull(ActivityChange("sold", "Vendida", "No", "Si"), price?.let { ActivityChange("price", "Precio", null, it.toPlainString()) }, ActivityChange("date", "Fecha", null, date.toString())),
        )
    }

    /** Reads the agents of the website and keeps their BMS user (an email match links new ones). */
    fun syncAgents(tenant: Connection, users: List<CrmUser>, userEmails: Map<UUID, String>, actor: ActivityActor): Int {
        val received = rpc(checked(tenant), "agents").arr().mapNotNull { it.obj() }
        val agents = received.mapNotNull { agent ->
            val id = agent["id"].str().toLongOrNull()?.takeIf { it > 0 } ?: return@mapNotNull null
            WpAgent(id, agent["name"].str(), agent["email"].str(), null)
        }
        WordPressSettingsStore.syncAgents(tenant, agents, users, userEmails, actor)
        // Cuenta todos los agentes que envio el sitio, incluso los que se omiten por no tener id valido.
        return received.size
    }

    // ---- Eventos (sitio -> BMS) ----

    private fun JsonElement?.obj() = this as? JsonObject
    private fun JsonElement?.arr() = (this as? JsonArray).orEmpty()
    private fun JsonElement?.str() = (this as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()

    /** La propiedad tal como la envia el plugin (ver [WpListing.parse]). */
    fun listing(element: JsonElement?): WpListing? = WpListing.parse(element)

    private val formLabels = mapOf("form" to "Formulario", "inquiry" to "Consulta de propiedad", "showing" to "Solicitud de visita")

    /**
     * Stores one event of the website. Forms, listing inquiries and showing requests go to the inbox
     * (channel "form") with the property linked and its agent as suggested responsible; favorites,
     * saved searches and views are added to the activity of the customer's open lead and never
     * create leads. Returns what happened, for the log.
     */
    fun handle(tenant: Connection, body: String, onNewLead: (UUID) -> Unit): String {
        val event = runCatching { json.parseToJsonElement(body) }.getOrNull().obj() ?: return "JSON invalido"
        val kind = event["event"].str()
        val listing = listing(event["listing"])
        val name = event["name"].str()
        val email = event["email"].str()
        return when (kind) {
            "ping" -> {
                WordPressSettingsStore.record(tenant, null, "")
                "ping"
            }
            "form", "inquiry", "showing" -> {
                val label = formLabels.getValue(kind)
                val source = when {
                    listing != null -> "Formulario - ${listing.title}"
                    event["form"].str().isNotEmpty() -> "Formulario - ${event["form"].str()}"
                    else -> "Formulario del sitio"
                }
                val header = "[Sitio web - $label]" + (listing?.let { " ${it.title}" } ?: event["form"].str().takeIf { it.isNotEmpty() }?.let { " $it" }.orEmpty())
                val result = CrmHost.receive(
                    tenant,
                    CrmInbound(
                        channel = "form",
                        name = name,
                        email = email,
                        phone = event["phone"].str(),
                        message = listOf(header, event["message"].str()).filter { it.isNotEmpty() }.joinToString("\n"),
                        messageId = event["id"].str().takeIf { it.isNotEmpty() }?.let { "wp:$it" },
                        source = source,
                        suggestedOwnerUserId = listing?.let { WordPressSettingsStore.userForAgent(tenant, it.agentId, it.agentName, it.agentEmail) },
                    ),
                    onNewLead = onNewLead,
                )
                val leadId = when (result) {
                    is CrmIntakeResult.Created -> result.leadId
                    is CrmIntakeResult.Updated -> result.leadId
                    else -> null
                }
                if (leadId != null && listing != null) linkListing(tenant, leadId, listing, "Consulto la propiedad ${listing.title} en el sitio.", SiteActor)
                WordPressSettingsStore.record(tenant, null, "")
                "$kind: ${result::class.simpleName}"
            }
            "favorite", "saved_search", "view" -> {
                val lead = CrmHost.openLeadIdByEmail(tenant, email) ?: return "$kind sin lead"
                val text = when (kind) {
                    "favorite" -> "Agrego a favoritos: ${listing?.title.orEmpty()}"
                    "saved_search" -> "Guardo una busqueda con alertas:\n${event["message"].str()}"
                    else -> "Vio la propiedad ${listing?.title.orEmpty()}"
                }
                CrmHost.addActivity(tenant, lead, "web", text, SiteActor)
                if (kind == "favorite" && listing != null) linkListing(tenant, lead, listing, null, SiteActor)
                "$kind registrado"
            }
            else -> "evento desconocido"
        }
    }

    /** The website itself: its events are attributed to the "Sitio web" integration in the lead's history and in the activity log. */
    private val SiteActor = CrmActor(null, "Sitio web", ActivityActor.integration("Sitio web"))

    fun linkListing(tenant: Connection, leadId: UUID, listing: WpListing, activity: String?, actor: CrmActor = SiteActor) {
        CrmAudit.atomically(tenant) {
            val added = CrmHost.linkItem(
                tenant, leadId, Source, listing.id.toString(), listing.title,
                subtitle = listOf(listing.priceText, listing.summary).filter { it.isNotEmpty() }.joinToString(" - "),
                url = listing.url, price = listing.priceValue,
            )
            if (added) CrmHost.addActivity(tenant, leadId, "item", activity ?: "Propiedad de interes: ${listing.title}.", actor)
        }
    }

    fun recordError(tenant: Connection, error: String) = WordPressSettingsStore.recordError(tenant, error)
}
