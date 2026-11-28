package com.ideasdeveloper.bms.server.company.api

import com.ideasdeveloper.bms.server.i18n.tr
import com.ideasdeveloper.bms.server.modules.install.serverModuleRoutes
import com.ideasdeveloper.bms.server.platform.serverInfoRoutes
import com.ideasdeveloper.bms.server.company.application.CompanyProvisioningException
import com.ideasdeveloper.bms.server.company.application.CoreUpdateOutcome
import com.ideasdeveloper.bms.server.company.application.CoreUpdateService
import com.ideasdeveloper.bms.server.company.application.CoreUpdateRequestBody
import com.ideasdeveloper.bms.server.company.application.CompanyProvisioningService
import com.ideasdeveloper.bms.server.company.application.CompanyMailRejectedException
import com.ideasdeveloper.bms.server.company.application.CompanyMailStorageException
import com.ideasdeveloper.bms.server.company.application.CompanySelfException
import com.ideasdeveloper.bms.server.company.application.BackupImportLimits
import io.ktor.utils.io.jvm.javaio.toInputStream
import com.ideasdeveloper.bms.server.company.application.CompanySelfService
import com.ideasdeveloper.bms.server.admins.ServerActor
import com.ideasdeveloper.bms.server.admins.ServerActorResolver
import com.ideasdeveloper.bms.server.admins.ServerPermission
import com.ideasdeveloper.bms.server.admins.ServerPermissionException
import com.ideasdeveloper.bms.server.admins.serverActor
import com.ideasdeveloper.bms.server.auth.application.EmailChangeRule
import com.ideasdeveloper.bms.server.auth.api.twoFactorResetAnswer
import com.ideasdeveloper.bms.server.app.RecentServerErrors
import com.ideasdeveloper.bms.server.modules.CompanyMailInput
import com.ideasdeveloper.bms.server.app.ServerLogsResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.ContentTransformationException
import io.ktor.server.request.receiveMultipart
import io.ktor.server.request.receive
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondFile
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.SQLException

fun Route.companyRoutes(
    authorizer: ServerActorResolver,
    provisioning: CompanyProvisioningService?,
    selfService: CompanySelfService?,
    coreUpdates: CoreUpdateService? = null,
    // Adds a company a scoped server administrator created (or duplicated) to their scope.
    addToScope: (userId: java.util.UUID, companyCode: String) -> Unit = { _, _ -> },
) {
    companySelfRoutes(selfService)

    companyListRoute(authorizer, provisioning)

    serverInfoRoutes(authorizer, provisioning)

    serverModuleRoutes(authorizer, provisioning)

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
