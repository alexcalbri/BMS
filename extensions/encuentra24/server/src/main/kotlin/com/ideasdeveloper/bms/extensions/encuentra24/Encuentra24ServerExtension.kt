package com.ideasdeveloper.bms.extensions.encuentra24

import com.ideasdeveloper.bms.modules.crm.CrmEncuentra24Ads
import com.ideasdeveloper.bms.modules.crm.CrmEncuentra24Settings
import com.ideasdeveloper.bms.modules.crm.CrmEncuentra24SyncResult
import com.ideasdeveloper.bms.modules.crm.CrmExtensionIds
import com.ideasdeveloper.bms.modules.crm.CrmExtensionPermissions
import com.ideasdeveloper.bms.modules.crm.CrmExtensions
import com.ideasdeveloper.bms.modules.crm.CrmHost
import com.ideasdeveloper.bms.modules.crm.Encuentra24Provider
import com.ideasdeveloper.bms.server.extensions.ExtensionContext
import com.ideasdeveloper.bms.server.extensions.ExtensionManifest
import com.ideasdeveloper.bms.server.extensions.ExtensionPermissions
import com.ideasdeveloper.bms.server.extensions.ExtensionSource
import com.ideasdeveloper.bms.server.extensions.ExtensionSourceType
import com.ideasdeveloper.bms.server.extensions.ExtensionUninstallDefinition
import com.ideasdeveloper.bms.server.extensions.ExtensionUninstallOption
import com.ideasdeveloper.bms.server.extensions.ServerExtension
import com.ideasdeveloper.bms.server.extensions.ServerExtensionProvider
import com.ideasdeveloper.bms.server.extensions.UninstallDefault
import com.ideasdeveloper.bms.server.extensions.UninstallItem
import com.ideasdeveloper.bms.server.i18n.tr
import java.sql.Connection
import java.time.LocalDate
import java.util.UUID

/** Extension "encuentra24": importa cada 5 minutos los contactos de los anuncios de la empresa como leads. No tiene rutas publicas. */
object Encuentra24ServerExtension : ServerExtension, Encuentra24Provider {
    const val AdsOption = "ads"

    // Debe coincidir con extension.json (el cargador compara id, modulo y version).
    override val manifest = ExtensionManifest(
        id = CrmExtensionIds.Encuentra24,
        module = "crm",
        displayName = "Encuentra24",
        description = "Importa como leads los mensajes, chats y llamadas de los anuncios de Encuentra24 y deja el anuncio en los intereses del lead.",
        version = "1.1.0",
        minModuleVersion = "0.27.0",
        source = ExtensionSource(
            ExtensionSourceType.GitHub,
            "https://github.com/alexcalbri/bms/tree/0000000000000000000000000000000000000000/extensions/encuentra24",
        ),
        permissions = listOf(
            ExtensionPermissions.NetworkOutboundPrefix + "www.encuentra24.com",
            ExtensionPermissions.SecretsWrite,
            CrmExtensionPermissions.LeadsCreate,
            CrmExtensionPermissions.LeadsUpdate,
            CrmExtensionPermissions.MessagesWrite,
            CrmExtensionPermissions.ItemsWrite,
        ),
        // Los ajustes se guardan en ext_encuentra24__settings y se editan en las pantallas del CRM: ninguno es obligatorio para activarla.
        settings = emptyList(),
        migrations = listOf("migrations/V001__settings.sql"),
        uninstall = ExtensionUninstallDefinition(
            listOf(
                ExtensionUninstallOption(AdsOption, "Anuncios de Encuentra24 vinculados a los leads", UninstallDefault.Keep),
            ),
        ),
    )

    override val migrationPaths = listOf("extensions/encuentra24/migrations/V001__settings.sql")

    override val runtimeTables = listOf(Encuentra24SettingsStore.Table)

    // ---- Encuentra24Provider: lo que el CRM invoca sin depender del código de Encuentra24 ----

    override fun active(host: CrmHost, tenant: Connection): Boolean =
        host.extensionEnabled(tenant, CrmExtensionIds.Encuentra24) && Encuentra24SettingsStore.load(host, tenant).ready

    override fun settings(host: CrmHost, tenant: Connection): CrmEncuentra24Settings = Encuentra24SettingsStore.load(host, tenant)

    override fun saveForm(host: CrmHost, tenant: Connection, values: Map<String, String>) = Encuentra24SettingsStore.saveForm(host, tenant, values)

    override fun sync(host: CrmHost, tenant: Connection, from: LocalDate?): CrmEncuentra24SyncResult =
        CrmEncuentra24.sync(host, tenant, from) { leadId -> host.notifyNewLead(tenant, leadId) }

    override fun detectAd(host: CrmHost, tenant: Connection, leadId: UUID, text: String) {
        if (!host.extensionEnabled(tenant, CrmExtensionIds.Encuentra24)) return
        CrmEncuentra24Ads.fromText(text)?.let { ad -> CrmEncuentra24.rememberAd(host, tenant, leadId, ad.id, ad.url, "") }
    }

    override fun uninstallPreview(context: ExtensionContext): List<UninstallItem> {
        val tenant = context.tenant
        return listOf(
            CrmExtensions.item(null, tr("Conexion con Encuentra24 y su API key (se borran)"), CrmExtensions.count(tenant, "SELECT count(*) FROM ${Encuentra24SettingsStore.Table} WHERE enabled OR coalesce(api_key, '') <> ''"), "delete"),
            CrmExtensions.item(AdsOption, tr("Anuncios de Encuentra24 vinculados a los leads"), CrmExtensions.count(tenant, "SELECT count(*) FROM crm_lead_items WHERE source = ?", CrmEncuentra24Ads.Source), UninstallDefault.Keep),
            CrmExtensions.item(null, tr("Leads y mensajes importados (se conservan)"), CrmExtensions.count(tenant, "SELECT count(*) FROM crm_messages WHERE channel = ?", CrmEncuentra24Ads.Source), "keep"),
        )
    }

    override fun uninstall(context: ExtensionContext, choices: Map<String, String>) {
        val tenant = context.tenant
        // Los ajustes viven en la tabla de la extension: se borra su fila.
        CrmExtensions.update(tenant, "DELETE FROM ${Encuentra24SettingsStore.Table} WHERE id = 1")
        if (choices[AdsOption] == UninstallDefault.Delete) {
            CrmExtensions.update(tenant, "DELETE FROM crm_lead_items WHERE source = ?", CrmEncuentra24Ads.Source)
        }
    }
}

/** Punto de entrada que el servidor instancia al cargar el paquete de la extension. */
class Encuentra24ServerExtensionProvider : ServerExtensionProvider {
    override fun create(): ServerExtension = Encuentra24ServerExtension
}
