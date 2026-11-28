package com.ideasdeveloper.bms.server.assistant

import com.ideasdeveloper.bms.AssistantIds
import com.ideasdeveloper.bms.server.activity.ActivityActions
import com.ideasdeveloper.bms.server.activity.ActivityActor
import com.ideasdeveloper.bms.server.activity.ActivityChange
import com.ideasdeveloper.bms.server.activity.ActivityLog
import com.ideasdeveloper.bms.server.activity.CoreActivity
import com.ideasdeveloper.bms.server.admins.AuditActor
import com.ideasdeveloper.bms.server.company.application.ProvisioningAudit
import com.ideasdeveloper.bms.server.company.application.auditDetails
import com.ideasdeveloper.bms.server.i18n.tr
import com.ideasdeveloper.bms.server.security.SecretBox
import java.sql.Connection
import javax.sql.DataSource

/** A settings request that cannot be applied; [code] is the API error code, [message] for people. */
class AssistantSettingsException(val code: String, message: String, val status: Int = 400) : Exception(message)

/** The server owner's settings (server_settings, no migration; GLOBAL_AGENT.md "Storage"). */
data class AssistantServerSettings(
    val enabled: Boolean = false,
    val model: String = AssistantIds.DefaultModel,
    val effort: String = AssistantIds.DefaultEffort,
    // 0 = unlimited.
    val defaultMonthlyTokens: Long = DefaultMonthlyTokens,
    // The limit also applies to companies that use their own key.
    val limitOwnKeyCompanies: Boolean = false,
    val retentionDays: Int = DefaultRetentionDays,
    val keySet: Boolean = false,
    val keyHint: String? = null,
) {
    companion object {
        const val DefaultMonthlyTokens = 2_000_000L
        const val DefaultRetentionDays = 90
    }
}

/** What the server says about one company: allowed (default yes) and its own monthly limit (null = the default). */
data class AssistantAllowance(val allowed: Boolean = true, val monthlyTokens: Long? = null)

/** The disclosure every settings screen shows before enabling (GLOBAL_AGENT.md §5). */
fun assistantDisclosure(): String = tr("Los datos que el asistente consulte se envian a Anthropic para generar la respuesta.")

/**
 * The disclosure accepted when the server owner (or an administrator) enables the assistant at server level: the common one
 * plus what only the server workspace adds (the audit and the activity of the companies the person may see).
 */
fun serverAssistantDisclosure(): String =
    assistantDisclosure() + " " + tr("El asistente del espacio del servidor puede leer la auditoria y la actividad de las empresas que la persona puede ver.")

/** Checks an API key's shape (never its value against anything) and builds the hint shown instead of it. */
object AssistantKeys {
    private val pattern = Regex("^sk-ant-[A-Za-z0-9_-]{16,300}$")

    fun validate(value: String): String {
        val key = value.trim()
        if (!pattern.matches(key)) throw AssistantSettingsException("API_KEY_INVALID", tr("La clave de API no tiene el formato de una clave de Anthropic (sk-ant-...)."))
        return key
    }

    fun hint(key: String): String = "sk-ant-…" + key.takeLast(4)
}

/**
 * Server-level settings in the control database's server_settings. The API key is sealed with BMS_MASTER_KEY and the
 * control database's data key (SecretBox on the central connection) and only its hint is ever returned. Every change
 * is audited in provisioning_audit_log in the same transaction, never with the key.
 */
class AssistantServerConfig(private val central: DataSource, private val secrets: SecretBox) {
    val secretsAvailable: Boolean get() = secrets.available

    private fun values(connection: Connection): Map<String, String> =
        connection.prepareStatement("SELECT setting_key, setting_value FROM server_settings WHERE setting_key LIKE 'assistant.%'").use { query ->
            query.executeQuery().use { rows -> buildMap { while (rows.next()) put(rows.getString(1), rows.getString(2)) } }
        }

    fun read(): AssistantServerSettings = central.connection.use { settings(values(it)) }

    private fun settings(values: Map<String, String>) = AssistantServerSettings(
        enabled = values[Enabled] == "true",
        model = values[Model]?.takeIf(AssistantModels::allowed) ?: AssistantIds.DefaultModel,
        effort = values[Effort]?.takeIf { it in AssistantIds.Efforts } ?: AssistantIds.DefaultEffort,
        defaultMonthlyTokens = values[DefaultLimit]?.toLongOrNull()?.takeIf { it >= 0 } ?: AssistantServerSettings.DefaultMonthlyTokens,
        limitOwnKeyCompanies = values[LimitOwnKey] == "true",
        retentionDays = values[Retention]?.toIntOrNull()?.takeIf { it in 1..MaxRetentionDays } ?: AssistantServerSettings.DefaultRetentionDays,
        keySet = !values[ApiKey].isNullOrEmpty(),
        keyHint = values[ApiKeyHint],
    )

    /** The server's API key in clear, for one request; null when none is set. Throws when it cannot be opened. */
    fun apiKey(): String? = central.connection.use { connection ->
        val sealed = values(connection)[ApiKey]?.takeIf { it.isNotEmpty() } ?: return null
        secrets.open(connection, sealed)
    }

    fun allowance(companyCode: String): AssistantAllowance = central.connection.use { allowance(values(it), companyCode) }

    fun allowances(): Map<String, AssistantAllowance> = central.connection.use { connection ->
        val values = values(connection)
        values.keys.mapNotNull { key -> key.removePrefix(CompanyPrefix).takeIf { key.startsWith(CompanyPrefix) }?.substringBeforeLast('.') }
            .distinct().associateWith { allowance(values, it) }
    }

    private fun allowance(values: Map<String, String>, code: String) = AssistantAllowance(
        allowed = values["$CompanyPrefix$code.allowed"] != "false",
        monthlyTokens = values["$CompanyPrefix$code.monthly_tokens"]?.toLongOrNull()?.takeIf { it >= 0 },
    )

    /** The monthly limit of a company (null = unlimited). */
    fun monthlyLimit(settings: AssistantServerSettings, allowance: AssistantAllowance): Long? =
        (allowance.monthlyTokens ?: settings.defaultMonthlyTokens).takeIf { it > 0 }

    fun update(
        enabled: Boolean?, acceptDisclosure: Boolean?, model: String?, effort: String?, defaultMonthlyTokens: Long?,
        limitOwnKeyCompanies: Boolean?, retentionDays: Int?, actor: AuditActor,
    ): AssistantServerSettings {
        if (model != null && !AssistantModels.allowed(model)) throw AssistantSettingsException("MODEL_INVALID", tr("Ese modelo no esta permitido."))
        if (effort != null && effort !in AssistantIds.Efforts) throw AssistantSettingsException("EFFORT_INVALID", tr("Ese nivel de esfuerzo no existe."))
        if (defaultMonthlyTokens != null && (defaultMonthlyTokens < 0 || defaultMonthlyTokens > MaxMonthlyTokens)) {
            throw AssistantSettingsException("VALUE_INVALID", tr("El limite mensual debe estar entre 0 (sin limite) y 100000000000 tokens."))
        }
        if (retentionDays != null && retentionDays !in 1..MaxRetentionDays) throw AssistantSettingsException("VALUE_INVALID", tr("La conservacion debe estar entre 1 y 3650 dias."))
        return transaction { connection ->
            val before = settings(values(connection))
            if (enabled == true && !before.enabled && acceptDisclosure != true) {
                throw AssistantSettingsException("DISCLOSURE_REQUIRED", tr("Para activar el asistente acepta el aviso: los datos que consulte se envian a Anthropic."))
            }
            enabled?.let { write(connection, Enabled, it.toString()) }
            model?.let { write(connection, Model, it) }
            effort?.let { write(connection, Effort, it) }
            defaultMonthlyTokens?.let { write(connection, DefaultLimit, it.toString()) }
            limitOwnKeyCompanies?.let { write(connection, LimitOwnKey, it.toString()) }
            retentionDays?.let { write(connection, Retention, it.toString()) }
            val after = settings(values(connection))
            val changed = listOf(
                Triple("enabled", before.enabled, after.enabled), Triple("model", before.model, after.model),
                Triple("effort", before.effort, after.effort), Triple("defaultMonthlyTokens", before.defaultMonthlyTokens, after.defaultMonthlyTokens),
                Triple("limitOwnKeyCompanies", before.limitOwnKeyCompanies, after.limitOwnKeyCompanies), Triple("retentionDays", before.retentionDays, after.retentionDays),
            ).filter { it.second != it.third }
            if (changed.isNotEmpty()) {
                ProvisioningAudit.insert(
                    connection, actor, "assistant.settings_changed", null, null,
                    auditDetails(*changed.flatMap { (field, from, to) -> listOf("$field.from" to from, "$field.to" to to) }.toTypedArray()),
                )
            }
            after
        }
    }

    fun setKey(apiKey: String, actor: AuditActor): AssistantServerSettings {
        val key = AssistantKeys.validate(apiKey)
        if (!secrets.available) throw AssistantSettingsException("SECRETS_UNAVAILABLE", tr("El servidor no tiene BMS_MASTER_KEY: no puede guardar la clave."), 503)
        return transaction { connection ->
            write(connection, ApiKey, secrets.seal(connection, key))
            write(connection, ApiKeyHint, AssistantKeys.hint(key))
            ProvisioningAudit.insert(connection, actor, "assistant.key_set", null, null, auditDetails("key" to "(cambiada)"))
            settings(values(connection))
        }
    }

    fun removeKey(actor: AuditActor): AssistantServerSettings = transaction { connection ->
        val had = !values(connection)[ApiKey].isNullOrEmpty()
        delete(connection, ApiKey)
        delete(connection, ApiKeyHint)
        if (had) ProvisioningAudit.insert(connection, actor, "assistant.key_removed", null, null, auditDetails("key" to "(quitada)"))
        settings(values(connection))
    }

    fun setAllowance(companyCode: String, allowed: Boolean, monthlyTokens: Long?, actor: AuditActor): AssistantAllowance {
        if (monthlyTokens != null && (monthlyTokens < 0 || monthlyTokens > MaxMonthlyTokens)) {
            throw AssistantSettingsException("VALUE_INVALID", tr("El limite mensual debe estar entre 0 (sin limite) y 100000000000 tokens."))
        }
        return transaction { connection ->
            val before = allowance(values(connection), companyCode)
            write(connection, "$CompanyPrefix$companyCode.allowed", allowed.toString())
            if (monthlyTokens == null) delete(connection, "$CompanyPrefix$companyCode.monthly_tokens")
            else write(connection, "$CompanyPrefix$companyCode.monthly_tokens", monthlyTokens.toString())
            val after = allowance(values(connection), companyCode)
            if (after != before) {
                ProvisioningAudit.insert(
                    connection, actor, "company.assistant_changed", companyCode, null,
                    auditDetails("allowed.from" to before.allowed, "allowed.to" to after.allowed, "monthlyTokens.from" to before.monthlyTokens, "monthlyTokens.to" to after.monthlyTokens),
                )
            }
            after
        }
    }

    /**
     * A server person changes a company's assistant (its switch or its own key) from the server list or from a switcher
     * session: ONE central transaction holds the audit row `company.assistant_changed` (the real person's kind, never the
     * key) and [apply], the company's own change (with its activity row), which runs after the row is written: when it
     * fails the row is rolled back with it, and a change is never left without its audit row. [details] says what changes
     * (empty: nothing to audit).
     */
    fun <T> auditedCompanyChange(actor: AuditActor, companyCode: String, details: List<Pair<String, Any?>>, apply: () -> T): T = transaction { connection ->
        if (details.isNotEmpty()) {
            ProvisioningAudit.insert(connection, actor, "company.assistant_changed", companyCode, null, auditDetails(*details.toTypedArray()))
        }
        apply()
    }

    /**
     * The server audit row of a tool the assistant ran in the server workspace ("Asistente de <persona>"): the person's own
     * kind (server_owner / server_admin), the tool, how it ended and the argument names (never their values), plus the
     * company code the tool acted on. The tool also wrote its own row, as the person.
     */
    fun auditAssistantTool(actor: AuditActor, personName: String, tool: String, arguments: kotlinx.serialization.json.JsonObject, ok: Boolean) {
        val company = (arguments["code"] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content?.take(63)?.takeIf { it.matches(CompanyCode) }
        central.connection.use { connection ->
            ProvisioningAudit.insert(
                connection, actor, "assistant.server_tool", company, null,
                auditDetails(
                    "via" to "assistant", "actor" to "Asistente de ${personName.take(80)}", "tool" to tool, "result" to if (ok) "ok" else "error",
                    "arguments" to arguments.keys.sorted().take(30),
                ),
            )
        }
    }

    private fun <T> transaction(block: (Connection) -> T): T = central.connection.use { connection ->
        connection.autoCommit = false
        try {
            block(connection).also { connection.commit() }
        } catch (failure: Throwable) {
            runCatching { connection.rollback() }
            throw failure
        } finally {
            runCatching { connection.autoCommit = true }
        }
    }

    private fun write(connection: Connection, key: String, value: String) {
        connection.prepareStatement(
            """
            INSERT INTO server_settings (setting_key, setting_value, updated_at) VALUES (?, ?, CURRENT_TIMESTAMP)
            ON CONFLICT (setting_key) DO UPDATE SET setting_value = EXCLUDED.setting_value, updated_at = CURRENT_TIMESTAMP
            """.trimIndent(),
        ).use { upsert ->
            upsert.setString(1, key)
            upsert.setString(2, value)
            upsert.executeUpdate()
        }
    }

    private fun delete(connection: Connection, key: String) {
        connection.prepareStatement("DELETE FROM server_settings WHERE setting_key = ?").use { it.setString(1, key); it.executeUpdate() }
    }

    companion object {
        const val Enabled = "assistant.enabled"
        const val Model = "assistant.model"
        const val Effort = "assistant.effort"
        const val DefaultLimit = "assistant.monthly_tokens.default"
        const val LimitOwnKey = "assistant.limit_own_key"
        const val Retention = "assistant.retention_days"
        const val ApiKey = "assistant.api_key"
        const val ApiKeyHint = "assistant.api_key_hint"
        const val CompanyPrefix = "assistant.company."
        const val MaxRetentionDays = 3650
        const val MaxMonthlyTokens = 100_000_000_000L
        private val CompanyCode = Regex("[a-z][a-z0-9_]{0,62}")
    }
}

/** The company's own switch and key (tenant assistant_settings, one row). */
data class AssistantCompanySettings(val enabled: Boolean = false, val ownKeySet: Boolean = false, val ownKeyHint: String? = null)

object AssistantCompanyConfig {
    fun read(tenant: Connection): AssistantCompanySettings = try {
        tenant.prepareStatement("SELECT enabled, api_key_enc, api_key_hint FROM public.assistant_settings WHERE id = 1").use { query ->
            query.executeQuery().use { rows ->
                if (rows.next()) AssistantCompanySettings(rows.getBoolean(1), !rows.getString(2).isNullOrEmpty(), rows.getString(3)) else AssistantCompanySettings()
            }
        }
    } catch (failure: java.sql.SQLException) {
        // Before tenant V018 the company has no assistant yet.
        if (failure.sqlState == "42P01") AssistantCompanySettings() else throw failure
    }

    /** The company's own key in clear, for one request; null when none. */
    fun ownKey(tenant: Connection, secrets: SecretBox): String? {
        val sealed = tenant.prepareStatement("SELECT api_key_enc FROM public.assistant_settings WHERE id = 1").use { query ->
            query.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
        }?.takeIf { it.isNotEmpty() } ?: return null
        return secrets.open(tenant, sealed)
    }

    /**
     * Applies the business owner's change in one transaction with its activity row (module "core", the key only as
     * "changed"). Turning it on needs [acceptDisclosure].
     */
    fun update(
        tenant: Connection, secrets: SecretBox, actor: ActivityActor,
        enabled: Boolean?, acceptDisclosure: Boolean?, apiKey: String?, removeApiKey: Boolean?,
    ): AssistantCompanySettings {
        val key = apiKey?.takeIf { it.isNotBlank() }?.let(AssistantKeys::validate)
        if (key != null && !secrets.available) throw AssistantSettingsException("SECRETS_UNAVAILABLE", tr("El servidor no tiene BMS_MASTER_KEY: no puede guardar la clave."), 503)
        return ActivityLog.atomically(tenant) {
            val before = read(tenant)
            if (enabled == true && !before.enabled && acceptDisclosure != true) {
                throw AssistantSettingsException("DISCLOSURE_REQUIRED", tr("Para activar el asistente acepta el aviso: los datos que consulte se envian a Anthropic."))
            }
            tenant.prepareStatement("INSERT INTO public.assistant_settings (id) VALUES (1) ON CONFLICT (id) DO NOTHING").use { it.executeUpdate() }
            if (enabled != null) {
                tenant.prepareStatement(
                    "UPDATE public.assistant_settings SET enabled = ?, disclosure_accepted_at = CASE WHEN ? THEN CURRENT_TIMESTAMP ELSE disclosure_accepted_at END, updated_at = CURRENT_TIMESTAMP WHERE id = 1",
                ).use {
                    it.setBoolean(1, enabled)
                    it.setBoolean(2, enabled && !before.enabled)
                    it.executeUpdate()
                }
            }
            val keyChanged = when {
                key != null -> {
                    tenant.prepareStatement("UPDATE public.assistant_settings SET api_key_enc = ?, api_key_hint = ?, updated_at = CURRENT_TIMESTAMP WHERE id = 1").use {
                        it.setString(1, secrets.seal(tenant, key))
                        it.setString(2, AssistantKeys.hint(key))
                        it.executeUpdate()
                    }
                    true
                }
                removeApiKey == true && before.ownKeySet -> {
                    tenant.prepareStatement("UPDATE public.assistant_settings SET api_key_enc = NULL, api_key_hint = NULL, updated_at = CURRENT_TIMESTAMP WHERE id = 1").use { it.executeUpdate() }
                    true
                }
                else -> false
            }
            val after = read(tenant)
            val changes = buildList {
                if (before.enabled != after.enabled) add(ActivityChange.of("enabled", "Asistente activado", before.enabled, after.enabled))
                if (keyChanged) add(ActivityChange.secret("apiKey", "Clave de API propia"))
            }
            if (changes.isNotEmpty()) {
                val summary = when {
                    before.enabled != after.enabled && after.enabled -> "Activo el asistente de IA de la empresa."
                    before.enabled != after.enabled -> "Desactivo el asistente de IA de la empresa."
                    key != null -> "Cambio la clave de API propia del asistente de IA."
                    else -> "Quito la clave de API propia del asistente de IA."
                }
                CoreActivity.record(tenant, actor, "assistant_settings", null, "Asistente de IA", ActivityActions.SettingsChanged, summary, changes)
            }
            after
        }
    }
}
