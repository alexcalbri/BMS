package com.ideasdeveloper.bms.server.app

import com.ideasdeveloper.bms.server.admins.routeKey
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.hooks.ReceiveRequestBytes
import io.ktor.server.plugins.PayloadTooLargeException
import io.ktor.server.request.contentLength
import io.ktor.server.routing.RoutingCall
import io.ktor.server.routing.RoutingNode
import io.ktor.server.routing.RoutingPipelineCall
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.writeFully
import io.ktor.utils.io.writer
import kotlinx.coroutines.Dispatchers

/**
 * Safety net for request bodies (SECURITY_FIXES.md B10): every route reads at most
 * [BodyLimitConfig.defaultBytes] unless it is one of [UploadRoutes], which keep their own limits
 * (they stream to disk and check size, quota and free space themselves). A larger declared
 * Content-Length is refused before reading (413); a body without one is cut when it crosses the limit.
 * It does not replace the proxy's limit: it keeps a missing one from being fatal.
 */
class BodyLimitConfig {
    var defaultBytes: Long = DefaultRequestBodyBytes
    var unlimited: (routeKey: String) -> Boolean = ::isUploadRoute
}

const val DefaultRequestBodyBytes = 4L * 1024 * 1024

/**
 * Routes that receive files ("METHOD /path" as Ktor declares them). Package A's Nginx needs the same
 * list for its larger client_max_body_size locations.
 */
val UploadRoutes: List<String> = listOf(
    // Company backup ZIP import (B4: 512 MB, entry and expanded-size limits).
    "POST /companies/me/backups/import",
    // Extension package ZIP (owner only, EXTENSION_UPLOAD_ENABLED; 50 MB).
    "POST /server/extensions/upload",
    // Module files (ModuleFiles: quota, free-disk floor, per-module maximum).
    "POST /modules/crm/ui/files",
    "POST /modules/tareas/ui/files",
    "POST /modules/propietarios/ui/files",
    "POST /modules/compras/ui/files",
    "POST /modules/mantenimiento/ui/files",
)

// A module that adds an upload later follows the same shape: POST /modules/<id>/ui/files.
private val moduleFilesRoute = Regex("POST /modules/[a-z][a-z0-9_]{0,62}/ui/files")

fun isUploadRoute(key: String): Boolean = key in UploadRoutes || moduleFilesRoute.matches(key)

private fun ApplicationCall.routingNode(): RoutingNode? = when (this) {
    is RoutingPipelineCall -> route
    is RoutingCall -> route
    else -> null
}

val RequestBodyLimit = createApplicationPlugin("BmsRequestBodyLimit", ::BodyLimitConfig) {
    val limit = pluginConfig.defaultBytes
    val unlimited = pluginConfig.unlimited
    on(ReceiveRequestBytes) { call, body ->
        val key = call.routingNode()?.let(::routeKey)
        if (key != null && unlimited(key)) return@on body
        val declared = call.request.contentLength()
        if (declared != null && declared > limit) throw PayloadTooLargeException(limit)
        call.application.writer(Dispatchers.IO) {
            val buffer = ByteArray(16 * 1024)
            var total = 0L
            while (true) {
                val read = body.readAvailable(buffer, 0, buffer.size)
                if (read == -1) break
                if (read == 0) {
                    if (body.isClosedForRead) break
                    continue
                }
                total += read
                if (total > limit) throw PayloadTooLargeException(limit)
                channel.writeFully(buffer, 0, read)
            }
        }.channel as ByteReadChannel
    }
}
