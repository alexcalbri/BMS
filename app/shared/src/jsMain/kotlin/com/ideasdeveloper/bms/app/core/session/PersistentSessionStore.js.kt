package com.ideasdeveloper.bms.app.core.session

import com.ideasdeveloper.bms.app.features.auth.data.LoginResponse
import web.storage.localStorage

actual object PersistentSessionStore {
    private const val UserIdKey = "bms.session.userId"
    private const val UsernameKey = "bms.session.username"
    private const val AccessTokenKey = "bms.session.accessToken"
    private const val ExpiresAtEpochSecondsKey = "bms.session.expiresAtEpochSeconds"
    private const val ScopeKey = "bms.session.scope"
    private const val CompanyCodeKey = "bms.session.companyCode"
    private const val WorkspaceCompanyCodeKey = "bms.session.workspaceCompanyCode"
    private const val RoleKey = "bms.session.role"
    private const val EnabledModulesKey = "bms.session.enabledModules"

    actual fun load(): LoginResponse? {
        val expiresAt = localStorage.getItem(ExpiresAtEpochSecondsKey)?.toLongOrNull() ?: 0L
        if (expiresAt <= nowEpochSeconds()) {
            clear()
            return null
        }

        return LoginResponse(
            userId = localStorage.getItem(UserIdKey)?.takeIf { it.isNotBlank() } ?: return null,
            username = localStorage.getItem(UsernameKey)?.takeIf { it.isNotBlank() } ?: return null,
            accessToken = localStorage.getItem(AccessTokenKey)?.takeIf { it.isNotBlank() } ?: return null,
            expiresInSeconds = expiresAt - nowEpochSeconds(),
            scope = localStorage.getItem(ScopeKey)?.takeIf { it.isNotBlank() } ?: return null,
            companyCode = localStorage.getItem(CompanyCodeKey)?.takeIf { it.isNotBlank() },
            workspaceCompanyCode = localStorage.getItem(WorkspaceCompanyCodeKey)?.takeIf { it.isNotBlank() },
            role = localStorage.getItem(RoleKey)?.takeIf { it.isNotBlank() } ?: return null,
            enabledModules = localStorage.getItem(EnabledModulesKey)
                ?.split(",")
                ?.filter { it.isNotBlank() }
                .orEmpty(),
        )
    }

    actual fun save(response: LoginResponse) {
        localStorage.setItem(UserIdKey, response.userId)
        localStorage.setItem(UsernameKey, response.username)
        localStorage.setItem(AccessTokenKey, response.accessToken)
        localStorage.setItem(ExpiresAtEpochSecondsKey, (nowEpochSeconds() + response.expiresInSeconds).toString())
        localStorage.setItem(ScopeKey, response.scope)
        response.companyCode?.let {
            localStorage.setItem(CompanyCodeKey, it)
        } ?: localStorage.removeItem(CompanyCodeKey)
        response.workspaceCompanyCode?.let {
            localStorage.setItem(WorkspaceCompanyCodeKey, it)
        } ?: localStorage.removeItem(WorkspaceCompanyCodeKey)
        localStorage.setItem(RoleKey, response.role)
        localStorage.setItem(EnabledModulesKey, response.enabledModules.joinToString(","))
    }

    actual fun clear() {
        localStorage.removeItem(UserIdKey)
        localStorage.removeItem(UsernameKey)
        localStorage.removeItem(AccessTokenKey)
        localStorage.removeItem(ExpiresAtEpochSecondsKey)
        localStorage.removeItem(ScopeKey)
        localStorage.removeItem(CompanyCodeKey)
        localStorage.removeItem(WorkspaceCompanyCodeKey)
        localStorage.removeItem(RoleKey)
        localStorage.removeItem(EnabledModulesKey)
    }

    private fun nowEpochSeconds(): Long = (js("Date.now()") as Double).toLong() / 1000L
}
