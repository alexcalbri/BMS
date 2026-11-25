package com.ideasdeveloper.bms.server.outbound.monday

import com.ideasdeveloper.bms.server.net.PinnedHost
import com.ideasdeveloper.bms.server.net.PinnedResponse
import com.ideasdeveloper.bms.server.outbound.OutboundFile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import org.slf4j.LoggerFactory
import java.io.OutputStream
import java.net.InetAddress
import java.net.URI
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/** Que hacer con un fallo de monday: esperar ([Throttled]), pedir otro token ([Auth]), reintentar ([Transient]) o rendirse ([Permanent]). */
enum class MondayErrorKind { Throttled, Auth, Transient, Permanent }

/** Fallo de monday ya clasificado. El mensaje se puede mostrar al usuario: nunca lleva el token. */
class MondayApiException(val kind: MondayErrorKind, message: String, val retryAfter: Duration? = null) : Exception(message)

/** Cliente de la API GraphQL de monday; en las pruebas se sustituye por uno simulado. Nunca se interpolan valores en la consulta. */
interface MondayApi {
    /** Ejecuta [query] con [variables] y devuelve el objeto `data`. Lanza [MondayApiException]. */
    fun call(token: String, query: String, variables: JsonObject = JsonObject(emptyMap())): JsonObject

    /**
     * Sube [file] a /v2/file como la variable [fileVariable] de [query] (otras variables de texto van como
     * `variables[nombre]`). Si [file] pesa mas de [maxBytes], falla sin abrirlo.
     */
    fun upload(
        token: String,
        query: String,
        variables: JsonObject = JsonObject(emptyMap()),
        fileVariable: String = "file",
        file: OutboundFile,
        maxBytes: Long,
    ): JsonObject
}

/** Clasificacion pura de las respuestas de monday. */
object MondayErrors {
    private val throttleCodes = setOf(
        "COMPLEXITY_BUDGET_EXHAUSTED", "ComplexityException", "maxComplexityExceeded", "RATE_LIMIT_EXCEEDED",
        "IP_RATE_LIMIT_EXCEEDED", "maxConcurrencyExceeded",
    )
    private const val DailyLimit = "DAILY_LIMIT_EXCEEDED"
    private const val Unauthorized = "UserUnauthorizedException"
    private val defaultWait = Duration.ofSeconds(60)
    private val maxWait = Duration.ofHours(24)
    private const val MaxMessage = 150
    private val json = Json { ignoreUnknownKeys = true }
    private val resetIn = Regex("(?i)reset(?:s)? in (\\d{1,6}) second")

    /**
     * Mira una respuesta de monday: null si todo fue bien (HTTP 2xx, sin `errors` y con `data`), o el fallo clasificado.
     * [headers] con los nombres en minusculas. Ojo: monday responde HTTP 200 con `errors` (p. ej. ColumnValueException).
     */
    fun classify(status: Int, headers: Map<String, String>, body: String, now: Instant = Instant.now()): MondayApiException? {
        val root = runCatching { json.parseToJsonElement(body) }.getOrNull().asObj()
        val errors = root?.get("errors").asArr().mapNotNull { it.asObj() }
        val codes = buildSet {
            errors.forEach { error ->
                add(error["extensions"].asObj()?.get("code").asStr())
                add(error["extensions"].asObj()?.get("error_code").asStr())
            }
            add(root?.get("error_code").asStr())
            add(root?.get("code").asStr())
        }.filter { it.isNotEmpty() }.toSet()
        val message = errors.firstOrNull()?.get("message").asStr().ifEmpty { root?.get("error_message").asStr() }.ifEmpty { root?.get("message").asStr() }
        val retrySeconds = headers["retry-after"]?.trim()?.toLongOrNull()
            ?: errors.firstNotNullOfOrNull { it["extensions"].asObj()?.get("retry_in_seconds").asStr().toLongOrNull() }
            ?: root?.get("retry_in_seconds").asStr().toLongOrNull()
            ?: root?.get("extensions").asObj()?.get("retry_in_seconds").asStr().toLongOrNull()
            ?: resetIn.find(message)?.groupValues?.get(1)?.toLongOrNull()
        return when {
            status == 401 || Unauthorized in codes ->
                MondayApiException(MondayErrorKind.Auth, "monday rechazo el token.")
            status == 403 ->
                MondayApiException(MondayErrorKind.Auth, "el token no tiene permiso sobre ese tablero o accion.")
            DailyLimit in codes -> {
                val midnight = now.atZone(ZoneOffset.UTC).toLocalDate().plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant()
                val wait = Duration.between(now, midnight).let { if (it < defaultWait) defaultWait else it }
                MondayApiException(MondayErrorKind.Throttled, "monday alcanzo el limite diario; se reintenta tras la medianoche UTC.", wait)
            }
            status == 429 || codes.any { it in throttleCodes } ->
                MondayApiException(MondayErrorKind.Throttled, "monday pidio esperar (limite de uso).", wait(retrySeconds))
            status >= 500 || status == 408 ->
                MondayApiException(MondayErrorKind.Transient, "monday respondio HTTP $status.", headers["retry-after"]?.trim()?.toLongOrNull()?.let { wait(it) })
            // El codigo de monday sirve para diagnosticar; el mensaje se recorta porque puede llevar datos personales.
            errors.isNotEmpty() || root?.get("error_message") != null ->
                MondayApiException(MondayErrorKind.Permanent, "monday" + codes.firstOrNull()?.let { " ($it)" }.orEmpty() + ": " + tidy(message.ifEmpty { "error sin detalle" }))
            status !in 200..299 -> MondayApiException(MondayErrorKind.Permanent, "monday respondio HTTP $status.")
            root?.get("data").asObj() == null -> MondayApiException(MondayErrorKind.Permanent, "monday respondio sin datos.")
            else -> null
        }
    }

    private fun wait(seconds: Long?): Duration {
        val wanted = seconds?.takeIf { it > 0 }?.let(Duration::ofSeconds) ?: defaultWait
        return if (wanted > maxWait) maxWait else wanted
    }

    /** Mensaje en una linea y recortado. */
    fun tidy(text: String): String = text.replace(Regex("\\s+"), " ").trim().let { if (it.length > MaxMessage) it.take(MaxMessage - 3) + "..." else it }
}

/** Fallo al abrir o leer el archivo local (no es un fallo de red). */
class LocalFileReadException(cause: Throwable) : IOException("No se pudo leer el archivo local.", cause)

/** Cuerpo multipart/form-data de /v2/file, con Content-Length exacto para enviarlo por streaming. */
class MondayMultipart(
    private val texts: Map<String, String>,
    private val fileField: String,
    private val file: OutboundFile,
    val boundary: String = "bms" + UUID.randomUUID().toString().replace("-", ""),
) {
    private fun clean(text: String) = text.replace(Regex("[\\r\\n\"\\\\]"), "_")

    private val head: ByteArray = ByteArrayOutputStream().also { out ->
        texts.forEach { (name, value) ->
            out.write("--$boundary\r\nContent-Disposition: form-data; name=\"${clean(name)}\"\r\n\r\n$value\r\n".toByteArray(Charsets.UTF_8))
        }
        val type = file.contentType.replace(Regex("[\\r\\n]"), " ").ifBlank { "application/octet-stream" }
        out.write(
            ("--$boundary\r\nContent-Disposition: form-data; name=\"${clean(fileField)}\"; filename=\"${clean(file.name)}\"\r\n" +
                "Content-Type: $type\r\n\r\n").toByteArray(Charsets.UTF_8),
        )
    }.toByteArray()
    private val tail: ByteArray = "\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8)

    val contentType: String get() = "multipart/form-data; boundary=$boundary"
    val contentLength: Long get() = head.size.toLong() + file.size + tail.size

    /** Escribe todo el cuerpo; el archivo se abre aqui, una vez, y se cierra al terminar. */
    fun write(out: OutputStream) {
        out.write(head)
        val input = try {
            file.open()
        } catch (failure: Exception) {
            throw LocalFileReadException(failure)
        }
        input.use {
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = try {
                    it.read(buffer)
                } catch (failure: IOException) {
                    throw LocalFileReadException(failure)
                }
                if (count < 0) break
                out.write(buffer, 0, count)
            }
        }
        out.write(tail)
    }
}

/** Cliente real: POST a https://api.monday.com/v2 (y /v2/file) con el host anclado. Nunca se usa en las pruebas. */
class HttpMondayApi : MondayApi {
    companion object {
        const val Host = "api.monday.com"
        const val ApiVersion = "2024-10"
        const val MaxResponseBytes = 20 * 1024 * 1024
        private val json = Json { ignoreUnknownKeys = true }
        private val log = LoggerFactory.getLogger("MondayApi")
    }

    override fun call(token: String, query: String, variables: JsonObject): JsonObject {
        val payload = JsonObject(mapOf("query" to JsonPrimitive(query), "variables" to variables)).toString().toByteArray(Charsets.UTF_8)
        return exchange(token, "/v2", "application/json") { validated, headers ->
            PinnedHost.openPinnedHttps(
                URI.create("https://$Host/v2"), validated, Host, "POST", headers, payload,
                timeoutMillis = 30_000, totalMillis = 60_000, maxBodyBytes = MaxResponseBytes + 1L,
            )
        }
    }

    override fun upload(token: String, query: String, variables: JsonObject, fileVariable: String, file: OutboundFile, maxBytes: Long): JsonObject {
        if (file.size > maxBytes) throw MondayApiException(MondayErrorKind.Permanent, "El archivo supera el maximo permitido.")
        val texts = linkedMapOf("query" to query)
        variables.forEach { (name, value) -> texts["variables[$name]"] = (value as? JsonPrimitive)?.content ?: value.toString() }
        val multipart = MondayMultipart(texts, "variables[$fileVariable]", file)
        return exchange(token, "/v2/file", multipart.contentType) { validated, headers ->
            PinnedHost.openPinnedHttps(
                URI.create("https://$Host/v2/file"), validated, Host, "POST", headers, multipart.contentLength, multipart::write,
                timeoutMillis = 60_000, totalMillis = 15 * 60_000L, maxBodyBytes = MaxResponseBytes + 1L,
            )
        }
    }

    private fun exchange(token: String, path: String, contentType: String, send: (InetAddress, Map<String, String>) -> PinnedResponse): JsonObject {
        val clean = token.trim()
        if (clean.isEmpty()) throw MondayApiException(MondayErrorKind.Auth, "Falta el token de monday.")
        val headers = mapOf("Authorization" to clean, "API-Version" to ApiVersion, "Content-Type" to contentType)
        val status: Int
        val retryAfter: String?
        val text: String
        try {
            // Se resuelve una vez y la conexion va a esa IP validada (anti DNS rebinding).
            val validated = PinnedHost.resolveValidated(Host)
            val answer = send(validated, headers).use { response ->
                val bytes = response.body.readNBytes(MaxResponseBytes + 1)
                if (bytes.size > MaxResponseBytes) throw MondayApiException(MondayErrorKind.Permanent, "La respuesta de monday es demasiado grande.")
                Triple(response.status, response.header("retry-after"), bytes.decodeToString())
            }
            status = answer.first
            retryAfter = answer.second
            text = answer.third
        } catch (failure: MondayApiException) {
            throw failure
        } catch (failure: Exception) {
            // Solo la clase del error en el log: el mensaje original podria arrastrar cabeceras o el token.
            log.warn("Fallo al hablar con monday ({}): {}", path, failure.javaClass.simpleName)
            val local = failure as? LocalFileReadException ?: failure.cause as? LocalFileReadException
            when {
                local != null -> throw MondayApiException(MondayErrorKind.Permanent, "El archivo ya no esta en el almacenamiento")
                failure is InterruptedException || (failure is InterruptedIOException && failure !is SocketTimeoutException) -> {
                    Thread.currentThread().interrupt()
                    throw MondayApiException(MondayErrorKind.Transient, "Se interrumpio el envio a monday.")
                }
                else -> throw MondayApiException(MondayErrorKind.Transient, "No se pudo contactar monday ($path).")
            }
        }
        MondayErrors.classify(status, listOfNotNull(retryAfter?.let { "retry-after" to it }).toMap(), text)?.let { throw it }
        return runCatching { json.parseToJsonElement(text) }.getOrNull().asObj()?.get("data").asObj()
            ?: throw MondayApiException(MondayErrorKind.Permanent, "monday respondio sin datos.")
    }
}
