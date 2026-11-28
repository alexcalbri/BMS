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

// Rutas de autoservicio del business_owner sobre su propia empresa: perfil, correo, usuarios y copias de seguridad.
internal fun Route.companySelfRoutes(selfService: CompanySelfService?) {
    // Devuelve el perfil de la empresa asociada a la sesion business_owner.
    get("/companies/me") {
        call.response.headers.append(HttpHeaders.CacheControl, "no-store")
        val context = call.businessOwnerContext()
        if (context == null) {
            call.respond(HttpStatusCode.Forbidden, CompanyErrorResponse("BUSINESS_OWNER_REQUIRED", tr("Debes iniciar sesion como business_owner.")))
            return@get
        }
        val service = selfService
        if (service == null) {
            call.respond(HttpStatusCode.ServiceUnavailable, CompanyErrorResponse("COMPANY_SELF_NOT_CONFIGURED", tr("El servidor no tiene configuracion de empresa disponible.")))
            return@get
        }
        try {
            call.respond(HttpStatusCode.OK, withContext(Dispatchers.IO) { service.profile(context.companyCode, context.token) })
        } catch (exception: CompanySelfException) {
            call.respond(HttpStatusCode.Forbidden, CompanyErrorResponse("COMPANY_PROFILE_UNAVAILABLE", exception.message ?: tr("No se pudo cargar la empresa.")))
        }
    }

    // Actualiza los datos visuales de la empresa del business_owner autenticado.
    put("/companies/me") {
        call.response.headers.append(HttpHeaders.CacheControl, "no-store")
        val context = call.businessOwnerContext()
        if (context == null) {
            call.respond(HttpStatusCode.Forbidden, CompanyErrorResponse("BUSINESS_OWNER_REQUIRED", tr("Debes iniciar sesion como business_owner.")))
            return@put
        }
        val service = selfService
        if (service == null) {
            call.respond(HttpStatusCode.ServiceUnavailable, CompanyErrorResponse("COMPANY_SELF_NOT_CONFIGURED", tr("El servidor no tiene configuracion de empresa disponible.")))
            return@put
        }
        val request = try {
            call.receive<UpdateCompanyProfileRequest>()
        } catch (_: BadRequestException) {
            call.respond(HttpStatusCode.BadRequest, CompanyErrorResponse("INVALID_REQUEST", tr("Debes enviar un JSON valido.")))
            return@put
        } catch (_: ContentTransformationException) {
            call.respond(HttpStatusCode.BadRequest, CompanyErrorResponse("INVALID_REQUEST", tr("Debes enviar un JSON valido.")))
            return@put
        }
        try {
            call.respond(HttpStatusCode.OK, withContext(Dispatchers.IO) { service.updateProfile(context.companyCode, context.token, request) })
        } catch (exception: CompanySelfException) {
            call.respond(HttpStatusCode.Conflict, CompanyErrorResponse("COMPANY_PROFILE_NOT_UPDATED", exception.message ?: tr("No se pudo actualizar la empresa.")))
        }
    }

    // SMTP de la empresa (correo saliente); la contrasena nunca se devuelve, solo passwordSet.
    get("/companies/me/mail") {
        call.response.headers.append(HttpHeaders.CacheControl, "no-store")
        val context = call.businessOwnerContext()
        val service = selfService
        if (context == null || service == null) {
            call.respond(HttpStatusCode.Forbidden, CompanyErrorResponse("BUSINESS_OWNER_REQUIRED", tr("Debes iniciar sesion como business_owner.")))
            return@get
        }
        try {
            call.respond(HttpStatusCode.OK, withContext(Dispatchers.IO) { service.mailSettings(context.companyCode, context.token) })
        } catch (exception: CompanySelfException) {
            call.respond(HttpStatusCode.Forbidden, CompanyErrorResponse("COMPANY_MAIL_UNAVAILABLE", exception.message ?: tr("No se pudo cargar el correo.")))
        }
    }

    // Guarda el SMTP de la empresa; rechaza puertos no permitidos y hosts privados antes de guardar.
    put("/companies/me/mail") {
        call.response.headers.append(HttpHeaders.CacheControl, "no-store")
        val context = call.businessOwnerContext()
        val service = selfService
        if (context == null || service == null) {
            call.respond(HttpStatusCode.Forbidden, CompanyErrorResponse("BUSINESS_OWNER_REQUIRED", tr("Debes iniciar sesion como business_owner.")))
            return@put
        }
        val request = try {
            call.receive<CompanyMailInput>()
        } catch (_: BadRequestException) {
            call.respond(HttpStatusCode.BadRequest, CompanyErrorResponse("INVALID_REQUEST", tr("Debes enviar un JSON valido.")))
            return@put
        } catch (_: ContentTransformationException) {
            call.respond(HttpStatusCode.BadRequest, CompanyErrorResponse("INVALID_REQUEST", tr("Debes enviar un JSON valido.")))
            return@put
        }
        try {
            call.respond(HttpStatusCode.OK, withContext(Dispatchers.IO) { service.updateMailSettings(context.companyCode, context.token, request) })
        } catch (exception: CompanyMailRejectedException) {
            call.respond(HttpStatusCode.BadRequest, CompanyErrorResponse("COMPANY_MAIL_NOT_SAVED", exception.message ?: tr("Datos de correo no validos.")))
        } catch (exception: CompanyMailStorageException) {
            call.respond(HttpStatusCode.InternalServerError, CompanyErrorResponse("COMPANY_MAIL_STORAGE_FAILED", tr("No se pudo guardar el correo de la empresa.")))
        } catch (exception: CompanySelfException) {
            call.respond(HttpStatusCode.Forbidden, CompanyErrorResponse("COMPANY_MAIL_UNAVAILABLE", exception.message ?: tr("No tienes permiso para cambiar el correo.")))
        }
    }

    // Lista los ZIPs de backup almacenados para la empresa actual.
    get("/companies/me/backups") {
        call.response.headers.append(HttpHeaders.CacheControl, "no-store")
        val context = call.businessOwnerContext()
        val service = selfService
        if (context == null || service == null) {
            call.respond(HttpStatusCode.Forbidden, CompanyErrorResponse("BUSINESS_OWNER_REQUIRED", tr("Debes iniciar sesion como business_owner.")))
            return@get
        }
        call.respond(HttpStatusCode.OK, CompanyBackupListResponse(withContext(Dispatchers.IO) { service.listBackups(context.companyCode, context.token) }))
    }

    get("/companies/me/users") {
        call.response.headers.append(HttpHeaders.CacheControl, "no-store")
        val context = call.businessOwnerContext()
        val service = selfService
        if (context == null || service == null) {
            call.respond(HttpStatusCode.Forbidden, CompanyErrorResponse("BUSINESS_OWNER_REQUIRED", tr("Debes iniciar sesion como business_owner.")))
            return@get
        }
        try {
            call.respond(HttpStatusCode.OK, withContext(Dispatchers.IO) { service.users(context.companyCode, context.token) })
        } catch (exception: CompanySelfException) {
            call.respond(HttpStatusCode.Forbidden, CompanyErrorResponse("COMPANY_USERS_UNAVAILABLE", exception.message ?: tr("No se pudieron cargar usuarios.")))
        }
    }

    // The business owner turns off a user's two-step verification (TWO_FACTOR.md §8).
    post("/companies/me/users/{userId}/two-factor/disable") {
        call.response.headers.append(HttpHeaders.CacheControl, "no-store")
        val context = call.businessOwnerContext()
        val service = selfService
        if (context == null || service == null) {
            call.respond(HttpStatusCode.Forbidden, CompanyErrorResponse("BUSINESS_OWNER_REQUIRED", tr("Debes iniciar sesion como business_owner.")))
            return@post
        }
        val userId = runCatching { java.util.UUID.fromString(call.parameters["userId"]) }.getOrNull()
            ?: return@post call.respond(HttpStatusCode.NotFound, CompanyErrorResponse("USER_NOT_FOUND", tr("Usuario no encontrado.")))
        val request = runCatching { call.receive<com.ideasdeveloper.bms.server.auth.api.TwoFactorPasswordRequest>() }.getOrNull()
            ?: return@post call.respond(HttpStatusCode.BadRequest, CompanyErrorResponse("INVALID_REQUEST", tr("Debes enviar un JSON valido.")))
        call.twoFactorResetAnswer {
            try {
                service.disableUserTwoFactor(context.companyCode, context.token, userId, request.currentPassword)
            } catch (refused: CompanySelfException) {
                throw com.ideasdeveloper.bms.server.auth.twofactor.TwoFactorResetException("BUSINESS_OWNER_REQUIRED", 403, refused.message.orEmpty())
            }
        }
    }

    post("/companies/me/users") {
        call.response.headers.append(HttpHeaders.CacheControl, "no-store")
        val context = call.businessOwnerContext()
        val service = selfService
        if (context == null || service == null) {
            call.respond(HttpStatusCode.Forbidden, CompanyErrorResponse("BUSINESS_OWNER_REQUIRED", tr("Debes iniciar sesion como business_owner.")))
            return@post
        }
        val request = try {
            call.receive<CreateCompanyUserRequest>()
        } catch (_: BadRequestException) {
            call.respond(HttpStatusCode.BadRequest, CompanyErrorResponse("INVALID_REQUEST", tr("Debes enviar un JSON valido.")))
            return@post
        } catch (_: ContentTransformationException) {
            call.respond(HttpStatusCode.BadRequest, CompanyErrorResponse("INVALID_REQUEST", tr("Debes enviar un JSON valido.")))
            return@post
        }
        try {
            call.respond(HttpStatusCode.Created, withContext(Dispatchers.IO) { service.createUser(context.companyCode, context.token, request) })
        } catch (exception: CompanySelfException) {
            call.respond(HttpStatusCode.Conflict, CompanyErrorResponse("COMPANY_USER_NOT_CREATED", exception.message ?: tr("No se pudo crear el usuario.")))
        }
    }

    put("/companies/me/users/{userId}/permissions") {
        call.response.headers.append(HttpHeaders.CacheControl, "no-store")
        val context = call.businessOwnerContext()
        val service = selfService
        if (context == null || service == null) {
            call.respond(HttpStatusCode.Forbidden, CompanyErrorResponse("BUSINESS_OWNER_REQUIRED", tr("Debes iniciar sesion como business_owner.")))
            return@put
        }
        val request = try {
            call.receive<UpdateCompanyUserPermissionsRequest>()
        } catch (_: BadRequestException) {
            call.respond(HttpStatusCode.BadRequest, CompanyErrorResponse("INVALID_REQUEST", tr("Debes enviar un JSON valido.")))
            return@put
        } catch (_: ContentTransformationException) {
            call.respond(HttpStatusCode.BadRequest, CompanyErrorResponse("INVALID_REQUEST", tr("Debes enviar un JSON valido.")))
            return@put
        }
        try {
            call.respond(
                HttpStatusCode.OK,
                withContext(Dispatchers.IO) {
                    service.updateUserPermissions(context.companyCode, context.token, call.parameters["userId"].orEmpty(), request)
                },
            )
        } catch (exception: CompanySelfException) {
            call.respond(HttpStatusCode.Conflict, CompanyErrorResponse("COMPANY_USER_NOT_UPDATED", exception.message ?: tr("No se pudo actualizar el usuario.")))
        }
    }

    // Every field of a user (user, name, email, active, permissions, password), by the owner.
    put("/companies/me/users/{userId}") {
        call.response.headers.append(HttpHeaders.CacheControl, "no-store")
        val context = call.businessOwnerContext()
        val service = selfService
        if (context == null || service == null) {
            call.respond(HttpStatusCode.Forbidden, CompanyErrorResponse("BUSINESS_OWNER_REQUIRED", tr("Debes iniciar sesion como business_owner.")))
            return@put
        }
        val request = runCatching { call.receive<UpdateCompanyUserRequest>() }.getOrNull()
            ?: return@put call.respond(HttpStatusCode.BadRequest, CompanyErrorResponse("INVALID_REQUEST", tr("Debes enviar un JSON valido.")))
        try {
            call.respond(
                HttpStatusCode.OK,
                withContext(Dispatchers.IO) { service.updateUser(context.companyCode, context.token, call.parameters["userId"].orEmpty(), request) },
            )
        } catch (exception: com.ideasdeveloper.bms.server.auth.application.AccountException) {
            // PASSWORD_REQUIRED / INVALID_PASSWORD: mismo contrato que PUT /auth/profile (400).
            val status = if (exception.code == "TOO_MANY_ATTEMPTS") HttpStatusCode.TooManyRequests else HttpStatusCode.BadRequest
            call.respond(status, CompanyErrorResponse(exception.code, exception.message.orEmpty()))
        } catch (exception: CompanySelfException) {
            call.respond(HttpStatusCode.Conflict, CompanyErrorResponse("COMPANY_USER_NOT_UPDATED", exception.message ?: tr("No se pudo actualizar el usuario.")))
        } catch (exception: CompanyProvisioningException) {
            call.respond(HttpStatusCode.Conflict, CompanyErrorResponse("COMPANY_USER_NOT_UPDATED", exception.message ?: tr("No se pudo actualizar el usuario.")))
        } catch (exception: SQLException) {
            call.respond(HttpStatusCode.ServiceUnavailable, CompanyErrorResponse("DATABASE_UNAVAILABLE", exception.toCompanyMessage()))
        }
    }

    // Crea un ZIP de backup no destructivo para la empresa actual.
    post("/companies/me/backups") {
        call.response.headers.append(HttpHeaders.CacheControl, "no-store")
        val context = call.businessOwnerContext()
        val service = selfService
        if (context == null || service == null) {
            call.respond(HttpStatusCode.Forbidden, CompanyErrorResponse("BUSINESS_OWNER_REQUIRED", tr("Debes iniciar sesion como business_owner.")))
            return@post
        }
        call.respond(HttpStatusCode.Created, withContext(Dispatchers.IO) { service.createProfileBackup(context.companyCode, context.token) })
    }

    // Recibe un ZIP y lo guarda en el servidor sin restaurar la base de datos.
    post("/companies/me/backups/import") {
        call.response.headers.append(HttpHeaders.CacheControl, "no-store")
        val context = call.businessOwnerContext()
        val service = selfService
        if (context == null || service == null) {
            call.respond(HttpStatusCode.Forbidden, CompanyErrorResponse("BUSINESS_OWNER_REQUIRED", tr("Debes iniciar sesion como business_owner.")))
            return@post
        }
        // Autoriza de verdad (sesion de business_owner) ANTES de leer el cuerpo multipart.
        try {
            withContext(Dispatchers.IO) { service.listBackups(context.companyCode, context.token) }
        } catch (exception: CompanySelfException) {
            call.respond(HttpStatusCode.Forbidden, CompanyErrorResponse("BUSINESS_OWNER_REQUIRED", tr("Debes iniciar sesion como business_owner.")))
            return@post
        }
        // Streamed to disk with size, entry and expanded-size limits (B4); nothing is held in memory.
        var stored: CompanyBackupResponse? = null
        var refusal: String? = null
        var sawFile = false
        try {
            call.receiveMultipart(formFieldLimit = BackupImportLimits.MaxBytes + 64 * 1024).forEachPart { part ->
                if (part is PartData.FileItem && part.name == "file" && !sawFile) {
                    sawFile = true
                    val name = part.originalFileName.orEmpty()
                    try {
                        stored = withContext(Dispatchers.IO) {
                            part.provider().toInputStream().use { input -> service.storeImportedBackup(context.companyCode, context.token, name, input, call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull()) }
                        }
                    } catch (exception: CompanySelfException) {
                        refusal = exception.message ?: tr("No se pudo importar el backup.")
                    }
                }
                part.dispose()
            }
        } catch (_: java.io.IOException) {
            refusal = refusal ?: tr("No se pudo importar el backup.")
        }
        val result = stored
        when {
            result != null -> call.respond(HttpStatusCode.Created, result)
            refusal != null -> call.respond(HttpStatusCode.Conflict, CompanyErrorResponse("BACKUP_NOT_IMPORTED", refusal!!))
            else -> call.respond(HttpStatusCode.BadRequest, CompanyErrorResponse("INVALID_BACKUP", tr("Debes subir un archivo ZIP.")))
        }
    }

    // Descarga un ZIP previamente generado o importado para la empresa.
    get("/companies/me/backups/{fileName}/download") {
        val context = call.businessOwnerContext()
        val service = selfService
        if (context == null || service == null) {
            call.respond(HttpStatusCode.Forbidden, CompanyErrorResponse("BUSINESS_OWNER_REQUIRED", tr("Debes iniciar sesion como business_owner.")))
            return@get
        }
        try {
            call.respondFile(withContext(Dispatchers.IO) { service.downloadBackup(context.companyCode, context.token, call.parameters["fileName"].orEmpty()).toFile() })
        } catch (exception: CompanySelfException) {
            call.respond(HttpStatusCode.NotFound, CompanyErrorResponse("BACKUP_NOT_FOUND", exception.message ?: tr("Backup no encontrado.")))
        }
    }

    // Elimina un ZIP del almacenamiento de backups de la empresa.
    delete("/companies/me/backups/{fileName}") {
        call.response.headers.append(HttpHeaders.CacheControl, "no-store")
        val context = call.businessOwnerContext()
        val service = selfService
        if (context == null || service == null) {
            call.respond(HttpStatusCode.Forbidden, CompanyErrorResponse("BUSINESS_OWNER_REQUIRED", tr("Debes iniciar sesion como business_owner.")))
            return@delete
        }
        try {
            withContext(Dispatchers.IO) { service.deleteBackup(context.companyCode, context.token, call.parameters["fileName"].orEmpty()) }
            call.respond(HttpStatusCode.NoContent)
        } catch (exception: CompanySelfException) {
            call.respond(HttpStatusCode.NotFound, CompanyErrorResponse("BACKUP_NOT_FOUND", exception.message ?: tr("Backup no encontrado.")))
        }
    }
}
