package com.ideasdeveloper.bms.app.features.serveradmin.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

// Contrato de administradores del servidor y auditoria (SERVER_ADMINS.md seccion 4).
// Todos los campos nuevos son opcionales con valor por defecto para tolerar variaciones del servidor.

@Serializable
// GET /server/me: quien es el llamador y que puede hacer.
data class ServerMeResponse(
    val kind: String = "server_owner",
    val permissions: List<String> = emptyList(),
    val allCompanies: Boolean = true,
    val companies: List<String> = emptyList(),
    val mustChangePassword: Boolean = false,
)

@Serializable
// GET /server/admin-permissions: catalogo de permisos (titulos ya en el idioma del usuario).
data class ServerPermissionInfo(
    val id: String,
    val title: String = "",
    val description: String = "",
    val group: String = "",
    // "normal" o "high"
    val risk: String = "normal",
    val scoped: Boolean = false,
)

@Serializable
// GET /server/admins
data class ServerAdminResponse(
    val id: String,
    val login: String,
    val displayName: String = "",
    val email: String = "",
    val active: Boolean = true,
    val allCompanies: Boolean = false,
    val companies: List<String> = emptyList(),
    val permissions: List<String> = emptyList(),
    val mustChangePassword: Boolean = false,
    val createdAt: JsonElement? = null,
    val twoFactorEnabled: Boolean? = null,
)

@Serializable
// POST /server/admins
data class CreateServerAdminRequest(
    val login: String,
    val displayName: String,
    val email: String,
    val password: String,
    val allCompanies: Boolean,
    val companies: List<String>,
    val permissions: List<String>,
)

@Serializable
// PUT /server/admins/{id}
data class UpdateServerAdminRequest(
    val displayName: String,
    val email: String,
    val allCompanies: Boolean,
    val companies: List<String>,
)

@Serializable
// PUT /server/admins/{id}/permissions
data class UpdateServerAdminPermissionsRequest(val permissions: List<String>)

@Serializable
// PUT /server/admins/{id}/password
data class ServerAdminPasswordRequest(val password: String)

@Serializable
// PUT /server/admins/{id}/status
data class ServerAdminStatusRequest(val active: Boolean)

@Serializable
// PUT /auth/password: el propio administrador cambia su contrasena (cambio obligatorio inicial).
data class ChangeOwnPasswordRequest(val currentPassword: String, val newPassword: String)

@Serializable
// Una fila del registro de auditoria. id, createdAt y details se leen sin asumir su tipo exacto.
data class AuditEntry(
    val id: JsonElement? = null,
    val createdAt: JsonElement? = null,
    val actorRole: String? = null,
    val actorName: String? = null,
    val actorKind: String? = null,
    val action: String = "",
    val companyCode: String? = null,
    val moduleId: String? = null,
    val details: JsonElement? = null,
)

@Serializable
// GET /server/audit
data class AuditPage(
    val items: List<AuditEntry> = emptyList(),
    val page: Int = 0,
    val hasMore: Boolean = false,
)

// Texto legible de un valor JSON recibido sin tipo fijo: cadenas sin comillas, nulos vacios, el resto como JSON.
fun JsonElement?.asDisplayText(): String = when (this) {
    null -> ""
    is JsonNull -> ""
    is JsonPrimitive -> if (isString) content else toString()
    else -> toString()
}
