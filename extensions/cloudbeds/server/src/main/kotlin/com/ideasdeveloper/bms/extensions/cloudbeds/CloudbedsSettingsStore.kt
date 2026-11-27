package com.ideasdeveloper.bms.extensions.cloudbeds

import com.ideasdeveloper.bms.server.activity.ActivityActor
import com.ideasdeveloper.bms.modules.crm.CrmAudit
import com.ideasdeveloper.bms.modules.crm.CrmCloudbedsSettings
import com.ideasdeveloper.bms.modules.crm.CrmCloudbedsStore
import com.ideasdeveloper.bms.modules.crm.CrmHost
import com.ideasdeveloper.bms.modules.crm.CrmTemplate
import com.ideasdeveloper.bms.modules.crm.CrmValidationException
import com.ideasdeveloper.bms.server.i18n.tr
import java.sql.Connection
import java.util.UUID

/** Persistencia de los ajustes de Cloudbeds en la tabla propia de la extension (ext_cloudbeds__settings, fila id = 1). */
object CloudbedsSettingsStore {
    const val Table = "ext_cloudbeds__settings"

    // Lo que se ve mientras no hay fila (extension recien instalada o sin datos): todo apagado.
    private val Disabled = CrmCloudbedsSettings(
        enabled = false, apiKey = null, propertyId = null, paymentMethod = "cash", welcomeEnabled = false, welcomeTemplateId = null,
        welcomeDaysBefore = 1, welcomeHour = 9, archiveOnCheckout = true, lastError = "", defaultCountry = "",
    )

    /** Los ajustes con la API key ya abierta (lista para usar); sin fila, los de por defecto con Cloudbeds apagado. */
    fun load(host: CrmHost, tenant: Connection): CrmCloudbedsSettings = tenant.prepareStatement(
        """
        SELECT enabled, api_key, property_id, payment_method, welcome_enabled, welcome_template_id, welcome_days_before,
               welcome_hour, archive_on_checkout, last_error, default_country
        FROM $Table WHERE id = 1
        """.trimIndent()
    ).use { query ->
        query.executeQuery().use { rows ->
            if (!rows.next()) return@use Disabled
            CrmCloudbedsSettings(
                rows.getBoolean(1), host.openSecret(tenant, rows.getString(2)), rows.getString(3), rows.getString(4), rows.getBoolean(5),
                rows.getObject(6, UUID::class.java), rows.getInt(7), rows.getInt(8), rows.getBoolean(9), rows.getString(10), rows.getString(11),
            )
        }
    }

    private val FormLabels = linkedMapOf(
        "enabled" to "Cloudbeds activado", "apiKey" to "API key", "propertyId" to "ID de la propiedad", "paymentMethod" to "Metodo de pago",
        "welcomeEnabled" to "Correo de bienvenida", "welcomeTemplate" to "Plantilla de bienvenida", "welcomeDaysBefore" to "Dias antes del check-in",
        "welcomeHour" to "Hora de envio", "archiveOnCheckout" to "Archivar al hacer check-out", "defaultCountry" to "Pais por defecto del huesped",
    )

    private fun values(s: CrmCloudbedsSettings, templates: List<CrmTemplate>) = mapOf(
        "enabled" to s.enabled, "apiKey" to s.apiKey, "propertyId" to s.propertyId, "paymentMethod" to s.paymentMethod, "welcomeEnabled" to s.welcomeEnabled,
        "welcomeTemplate" to templates.firstOrNull { it.id == s.welcomeTemplateId }?.name, "welcomeDaysBefore" to s.welcomeDaysBefore, "welcomeHour" to s.welcomeHour,
        "archiveOnCheckout" to s.archiveOnCheckout, "defaultCountry" to s.defaultCountry.ifEmpty { null },
    )

    fun saveForm(host: CrmHost, tenant: Connection, values: Map<String, String>, templates: List<CrmTemplate>, actor: ActivityActor) = CrmAudit.atomically(tenant) {
        host.cloudbedsInactiveReason(tenant)?.let { throw CrmValidationException(it) }
        val current = load(host, tenant)
        val key = values["apiKey"]?.trim()?.takeIf { it.isNotEmpty() && !host.isSecretPlaceholder(it) } ?: current.apiKey
        val property = values["propertyId"].orEmpty().trim().ifEmpty { null }
        if (property != null && !property.matches(Regex("^[0-9]{1,30}$"))) throw CrmValidationException(tr("El ID de la propiedad solo lleva numeros."))
        val country = values["defaultCountry"].orEmpty().trim().uppercase()
        if (!country.matches(CrmCloudbedsStore.CountryCode) && country.isNotEmpty()) throw CrmValidationException(tr("El pais por defecto es un codigo de 2 letras, por ejemplo US o NI."))
        val enabled = values["enabled"] == "true"
        if (enabled && key.isNullOrBlank()) throw CrmValidationException(tr("Para activar Cloudbeds escribe la API key."))
        val payment = CrmCloudbedsStore.paymentLabels.entries.firstOrNull { it.value == values["paymentMethod"] }?.key ?: current.paymentMethod
        val template = values["welcomeTemplate"]?.let { name -> templates.firstOrNull { it.name == name }?.id }
        val welcome = values["welcomeEnabled"] == "true"
        if (welcome && template == null) throw CrmValidationException(tr("Elige la plantilla del correo de bienvenida (creala en Plantillas)."))
        val days = values["welcomeDaysBefore"].orEmpty().trim().ifEmpty { "1" }.toIntOrNull()?.takeIf { it in 0..60 }
            ?: throw CrmValidationException(tr("Los dias antes del check-in deben ser un numero entre 0 y 60."))
        val hour = values["welcomeHour"].orEmpty().trim().ifEmpty { "9" }.toIntOrNull()?.takeIf { it in 0..23 }
            ?: throw CrmValidationException(tr("La hora de envio debe ser un numero entre 0 y 23."))
        // Upsert: la fila la crea onEnable, pero si faltara se crea aqui en vez de perder el guardado.
        tenant.prepareStatement(
            """
            INSERT INTO $Table (id, enabled, api_key, property_id, payment_method, welcome_enabled, welcome_template_id,
                welcome_days_before, welcome_hour, archive_on_checkout, default_country, updated_at)
            VALUES (1, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
            ON CONFLICT (id) DO UPDATE SET enabled = EXCLUDED.enabled, api_key = EXCLUDED.api_key, property_id = EXCLUDED.property_id,
                payment_method = EXCLUDED.payment_method, welcome_enabled = EXCLUDED.welcome_enabled,
                welcome_template_id = EXCLUDED.welcome_template_id, welcome_days_before = EXCLUDED.welcome_days_before,
                welcome_hour = EXCLUDED.welcome_hour, archive_on_checkout = EXCLUDED.archive_on_checkout,
                default_country = EXCLUDED.default_country, updated_at = CURRENT_TIMESTAMP
            """.trimIndent()
        ).use { update ->
            update.setBoolean(1, enabled)
            update.setString(2, host.sealSecret(tenant, key))
            update.setString(3, property)
            update.setString(4, payment)
            update.setBoolean(5, welcome)
            update.setObject(6, template)
            update.setInt(7, days)
            update.setInt(8, hour)
            update.setBoolean(9, values["archiveOnCheckout"] == "true")
            update.setString(10, country)
            update.executeUpdate()
        }
        CrmAudit.settingsChanged(
            tenant, actor, "Cloudbeds", "Cambio los ajustes de Cloudbeds.", values(current, templates), values(load(host, tenant), templates), FormLabels, secrets = setOf("apiKey"),
        )
    }

    fun recordError(host: CrmHost, tenant: Connection, error: String) {
        tenant.prepareStatement("UPDATE $Table SET last_error = ?, last_sync = CURRENT_TIMESTAMP WHERE id = 1").use {
            it.setString(1, error.take(500))
            it.executeUpdate()
        }
    }

    /** Turns the welcome email of Cloudbeds on or off (an automation of the CRM can replace it). */
    fun setWelcomeEnabled(host: CrmHost, tenant: Connection, enabled: Boolean, actor: ActivityActor) = CrmAudit.atomically(tenant) {
        val before = load(host, tenant).welcomeEnabled
        tenant.prepareStatement("UPDATE $Table SET welcome_enabled = ?, updated_at = CURRENT_TIMESTAMP WHERE id = 1").use { update ->
            update.setBoolean(1, enabled)
            update.executeUpdate()
        }
        CrmAudit.settingsChanged(
            tenant, actor, "Cloudbeds", if (enabled) "Activo el correo de bienvenida de Cloudbeds." else "Apago el correo de bienvenida de Cloudbeds.",
            mapOf("welcomeEnabled" to before), mapOf("welcomeEnabled" to enabled), mapOf("welcomeEnabled" to "Correo de bienvenida"),
        )
    }
}
