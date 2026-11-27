package com.ideasdeveloper.bms.app.features.auth.data

import kotlinx.serialization.Serializable

// Contrato de la verificacion en dos pasos (TWO_FACTOR.md seccion 3). Todos los campos tienen valor por
// defecto para tolerar variaciones del servidor.

@Serializable
// GET /auth/two-factor: estado de la cuenta propia.
data class TwoFactorStatus(
    val enabled: Boolean = false,
    // Hubo un "setup" que aun no se confirmo con un codigo.
    val pending: Boolean = false,
    val enabledAt: String? = null,
    val recoveryCodesLeft: Int = 0,
    // Administrador de servidor al que el dueno se la exige.
    val requiredByServer: Boolean = false,
    // Falso cuando el servidor no puede guardar secretos (sin llave maestra o sin migraciones).
    val available: Boolean = true,
)

@Serializable
// POST /auth/two-factor/setup: secreto Base32 y URI otpauth para la aplicacion de autenticacion.
data class TwoFactorSetup(
    val secret: String,
    val otpauthUri: String,
    val issuer: String = "",
    val accountName: String = "",
    val algorithm: String = "SHA1",
    val digits: Int = 6,
    val period: Int = 30,
)

@Serializable
// POST /auth/two-factor/enable y /recovery-codes: los 10 codigos de recuperacion, que no se pueden volver a leer.
data class TwoFactorRecoveryCodes(
    val recoveryCodes: List<String> = emptyList(),
)

@Serializable
// GET/PUT /server/two-factor-policy (solo el dueno del servidor).
data class TwoFactorPolicy(
    val requireForAdmins: Boolean = false,
)
