package com.ideasdeveloper.bms.server.platform.coreupdate

import com.ideasdeveloper.bms.server.admins.ServerActor
import com.ideasdeveloper.bms.server.admins.ServerActorResolver
import com.ideasdeveloper.bms.server.admins.ServerPermission
import com.ideasdeveloper.bms.server.admins.ServerPermissionException
import com.ideasdeveloper.bms.server.admins.serverActor
import com.ideasdeveloper.bms.server.app.RecentServerErrors
import com.ideasdeveloper.bms.server.app.ServerLogsResponse
import com.ideasdeveloper.bms.server.auth.api.twoFactorResetAnswer
import com.ideasdeveloper.bms.server.auth.application.EmailChangeRule
import com.ideasdeveloper.bms.server.company.api.CompanyErrorResponse
import com.ideasdeveloper.bms.server.company.application.BackupImportLimits
import com.ideasdeveloper.bms.server.company.application.CompanyMailRejectedException
import com.ideasdeveloper.bms.server.company.application.CompanyMailStorageException
import com.ideasdeveloper.bms.server.company.application.CompanyProvisioningException
import com.ideasdeveloper.bms.server.company.application.CompanyProvisioningService
import com.ideasdeveloper.bms.server.company.application.CompanySelfException
import com.ideasdeveloper.bms.server.company.application.CompanySelfService
import com.ideasdeveloper.bms.server.company.application.CoreUpdateOutcome
import com.ideasdeveloper.bms.server.company.application.CoreUpdateRequestBody
import com.ideasdeveloper.bms.server.company.application.CoreUpdateService
import com.ideasdeveloper.bms.server.i18n.tr
import com.ideasdeveloper.bms.server.modules.CompanyMailInput
import com.ideasdeveloper.bms.server.modules.install.serverModuleRoutes
import com.ideasdeveloper.bms.server.platform.serverInfoRoutes
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.ContentTransformationException
import io.ktor.server.request.receive
import io.ktor.server.request.receiveMultipart
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondFile
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.utils.io.jvm.javaio.toInputStream
import java.sql.SQLException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Actualizacion del Core pedida desde la app por el server owner: estado y solicitud.
internal fun Route.coreUpdateRoutes(authorizer: ServerActorResolver, coreUpdates: CoreUpdateService?) {
    // Estado de la actualizacion del Core y ultima solicitud hecha desde la app.
    get("/server/core-update") {
        call.response.headers.append(HttpHeaders.CacheControl, "no-store")
        val actor = call.serverActor(authorizer, tr("Debes iniciar sesion como server_owner.")) ?: return@get
        val service = coreUpdates
            ?: return@get call.respond(HttpStatusCode.ServiceUnavailable, CompanyErrorResponse("CORE_UPDATE_NOT_CONFIGURED", tr("La actualizacion del servidor no esta disponible.")))
        call.respond(HttpStatusCode.OK, withContext(Dispatchers.IO) { service.status() })
    }

    // Pide actualizar el Core a la ultima version del catalogo; la ejecuta el ayudante root del servidor.
    // Cuerpo opcional {"targetCommit": "<40 hex>"}: el commit que la app mostro (latestCommit del GET).
    post("/server/core-update") {
        call.response.headers.append(HttpHeaders.CacheControl, "no-store")
        val actor = call.serverActor(authorizer, tr("Debes iniciar sesion como server_owner.")) ?: return@post
        val service = coreUpdates
            ?: return@post call.respond(HttpStatusCode.ServiceUnavailable, CompanyErrorResponse("CORE_UPDATE_NOT_CONFIGURED", tr("La actualizacion del servidor no esta disponible.")))
        val text = runCatching { call.receiveText() }.getOrDefault("")
        val body = if (text.isBlank()) {
            CoreUpdateRequestBody()
        } else {
            runCatching { kotlinx.serialization.json.Json { ignoreUnknownKeys = true }.decodeFromString(CoreUpdateRequestBody.serializer(), text) }.getOrNull()
                ?: return@post call.respond(HttpStatusCode.BadRequest, CompanyErrorResponse("INVALID_REQUEST", tr("Debes enviar un JSON valido.")))
        }
        when (val outcome = withContext(Dispatchers.IO) { service.request(actor.login, body.targetCommit) }) {
            is CoreUpdateOutcome.Accepted -> call.respond(HttpStatusCode.Accepted, outcome.request)
            CoreUpdateOutcome.CatalogUnavailable -> call.respond(HttpStatusCode.ServiceUnavailable, CompanyErrorResponse("VERSION_CATALOG_UNAVAILABLE", tr("No se pudo leer el catalogo de versiones.")))
            CoreUpdateOutcome.UpToDate -> call.respond(HttpStatusCode.Conflict, CompanyErrorResponse("CORE_UP_TO_DATE", tr("El servidor ya tiene la ultima version.")))
            CoreUpdateOutcome.InProgress -> call.respond(HttpStatusCode.Conflict, CompanyErrorResponse("CORE_UPDATE_IN_PROGRESS", tr("Ya hay una actualizacion en curso.")))
            CoreUpdateOutcome.CommitUnavailable -> call.respond(HttpStatusCode.ServiceUnavailable, CompanyErrorResponse("CORE_COMMIT_UNAVAILABLE", tr("No se pudo leer en GitHub el commit de la ultima version.")))
            CoreUpdateOutcome.InvalidCommit -> call.respond(HttpStatusCode.BadRequest, CompanyErrorResponse("CORE_COMMIT_INVALID", tr("Indica el commit (40 hex) que revisaste.")))
            is CoreUpdateOutcome.CommitChanged -> call.respond(HttpStatusCode.Conflict, CompanyErrorResponse("CORE_UPDATE_COMMIT_CHANGED", tr("La version publicada cambio (ahora: commit {0}). Revisala otra vez.", outcome.latestCommit)))
        }
    }
}
