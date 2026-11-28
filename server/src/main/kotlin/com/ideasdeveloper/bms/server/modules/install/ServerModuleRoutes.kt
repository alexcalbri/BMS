package com.ideasdeveloper.bms.server.modules.install

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
import com.ideasdeveloper.bms.server.company.api.ServerWorkspaceResponse
import com.ideasdeveloper.bms.server.company.api.UpdateCompanyModuleRequest
import com.ideasdeveloper.bms.server.company.api.UpdateServerModuleCatalogRequest
import com.ideasdeveloper.bms.server.company.api.modulePackageStatus
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

// Modulos del servidor: catalogo, instalacion, autorizacion de modulos sin firma, espacio del servidor y su uso.
internal fun Route.serverModuleRoutes(authorizer: ServerActorResolver, provisioning: CompanyProvisioningService?) {
    get("/server/modules") {
        call.response.headers.append(HttpHeaders.CacheControl, "no-store")
        val actor = call.serverActor(authorizer, tr("Debes iniciar sesion como server_owner para administrar modulos del servidor.")) ?: return@get

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

        call.respond(HttpStatusCode.OK, withContext(Dispatchers.IO) { service.listServerModules() })
    }

    get("/server/modules/catalog") {
        call.response.headers.append(HttpHeaders.CacheControl, "no-store")
        val actor = call.serverActor(authorizer, tr("Debes iniciar sesion como server_owner para consultar el catalogo.")) ?: return@get
        val service = provisioning
        if (service == null) {
            call.respond(HttpStatusCode.ServiceUnavailable, CompanyErrorResponse("PROVISIONING_NOT_CONFIGURED", tr("El servidor no tiene configuracion de provisioning disponible.")))
            return@get
        }
        call.respond(HttpStatusCode.OK, withContext(Dispatchers.IO) { service.serverModuleCatalog() })
    }

    put("/server/modules/catalog") {
        call.response.headers.append(HttpHeaders.CacheControl, "no-store")
        val actor = call.serverActor(authorizer, tr("Debes iniciar sesion como server_owner para configurar el catalogo.")) ?: return@put
        val service = provisioning
        if (service == null) {
            call.respond(HttpStatusCode.ServiceUnavailable, CompanyErrorResponse("PROVISIONING_NOT_CONFIGURED", tr("El servidor no tiene configuracion de provisioning disponible.")))
            return@put
        }
        val request = try {
            call.receive<UpdateServerModuleCatalogRequest>()
        } catch (_: BadRequestException) {
            call.respond(HttpStatusCode.BadRequest, CompanyErrorResponse("INVALID_REQUEST", tr("Debes enviar un JSON valido.")))
            return@put
        } catch (_: ContentTransformationException) {
            call.respond(HttpStatusCode.BadRequest, CompanyErrorResponse("INVALID_REQUEST", tr("Debes enviar un JSON valido.")))
            return@put
        }
        try {
            call.respond(HttpStatusCode.OK, withContext(Dispatchers.IO) { service.updateServerModuleCatalog(request.catalogUrl, actor.audit) })
        } catch (exception: CompanyProvisioningException) {
            call.respond(HttpStatusCode.Conflict, CompanyErrorResponse("SERVER_MODULE_CATALOG_NOT_UPDATED", exception.message ?: tr("No se pudo actualizar el catalogo.")))
        }
    }

    delete("/server/modules/{moduleId}") {
        call.response.headers.append(HttpHeaders.CacheControl, "no-store")
        val actor = call.serverActor(authorizer, tr("Debes iniciar sesion como server_owner para eliminar modulos del servidor.")) ?: return@delete

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

        val moduleId = call.parameters["moduleId"].orEmpty()
        try {
            withContext(Dispatchers.IO) {
                service.deleteServerModule(moduleId, actor.audit)
            }
        } catch (exception: CompanyProvisioningException) {
            call.respond(
                HttpStatusCode.Conflict,
                CompanyErrorResponse("SERVER_MODULE_NOT_DELETED", exception.message ?: tr("No se pudo eliminar el modulo del servidor.")),
            )
            return@delete
        }

        call.respond(HttpStatusCode.NoContent)
    }

    post("/server/modules/{moduleId}/install") {
        call.response.headers.append(HttpHeaders.CacheControl, "no-store")
        val actor = call.serverActor(authorizer, tr("Debes iniciar sesion como server_owner para instalar modulos del servidor.")) ?: return@post

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

        val moduleId = call.parameters["moduleId"].orEmpty()
        val response = try {
            withContext(Dispatchers.IO) {
                service.installServerModule(moduleId, actor.audit, call.request.headers["X-App-Version"])
            }
        } catch (exception: CompanyProvisioningException) {
            call.respond(
                HttpStatusCode.Conflict,
                CompanyErrorResponse(exception.code ?: "SERVER_MODULE_NOT_INSTALLED", exception.message ?: tr("No se pudo instalar el modulo del servidor.")),
            )
            return@post
        }

        call.respond(HttpStatusCode.OK, response)
    }

    // Descarga el modulo del catalogo y devuelve su commit y hash para que el dueno los revise (igual que las extensiones).
    get("/server/modules/{moduleId}/inspect") {
        call.response.headers.append(HttpHeaders.CacheControl, "no-store")
        val actor = call.serverActor(authorizer, tr("Debes iniciar sesion como server_owner para revisar paquetes de modulos.")) ?: return@get
        val service = provisioning ?: run {
            call.respond(HttpStatusCode.ServiceUnavailable, CompanyErrorResponse("PROVISIONING_NOT_CONFIGURED", tr("El servidor no tiene configuracion de provisioning disponible.")))
            return@get
        }
        try {
            call.respond(HttpStatusCode.OK, withContext(Dispatchers.IO) { service.inspectServerModule(call.parameters["moduleId"].orEmpty()) })
        } catch (exception: CompanyProvisioningException) {
            call.respond(modulePackageStatus(exception), CompanyErrorResponse(exception.code ?: "INVALID_REQUEST", exception.message ?: tr("No se pudo revisar el modulo.")))
        }
    }

    // El dueno autoriza el hash y commit de un modulo sin firma, con confirmacion escrita (igual que las extensiones).
    post("/server/modules/{moduleId}/authorize-unsigned") {
        call.response.headers.append(HttpHeaders.CacheControl, "no-store")
        val actor = call.serverActor(authorizer, tr("Debes iniciar sesion como server_owner para autorizar paquetes sin firma.")) ?: return@post
        val service = provisioning ?: run {
            call.respond(HttpStatusCode.ServiceUnavailable, CompanyErrorResponse("PROVISIONING_NOT_CONFIGURED", tr("El servidor no tiene configuracion de provisioning disponible.")))
            return@post
        }
        val request = try {
            call.receive<com.ideasdeveloper.bms.server.modules.AuthorizeUnsignedModuleRequest>()
        } catch (_: BadRequestException) {
            call.respond(HttpStatusCode.BadRequest, CompanyErrorResponse("INVALID_REQUEST", tr("Debes enviar un JSON valido.")))
            return@post
        }
        try {
            call.respond(
                HttpStatusCode.OK,
                withContext(Dispatchers.IO) {
                    service.authorizeUnsignedServerModule(
                        call.parameters["moduleId"].orEmpty(), actor.audit, request.confirmationText,
                        request.expectedPackageSha256, request.expectedCommit,
                    )
                },
            )
        } catch (exception: CompanyProvisioningException) {
            call.respond(modulePackageStatus(exception), CompanyErrorResponse(exception.code ?: "INVALID_REQUEST", exception.message ?: tr("No se pudo autorizar el modulo.")))
        }
    }

    // Creates the server company (server workspace) when it does not exist yet and returns its code.
    // Single-company installs use it as their company: the server owner is its business owner.
    post("/server/workspace") {
        call.response.headers.append(HttpHeaders.CacheControl, "no-store")
        val actor = call.serverActor(authorizer, tr("Debes iniciar sesion como server_owner para crear la empresa del servidor.")) ?: return@post
        val serverOwnerRole = actor.executorRole
        val service = provisioning
        if (service == null) {
            call.respond(
                HttpStatusCode.ServiceUnavailable,
                CompanyErrorResponse("PROVISIONING_NOT_CONFIGURED", tr("El servidor no tiene configuracion de provisioning disponible.")),
            )
            return@post
        }
        val code = try {
            withContext(Dispatchers.IO) { service.ensureServerWorkspace(serverOwnerRole, actor.audit) }
        } catch (exception: CompanyProvisioningException) {
            call.respond(
                HttpStatusCode.Conflict,
                CompanyErrorResponse("SERVER_WORKSPACE_NOT_CREATED", exception.message ?: tr("No se pudo crear la empresa del servidor.")),
            )
            return@post
        }
        call.respond(HttpStatusCode.OK, ServerWorkspaceResponse(code))
    }

    // Uses a module in the server workspace (created on first use), without a company.
    put("/server/modules/{moduleId}/server-usage") {
        call.response.headers.append(HttpHeaders.CacheControl, "no-store")
        val actor = call.serverActor(authorizer, tr("Debes iniciar sesion como server_owner para usar modulos en el servidor.")) ?: return@put
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
        val request = call.receive<UpdateCompanyModuleRequest>()
        val moduleId = call.parameters["moduleId"].orEmpty()
        val response = try {
            withContext(Dispatchers.IO) {
                service.setServerModuleUsage(moduleId, request.enabled, serverOwnerRole, actor.audit)
            }
        } catch (exception: CompanyProvisioningException) {
            call.respond(
                HttpStatusCode.Conflict,
                CompanyErrorResponse("SERVER_MODULE_USAGE", exception.message ?: tr("No se pudo cambiar el uso del modulo en el servidor.")),
            )
            return@put
        }
        call.respond(HttpStatusCode.OK, response)
    }
}
