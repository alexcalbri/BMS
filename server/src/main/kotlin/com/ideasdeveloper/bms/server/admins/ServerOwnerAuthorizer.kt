package com.ideasdeveloper.bms.server.admins

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
        return liveAdmin(admins, userId)
    }

    companion object {
        /**
         * The server actor of central user [userId] read now, without a session (an AI agent of a
         * server owner or administrator, re-checked on every call): None when no longer an active owner
         * or administrator.
         */
        fun liveActor(admins: ServerAdminStore, central: DataSource, userId: UUID): ServerActorLookup {
            if (admins.isOwner(userId)) {
                val login = central.connection.use { connection ->
                    connection.prepareStatement("SELECT postgres_role FROM application_users WHERE id = ?").use { query ->
                        query.setObject(1, userId)
                        query.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
                    }
                } ?: return ServerActorLookup.None
                return ServerActorLookup.Found(ServerActor(userId, login, ServerActorKind.Owner, emptySet(), true, emptySet(), login))
            }
            return liveAdmin(admins, userId)
        }

        private fun liveAdmin(admins: ServerAdminStore, userId: UUID): ServerActorLookup {
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
        return when {
            admin.mustChangePassword -> ServerActorLookup.PasswordChangeRequired(actor)
            // The owner requires two-step verification and this administrator has not turned it on yet.
            com.ideasdeveloper.bms.server.auth.twofactor.TwoFactorPolicy.adminSetupPending(admins.central, userId) ->
                ServerActorLookup.PasswordChangeRequired(actor, twoFactorSetup = true)
            else -> ServerActorLookup.Found(actor)
        }
        }
    }
}

fun Connection.existsBySingleString(table: String, column: String, value: String): Boolean {
    prepareStatement("SELECT 1 FROM $table WHERE $column = ?").use { query ->
        query.setString(1, value)
        query.executeQuery().use { rows -> return rows.next() }
    }
}
