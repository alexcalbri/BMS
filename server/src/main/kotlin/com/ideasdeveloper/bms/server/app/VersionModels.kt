package com.ideasdeveloper.bms.server.app

import com.ideasdeveloper.bms.server.company.application.CoreUpdateService
import kotlinx.serialization.Serializable

const val BMSVersion = "0.12.0"

// El servidor no conoce la version de la app (cada cliente trae la suya): solo informa la ultima disponible.
@Serializable
data class VersionResponse(
    val coreVersion: String = BMSVersion,
    val latestCoreVersion: String? = null,
    val latestAppVersion: String? = null,
    val moduleUpdates: List<ModuleVersionUpdate> = emptyList(),
) {
    val coreUpdateAvailable: Boolean
        get() = CoreUpdateService.isUpdateAvailable(coreVersion, latestCoreVersion)
    val modulesUpdateAvailable: Boolean
        get() = moduleUpdates.isNotEmpty()

    /** What anyone may read before signing in (B7): the Core version for the app's compatibility check and the latest app. */
    fun publicView(): VersionResponse = VersionResponse(coreVersion = coreVersion, latestAppVersion = latestAppVersion)
}

@Serializable
data class RemoteVersionCatalog(
    val latestCoreVersion: String? = null,
    val latestAppVersion: String? = null,
)

@Serializable
data class ModuleVersionUpdate(
    val moduleId: String,
    val displayName: String,
    val currentVersion: String,
    val latestVersion: String,
)
