package com.ideasdeveloper.bms.app.features.assistant.data

import com.ideasdeveloper.bms.app.features.auth.data.LoginResponse
import com.ideasdeveloper.bms.app.features.auth.data.isServerLevel
import kotlinx.serialization.Serializable

// Codigo del espacio del servidor (igual que ServerWorkspace.Code en el servidor): con el, las rutas /companies/me/...
// responden en nombre del servidor, igual que para los modulos del espacio del servidor.
const val AssistantServerWorkspaceCode = "servidor"

// Codigo que se manda en X-Company-Code al asistente: la empresa de la sesion o, en una sesion del servidor, su espacio.
// Null si la sesion no tiene ninguno de los dos.
val LoginResponse?.assistantCompanyCode: String?
    get() = this?.companyCode ?: this?.workspaceCompanyCode ?: if (isServerLevel) AssistantServerWorkspaceCode else null

// True cuando el asistente de esta sesion es el del espacio del servidor (no el de una empresa).
val LoginResponse?.assistantInServerWorkspace: Boolean
    get() = this != null && companyCode == null && isServerLevel

// Fila de la lista de empresas del servidor (GET /server/assistant/companies). Todos los campos nuevos son opcionales para
// que un servidor anterior siga funcionando: [companyEnabled] (el valor que el servidor documente como "activado en la
// empresa"; si falta se usa [enabled]) y [serverWorkspace] (la fila es el espacio del servidor).
@Serializable
data class AssistantServerCompanyRow(
    val code: String,
    val name: String = "",
    val allowed: Boolean = false,
    val monthlyTokens: Long? = null,
    val effectiveMonthlyTokens: Long? = null,
    val enabled: Boolean = false,
    val companyEnabled: Boolean? = null,
    val ownKeySet: Boolean = false,
    val monthUsedTokens: Long = 0,
    val serverWorkspace: Boolean? = null,
) {
    // Activado en la empresa (el asistente lo activo la empresa o, ahora, el gestor del servidor).
    val activeInCompany: Boolean get() = companyEnabled ?: enabled

    // La fila es el espacio del servidor y no una empresa.
    val isServerWorkspace: Boolean get() = serverWorkspace == true || code == AssistantServerWorkspaceCode
}

@Serializable
data class AssistantServerCompanyRowsResponse(val companies: List<AssistantServerCompanyRow> = emptyList())

// PUT /server/assistant/companies/{code}: [companyEnabled] y [acceptDisclosure] son opcionales (null = no se envian).
@Serializable
data class AssistantServerCompanyUpdateRequest(
    val allowed: Boolean,
    val monthlyTokens: Long? = null,
    val companyEnabled: Boolean? = null,
    val acceptDisclosure: Boolean? = null,
)

// GET /companies/me/assistant/attention: lo que espera a la persona en todos sus dispositivos (en una empresa y en el
// espacio del servidor). Todo con valor por defecto para tolerar respuestas parciales.
@Serializable
data class AssistantAttention(
    val pendingConfirmations: Int = 0,
    val unseenFinished: Int = 0,
    val conversationIds: List<String> = emptyList(),
)
