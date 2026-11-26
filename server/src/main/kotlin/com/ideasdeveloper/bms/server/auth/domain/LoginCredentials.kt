package com.ideasdeveloper.bms.server.auth.domain

import kotlinx.serialization.Serializable

@Serializable
class LoginCredentials(
    val username: String,
    val password: String,
    val companyCode: String? = null,
    // Set by the route from the trusted client address, never read from the request body.
    @kotlinx.serialization.Transient
    val clientAddress: String = "",
) {
    fun withClientAddress(address: String) = LoginCredentials(username, password, companyCode, address)
}
