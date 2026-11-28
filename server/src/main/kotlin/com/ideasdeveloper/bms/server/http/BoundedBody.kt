package com.ideasdeveloper.bms.server.http

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.decodeURLQueryComponent
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respond
import io.ktor.utils.io.jvm.javaio.toInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Request bodies of a bounded size, for the small public routes that cannot rely on the global request limit.
 * A body is never read past `maxBytes + 1` bytes, so an oversize or endless upload costs a bounded amount of memory.
 */

/**
 * The body as bytes, at most [maxBytes]. Null (nothing responded) when the declared Content-Length is already over
 * the limit, in which case the body is not read at all, or when the body turns out to be larger.
 */
suspend fun ApplicationCall.receiveBoundedBytes(maxBytes: Int): ByteArray? {
    val declared = request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
    if (declared != null && declared > maxBytes) return null
    val bytes = withContext(Dispatchers.IO) { receiveChannel().toInputStream().use { it.readNBytes(maxBytes + 1) } }
    return if (bytes.size > maxBytes) null else bytes
}

/** [receiveBoundedBytes] as UTF-8 text. */
suspend fun ApplicationCall.receiveBoundedText(maxBytes: Int): String? =
    receiveBoundedBytes(maxBytes)?.let { String(it, Charsets.UTF_8) }

/** [receiveBoundedBytes] that answers 413 itself when the body is over the limit; the result is then null. */
suspend fun ApplicationCall.receiveBoundedBytesOr413(maxBytes: Int): ByteArray? =
    receiveBoundedBytes(maxBytes) ?: run { respond(HttpStatusCode.PayloadTooLarge); null }

/** [receiveBoundedText] that answers 413 itself when the body is over the limit; the result is then null. */
suspend fun ApplicationCall.receiveBoundedTextOr413(maxBytes: Int): String? =
    receiveBoundedText(maxBytes) ?: run { respond(HttpStatusCode.PayloadTooLarge); null }

/** The pairs `key=value` of an application/x-www-form-urlencoded [text] (`+` is a space), at most [limit] of them. */
fun formOf(text: String, limit: Int): Parameters = Parameters.build {
    text.split('&').filter { it.isNotEmpty() }.take(limit).forEach { pair ->
        append(pair.substringBefore('=').decodeURLQueryComponent(plusIsSpace = true), pair.substringAfter('=', "").decodeURLQueryComponent(plusIsSpace = true))
    }
}
