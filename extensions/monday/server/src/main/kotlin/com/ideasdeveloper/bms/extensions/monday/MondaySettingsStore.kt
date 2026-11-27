package com.ideasdeveloper.bms.extensions.monday

import com.ideasdeveloper.bms.server.activity.ActivityActor
import com.ideasdeveloper.bms.modules.crm.CrmAudit
import com.ideasdeveloper.bms.modules.crm.CrmHost
import java.sql.Connection

/** Persistencia del token de monday en la tabla propia de la extension (ext_monday__settings, fila id = 1). */
object MondaySettingsStore {
    const val Table = "ext_monday__settings"

    /** El token ya abierto, o null si no hay (o no se puede leer: no debe enviarse a monday). */
    fun loadToken(host: CrmHost, tenant: Connection): String? =
        tenant.prepareStatement("SELECT token FROM $Table WHERE id = 1").use { query ->
            query.executeQuery().use { rows ->
                if (rows.next()) host.openSecret(tenant, rows.getString(1))?.takeIf { it.isNotBlank() && !host.isSecretUnreadable(it) } else null
            }
        }

    /** Guarda el token sellado (upsert: sin fila, la crea); null o vacio lo borra. */
    fun saveToken(host: CrmHost, tenant: Connection, token: String?, actor: ActivityActor) = CrmAudit.atomically(tenant) {
        val before = loadToken(host, tenant)
        tenant.prepareStatement(
            """
            INSERT INTO $Table (id, token) VALUES (1, ?)
            ON CONFLICT (id) DO UPDATE SET token = EXCLUDED.token, updated_at = CURRENT_TIMESTAMP
            """.trimIndent()
        ).use { update ->
            update.setString(1, host.sealSecret(tenant, token?.trim()?.takeIf { it.isNotEmpty() }))
            update.executeUpdate()
        }
        // Only that the token changed (or was removed), never its value.
        val after = token?.trim()?.takeIf { it.isNotEmpty() }
        CrmAudit.settingsChanged(
            tenant, actor, "monday", if (after == null) "Borro el token de la API de monday." else "Guardo el token de la API de monday.",
            mapOf("token" to before), mapOf("token" to after), mapOf("token" to "Token de la API de monday"), secrets = setOf("token"),
        )
    }
}
