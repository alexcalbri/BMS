package com.ideasdeveloper.bms.server.company.application

import com.ideasdeveloper.bms.server.admins.ServerActor
import com.ideasdeveloper.bms.server.admins.ServerActorKind
import com.ideasdeveloper.bms.server.admins.ServerActorLookup
import com.ideasdeveloper.bms.server.admins.ServerActorResolver
import com.ideasdeveloper.bms.server.admins.ServerAdminStore
import com.ideasdeveloper.bms.server.auth.application.SessionService
import java.sql.Connection
import java.util.UUID
import javax.sql.DataSource

/**
 * Who is behind a central session. [serverOwnerRole] answers only for server owners (unchanged);
 * [resolve] also recognises server administrators, read live from the database on every call.
 */
class ServerOwnerAuthorizer(
    private val central: DataSource,
    private val sessions: SessionService,
) : ServerActorResolver {
    private val admins = ServerAdminStore(central)

    fun serverOwnerRole(accessToken: String): String? {
        val user = sessions.validateSession(accessToken) ?: return null
        val isOwner = central.connection.use { connection ->
            connection.prepareStatement("SELECT 1 FROM server_owners WHERE user_id = ?").use { query ->
                query.setObject(1, UUID.fromString(user.id.toString()))
                query.executeQuery().use { rows -> rows.next() }
            }
        }
        return if (isOwner) user.postgresRole else null
    }

    override fun resolve(accessToken: String): ServerActorLookup {
        val user = sessions.validateSession(accessToken) ?: return ServerActorLookup.None
        val userId = UUID.fromString(user.id.toString())
        if (admins.isOwner(userId)) {
            return ServerActorLookup.Found(
                ServerActor(userId, user.postgresRole, ServerActorKind.Owner, emptySet(), true, emptySet(), user.postgresRole),
            )
        }
        val admin = admins.find(userId)?.takeIf { it.usable } ?: return ServerActorLookup.None
        val executor = admins.executorRole() ?: return ServerActorLookup.NoExecutor
        val actor = ServerActor(
            userId = userId,
            login = admin.login,
            kind = ServerActorKind.Admin,
            permissions = admin.permissions,
            allCompanies = admin.allCompanies,
            companies = admin.companies,
            executorRole = executor,
            mustChangePassword = admin.mustChangePassword,
            displayName = admin.displayName,
            email = admin.email,
        )
        return if (admin.mustChangePassword) ServerActorLookup.PasswordChangeRequired(actor) else ServerActorLookup.Found(actor)
    }
}

fun Connection.existsBySingleString(table: String, column: String, value: String): Boolean {
    prepareStatement("SELECT 1 FROM $table WHERE $column = ?").use { query ->
        query.setString(1, value)
        query.executeQuery().use { rows -> return rows.next() }
    }
}
