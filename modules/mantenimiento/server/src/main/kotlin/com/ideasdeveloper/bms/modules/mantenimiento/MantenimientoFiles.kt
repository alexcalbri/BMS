package com.ideasdeveloper.bms.modules.mantenimiento

import com.ideasdeveloper.bms.server.i18n.tr
import com.ideasdeveloper.bms.server.modules.ModuleAccessService
import com.ideasdeveloper.bms.server.modules.requireModuleAccess
import com.ideasdeveloper.bms.ui.UiFileRef
import com.ideasdeveloper.bms.ui.UiJson
import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.contentType
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondOutputStream
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.utils.io.jvm.javaio.toInputStream
import java.net.URLDecoder
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

/**
 * Subida y descarga de los adjuntos de las órdenes. Una subida espera (sin orden) a que se guarde su formulario; la descarga solo
 * entrega archivos de órdenes que el usuario ve y las subidas pendientes a quien las subió (el filtro va en SQL, en el repositorio).
 * Lo que un navegador podría ejecutar dentro del origen de la app se sirve siempre como descarga simple, con `nosniff`.
 */
object MantenimientoFiles {
    private val log = LoggerFactory.getLogger(MantenimientoFiles::class.java)

    /** Tipos que se respetan al servir; cualquier otro (en especial image/svg+xml y text/html) se sirve como application/octet-stream. */
    val AllowedTypes: Set<String> = setOf(
        "application/pdf",
        "image/png",
        "image/jpeg",
        "image/gif",
        "image/webp",
        "text/plain",
        "text/csv",
        "application/vnd.ms-excel",
        "application/msword",
    )

    private const val OpenXmlPrefix = "application/vnd.openxmlformats-officedocument."

    /** El tipo a servir para el [stored] guardado: el mismo si está permitido, si no octet-stream. */
    fun safeType(stored: String?): String {
        val type = stored?.substringBefore(';')?.trim()?.lowercase().orEmpty()
        val allowed = type in AllowedTypes || (type.startsWith(OpenXmlPrefix) && type.length > OpenXmlPrefix.length && type.none { it.isWhitespace() })
        return if (allowed) type else "application/octet-stream"
    }

    fun contentType(stored: String?): ContentType = runCatching { ContentType.parse(safeType(stored)) }.getOrDefault(ContentType.Application.OctetStream)

    /** Registra `POST /files` y `GET /files/{id}` dentro de la ruta del módulo [route]. */
    fun install(route: Route, access: ModuleAccessService) {
        route.post("/files") {
            val permissions = call.requireModuleAccess(access, MantenimientoModule.id) ?: return@post
            if (!permissions.has(MantenimientoModule.View)) {
                return@post call.respond(HttpStatusCode.Forbidden, MantenimientoErrorResponse("missing_permission", tr("No tienes permiso para esta acción.")))
            }
            val length = call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
            if (length != null && length > MantenimientoRepository.MaxFileBytes) {
                return@post call.respond(HttpStatusCode.PayloadTooLarge, MantenimientoErrorResponse("too_large", tr("El archivo supera el máximo de {0} MB.", MantenimientoRepository.MaxFileBytes / (1024 * 1024))))
            }
            fun header(name: String) = call.request.headers[name]?.let { runCatching { URLDecoder.decode(it, Charsets.UTF_8) }.getOrNull() }.orEmpty()
            val name = header("X-File-Name")
            val path = header("X-File-Path")
            val contentType = call.request.contentType().withoutParameters().toString()
            val companyCode = access.companyCode(call)
                ?: return@post call.respond(HttpStatusCode.BadRequest, MantenimientoErrorResponse("MISSING_COMPANY", tr("La empresa es requerida.")))
            // El cuerpo va directo al almacenamiento de archivos, sin conexión a la base abierta; después, una transacción corta registra la fila.
            val files = access.files(companyCode, MantenimientoModule.id)
            val result = withContext(Dispatchers.IO) {
                try {
                    // Conexión corta para el tope de pendientes, antes de escribir nada en el almacenamiento.
                    val full = access.tenantConnectionForCompany(companyCode)?.use { MantenimientoRepository.pendingUploadsFull(it, permissions.userId) }
                        ?: return@withContext null
                    if (full) return@withContext Result.failure<UiFileRef>(MantenimientoValidationException(MantenimientoRepository.tooManyPendingMessage()))
                    val upload = call.receiveChannel().toInputStream().use { body -> MantenimientoRepository.storeUpload(files, name, contentType, body) }
                    // Desde aquí el archivo existe sin fila: cualquier fallo (incluso al abrir la conexión) lo borra.
                    val tenant = try {
                        access.tenantConnectionForCompany(companyCode)
                    } catch (failure: Throwable) {
                        MantenimientoRepository.discardStored(files, upload.stored)
                        throw failure
                    }
                    if (tenant == null) {
                        MantenimientoRepository.discardStored(files, upload.stored)
                        null
                    } else {
                        // registerUpload borra el archivo si el tope o el INSERT fallan.
                        tenant.use { Result.success(MantenimientoRepository.registerUpload(it, files, upload, path, permissions.userId)) }
                    }
                } catch (stop: CancellationException) {
                    throw stop
                } catch (failure: Exception) {
                    Result.failure(failure)
                }
            } ?: return@post call.respond(HttpStatusCode.Forbidden, MantenimientoErrorResponse("TENANT_UNAVAILABLE", tr("No se pudo abrir la empresa.")))
            result.fold(
                onSuccess = { ref: UiFileRef -> call.respondText(UiJson.encodeToString(UiFileRef.serializer(), ref), ContentType.Application.Json) },
                onFailure = { failure ->
                    if (failure is MantenimientoValidationException) {
                        call.respond(HttpStatusCode.BadRequest, MantenimientoErrorResponse("invalid_file", failure.message.orEmpty()))
                    } else {
                        log.warn("No se pudo guardar un archivo de mantenimiento ({}).", failure.javaClass.simpleName)
                        call.respond(HttpStatusCode.InternalServerError, MantenimientoErrorResponse("DATABASE_ERROR", tr("No se pudo guardar el archivo.")))
                    }
                },
            )
        }

        route.get("/files/{id}") {
            val permissions = call.requireModuleAccess(access, MantenimientoModule.id) ?: return@get
            if (!permissions.has(MantenimientoModule.View)) {
                return@get call.respond(HttpStatusCode.Forbidden, MantenimientoErrorResponse("missing_permission", tr("No tienes permiso para esta acción.")))
            }
            val id = call.parameters["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                ?: return@get call.respond(HttpStatusCode.NotFound)
            val companyCode = access.companyCode(call)
                ?: return@get call.respond(HttpStatusCode.BadRequest, MantenimientoErrorResponse("MISSING_COMPANY", tr("La empresa es requerida.")))
            val scope = MantenimientoScope.from(permissions)
            val file = withContext(Dispatchers.IO) {
                access.tenantConnectionForCompany(companyCode)?.use { tenant ->
                    MantenimientoRepository.file(tenant, access.files(companyCode, MantenimientoModule.id), scope, id)
                }
            } ?: return@get call.respond(HttpStatusCode.NotFound, MantenimientoErrorResponse("not_found", tr("Ese archivo ya no está disponible.")))
            val content = withContext(Dispatchers.IO) { file.open() }
                ?: return@get call.respond(HttpStatusCode.NotFound, MantenimientoErrorResponse("missing_content", tr("El archivo ya no está en el almacenamiento del servidor.")))
            // Los encabezados solo salen cuando el contenido de verdad se envía.
            call.response.header(
                HttpHeaders.ContentDisposition,
                ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, file.attachment.name).toString(),
            )
            call.response.header("X-Content-Type-Options", "nosniff")
            call.respondOutputStream(contentType(file.attachment.contentType)) { content.use { it.copyTo(this) } }
        }
    }
}
