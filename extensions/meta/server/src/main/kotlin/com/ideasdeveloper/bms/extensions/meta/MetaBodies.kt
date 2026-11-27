package com.ideasdeveloper.bms.extensions.meta

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

/** Lectura acotada del cuerpo de las rutas publicas pequenas de Meta (conexion, desautorizacion, borrado de datos): nunca se lee mas de 64 KB. */
object MetaBodies {
    const val MaxBytes = 64 * 1024

    /** Cuerpo como texto UTF-8 de a lo sumo [maxBytes]; si es mayor responde 413 y devuelve nulo (la lectura se corta). */
    suspend fun readText(call: ApplicationCall, maxBytes: Int = MaxBytes): String? {
        val declared = call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
        val body = if (declared != null && declared > maxBytes) null
        else withContext(Dispatchers.IO) { call.receiveChannel().toInputStream().use { it.readNBytes(maxBytes + 1) } }
        if (body == null || body.size > maxBytes) {
            call.respond(HttpStatusCode.PayloadTooLarge)
            return null
        }
        return String(body, Charsets.UTF_8)
    }

    /** Formulario application/x-www-form-urlencoded acotado como [readText]. */
    suspend fun readForm(call: ApplicationCall, maxBytes: Int = MaxBytes): Parameters? = readText(call, maxBytes)?.let { form(it) }

    /** Pares clave=valor de un formulario (el "+" es un espacio), a lo sumo [limit]. */
    fun form(text: String, limit: Int = 100): Parameters = Parameters.build {
        text.split('&').filter { it.isNotEmpty() }.take(limit).forEach { pair ->
            append(pair.substringBefore('=').decodeURLQueryComponent(plusIsSpace = true), pair.substringAfter('=', "").decodeURLQueryComponent(plusIsSpace = true))
        }
    }
}
