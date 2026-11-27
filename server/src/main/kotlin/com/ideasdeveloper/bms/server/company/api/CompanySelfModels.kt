package com.ideasdeveloper.bms.server.company.api

import kotlinx.serialization.Serializable

@Serializable
// Perfil editable de empresa expuesto al business_owner.
data class CompanyProfileResponse(
    val code: String,
    val name: String,
    val logoUrl: String?,
    val primaryColor: String,
    val secondaryColor: String,
    val accentColor: String,
    // Idioma por defecto de la empresa ("es", "en"), I18N.md §7.
    val defaultLanguage: String = "es",
)

@Serializable
// Payload usado por el business_owner para cambiar datos visuales de su empresa.
data class UpdateCompanyProfileRequest(
    val name: String,
    val logoUrl: String? = null,
    val primaryColor: String,
    val secondaryColor: String,
    val accentColor: String,
    // Idioma por defecto de la empresa ("es", "en"); null lo deja como esta.
    val defaultLanguage: String? = null,
)

@Serializable
// Describe un archivo ZIP disponible en el almacenamiento de backups de la empresa.
data class CompanyBackupResponse(
    val fileName: String,
    val sizeBytes: Long,
    val createdAt: String,
    val downloadUrl: String,
)

@Serializable
// Respuesta de listado para los ZIPs de una empresa.
data class CompanyBackupListResponse(
    val backups: List<CompanyBackupResponse>,
)

@Serializable
data class CompanyPermissionResponse(
    val permissionId: String,
    val moduleId: String,
    val title: String,
    val description: String,
)

@Serializable
data class CompanyUserResponse(
    val userId: String,
    val username: String,
    val isActive: Boolean,
    val permissions: List<String>,
    val displayName: String? = null,
    val email: String? = null,
    val userCode: String = "",
    // The company's business owner: edited with its own rules (no permissions, always active).
    val isBusinessOwner: Boolean = false,
    // Two-step verification on (TWO_FACTOR.md §8.4); null when it cannot be told.
    val twoFactorEnabled: Boolean? = null,
    // Whether the caller may turn this user's two-step verification off (TWO_FACTOR.md §8.4); null when unknown.
    val canResetTwoFactor: Boolean? = null,
)

@Serializable
data class CompanyUsersResponse(
    val users: List<CompanyUserResponse>,
    val permissions: List<CompanyPermissionResponse>,
)

@Serializable
data class CreateCompanyUserRequest(
    val username: String,
    val password: String,
    val permissions: List<String> = emptyList(),
    // Required for every user: changes are recorded with them and the email receives reset codes.
    val displayName: String = "",
    val email: String = "",
)

@Serializable
data class UpdateCompanyUserPermissionsRequest(
    val permissions: List<String>,
)

/**
 * Every field of a user, as an owner edits it. [password] blank keeps the current one. For the
 * business owner [isActive] and [permissions] are ignored and a new [username] needs a [password].
 */
@Serializable
data class UpdateCompanyUserRequest(
    val username: String,
    val displayName: String,
    val email: String,
    val isActive: Boolean = true,
    val permissions: List<String> = emptyList(),
    val password: String = "",
    // Solo cuando el business owner cambia SU PROPIO correo: su contrasena actual.
    val currentPassword: String? = null,
)

// Empresa del servidor: la empresa que el server_owner administra sin crear una empresa.
@Serializable
data class ServerWorkspaceResponse(
    val code: String,
)
