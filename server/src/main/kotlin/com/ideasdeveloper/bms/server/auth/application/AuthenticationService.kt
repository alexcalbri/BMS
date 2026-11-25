package com.ideasdeveloper.bms.server.auth.application

import com.ideasdeveloper.bms.server.auth.domain.ApplicationUser
import com.ideasdeveloper.bms.server.auth.domain.LoginCredentials
import com.ideasdeveloper.bms.server.auth.infrastructure.database.ApplicationUserRepository
import com.ideasdeveloper.bms.server.auth.infrastructure.security.PostgresCredentialVerifier

class AuthenticationService(
    private val credentialVerifier: PostgresCredentialVerifier,
    private val userRepository: ApplicationUserRepository,
) {

    fun authenticate(credentials: LoginCredentials): ApplicationUser? {
        val user = userRepository.findByPostgresRole(
            credentials.username,
        )

        // Un rol de PostgreSQL es global al servidor: su contrasena solo vale aqui si el usuario
        // es dueno de esta base; si no, podria entrar a otra empresa con el mismo nombre de rol.
        val viaPostgres = credentialVerifier.verify(credentials) &&
            user != null && userRepository.isOwner(user.id)

        if (!viaPostgres &&
            !userRepository.verifyApplicationPassword(credentials.username, credentials.password)
        ) {
            return null
        }

        if (user == null || !user.isActive) {
            return null
        }

        return user
    }
}
