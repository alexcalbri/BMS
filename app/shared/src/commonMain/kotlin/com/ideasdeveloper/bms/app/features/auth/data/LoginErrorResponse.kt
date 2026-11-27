package com.ideasdeveloper.bms.app.features.auth.data

import kotlinx.serialization.Serializable

@Serializable
// Error estructurado que el servidor devuelve cuando falla el login.
data class LoginErrorResponse(
    val code: String,
    val message: String,
    // Solo en TWO_FACTOR_REQUIRED (segundo paso del inicio de sesion) y TWO_FACTOR_INVALID_CODE; null en el resto.
    val challengeToken: String? = null,
    val expiresInSeconds: Long? = null,
    val attemptsLeft: Int? = null,
    // Solo en TWO_FACTOR_REQUIRED: "totp", "recovery_code" y, si el servidor tiene correo, "email_recovery".
    val methods: List<String> = emptyList(),
)
