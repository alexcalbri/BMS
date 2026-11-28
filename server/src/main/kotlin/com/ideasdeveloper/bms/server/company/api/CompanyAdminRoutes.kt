package com.ideasdeveloper.bms.server.company.api

import com.ideasdeveloper.bms.server.admins.ServerActor
import com.ideasdeveloper.bms.server.admins.ServerActorResolver
import com.ideasdeveloper.bms.server.admins.ServerPermission
import com.ideasdeveloper.bms.server.admins.ServerPermissionException
import com.ideasdeveloper.bms.server.admins.serverActor
import com.ideasdeveloper.bms.server.app.RecentServerErrors
import com.ideasdeveloper.bms.server.app.ServerLogsResponse
import com.ideasdeveloper.bms.server.auth.api.twoFactorResetAnswer
import com.ideasdeveloper.bms.server.auth.application.EmailChangeRule
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

// Rutas del server_owner (y administradores con alcance) sobre las empresas del servidor.
internal fun Route.companyListRoute(authorizer: ServerActorResolver, provisioning: CompanyProvisioningService?) {
    get("/companies") {
        call.response.headers.append(HttpHeaders.CacheControl, "no-store")
        val actor = call.serverActor(authorizer, tr("Debes iniciar sesion como server_owner para consultar empresas.")) ?: return@get

        val service = provisioning
        if (service == null) {
            call.respond(
                HttpStatusCode.ServiceUnavailable,
                CompanyErrorResponse(
                    code = "PROVISIONING_NOT_CONFIGURED",
                    message = tr("El servidor no tiene configuracion de provisioning disponible."),
                ),
            )
            return@get
        }

        call.respond(HttpStatusCode.OK, withContext(Dispatchers.IO) { service.listCompanies(actor.companyScope) })
    }
}
