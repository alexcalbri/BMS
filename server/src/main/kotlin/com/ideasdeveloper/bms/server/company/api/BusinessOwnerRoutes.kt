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
import com.ideasdeveloper.bms.server.platform.coreupdate.CoreUpdateOutcome
import com.ideasdeveloper.bms.server.platform.coreupdate.CoreUpdateRequestBody
import com.ideasdeveloper.bms.server.platform.coreupdate.CoreUpdateService
import com.ideasdeveloper.bms.server.i18n.tr
import com.ideasdeveloper.bms.server.modules.CompanyMailInput
import com.ideasdeveloper.bms.server.modules.install.serverModuleRoutes
import com.ideasdeveloper.bms.server.platform.coreupdate.coreUpdateRoutes
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

// Business owner de una empresa, editado por el server owner (con alcance): lectura, datos y contrasena.
internal fun Route.businessOwnerRoutes(authorizer: ServerActorResolver, provisioning: CompanyProvisioningService?) {
    // The business owner of a company, for the server owner to edit it.
    get("/companies/{code}/business-owner") {
        call.response.headers.append(HttpHeaders.CacheControl, "no-store")
        val actor = call.serverActor(authorizer, tr("Debes iniciar sesion como server_owner.")) ?: return@get
        val service = provisioning
            ?: return@get call.respond(HttpStatusCode.ServiceUnavailable, CompanyErrorResponse("PROVISIONING_NOT_CONFIGURED", tr("El servidor no tiene configuracion de provisioning disponible.")))
        val code = call.parameters["code"].orEmpty()
        try {
            call.respond(
                HttpStatusCode.OK,
                withContext(Dispatchers.IO) {
                    // Same scope rule as editing: the owner's other companies and owner group must be in scope.
                    requireOwnerInScope(actor, service, code)
                    service.businessOwner(code)
                },
            )
        } catch (refusal: ServerPermissionException) {
            call.respond(HttpStatusCode.Forbidden, CompanyErrorResponse(refusal.code, refusal.message.orEmpty()))
        } catch (exception: CompanyProvisioningException) {
            call.respond(HttpStatusCode.NotFound, CompanyErrorResponse("BUSINESS_OWNER_NOT_FOUND", exception.message ?: tr("Business owner no encontrado.")))
        } catch (exception: SQLException) {
            call.respond(HttpStatusCode.ServiceUnavailable, CompanyErrorResponse("DATABASE_UNAVAILABLE", exception.toCompanyMessage()))
        }
    }

    // User, name, email and password of a company's business owner, by the server owner.
    put("/companies/{code}/business-owner") {
        call.response.headers.append(HttpHeaders.CacheControl, "no-store")
        val actor = call.serverActor(authorizer, tr("Debes iniciar sesion como server_owner.")) ?: return@put
        val serverOwnerRole = actor.executorRole
        val service = provisioning
            ?: return@put call.respond(HttpStatusCode.ServiceUnavailable, CompanyErrorResponse("PROVISIONING_NOT_CONFIGURED", tr("El servidor no tiene configuracion de provisioning disponible.")))
        val request = runCatching { call.receive<UpdateBusinessOwnerRequest>() }.getOrNull()
            ?: return@put call.respond(HttpStatusCode.BadRequest, CompanyErrorResponse("INVALID_REQUEST", tr("Debes enviar un JSON valido.")))
        val code = call.parameters["code"].orEmpty()
        try {
            call.respond(
                HttpStatusCode.OK,
                withContext(Dispatchers.IO) {
                    // A new password, a new user (which needs one) or a new email (password recovery by
                    // email code goes to it) is the high-risk owner_password permission: each one is a way
                    // to take over the company. With only companies.edit the display name may change.
                    val current = service.businessOwner(code)
                    if (request.password.isNotEmpty() || request.username.trim() != current.username ||
                        EmailChangeRule.isChange(current.email, request.email)
                    ) {
                        actor.requireCompany(ServerPermission.CompaniesOwnerPassword, code)
                    }
                    requireOwnerInScope(actor, service, code)
                    service.updateBusinessOwner(code, request, serverOwnerRole, actor.audit)
                },
            )
        } catch (refusal: ServerPermissionException) {
            call.respond(HttpStatusCode.Forbidden, CompanyErrorResponse(refusal.code, refusal.message.orEmpty()))
        } catch (exception: CompanyProvisioningException) {
            call.respond(HttpStatusCode.Conflict, CompanyErrorResponse("BUSINESS_OWNER_NOT_UPDATED", exception.message ?: tr("No se pudo actualizar el business owner.")))
        } catch (exception: SQLException) {
            call.respond(HttpStatusCode.ServiceUnavailable, CompanyErrorResponse("DATABASE_UNAVAILABLE", exception.toCompanyMessage()))
        }
    }

    put("/companies/{code}/owner-password") {
        call.response.headers.append(HttpHeaders.CacheControl, "no-store")
        val actor = call.serverActor(authorizer, tr("Debes iniciar sesion como server_owner para cambiar la contrasena del business owner.")) ?: return@put
        val serverOwnerRole = actor.executorRole

        val service = provisioning
        if (service == null) {
            call.respond(
                HttpStatusCode.ServiceUnavailable,
                CompanyErrorResponse(
                    code = "PROVISIONING_NOT_CONFIGURED",
                    message = tr("El servidor no tiene configuracion de provisioning disponible."),
                ),
            )
            return@put
        }

        val request = try {
            call.receive<ResetBusinessOwnerPasswordRequest>()
        } catch (_: BadRequestException) {
            call.respond(HttpStatusCode.BadRequest, CompanyErrorResponse("INVALID_REQUEST", tr("Debes enviar un JSON valido.")))
            return@put
        } catch (_: ContentTransformationException) {
            call.respond(HttpStatusCode.BadRequest, CompanyErrorResponse("INVALID_REQUEST", tr("Debes enviar un JSON valido.")))
            return@put
        }

        val code = call.parameters["code"].orEmpty()
        try {
            withContext(Dispatchers.IO) {
                requireOwnerInScope(actor, service, code)
                service.resetBusinessOwnerPassword(code, request.password, serverOwnerRole, actor.audit)
            }
        } catch (refusal: ServerPermissionException) {
            call.respond(HttpStatusCode.Forbidden, CompanyErrorResponse(refusal.code, refusal.message.orEmpty()))
            return@put
        } catch (exception: CompanyProvisioningException) {
            call.respond(
                HttpStatusCode.Conflict,
                CompanyErrorResponse("PASSWORD_NOT_UPDATED", exception.message ?: tr("No se pudo cambiar la contrasena.")),
            )
            return@put
        } catch (exception: SQLException) {
            call.respond(
                HttpStatusCode.ServiceUnavailable,
                CompanyErrorResponse("DATABASE_UNAVAILABLE", exception.toCompanyMessage()),
            )
            return@put
        }

        call.respond(HttpStatusCode.NoContent)
    }
}
