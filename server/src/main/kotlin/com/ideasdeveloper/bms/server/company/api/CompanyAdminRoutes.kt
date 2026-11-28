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
import com.ideasdeveloper.bms.server.modules.install.serverModuleRoutes
import com.ideasdeveloper.bms.server.platform.serverInfoRoutes

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

internal fun Route.companyAdminRoutes(authorizer: ServerActorResolver, provisioning: CompanyProvisioningService?, addToScope: (userId: java.util.UUID, companyCode: String) -> Unit) {
    post("/companies") {
        call.response.headers.append(HttpHeaders.CacheControl, "no-store")

        val actor = call.serverActor(authorizer, tr("Debes iniciar sesion como server_owner para crear empresas.")) ?: return@post
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
            return@post
        }

        val request = try {
            call.receive<CreateCompanyRequest>()
        } catch (_: BadRequestException) {
            call.respond(HttpStatusCode.BadRequest, CompanyErrorResponse("INVALID_REQUEST", tr("Debes enviar un JSON valido.")))
            return@post
        } catch (_: ContentTransformationException) {
            call.respond(HttpStatusCode.BadRequest, CompanyErrorResponse("INVALID_REQUEST", tr("Debes enviar un JSON valido.")))
            return@post
        }

        val response = try {
            withContext(Dispatchers.IO) {
                service.createCompany(request, serverOwnerRole, actor.audit).also { created ->
                    // A company a scoped administrator creates joins their scope.
                    if (actor.companyScope != null) addToScope(actor.userId, created.code)
                }
            }
        } catch (exception: CompanyProvisioningException) {
            call.respond(
                HttpStatusCode.Conflict,
                CompanyErrorResponse("COMPANY_NOT_CREATED", exception.message ?: tr("No se pudo crear la empresa.")),
            )
            return@post
        } catch (exception: SQLException) {
            call.respond(
                HttpStatusCode.ServiceUnavailable,
                CompanyErrorResponse("DATABASE_UNAVAILABLE", exception.toProvisioningMessage()),
            )
            return@post
        }

        call.respond(HttpStatusCode.Created, response)
    }


    put("/companies/{code}") {
        call.response.headers.append(HttpHeaders.CacheControl, "no-store")
        val actor = call.serverActor(authorizer, tr("Debes iniciar sesion como server_owner para administrar empresas.")) ?: return@put
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
            call.receive<UpdateCompanyRequest>()
        } catch (_: BadRequestException) {
            call.respond(HttpStatusCode.BadRequest, CompanyErrorResponse("INVALID_REQUEST", tr("Debes enviar un JSON valido.")))
            return@put
        } catch (_: ContentTransformationException) {
            call.respond(HttpStatusCode.BadRequest, CompanyErrorResponse("INVALID_REQUEST", tr("Debes enviar un JSON valido.")))
            return@put
        }

        val code = call.parameters["code"].orEmpty()
        val response = try {
            withContext(Dispatchers.IO) {
                service.updateCompany(code, request, serverOwnerRole, actor.audit)
            }
        } catch (exception: CompanyProvisioningException) {
            call.respond(
                HttpStatusCode.Conflict,
                CompanyErrorResponse("COMPANY_NOT_UPDATED", exception.message ?: tr("No se pudo actualizar la empresa.")),
            )
            return@put
        } catch (exception: SQLException) {
            call.respond(
                HttpStatusCode.ServiceUnavailable,
                CompanyErrorResponse("DATABASE_UNAVAILABLE", exception.toCompanyMessage()),
            )
            return@put
        }

        call.respond(HttpStatusCode.OK, response)
    }

    put("/companies/{code}/status") {
        call.response.headers.append(HttpHeaders.CacheControl, "no-store")
        val actor = call.serverActor(authorizer, tr("Debes iniciar sesion como server_owner para administrar empresas.")) ?: return@put
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
            call.receive<UpdateCompanyStatusRequest>()
        } catch (_: BadRequestException) {
            call.respond(HttpStatusCode.BadRequest, CompanyErrorResponse("INVALID_REQUEST", tr("Debes enviar un JSON valido.")))
            return@put
        } catch (_: ContentTransformationException) {
            call.respond(HttpStatusCode.BadRequest, CompanyErrorResponse("INVALID_REQUEST", tr("Debes enviar un JSON valido.")))
            return@put
        }

        val code = call.parameters["code"].orEmpty()
        val response = try {
            withContext(Dispatchers.IO) {
                service.setCompanyActive(code, request.active, serverOwnerRole, actor.audit)
            }
        } catch (exception: CompanyProvisioningException) {
            call.respond(
                HttpStatusCode.Conflict,
                CompanyErrorResponse("COMPANY_NOT_UPDATED", exception.message ?: tr("No se pudo actualizar la empresa.")),
            )
            return@put
        } catch (exception: SQLException) {
            call.respond(
                HttpStatusCode.ServiceUnavailable,
                CompanyErrorResponse("DATABASE_UNAVAILABLE", exception.toCompanyMessage()),
            )
            return@put
        }

        call.respond(HttpStatusCode.OK, response)
    }

    delete("/companies/{code}") {
        call.response.headers.append(HttpHeaders.CacheControl, "no-store")
        val actor = call.serverActor(authorizer, tr("Debes iniciar sesion como server_owner para eliminar empresas.")) ?: return@delete
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
            return@delete
        }

        val code = call.parameters["code"].orEmpty()
        try {
            withContext(Dispatchers.IO) {
                service.deleteInactiveCompany(code, serverOwnerRole, actor.audit)
            }
        } catch (exception: CompanyProvisioningException) {
            call.respond(
                HttpStatusCode.Conflict,
                CompanyErrorResponse("COMPANY_NOT_DELETED", exception.message ?: tr("No se pudo eliminar la empresa.")),
            )
            return@delete
        } catch (exception: SQLException) {
            call.respond(
                HttpStatusCode.ServiceUnavailable,
                CompanyErrorResponse("DATABASE_UNAVAILABLE", exception.toCompanyMessage()),
            )
            return@delete
        }

        call.respond(HttpStatusCode.NoContent)
    }

    put("/companies/{code}/modules/{moduleId}") {
        call.response.headers.append(HttpHeaders.CacheControl, "no-store")
        // Regla core: solo server_owner decide la disponibilidad de modulos por empresa.
        // business_owner administra usuarios/permisos solo dentro de su propia base tenant,
        // pero no habilita modulos.
        val actor = call.serverActor(authorizer, tr("Debes iniciar sesion como server_owner para administrar modulos.")) ?: return@put
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
            call.receive<UpdateCompanyModuleRequest>()
        } catch (_: BadRequestException) {
            call.respond(HttpStatusCode.BadRequest, CompanyErrorResponse("INVALID_REQUEST", tr("Debes enviar un JSON valido.")))
            return@put
        } catch (_: ContentTransformationException) {
            call.respond(HttpStatusCode.BadRequest, CompanyErrorResponse("INVALID_REQUEST", tr("Debes enviar un JSON valido.")))
            return@put
        }

        val code = call.parameters["code"].orEmpty()
        val moduleId = call.parameters["moduleId"].orEmpty()
        val response = try {
            withContext(Dispatchers.IO) {
                service.setModuleEnabled(code, moduleId, request.enabled, serverOwnerRole, actor.audit)
            }
        } catch (exception: CompanyProvisioningException) {
            call.respond(
                HttpStatusCode.Conflict,
                CompanyErrorResponse("MODULE_NOT_UPDATED", exception.message ?: tr("No se pudo actualizar el modulo.")),
            )
            return@put
        } catch (exception: SQLException) {
            call.respond(
                HttpStatusCode.ServiceUnavailable,
                CompanyErrorResponse("DATABASE_UNAVAILABLE", exception.toModuleMessage()),
            )
            return@put
        }

        call.respond(HttpStatusCode.OK, response)
    }

    // Solo desde la app: las contrasenas nunca pasan por el MCP de agentes.
    // Solo el dueno del servidor duplica: la copia mantiene el business owner o recibe uno nuevo (newOwner).
    post("/companies/{code}/duplicate") {
        call.response.headers.append(HttpHeaders.CacheControl, "no-store")
        val actor = call.serverActor(authorizer, tr("Debes iniciar sesion como server_owner para duplicar empresas.")) ?: return@post
        val serverOwnerRole = actor.executorRole
        val service = provisioning
            ?: return@post call.respond(HttpStatusCode.ServiceUnavailable, CompanyErrorResponse("PROVISIONING_NOT_CONFIGURED", tr("El servidor no tiene configuracion de provisioning disponible.")))
        val request = runCatching { call.receive<DuplicateCompanyRequest>() }.getOrNull()
            ?: return@post call.respond(HttpStatusCode.BadRequest, CompanyErrorResponse("INVALID_REQUEST", tr("Indica el codigo de la nueva empresa.")))
        val source = call.parameters["code"].orEmpty()
        try {
            val created = withContext(Dispatchers.IO) {
                service.duplicateCompany(source, request.code, serverOwnerRole, actor.audit, request.newOwner).also { copy ->
                    if (actor.companyScope != null) addToScope(actor.userId, copy.code)
                }
            }
            call.respond(HttpStatusCode.Created, created)
        } catch (exception: CompanyProvisioningException) {
            call.respond(HttpStatusCode.Conflict, CompanyErrorResponse("COMPANY_NOT_DUPLICATED", exception.message ?: tr("No se pudo duplicar la empresa.")))
        } catch (exception: SQLException) {
            call.respond(HttpStatusCode.ServiceUnavailable, CompanyErrorResponse("DATABASE_UNAVAILABLE", exception.toProvisioningMessage()))
        }
    }

    // El dueno del servidor vincula la empresa al grupo de otra (mismo business owner) o la desvincula.
    put("/companies/{code}/link") {
        call.response.headers.append(HttpHeaders.CacheControl, "no-store")
        val actor = call.serverActor(authorizer, tr("Debes iniciar sesion como server_owner para vincular empresas.")) ?: return@put
        val serverOwnerRole = actor.executorRole
        val service = provisioning
            ?: return@put call.respond(HttpStatusCode.ServiceUnavailable, CompanyErrorResponse("PROVISIONING_NOT_CONFIGURED", tr("El servidor no tiene configuracion de provisioning disponible.")))
        val request = runCatching { call.receive<LinkCompanyRequest>() }.getOrNull()
            ?: return@put call.respond(HttpStatusCode.BadRequest, CompanyErrorResponse("INVALID_REQUEST", tr("Debes enviar un JSON valido.")))
        try {
            val companies = withContext(Dispatchers.IO) { service.linkCompany(call.parameters["code"].orEmpty(), request.targetCode, serverOwnerRole, actor.audit) }
            call.respond(HttpStatusCode.OK, companies)
        } catch (exception: CompanyProvisioningException) {
            call.respond(HttpStatusCode.Conflict, CompanyErrorResponse("COMPANY_NOT_LINKED", exception.message ?: tr("No se pudo vincular la empresa.")))
        } catch (exception: SQLException) {
            call.respond(HttpStatusCode.ServiceUnavailable, CompanyErrorResponse("DATABASE_UNAVAILABLE", exception.toCompanyMessage()))
        }
    }
}
