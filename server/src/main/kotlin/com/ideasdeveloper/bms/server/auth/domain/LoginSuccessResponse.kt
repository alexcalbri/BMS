package com.ideasdeveloper.bms.server.auth.domain

import kotlinx.serialization.Serializable

@Serializable
data class LoginSuccessResponse(
    val userId: String,
    val username: String,
    val accessToken: String,
    val expiresInSeconds: Long,
    val scope: String = "company",
    val companyCode: String? = null,
    val role: String = "user",
    val enabledModules: List<String> = emptyList(),
    // Server owners: company code of the server workspace where they use modules without a company.
    val workspaceCompanyCode: String? = null,
    val displayName: String? = null,
    val email: String? = null,
    // False until the user has a valid name and email; the app asks for them before continuing.
    val profileComplete: Boolean = true,
    // Server sessions: the name of the server, shown instead of a company name.
    val serverName: String? = null,
    // A server owner opened this company session as its business owner (company switcher).
    val serverOwnerAccess: Boolean = false,
    // Effective language of the user (I18N.md §7): their own, else the company default, else "es".
    // The app switches to it after signing in and sends it as Accept-Language.
    val language: String = "es",
    // Default language of the company (for server sessions, the server workspace's), "es" without one.
    val companyLanguage: String = "es",
    // The user's own language, null when they follow the company default.
    val userLanguage: String? = null,
    // Server sessions (Core 0.11.0, SERVER_ADMINS.md): role is "server_owner" or "server_admin".
    // Permission ids the session holds on the server: every one for the owner, the granted ones for an administrator.
    val serverPermissions: List<String> = emptyList(),
    // Companies a server administrator may act on; null means every company (and for the owner).
    val serverCompanies: List<String>? = null,
    // A server administrator must change the initial password (PUT /auth/password) before anything else works.
    val mustChangePassword: Boolean = false,
    // Two-step verification (TWO_FACTOR.md): a server administrator the owner requires to use it, not enrolled yet.
    val twoFactorSetupRequired: Boolean = false,
    // The second step used a recovery code; how many are left.
    val recoveryCodeUsed: Boolean = false,
    val recoveryCodesLeft: Int? = null,
)

// A company the session can move to.
@Serializable
class SwitchTarget(val code: String, val name: String)

// serverName is set when the session may go back to (or stay on) the server: server owners only.
// current is the company of the session, null on the server.
@Serializable
class SwitchTargetsResponse(val serverName: String?, val current: String?, val companies: List<SwitchTarget>)
