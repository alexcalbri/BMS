package com.ideasdeveloper.bms.app.features.auth.data

import kotlinx.serialization.Serializable

@Serializable
// Respuesta de autenticacion que alimenta sesion, rol y modulos visibles.
data class LoginResponse(
    val userId: String,
    val username: String,
    val accessToken: String,
    val expiresInSeconds: Long,
    val scope: String,
    val companyCode: String? = null,
    val role: String,
    val enabledModules: List<String> = emptyList(),
    // server_owner: codigo del espacio del servidor donde usa modulos sin una empresa.
    val workspaceCompanyCode: String? = null,
    val displayName: String? = null,
    val email: String? = null,
    // Falso hasta que el usuario tenga nombre y correo; la app los pide antes de continuar.
    val profileComplete: Boolean = true,
    // Sesiones del servidor: nombre del servidor, que se muestra en lugar de una empresa.
    val serverName: String? = null,
    // El dueno del servidor abrio esta sesion de empresa como su business owner (selector de empresas).
    val serverOwnerAccess: Boolean = false,
    // Idioma efectivo del usuario ("es" o "en") y el de su empresa; null en servidores antiguos.
    val language: String? = null,
    val companyLanguage: String? = null,
    // Eleccion propia del usuario; null = sigue al idioma de la empresa.
    val userLanguage: String? = null,
)
