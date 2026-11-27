package com.ideasdeveloper.bms.extensions.wordpress

import com.ideasdeveloper.bms.modules.crm.CrmAudit
import com.ideasdeveloper.bms.modules.crm.CrmExtensionIds
import com.ideasdeveloper.bms.modules.crm.CrmHost
import com.ideasdeveloper.bms.modules.crm.CrmUser
import com.ideasdeveloper.bms.modules.crm.CrmValidationException
import com.ideasdeveloper.bms.modules.crm.CrmWordPressException
import com.ideasdeveloper.bms.modules.crm.CrmWordPressSettings
import com.ideasdeveloper.bms.modules.crm.WpAgent
import com.ideasdeveloper.bms.server.activity.ActivityActions
import com.ideasdeveloper.bms.server.activity.ActivityActor
import com.ideasdeveloper.bms.server.activity.ActivityChange
import com.ideasdeveloper.bms.server.i18n.tr
import java.security.SecureRandom
import java.sql.Connection
import java.util.UUID

/**
 * Persistencia de WordPress en las tablas propias de la extension: los ajustes del sitio (ext_wordpress__settings,
 * fila id = 1) y los agentes del sitio (ext_wordpress__agents). El secreto se guarda sellado, siempre por el facade del CRM.
 */
object WordPressSettingsStore {
    const val SettingsTable = "ext_wordpress__settings"
    const val AgentsTable = "ext_wordpress__agents"
    private val random = SecureRandom()

    // Lo que se ve mientras no hay fila (extension recien instalada o sin datos): todo apagado.
    private val Disabled = CrmWordPressSettings(enabled = false, siteUrl = null, secret = null, siteName = "", lastEventAt = null, lastError = "")

    // ---- Ajustes ----

    /** Los ajustes con el secreto ya abierto; sin fila, los de por defecto con WordPress apagado. */
    fun load(host: CrmHost, tenant: Connection): CrmWordPressSettings = tenant.prepareStatement(
        "SELECT enabled, site_url, secret, site_name, last_event_at, last_error FROM $SettingsTable WHERE id = 1"
    ).use { query ->
        query.executeQuery().use { rows ->
            if (!rows.next()) return@use Disabled
            CrmWordPressSettings(rows.getBoolean(1), rows.getString(2), host.openSecret(tenant, rows.getString(3)), rows.getString(4), rows.getTimestamp(5)?.toInstant(), rows.getString(6))
        }
    }

    /** Guarda el formulario de Integraciones (upsert: sin fila, la crea); el secreto no se toca aqui. */
    private val FormLabels = linkedMapOf("enabled" to "WordPress activado", "siteUrl" to "Direccion del sitio")

    fun saveForm(host: CrmHost, tenant: Connection, values: Map<String, String>, actor: ActivityActor) = CrmAudit.atomically(tenant) {
        val url = values["siteUrl"].orEmpty().trim().trimEnd('/').ifEmpty { null }
        if (url != null && !url.matches(Regex("^https://[A-Za-z0-9.-]+(:[0-9]{1,5})?(/[^\\s]*)?$"))) {
            throw CrmValidationException(tr("Escribe la direccion del sitio con https://, por ejemplo https://mi-inmobiliaria.com."))
        }
        host.extensionInactiveReason(tenant, CrmExtensionIds.WordPress)?.let { throw CrmValidationException(it) }
        if (url != null) {
            try {
                CrmWordPress.checkSite(url)
            } catch (failure: CrmWordPressException) {
                throw CrmValidationException(failure.message.orEmpty())
            }
        }
        val enabled = values["enabled"] == "true"
        val secret = load(host, tenant).secret
        if (enabled && (url == null || secret == null)) throw CrmValidationException(tr("Para activar WordPress escribe la direccion del sitio y genera el secreto."))
        val before = load(host, tenant)
        tenant.prepareStatement(
            """
            INSERT INTO $SettingsTable (id, enabled, site_url, updated_at) VALUES (1, ?, ?, CURRENT_TIMESTAMP)
            ON CONFLICT (id) DO UPDATE SET enabled = EXCLUDED.enabled, site_url = EXCLUDED.site_url, updated_at = CURRENT_TIMESTAMP
            """.trimIndent()
        ).use { update ->
            update.setBoolean(1, enabled)
            update.setString(2, url)
            update.executeUpdate()
        }
        CrmAudit.settingsChanged(
            tenant, actor, "WordPress", "Cambio los ajustes de WordPress.",
            mapOf("enabled" to before.enabled, "siteUrl" to before.siteUrl), mapOf("enabled" to enabled, "siteUrl" to url), FormLabels,
        )
    }

    /** Crea un secreto nuevo (48 letras y numeros), lo guarda sellado y lo devuelve para pegarlo en el plugin. */
    fun newSecret(host: CrmHost, tenant: Connection, actor: ActivityActor): String = CrmAudit.atomically(tenant) {
        val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789"
        val secret = (1..48).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("")
        tenant.prepareStatement(
            """
            INSERT INTO $SettingsTable (id, secret, updated_at) VALUES (1, ?, CURRENT_TIMESTAMP)
            ON CONFLICT (id) DO UPDATE SET secret = EXCLUDED.secret, updated_at = CURRENT_TIMESTAMP
            """.trimIndent()
        ).use { it.setString(1, host.sealSecret(tenant, secret)); it.executeUpdate() }
        // The secret itself is never stored in the log: only that it was renewed.
        CrmAudit.record(
            tenant, actor, CrmAudit.Settings, "WordPress", "WordPress", ActivityActions.SettingsChanged, "Genero un secreto nuevo para el plugin de WordPress.",
            listOf(ActivityChange.secret("secret", "Secreto del plugin")),
        )
        secret
    }

    /** Anota el ultimo evento del sitio: su nombre (si lo informo) y el error (vacio = sin error). */
    fun record(tenant: Connection, siteName: String?, error: String) {
        tenant.prepareStatement(
            "UPDATE $SettingsTable SET last_event_at = CURRENT_TIMESTAMP, site_name = COALESCE(?, site_name), last_error = ? WHERE id = 1"
        ).use { update ->
            update.setString(1, siteName)
            update.setString(2, error.take(500))
            update.executeUpdate()
        }
    }

    fun recordError(tenant: Connection, error: String) = record(tenant, null, error)

    // ---- Agentes del sitio ----

    /** Guarda los agentes del sitio y vincula a los nuevos con el usuario de BMS que tiene su mismo correo; devuelve cuantos recibio. */
    fun syncAgents(tenant: Connection, agents: List<WpAgent>, users: List<CrmUser>, userEmails: Map<UUID, String>, actor: ActivityActor): Int = CrmAudit.atomically(tenant) {
        val known = agents(tenant).associate { it.wpUserId to it.userId }
        agents.forEach { agent ->
            if (agent.wpUserId <= 0) return@forEach
            val match = users.firstOrNull { user -> userEmails[user.id]?.equals(agent.email, ignoreCase = true) == true }?.id
            tenant.prepareStatement(
                """
                INSERT INTO $AgentsTable (wp_user_id, name, email, user_id) VALUES (?, ?, ?, ?)
                ON CONFLICT (wp_user_id) DO UPDATE SET name = EXCLUDED.name, email = EXCLUDED.email,
                    user_id = COALESCE($AgentsTable.user_id, EXCLUDED.user_id), updated_at = CURRENT_TIMESTAMP
                """.trimIndent()
            ).use { upsert ->
                upsert.setLong(1, agent.wpUserId)
                upsert.setString(2, agent.name.take(200))
                upsert.setString(3, agent.email.take(254))
                upsert.setObject(4, match)
                upsert.executeUpdate()
            }
        }
        val linked = agents.count { agent -> userEmails.isNotEmpty() && known[agent.wpUserId] == null && users.any { userEmails[it.id]?.equals(agent.email, ignoreCase = true) == true } }
        CrmAudit.record(
            tenant, actor, "wp_agent", null, "Agentes del sitio", ActivityActions.Updated, "Sincronizo ${agents.size} agente(s) del sitio de WordPress.",
            listOf(
                ActivityChange("agents", "Agentes leidos", null, agents.joinToString(", ") { it.name }),
                ActivityChange("linked", "Vinculados a un usuario por correo", null, linked.toString()),
            ),
        )
        agents.size
    }

    fun agents(tenant: Connection): List<WpAgent> =
        tenant.prepareStatement("SELECT wp_user_id, name, email, user_id FROM $AgentsTable ORDER BY name").use { query ->
            query.executeQuery().use { rows -> buildList { while (rows.next()) add(WpAgent(rows.getLong(1), rows.getString(2), rows.getString(3), rows.getObject(4, UUID::class.java))) } }
        }

    fun mapAgent(tenant: Connection, wpUserId: Long, userId: UUID?, actor: ActivityActor) = CrmAudit.atomically(tenant) {
        fun userName(id: UUID?): String? = id?.let {
            tenant.prepareStatement("SELECT postgres_role FROM application_users WHERE id = ?").use { query ->
                query.setObject(1, it)
                query.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else it.toString() }
            }
        }
        val before = agents(tenant).firstOrNull { it.wpUserId == wpUserId }
        tenant.prepareStatement("UPDATE $AgentsTable SET user_id = ?, updated_at = CURRENT_TIMESTAMP WHERE wp_user_id = ?").use { update ->
            update.setObject(1, userId)
            update.setLong(2, wpUserId)
            update.executeUpdate()
        }
        if (before != null && before.userId != userId) {
            CrmAudit.record(
                tenant, actor, "wp_agent", wpUserId.toString(), before.name, ActivityActions.Updated, "Vinculo al agente del sitio ${before.name} con un usuario de BMS.",
                listOf(ActivityChange.of("user", "Usuario vinculado", userName(before.userId), userName(userId))),
            )
        }
    }

    /** El usuario de BMS del agente del sitio; los agentes vistos por primera vez se anotan para poder vincularlos en Integraciones. */
    fun userForAgent(tenant: Connection, wpUserId: Long?, name: String, email: String): UUID? {
        if (wpUserId == null || wpUserId <= 0) return null
        // The agent row and its log row are saved together.
        return CrmAudit.atomically(tenant) {
            tenant.prepareStatement(
                """
                INSERT INTO $AgentsTable (wp_user_id, name, email) VALUES (?, ?, ?)
                ON CONFLICT (wp_user_id) DO UPDATE SET name = EXCLUDED.name, email = EXCLUDED.email
                RETURNING user_id, (xmax = 0)
                """.trimIndent()
            ).use { upsert ->
                upsert.setLong(1, wpUserId)
                upsert.setString(2, name.take(200))
                upsert.setString(3, email.take(254))
                upsert.executeQuery().use { rows ->
                    if (!rows.next()) return@use null
                    // A site agent seen for the first time is kept: the website sent it.
                    if (rows.getBoolean(2)) {
                        CrmAudit.record(
                            tenant, ActivityActor.integration("Sitio web"), "wp_agent", wpUserId.toString(), name.take(200), ActivityActions.Created,
                            "Registro al agente del sitio ${name.take(200)} (visto por primera vez en un evento del sitio).",
                            listOf(ActivityChange("name", "Nombre", null, name.take(200)), ActivityChange("email", "Correo", null, email.take(254))),
                        )
                    }
                    rows.getObject(1, UUID::class.java)
                }
            }
        }
    }
}
