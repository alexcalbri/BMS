package com.ideasdeveloper.bms.app.features.auth.data

// Roles de sesion del nivel servidor.
const val ServerOwnerRole = "server_owner"
const val ServerAdminRole = "server_admin"

// Dueno del servidor: poder total y unico que gestiona administradores, vinculos, catalogo y paquetes.
val LoginResponse?.isServerOwner: Boolean
    get() = this?.role == ServerOwnerRole

// Administrador del servidor: solo puede lo que sus permisos nombran.
val LoginResponse?.isServerAdmin: Boolean
    get() = this?.role == ServerAdminRole

// Sesion del nivel servidor (dueno o administrador), no de una empresa.
val LoginResponse?.isServerLevel: Boolean
    get() = this?.role == ServerOwnerRole || this?.role == ServerAdminRole

// El dueno puede todo; el administrador solo los permisos concedidos; cualquier otra sesion, nada.
fun LoginResponse?.can(permission: String): Boolean {
    val session = this ?: return false
    return when (session.role) {
        ServerOwnerRole -> true
        ServerAdminRole -> permission in session.serverPermissions
        else -> false
    }
}

// Verdadero si la sesion puede actuar sobre la empresa: el dueno y los administradores sin limite (serverCompanies null) sobre todas.
fun LoginResponse?.canAccessCompany(companyCode: String): Boolean {
    val session = this ?: return false
    return when (session.role) {
        ServerOwnerRole -> true
        ServerAdminRole -> session.serverCompanies?.contains(companyCode) ?: true
        else -> false
    }
}

// Permisos del servidor (SERVER_ADMINS.md seccion 3).
object ServerPermission {
    const val CompaniesView = "server.companies.view"
    const val CompaniesCreate = "server.companies.create"
    const val CompaniesEdit = "server.companies.edit"
    const val CompaniesStatus = "server.companies.status"
    const val CompaniesModules = "server.companies.modules"
    const val CompaniesExtensions = "server.companies.extensions"
    const val CompaniesOwnerPassword = "server.companies.owner_password"
    const val CompaniesEnter = "server.companies.enter"
    const val ModulesView = "server.modules.view"
    const val ModulesInstall = "server.modules.install"
    const val ModulesRemove = "server.modules.remove"
    const val ModulesAuthorizeUnsigned = "server.modules.authorize_unsigned"
    const val ExtensionsInstall = "server.extensions.install"
    const val ExtensionsAuthorizeUnsigned = "server.extensions.authorize_unsigned"
    const val CoreUpdate = "server.core.update"
    const val LogsView = "server.logs.view"
    const val AuditView = "server.audit.view"
    const val Settings = "server.settings"
    const val Workspace = "server.workspace"
    // Alto riesgo: desactiva la verificacion en dos pasos de usuarios de las empresas de su alcance. Titulo y riesgo vienen del catalogo del servidor.
    const val UsersTwoFactorReset = "server.users.two_factor_reset"
}
