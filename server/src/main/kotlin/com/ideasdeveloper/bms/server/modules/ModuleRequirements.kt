package com.ideasdeveloper.bms.server.modules

import com.ideasdeveloper.bms.server.i18n.tr
import com.ideasdeveloper.bms.ModuleVersion

/** Versiones minimas de Core y app que un modulo necesita; nulo significa sin minimo. */
data class ModuleRequirements(
    val minCoreVersion: String? = null,
    val minAppVersion: String? = null,
)

/** Un minimo no cumplido: [code] es MODULE_NEEDS_CORE o MODULE_NEEDS_APP. */
data class ModuleRequirementProblem(val code: String, val message: String)

/** Regla de versiones minimas, sin estado para poder probarla sola. */
object ModuleRequirementGate {
    const val NeedsCore = "MODULE_NEEDS_CORE"
    const val NeedsApp = "MODULE_NEEDS_APP"

    /** Problema si [coreVersion] es menor que [minCoreVersion]; sin minimo o con minimo vacio no hay problema. */
    fun coreProblem(minCoreVersion: String?, coreVersion: String): ModuleRequirementProblem? {
        val minimum = minCoreVersion?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (ModuleVersion.compare(coreVersion, minimum) >= 0) return null
        return ModuleRequirementProblem(
            NeedsCore,
            tr("Este modulo necesita el Core {0} o superior; el servidor tiene {1}. Actualiza el servidor primero.", minimum, coreVersion),
        )
    }

    /** Problema si [appVersion] es menor que [minAppVersion]; sin version de app (app vieja o MCP) no se bloquea. */
    fun appProblem(minAppVersion: String?, appVersion: String?): ModuleRequirementProblem? {
        val minimum = minAppVersion?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val current = appVersion?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (ModuleVersion.compare(current, minimum) >= 0) return null
        return ModuleRequirementProblem(
            NeedsApp,
            tr("Este modulo necesita la app {0} o superior; tu app es {1}. Actualizala antes de instalar.", minimum, current),
        )
    }

    /** Aviso cuando hay minimo de app pero quien instala no manda su version. */
    fun missingAppVersionWarning(minAppVersion: String?, appVersion: String?): String? =
        if (!minAppVersion.isNullOrBlank() && appVersion.isNullOrBlank()) {
            tr("No se pudo comprobar la version de la app; este modulo necesita la app {0} o superior.", minAppVersion)
        } else {
            null
        }
}
