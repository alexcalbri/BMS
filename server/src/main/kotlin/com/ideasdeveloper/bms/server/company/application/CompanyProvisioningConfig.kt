package com.ideasdeveloper.bms.server.company.application

/**
 * Connections of company provisioning (SELF_HOSTING.md "Roles de PostgreSQL"). The runtime role ([runtimeUser]) is
 * the plain role of the application's pooled connections. Creating, migrating or deleting a company runs on
 * short-lived connections of their own as [provisioningUser] (NOINHERIT; it holds nothing until it runs SET ROLE to
 * the server owner's role), never on the runtime role. Without [provisioningUser] (development, tests and servers
 * not yet moved to the new layout) provisioning connects as the runtime role, as before.
 */
data class CompanyProvisioningConfig(
    val administrationJdbcUrl: String,
    val runtimeUser: String,
    val runtimePassword: String,
    val tenantJdbcUrlPrefix: String,
    val migrationsRoot: String,
    val provisioningUser: String? = null,
    val provisioningPassword: String? = null,
) {
    val separateProvisioningRole: Boolean get() = !provisioningUser.isNullOrBlank() && provisioningUser != runtimeUser
    val adminUser: String get() = if (separateProvisioningRole) provisioningUser!! else runtimeUser
    val adminPassword: String get() = if (separateProvisioningRole) provisioningPassword.orEmpty() else runtimePassword

    override fun toString(): String =
        "CompanyProvisioningConfig(administrationJdbcUrl=$administrationJdbcUrl, runtimeUser=$runtimeUser, " +
            "tenantJdbcUrlPrefix=$tenantJdbcUrlPrefix, migrationsRoot=$migrationsRoot, provisioningUser=$provisioningUser)"
}
