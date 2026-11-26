package com.ideasdeveloper.bms.server.auth.application

import com.ideasdeveloper.bms.server.auth.domain.LoginCredentials
import com.ideasdeveloper.bms.server.auth.domain.LoginScope
import com.ideasdeveloper.bms.server.auth.domain.LoginSuccessResponse

/** Company codes are lookup keys, never database names or connection URLs. */
class ScopedLoginService(
    private val serverScope: LoginScope,
    private val findActiveDatabase: (String) -> String?,
    private val companyScope: (String) -> LoginScope?,
    private val delayMillis: (LoginCredentials) -> Long = { 0L },
) {
    /** Milliseconds the caller should wait (non-blocking) before [login]; 0 for none. */
    fun delayMillisFor(credentials: LoginCredentials): Long = delayMillis(credentials)

    fun login(credentials: LoginCredentials): LoginSuccessResponse? {
        val code = credentials.companyCode ?: return serverScope.login(credentials)
        if (!code.matches(Regex("[a-z][a-z0-9_]{0,62}"))) return null
        val database = findActiveDatabase(code) ?: return null
        return companyScope(database)?.login(credentials)
    }
}
