package com.ideasdeveloper.bms.server.platform

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
import com.ideasdeveloper.bms.server.company.api.ServerNameRequest
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

// Informacion y nombre del servidor para los server owners: errores recientes (/server/logs) y nombre (/server/name).
internal fun Route.serverInfoRoutes(authorizer: ServerActorResolver, provisioning: CompanyProvisioningService?) {
    get("/server/logs") {
        call.response.headers.append(HttpHeaders.CacheControl, "no-store")
        val actor = call.serverActor(authorizer, tr("Debes iniciar sesion como server_owner para consultar los errores.")) ?: return@get
        call.respond(HttpStatusCode.OK, ServerLogsResponse(RecentServerErrors.snapshot().asReversed()))
    }

    // Nombre del servidor que ven los server owners en el panel y el selector de empresas.
    put("/server/name") {
        call.response.headers.append(HttpHeaders.CacheControl, "no-store")
        val actor = call.serverActor(authorizer, tr("Debes iniciar sesion como server_owner.")) ?: return@put
        val serverOwnerRole = actor.executorRole
        val service = provisioning
            ?: return@put call.respond(HttpStatusCode.ServiceUnavailable, CompanyErrorResponse("PROVISIONING_NOT_CONFIGURED", tr("El servidor no tiene configuracion de provisioning disponible.")))
        val request = runCatching { call.receive<ServerNameRequest>() }.getOrNull()
            ?: return@put call.respond(HttpStatusCode.BadRequest, CompanyErrorResponse("INVALID_REQUEST", tr("Debes enviar un JSON valido.")))
        try {
            call.respond(HttpStatusCode.OK, withContext(Dispatchers.IO) { service.updateServerName(request.name, serverOwnerRole, actor.audit) })
        } catch (exception: CompanyProvisioningException) {
            call.respond(HttpStatusCode.BadRequest, CompanyErrorResponse("SERVER_NAME_INVALID", exception.message ?: tr("Nombre invalido.")))
        }
    }
}
