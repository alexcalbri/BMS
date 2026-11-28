package com.ideasdeveloper.bms.server.company.api

import com.ideasdeveloper.bms.server.admins.ServerActor
import com.ideasdeveloper.bms.server.admins.ServerPermission
import com.ideasdeveloper.bms.server.admins.ServerPermissionException
import com.ideasdeveloper.bms.server.company.application.CompanyProvisioningException
import com.ideasdeveloper.bms.server.company.application.CompanyProvisioningService
import com.ideasdeveloper.bms.server.i18n.tr
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import java.sql.SQLException

// Contexto minimo extraido de headers para validar operaciones business_owner.
internal data class BusinessOwnerRouteContext(
    val companyCode: String,
    val token: String,
)

// Lee token Bearer y codigo de empresa; la autorizacion real ocurre en el servicio.
internal fun io.ktor.server.application.ApplicationCall.businessOwnerContext(): BusinessOwnerRouteContext? {
    val companyCode = request.headers["X-Company-Code"]?.takeIf { it.isNotBlank() } ?: return null
    val token = request.headers[HttpHeaders.Authorization]
        ?.takeIf { it.startsWith("Bearer ") }
        ?.removePrefix("Bearer ")
        ?: return null
    return BusinessOwnerRouteContext(companyCode, token)
}

// Mismo mapa de estados que las rutas de paquetes de extensiones.
internal fun modulePackageStatus(exception: CompanyProvisioningException): HttpStatusCode = when (exception.code) {
    "NOT_FOUND" -> HttpStatusCode.NotFound
    "PACKAGE_CHANGED" -> HttpStatusCode.Conflict
    "BUILDER_UNAVAILABLE", "CATALOG_UNAVAILABLE" -> HttpStatusCode.ServiceUnavailable
    "PACKAGE_UNAVAILABLE" -> HttpStatusCode.BadGateway
    else -> HttpStatusCode.BadRequest
}

/**
 * A scoped administrator may change a business owner only when every company that owner owns (the
 * PostgreSQL role is shared by duplicated companies) and every company of its owner group (whose
 * business owner switches into all of them without a password) is in their scope.
 */
internal fun requireOwnerInScope(actor: ServerActor, service: CompanyProvisioningService, code: String) {
    val scope = actor.companyScope ?: return
    // The refusal does not name the other company: the caller may not even know it exists.
    if ((service.companiesSharingOwner(code) + service.ownerGroupOf(code)).any { it !in scope }) {
        throw ServerPermissionException.outOfScope(ServerPermission.CompaniesEdit)
    }
}

internal fun SQLException.toProvisioningMessage(): String =
    tr("No se pudo completar la provision de la empresa. PostgreSQL {0}: {1}", sqlState.orEmpty(), message.orEmpty())

internal fun SQLException.toModuleMessage(): String =
    tr("No se pudo actualizar el modulo. PostgreSQL {0}: {1}", sqlState.orEmpty(), message.orEmpty())

internal fun SQLException.toCompanyMessage(): String =
    tr("No se pudo actualizar la empresa. PostgreSQL {0}: {1}", sqlState.orEmpty(), message.orEmpty())
