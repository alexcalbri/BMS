package com.ideasdeveloper.bms.server.company.api

import com.ideasdeveloper.bms.server.platform.coreupdate.coreUpdateRoutes
import com.ideasdeveloper.bms.server.modules.install.serverModuleRoutes
import com.ideasdeveloper.bms.server.platform.serverInfoRoutes
import com.ideasdeveloper.bms.server.platform.coreupdate.CoreUpdateService
import com.ideasdeveloper.bms.server.company.application.CompanyProvisioningService
import com.ideasdeveloper.bms.server.company.application.CompanySelfService
import com.ideasdeveloper.bms.server.admins.ServerActorResolver
import io.ktor.server.routing.Route

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

    companyAdminRoutes(authorizer, provisioning, addToScope)

    coreUpdateRoutes(authorizer, coreUpdates)

    businessOwnerRoutes(authorizer, provisioning)
}
