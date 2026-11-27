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
    // Administrador del servidor (role = "server_admin"): permisos concedidos y empresas a las que se limita
    // (null = todas). Vacios en servidores antiguos y en el dueno del servidor, que no los necesita.
    val serverPermissions: List<String> = emptyList(),
    val serverCompanies: List<String>? = null,
    // El administrador debe cambiar la contrasena inicial antes de usar nada mas.
    val mustChangePassword: Boolean = false,
    // Verificacion en dos pasos: el dueno del servidor la exige a los administradores y este aun no la activo.
    val twoFactorSetupRequired: Boolean = false,
    // El segundo paso se hizo con un codigo de recuperacion: avisa y dice cuantos quedan (null si no se uso).
    val recoveryCodeUsed: Boolean = false,
    val recoveryCodesLeft: Int? = null,
    // Entro con el codigo de recuperacion por correo: la verificacion en dos pasos de la cuenta quedo desactivada.
    val twoFactorDisabled: Boolean = false,
    // Una recuperacion por correo en curso se cancelo porque la persona entro con normalidad.
    val emailRecoveryCancelled: Boolean = false,
    // Entro con el ultimo codigo de recuperacion: debe configurar un autenticador nuevo (TWO_FACTOR.md 9).
    val lastRecoveryCodeUsed: Boolean = false,
)
