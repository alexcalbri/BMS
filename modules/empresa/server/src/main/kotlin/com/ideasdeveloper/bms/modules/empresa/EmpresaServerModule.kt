package com.ideasdeveloper.bms.modules.empresa

import com.ideasdeveloper.bms.ActivityPermissionIds
import com.ideasdeveloper.bms.AssistantIds
import com.ideasdeveloper.bms.server.modules.ModuleDefinition
import com.ideasdeveloper.bms.server.modules.ModuleMcp
import com.ideasdeveloper.bms.server.modules.ModulePermissionDefinition
import com.ideasdeveloper.bms.server.modules.ServerModule
import com.ideasdeveloper.bms.server.modules.ServerModuleProvider

/**
 * Base module that is always installed, like Clientes. Company administration routes and screens
 * belong to Core (company api and the app's Empresa screens), so this module declares no routes;
 * it exists so the server lists Empresa as an installed base module. It declares the company-level
 * permission to read the activity log and the agent tools that read it (ACTIVITY_LOG.md §7), and the permission to
 * use the in-app assistant (0.4.0, GLOBAL_AGENT.md).
 */
object EmpresaServerModule : ServerModule {
    override val definition = ModuleDefinition(
        id = EmpresaModule.id,
        displayName = EmpresaModule.displayName,
        description = "Administracion de empresas, identidad y configuracion de cada empresa.",
        version = "0.4.0",
        minCoreVersion = "0.14.0",
        locked = true,
        permissions = listOf(
            ModulePermissionDefinition(
                ActivityPermissionIds.View,
                "Ver el registro de actividad",
                "Permite ver quien hizo cada cambio en la empresa (usuario, modulo, registro y fecha) y exportarlo.",
            ),
            // The in-app assistant of the Core (GLOBAL_AGENT.md): who sees and uses it. Only with the module's tools the user already has.
            ModulePermissionDefinition(
                AssistantIds.UsePermission,
                "Usar el asistente de IA",
                "Permite usar el asistente de IA de la app. El asistente solo puede hacer lo que el usuario tiene permitido y pide confirmacion antes de cambiar datos. Lo que consulte se envia a Anthropic.",
            ),
        ),
    )

    override val mcp: ModuleMcp
        get() = EmpresaActivityTools.mcp
}

class EmpresaServerModuleProvider : ServerModuleProvider {
    override fun create(): ServerModule = EmpresaServerModule
}
