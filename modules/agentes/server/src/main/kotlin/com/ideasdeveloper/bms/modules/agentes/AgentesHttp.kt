package com.ideasdeveloper.bms.modules.agentes

import io.ktor.http.decodeURLQueryComponent
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.origin
import io.ktor.server.request.receiveChannel
import io.ktor.utils.io.jvm.javaio.toInputStream

// Base publica fija opcional (BMS_PUBLIC_BASE_URL, por ejemplo https://bms.miempresa.com): si existe, el issuer y los endpoints no dependen de ninguna cabecera.
private val configuredBase: String? = System.getenv("BMS_PUBLIC_BASE_URL")?.trim()?.trimEnd('/')?.takeIf { it.startsWith("https://") || it.startsWith("http://") }

// Public origin of this server (https://host). Prefiere la base configurada; si no, usa el Host que Nginx valida (nunca X-Forwarded-Host, que escribe el cliente).
internal fun ApplicationCall.publicBase(): String {
    configuredBase?.let { return it }
    // X-Forwarded-Proto solo se acepta del Nginx local.
    val proxied = request.local.remoteAddress == "127.0.0.1"
    val scheme = (if (proxied) request.headers["X-Forwarded-Proto"]?.substringBefore(',')?.trim() else null)
        ?.takeIf { it == "https" || it == "http" } ?: request.origin.scheme
    val host = request.headers["Host"]?.trim()?.takeIf { it.isNotEmpty() && it.none { char -> char.isWhitespace() || char == '/' || char == '@' } } ?: request.origin.serverHost
    return "$scheme://$host"
}

// Address of the client; behind the local Nginx it comes in X-Real-IP (same rule as the login rate limit).
internal fun ApplicationCall.clientIp(): String {
    val remote = request.local.remoteAddress
    return if (remote == "127.0.0.1") request.headers["X-Real-IP"]?.takeIf { it.isNotBlank() } ?: remote else remote
}

/** Maximo del cuerpo de los formularios y registros OAuth (sin sesion): 64 KB. */
const val MaxOAuthBodyBytes = 64 * 1024

/** Cuerpo de la peticion como texto UTF-8 de a lo sumo [maxBytes]; nulo si lo pasa (la lectura se corta, no se lee entero). */
suspend fun ApplicationCall.readBoundedText(maxBytes: Int): String? {
    val declared = request.headers[io.ktor.http.HttpHeaders.ContentLength]?.toLongOrNull()
    if (declared != null && declared > maxBytes) return null
    val bytes = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        receiveChannel().toInputStream().use { it.readNBytes(maxBytes + 1) }
    }
    return if (bytes.size > maxBytes) null else String(bytes, Charsets.UTF_8)
}

/** Formulario application/x-www-form-urlencoded de a lo sumo [maxBytes]; nulo si lo pasa. */
suspend fun ApplicationCall.readBoundedForm(maxBytes: Int = MaxOAuthBodyBytes): io.ktor.http.Parameters? =
    readBoundedText(maxBytes)?.let { formOf(it, 1000) }

/** Pares clave=valor de un formulario application/x-www-form-urlencoded (el "+" es un espacio), a lo sumo [limit]. */
fun formOf(text: String, limit: Int): io.ktor.http.Parameters = io.ktor.http.Parameters.build {
    text.split('&').filter { it.isNotEmpty() }.take(limit).forEach { pair ->
        append(pair.substringBefore('=').decodeURLQueryComponent(plusIsSpace = true), pair.substringAfter('=', "").decodeURLQueryComponent(plusIsSpace = true))
    }
}
