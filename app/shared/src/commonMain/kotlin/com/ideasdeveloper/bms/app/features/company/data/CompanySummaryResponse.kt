package com.ideasdeveloper.bms.app.features.company.data

import kotlinx.serialization.Serializable

@Serializable
// Resumen que usa la pantalla de Empresa para listar tenants administrables.
data class CompanySummaryResponse(
    val code: String,
    val name: String,
    val databaseName: String,
    val isActive: Boolean,
    val modules: List<CompanyModuleResponse>,
    val logoUrl: String? = null,
    val primaryColor: String = "#667EEA",
    val secondaryColor: String = "#764BA2",
    val accentColor: String = "#FFFFFF",
    // Empresas del mismo business owner; el owner cambia entre ellas desde el panel.
    val linkedCompanies: List<String> = emptyList(),
)

@Serializable
// Estado de un modulo instalable o habilitable dentro de una empresa.
data class CompanyModuleResponse(
    val moduleId: String,
    val displayName: String,
    val enabled: Boolean,
    val locked: Boolean,
)

@Serializable
// Estado de un modulo instalado en el servidor y su uso en empresas.
data class ServerModuleResponse(
    val moduleId: String,
    val displayName: String,
    val description: String,
    val version: String,
    val latestVersion: String? = null,
    val locked: Boolean,
    val installed: Boolean,
    val activeCompanyCount: Int,
    val packageUrl: String? = null,
    val packageSha256: String? = null,
    val installedPackagePath: String? = null,
    // Instalacion en segundo plano de un modulo opcional: installing, restarting o failed.
    val status: String? = null,
    val statusMessage: String? = null,
    // El servidor compara versiones: hay una mas nueva en el catalogo, o la instalada es mas nueva.
    val updateAvailable: Boolean = false,
    val newerThanCatalog: Boolean = false,
    // Activo en el espacio del servidor (uso del server_owner y su personal sin una empresa).
    val usedByServer: Boolean = false,
    // Minimos de la version instalada y de la disponible; installBlockedReason explica por que no se puede instalar.
    val minCoreVersion: String? = null,
    val minAppVersion: String? = null,
    val latestMinCoreVersion: String? = null,
    val latestMinAppVersion: String? = null,
    val installBlockedReason: String? = null,
    // Aviso del servidor tras instalar (por ejemplo, no llego la version de la app y no se pudo validar).
    val warning: String? = null,
)

@Serializable
data class ServerModuleCatalogResponse(
    val catalogUrl: String,
)

@Serializable
// GET /server/modules/{id}/inspect: lo que se descarga hoy del catalogo, para revisarlo antes de autorizar un modulo sin firma.
data class ModulePackageInspection(
    val id: String = "",
    val commit: String = "",
    val commitUrl: String = "",
    // Hash del arbol calculado sobre lo descargado.
    val packageSha256: String = "",
    // Verdadero solo con una firma verificada.
    val signed: Boolean = false,
    val keyId: String? = null,
    // Por que una firma presente no vale; si existe, el modulo no se puede autorizar.
    val signatureProblem: String? = null,
)

@Serializable
data class AuthorizeUnsignedModuleRequest(
    val confirmationText: String,
    val expectedPackageSha256: String,
    val expectedCommit: String,
)

@Serializable
data class ModuleUnsignedAuthorizationResponse(
    val id: String = "",
    val packageSha256: String = "",
    val sourceUrl: String = "",
)

@Serializable
data class UpdateServerModuleCatalogRequest(
    val catalogUrl: String,
)

@Serializable
// Payload para activar o desactivar un modulo de empresa.
data class UpdateCompanyModuleRequest(
    val enabled: Boolean,
)

@Serializable
// Payload para cambiar el estado activo de una empresa.
data class UpdateCompanyStatusRequest(
    val active: Boolean,
)

@Serializable
data class BusinessOwnerResponse(
    val username: String,
    val displayName: String = "",
    val email: String = "",
    val twoFactorEnabled: Boolean? = null,
)

@Serializable
// Cuerpo de las rutas que desactivan la verificacion en dos pasos de otra persona: la contrasena del que lo hace.
data class TwoFactorResetRequest(val currentPassword: String)

@Serializable
// Respuesta de esas rutas (TWO_FACTOR.md 8.2).
data class TwoFactorResetResponse(
    val userId: String = "",
    val twoFactorEnabled: Boolean = false,
    val sessionsClosed: Int = 0,
    val agentsDisconnected: Int = 0,
    val agentsDisconnectFailed: Boolean = false,
    val message: String = "",
)

// Blank password keeps it; a new username needs a new password.
@Serializable
data class UpdateBusinessOwnerRequest(
    val username: String,
    val displayName: String,
    val email: String,
    val password: String = "",
)

@Serializable
data class ResetBusinessOwnerPasswordRequest(
    val password: String,
)

@Serializable
// Payload para actualizar los datos administrables de una empresa.
data class UpdateCompanyRequest(
    val name: String,
    val logoUrl: String? = null,
    val primaryColor: String,
    val secondaryColor: String,
    val accentColor: String,
)
