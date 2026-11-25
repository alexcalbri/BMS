package com.ideasdeveloper.bms.server.auth.domain

fun interface LoginScope {
    fun login(credentials: LoginCredentials): LoginSuccessResponse?
}
