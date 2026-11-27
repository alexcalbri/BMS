package com.ideasdeveloper.bms.modules.hotspot

import com.ideasdeveloper.bms.server.activity.ActivityActions
import com.ideasdeveloper.bms.server.activity.ActivityActor
import com.ideasdeveloper.bms.server.activity.ActivityChange
import com.ideasdeveloper.bms.server.activity.ActivityChanges
import com.ideasdeveloper.bms.server.activity.ActivityEntry
import com.ideasdeveloper.bms.server.activity.ActivityLog
import com.ideasdeveloper.bms.server.modules.CustomerSql
import java.sql.Connection
import java.time.LocalDate
import java.util.UUID

/**
 * Activity log rows of the hotspot (ACTIVITY_LOG.md §4). Every write made by staff or by the
 * configuration goes through here, in the same transaction as the change: the row is written before
 * the commit, and a delete writes its row before the record goes. Stored text is Spanish, without tr.
 *
 * Not logged, on purpose: guest traffic (registrations, codes, sessions that end on their own, the
 * network guard's disconnects, roaming) and the pass balance a guest spends. Those are the access
 * history the hotspot already keeps (hotspot_accesses, hotspot_registrations).
 */
object HotspotActivity {
    const val ModuleId = HotspotModule.id

    // Settings of the portal, by the field HotspotSettingsRepository reads. The UniFi keys are the
    // controller's own (see NetworkControllerProvider.recordConfiguration).
    val SettingsLabels: Map<String, String> = linkedMapOf(
        "domain" to "Dominio",
        "networkName" to "Nombre de la red",
        "portalTitle" to "Titulo del portal",
        "portalSubtitle" to "Subtitulo del portal",
        "logoUrl" to "Logo",
        "consentText" to "Texto de consentimiento",
        "termsUrl" to "Terminos",
        "privacyUrl" to "Privacidad",
        "footerText" to "Pie de pagina",
        "successUrl" to "Paginas al conectarse",
        "accessMinutes" to "Tiempo de acceso (minutos)",
        "dataLimitMb" to "Limite de datos (MB)",
        "rxKbps" to "Velocidad de descarga (kbps)",
        "txKbps" to "Velocidad de subida (kbps)",
        "reconnectMinutes" to "Tiempo para reconectar (minutos)",
        "codeTtlMinutes" to "Vigencia del codigo (minutos)",
        "emailSubject" to "Asunto del correo",
        "crmAccessEnabled" to "Acceso por CRM",
        "crmStageId" to "Etapa del CRM que permite el acceso",
        "crmScheduleMode" to "Programacion del CRM",
        "crmCheckIntervalMinutes" to "Intervalo de revision del CRM (minutos)",
        "crmCheckTime" to "Hora de revision del CRM",
    )

    private val ColorLabels = linkedMapOf("primary" to "Color principal", "secondary" to "Color secundario", "text" to "Color del texto")

    private val NetworkLabels = linkedMapOf(
        "ssid" to "Red (SSID)",
        "accessMode" to "Tipo de acceso",
        "accessSources" to "Quien puede conectarse",
        "accessMinutes" to "Tiempo de acceso",
        "reconnectMinutes" to "Tiempo para reconectar",
        "shared" to "Compartida con empresas vinculadas",
    )

    /** Runs [block] in one transaction of [connection]: its writes and their activity rows commit together or not at all. */
    fun <T> transaction(connection: Connection, block: () -> T): T {
        val autoCommit = connection.autoCommit
        connection.autoCommit = false
        try {
            val result = block()
            connection.commit()
            return result
        } catch (failure: Throwable) {
            connection.rollback()
            throw failure
        } finally {
            connection.autoCommit = autoCommit
        }
    }

    /** Who the log shows for the customers the guest portal creates. */
    private val PortalActor = ActivityActor.integration("Portal WiFi")

    private val GuestCustomerLabels = linkedMapOf("name" to "Nombre", "email" to "Correo", "phone" to "Telefono")

    /**
     * The customer of Clientes a guest of the portal is: an existing one (linked, untouched) or a new one of type hotspot, whose
     * creation is recorded in the activity log in the same transaction. The rest of the guest traffic is not logged.
     */
    fun findOrCreateGuestCustomer(connection: Connection, name: String, email: String, phone: String): UUID {
        connection.prepareStatement(
            "SELECT id FROM customers WHERE lower(primary_email) = lower(?) AND status <> 'archived' AND ${CustomerSql.Visible} ORDER BY created_at LIMIT 1"
        ).use { query ->
            query.setString(1, email)
            query.executeQuery().use { rows -> if (rows.next()) return rows.getObject(1, UUID::class.java) }
        }
        val id = UUID.randomUUID()
        // The portal calls this inside its own transaction; alone, it opens one so the row and the customer are saved together.
        val write = {
            connection.prepareStatement(
                """
                INSERT INTO customers (id, display_name, primary_email, primary_phone, status, customer_type)
                VALUES (?, ?, ?, ?, 'active', 'hotspot')
                """.trimIndent()
            ).use { insert ->
                insert.setObject(1, id)
                insert.setString(2, name)
                insert.setString(3, email)
                insert.setString(4, phone)
                insert.executeUpdate()
            }
            // The record is a Clientes one, so the row is Clientes' (the portal only says who made it).
            record(
                connection, PortalActor, "customer", id.toString(), name, ActivityActions.Created, "Registro al cliente $name desde el portal WiFi.",
                ActivityChanges.created(mapOf("name" to name, "email" to email, "phone" to phone), GuestCustomerLabels), module = "clientes",
            )
        }
        if (connection.autoCommit) transaction(connection) { write() } else write()
        return id
    }

    private fun record(connection: Connection, actor: ActivityActor, entityType: String, entityId: String?, label: String, action: String, summary: String, changes: List<ActivityChange> = emptyList(), module: String = ModuleId) {
        ActivityLog.record(connection, actor, ActivityEntry(module, entityType, entityId, label, action, summary, changes))
    }

    // ---- Settings (Configuracion, Integraciones de UniFi aparte) ----

    /** Saves the portal settings and records the fields that changed (see [settingsChanged]). */
    fun saveSettings(connection: Connection, actor: ActivityActor, before: HotspotSettings, after: HotspotSettings) {
        transaction(connection) {
            HotspotSettingsRepository.save(connection, after)
            settingsChanged(connection, actor, before, after)
        }
    }

    /** One row with the changed fields; then the network controller records its own keys (secrets masked). */
    fun settingsChanged(connection: Connection, actor: ActivityActor, before: HotspotSettings, after: HotspotSettings) {
        val changes = ActivityChanges.diff(settingValues(before), settingValues(after), SettingsLabels)
        if (changes.isNotEmpty()) {
            val count = if (changes.size == 1) "1 ajuste" else "${changes.size} ajustes"
            record(connection, actor, "configuracion", ModuleId, "Configuracion del hotspot", ActivityActions.SettingsChanged, "Cambio $count del hotspot.", changes)
        }
        HotspotIntegrations.controller(connection)?.let { controller ->
            HotspotModuleExtensions.providerFor(controller.id)?.recordConfiguration(connection, actor, before, after)
        }
    }

    private fun settingValues(settings: HotspotSettings): Map<String, Any?> = mapOf(
        "domain" to settings.domain,
        "networkName" to settings.networkName,
        "portalTitle" to settings.portalTitle,
        "portalSubtitle" to settings.portalSubtitle,
        "logoUrl" to settings.logoUrl,
        "consentText" to settings.consentText,
        "termsUrl" to settings.termsUrl,
        "privacyUrl" to settings.privacyUrl,
        "footerText" to settings.footerText,
        "successUrl" to settings.successUrl,
        "accessMinutes" to settings.accessMinutes,
        "dataLimitMb" to settings.dataLimitMb,
        "rxKbps" to settings.rxKbps,
        "txKbps" to settings.txKbps,
        "reconnectMinutes" to settings.reconnectMinutes,
        "codeTtlMinutes" to settings.codeTtlMinutes,
        "emailSubject" to settings.emailSubject,
        "crmAccessEnabled" to settings.crmAccessEnabled,
        "crmStageId" to settings.crmStageId,
        "crmScheduleMode" to settings.crmScheduleMode,
        "crmCheckIntervalMinutes" to settings.crmCheckIntervalMinutes,
        "crmCheckTime" to settings.crmCheckTime,
    )

    /** Saves the portal colors of Diseno; only a change is a row. An invalid color writes nothing. */
    fun saveColors(connection: Connection, actor: ActivityActor, primary: String?, secondary: String?, text: String?): PortalColors = transaction(connection) {
        val before = HotspotSettingsRepository.load(connection).portalColors
        val after = HotspotSettingsRepository.saveColors(connection, primary, secondary, text)
        val changes = ActivityChanges.diff(colorValues(before), colorValues(after), ColorLabels)
        if (changes.isNotEmpty()) record(connection, actor, "apariencia", "colores", "Colores del portal", ActivityActions.SettingsChanged, "Cambio los colores del portal.", changes)
        after
    }

    private fun colorValues(colors: PortalColors): Map<String, Any?> = mapOf("primary" to colors.primary, "secondary" to colors.secondary, "text" to colors.text)

    /** Saves the custom HTML of a portal view (or puts the BMS design back when blank). The HTML itself is not stored. */
    fun saveTemplate(connection: Connection, actor: ActivityActor, view: HotspotTemplates.View, html: String) {
        transaction(connection) {
            val before = HotspotTemplates.load(connection)[view.id]
            HotspotTemplates.save(connection, view, html)
            val after = html.takeIf { it.isNotBlank() }
            if (before != after) {
                val summary = if (after == null) "Devolvio ${view.label} al diseno de BMS." else "Cambio el HTML de ${view.label}."
                record(
                    connection, actor, "plantilla", view.id, view.label, ActivityActions.SettingsChanged, summary,
                    listOf(ActivityChange("html", "HTML", templateState(before), templateState(after))),
                )
            }
        }
    }

    // What the row says about the HTML: its size, never its content.
    private fun templateState(html: String?): String = if (html == null) "Diseno de BMS" else "HTML personalizado de ${html.length} caracteres"

    // ---- Networks ----

    private fun networkValues(network: HotspotNetwork): Map<String, Any?> = mapOf(
        "ssid" to network.ssid,
        "accessMode" to HotspotNetworks.modeLabel(network.accessMode),
        "accessSources" to network.accessSources.sorted(),
        "accessMinutes" to network.accessMinutes,
        "reconnectMinutes" to network.reconnectMinutes,
        "shared" to network.shared,
    )

    /** Creates or edits a network (a new one when [before] is null) and records it in the same transaction. */
    fun saveNetwork(connection: Connection, actor: ActivityActor, before: HotspotNetwork?, network: HotspotNetwork) {
        transaction(connection) {
            HotspotNetworks.save(connection, network)
            if (before == null) {
                record(
                    connection, actor, "red", network.id.toString(), network.ssid, ActivityActions.Created, "Creo la red ${network.ssid}.",
                    ActivityChanges.created(networkValues(network), NetworkLabels),
                )
            } else {
                val changes = ActivityChanges.diff(networkValues(before), networkValues(network), NetworkLabels)
                if (changes.isNotEmpty()) {
                    record(connection, actor, "red", network.id.toString(), network.ssid, ActivityActions.Updated, "Modifico la red ${network.ssid}.", changes)
                }
            }
        }
    }

    /** Deletes a network. Its row is written first, with the settings it had. False when it does not exist. */
    fun deleteNetwork(connection: Connection, actor: ActivityActor, id: UUID): Boolean = transaction(connection) {
        val network = HotspotNetworks.find(connection, id) ?: return@transaction false
        record(
            connection, actor, "red", id.toString(), network.ssid, ActivityActions.Deleted, "Elimino la red ${network.ssid}.",
            ActivityChanges.deleted(networkValues(network), NetworkLabels),
        )
        HotspotNetworks.delete(connection, id)
    }

    // ---- Integrations ----

    /**
     * Turns an integration on or off and records it when the set of active integrations changes
     * (enabling a network controller turns the other one off, and the row says so).
     */
    fun integrationChanged(connection: Connection, actor: ActivityActor, integration: HotspotIntegration, enabled: Boolean, before: Set<String>, after: Set<String>, titles: Map<String, String>) {
        if (before == after) return
        fun names(ids: Set<String>) = ids.map { titles[it] ?: it }.sorted().joinToString(", ").ifEmpty { "Ninguna" }
        val (action, summary) = if (enabled) {
            ActivityActions.Enabled to "Activo la integracion ${integration.title}."
        } else {
            ActivityActions.Disabled to "Desactivo la integracion ${integration.title}."
        }
        record(
            connection, actor, "integracion", integration.id, integration.title, action, summary,
            listOf(ActivityChange("integraciones_activas", "Integraciones activas", names(before), names(after))),
        )
    }

    // ---- User codes and grants ----

    /** Revokes or restores a user's WiFi access. Returns whether the row changed. */
    fun setUserAccess(connection: Connection, actor: ActivityActor, user: HotspotUser, enabled: Boolean): Boolean = transaction(connection) {
        val changed = HotspotUserCodes.setAccessEnabled(connection, user.id, enabled)
        if (changed) {
            val (summary, old, new) = if (enabled) {
                Triple("Restauro el acceso WiFi de ${user.name}.", "Revocado", "Activo")
            } else {
                Triple("Revoco el acceso WiFi de ${user.name}.", "Activo", "Revocado")
            }
            record(
                connection, actor, "acceso_wifi", user.id.toString(), user.name, ActivityActions.Updated, summary,
                listOf(ActivityChange("acceso_wifi", "Acceso WiFi", old, new)),
            )
        }
        changed
    }

    /** Lets a server-company user join a user-code network of another company. False when they already could. */
    fun addGrant(connection: Connection, actor: ActivityActor, companyCode: String, ssid: String, userId: UUID, userLabel: String): Boolean = transaction(connection) {
        if (HotspotUserCodes.hasGrant(connection, companyCode, ssid, userId)) return@transaction false
        HotspotUserCodes.addGrant(connection, companyCode, ssid, userId)
        val network = "$companyCode · $ssid"
        record(
            connection, actor, "acceso_red_otra_empresa", null, network, ActivityActions.Created,
            "Doy acceso a $userLabel a la red $ssid de $companyCode.",
            ActivityChanges.created(mapOf("user" to userLabel, "network" to network), linkedMapOf("user" to "Usuario", "network" to "Red")),
        )
        true
    }

    /** Removes a grant; its row is written before the delete. False when it does not exist. */
    fun deleteGrant(connection: Connection, actor: ActivityActor, grantId: UUID): Boolean = transaction(connection) {
        val grant = HotspotUserCodes.grants(connection).firstOrNull { it.id == grantId } ?: return@transaction false
        val network = "${grant.companyCode} · ${grant.ssid}"
        record(
            connection, actor, "acceso_red_otra_empresa", null, network, ActivityActions.Deleted,
            "Quito un acceso a la red ${grant.ssid} de ${grant.companyCode}.",
            ActivityChanges.deleted(mapOf("network" to network), linkedMapOf("network" to "Red")),
        )
        HotspotUserCodes.deleteGrant(connection, grantId)
    }

    // ---- Exports and jobs ----

    /** The visitors' report downloaded as CSV (the one read that is logged). */
    fun exportReport(connection: Connection, actor: ActivityActor, from: LocalDate, to: LocalDate) {
        record(
            connection, actor, "reporte", null, "Reporte de visitantes", ActivityActions.Exported,
            "Exporto el reporte de visitantes del $from al $to a CSV.",
            ActivityChanges.created(mapOf("from" to from.toString(), "to" to to.toString()), linkedMapOf("from" to "Desde", "to" to "Hasta")),
        )
    }

    /** Who acts when the CRM check changes the hotspot's code sessions by itself. */
    fun crmCheckActor(): ActivityActor = ActivityActor.job("Control de acceso del CRM")

    /** Code sessions whose expiry the CRM check moved (counts only, no guest data). */
    fun sessionsAdjusted(connection: Connection, actor: ActivityActor, count: Int) {
        val noun = if (count == 1) "sesion" else "sesiones"
        record(
            connection, actor, "acceso_codigo_cliente", null, "Sesiones de codigo de cliente", ActivityActions.Updated,
            "Ajusto el vencimiento de $count $noun de codigo de cliente segun el CRM.",
        )
    }

    /** The one-off migration of the hotspot's own SMTP into the company's mail settings (a job). */
    fun mailMigrated(connection: Connection, summary: String) {
        record(
            connection, ActivityActor.job("Migracion de correo de Hotspot"), "correo", null, "Correo de Hotspot",
            ActivityActions.SettingsChanged, summary,
        )
    }
}
