package com.ideasdeveloper.bms.app.core.session

import com.ideasdeveloper.bms.app.features.auth.data.LoginResponse
import platform.Foundation.NSDate
import platform.Foundation.NSUserDefaults

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

    private val defaults = NSUserDefaults.standardUserDefaults

    actual fun load(): LoginResponse? {
        val expiresAt = defaults.stringForKey(ExpiresAtEpochSecondsKey)?.toLongOrNull() ?: 0L
        if (expiresAt <= nowEpochSeconds()) {
            clear()
            return null
        }

        return LoginResponse(
            userId = defaults.stringForKey(UserIdKey)?.takeIf { it.isNotBlank() } ?: return null,
            username = defaults.stringForKey(UsernameKey)?.takeIf { it.isNotBlank() } ?: return null,
            accessToken = defaults.stringForKey(AccessTokenKey)?.takeIf { it.isNotBlank() } ?: return null,
            expiresInSeconds = expiresAt - nowEpochSeconds(),
            scope = defaults.stringForKey(ScopeKey)?.takeIf { it.isNotBlank() } ?: return null,
            companyCode = defaults.stringForKey(CompanyCodeKey)?.takeIf { it.isNotBlank() },
            workspaceCompanyCode = defaults.stringForKey(WorkspaceCompanyCodeKey)?.takeIf { it.isNotBlank() },
            role = defaults.stringForKey(RoleKey)?.takeIf { it.isNotBlank() } ?: return null,
            enabledModules = defaults.stringForKey(EnabledModulesKey)
                ?.split(",")
                ?.filter { it.isNotBlank() }
                .orEmpty(),
        )
    }

    actual fun save(response: LoginResponse) {
        defaults.setObject(response.userId, UserIdKey)
        defaults.setObject(response.username, UsernameKey)
        defaults.setObject(response.accessToken, AccessTokenKey)
        defaults.setObject((nowEpochSeconds() + response.expiresInSeconds).toString(), ExpiresAtEpochSecondsKey)
        defaults.setObject(response.scope, ScopeKey)
        response.companyCode?.let {
            defaults.setObject(it, CompanyCodeKey)
        } ?: defaults.removeObjectForKey(CompanyCodeKey)
        response.workspaceCompanyCode?.let {
            defaults.setObject(it, WorkspaceCompanyCodeKey)
        } ?: defaults.removeObjectForKey(WorkspaceCompanyCodeKey)
        defaults.setObject(response.role, RoleKey)
        defaults.setObject(response.enabledModules.joinToString(","), EnabledModulesKey)
    }

    actual fun clear() {
        defaults.removeObjectForKey(UserIdKey)
        defaults.removeObjectForKey(UsernameKey)
        defaults.removeObjectForKey(AccessTokenKey)
        defaults.removeObjectForKey(ExpiresAtEpochSecondsKey)
        defaults.removeObjectForKey(ScopeKey)
        defaults.removeObjectForKey(CompanyCodeKey)
        defaults.removeObjectForKey(WorkspaceCompanyCodeKey)
        defaults.removeObjectForKey(RoleKey)
        defaults.removeObjectForKey(EnabledModulesKey)
    }

    private fun nowEpochSeconds(): Long = NSDate().timeIntervalSince1970.toLong()
}
