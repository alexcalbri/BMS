package com.ideasdeveloper.bms.modules.empresa

import com.ideasdeveloper.bms.ActivityPermissionIds
import com.ideasdeveloper.bms.server.modules.ModuleDefinition
import com.ideasdeveloper.bms.server.modules.ModuleMcp
import com.ideasdeveloper.bms.server.modules.ModulePermissionDefinition
import com.ideasdeveloper.bms.server.modules.ServerModule
import com.ideasdeveloper.bms.server.modules.ServerModuleProvider

/**
 * Base module that is always installed, like Clientes. Company administration routes and screens
 * belong to Core (company api and the app's Empresa screens), so this module declares no routes;
 * it exists so the server lists Empresa as an installed base module. It declares the company-level
 * permission to read the activity log and the agent tools that read it (ACTIVITY_LOG.md §7).
 */
object EmpresaServerModule : ServerModule {
    override val definition = ModuleDefinition(
        id = EmpresaModule.id,
        displayName = EmpresaModule.displayName,
        description = "Administracion de empresas, identidad y configuracion de cada empresa.",
        version = "0.3.0",
        minCoreVersion = "0.12.0",
        locked = true,
        permissions = listOf(
            ModulePermissionDefinition(
                ActivityPermissionIds.View,
                "Ver el registro de actividad",
                "Permite ver quien hizo cada cambio en la empresa (usuario, modulo, registro y fecha) y exportarlo.",
            ),
        ),
    )

    override val mcp: ModuleMcp
        get() = EmpresaActivityTools.mcp
}

class EmpresaServerModuleProvider : ServerModuleProvider {
    override fun create(): ServerModule = EmpresaServerModule
}
