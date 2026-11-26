package com.ideasdeveloper.bms.extensions.monday

import com.ideasdeveloper.bms.modules.crm.CrmActor
import com.ideasdeveloper.bms.modules.crm.CrmExtensionActionResult
import com.ideasdeveloper.bms.modules.crm.CrmExtensionIds
import com.ideasdeveloper.bms.modules.crm.CrmExtensionSection
import com.ideasdeveloper.bms.modules.crm.CrmExtensionPermissions
import com.ideasdeveloper.bms.modules.crm.CrmExtensions
import com.ideasdeveloper.bms.modules.crm.CrmFields
import com.ideasdeveloper.bms.modules.crm.CrmHost
import com.ideasdeveloper.bms.modules.crm.CrmImportSource
import com.ideasdeveloper.bms.modules.crm.CrmOutboundSource
import com.ideasdeveloper.bms.modules.crm.CrmScreenProvider
import com.ideasdeveloper.bms.modules.crm.CrmScreens
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
import com.ideasdeveloper.bms.server.modules.McpTool
import com.ideasdeveloper.bms.server.outbound.ExtensionConnectionPermission
import com.ideasdeveloper.bms.server.outbound.IntegrationConnections
import com.ideasdeveloper.bms.server.outbound.OutboundQueue
import com.ideasdeveloper.bms.server.outbound.OutboundSink
import com.ideasdeveloper.bms.server.outbound.monday.MondayOutboundMcp
import com.ideasdeveloper.bms.server.outbound.monday.OutboundQueueView
import com.ideasdeveloper.bms.ui.UiNode
import com.ideasdeveloper.bms.ui.UiNotice
import com.ideasdeveloper.bms.ui.UiText
import com.ideasdeveloper.bms.ui.UiTone

/**
 * Extension "monday": lee tableros de monday con el token de la empresa y los importa como leads, y envia a monday los
 * leads nuevos, sus cambios, notas y archivos ([OutboundSink] con [MondayOutbound]; pantalla y herramientas en [CrmScreenProvider]). Los campos del CRM
 * que crea la importacion quedan marcados (crm_field_definitions.extension_id) para decidir su suerte al desinstalarla.
 * No tiene rutas publicas: es una fuente de importacion del CRM ([CrmImportSource], que el CRM recorre al correr el
 * trabajo "import"), y cada paso comprueba que la extension siga activa.
 */
object MondayServerExtension : ServerExtension, CrmImportSource by MondayImport, OutboundSink by MondayOutbound, CrmScreenProvider {
    const val CreatedFieldsOption = "created_fields"
    const val HistoryOption = "import_history"

    /** Prefijo de las herramientas MCP de la extension ("<extensionId>_"). */
    const val ToolPrefix = "monday_"

    // Debe coincidir con extension.json (el cargador compara id, modulo y version).
    override val manifest = ExtensionManifest(
        id = CrmExtensionIds.MondayImport,
        module = "crm",
        displayName = "Importacion desde monday",
        description = "Importa tableros de monday como leads y envia a monday los leads nuevos, sus cambios, notas (con menciones) y archivos.",
        version = "1.2.0",
        // El envio necesita el contrato de salida del CRM (OutboundSource, permiso crm.leads.export), desde la 0.29.0.
        minModuleVersion = "0.29.0",
        source = ExtensionSource(
            ExtensionSourceType.GitHub,
            "https://github.com/alexcalbri/bms/tree/0000000000000000000000000000000000000000/extensions/monday",
        ),
        // Los archivos de monday se bajan de enlaces que monday da en cada respuesta (host variable), no de un host fijo.
        permissions = listOf(
            ExtensionPermissions.NetworkOutboundPrefix + "api.monday.com",
            ExtensionPermissions.SecretsWrite,
            CrmExtensionPermissions.LeadsCreate,
            CrmExtensionPermissions.LeadsUpdate,
            CrmExtensionPermissions.FieldsCreate,
            CrmExtensionPermissions.MessagesWrite,
            CrmExtensionPermissions.LeadsExport,
            ExtensionConnectionPermission,
        ),
        // El token se guarda en ext_monday__settings y se edita en la pantalla Importar del CRM: ningun ajuste es obligatorio para activarla.
        settings = emptyList(),
        migrations = listOf("migrations/V001__settings.sql", "migrations/V002__outbound.sql"),
        uninstall = ExtensionUninstallDefinition(
            listOf(
                ExtensionUninstallOption(CreatedFieldsOption, "Campos del CRM creados por la importacion (con sus valores en los leads)", UninstallDefault.Ask),
                ExtensionUninstallOption(HistoryOption, "Historial de importaciones de monday", UninstallDefault.Delete),
            ),
        ),
    )

    override val migrationPaths = listOf("extensions/monday/migrations/V001__settings.sql", "extensions/monday/migrations/V002__outbound.sql")

    override val runtimeTables = listOf(MondaySettingsStore.Table, MondayOutboundTable)

    // ---- CrmScreenProvider: pantalla "Envio a monday" y sus herramientas ----

    override val extensionId: String = CrmExtensionIds.MondayImport

    override val sections get() = listOf(CrmExtensionSection(MondayOutboundScreens.Section, tr("Envio a monday"), CrmScreens.Settings))

    /**
     * Las herramientas de importacion (que el CRM ya recoge como [CrmImportSource]) y las del envio, con el mismo permiso
     * de configuracion que la pantalla. Salen en una sola lista porque el CRM lee la misma propiedad por las dos interfaces.
     */
    override val mcpTools: List<McpTool> get() = importAndOutboundTools

    private val importAndOutboundTools: List<McpTool> by lazy {
        MondayMcpTools.tools + MondayOutboundMcp.tools(
            prefix = ToolPrefix,
            permission = CrmScreens.Settings,
            sink = MondayOutbound,
            source = CrmOutboundSource,
            queueView = OutboundQueueView(MondayConnectionService),
            token = { MondayOutbound.token(it) },
            inactive = { CrmHost.extensionInactiveReason(it, CrmExtensionIds.MondayImport) },
        )
    }

    override fun screen(context: CrmScreens.Context, screen: String, params: Map<String, String>, submitted: Map<String, String>?): List<UiNode> =
        if (screen == MondayOutboundScreens.Section) MondayOutboundScreens.screen(context, submitted)
        else listOf(UiText(tr("Esa pantalla no existe."), tone = UiTone.Warning))

    override fun action(
        context: CrmScreens.Context,
        actor: CrmActor,
        screen: String,
        op: String,
        params: Map<String, String>,
        values: Map<String, String>,
    ): CrmExtensionActionResult =
        if (screen == MondayOutboundScreens.Section) MondayOutboundScreens.action(context, op, params, values)
        else CrmExtensionActionResult(screen, UiNotice(tr("Esa accion no existe."), UiTone.Warning), params - "op")

    override fun uninstallPreview(context: ExtensionContext): List<UninstallItem> {
        val tenant = context.tenant
        return listOf(
            CrmExtensions.item(null, tr("Token de la API de monday (se borra)"), CrmExtensions.count(tenant, "SELECT count(*) FROM ${MondaySettingsStore.Table} WHERE coalesce(token, '') <> ''"), "delete"),
            CrmExtensions.item(
                CreatedFieldsOption, tr("Campos del CRM creados por la importacion"),
                CrmExtensions.count(tenant, "SELECT count(*) FROM crm_field_definitions WHERE extension_id = ?", CrmExtensionIds.MondayImport), UninstallDefault.Ask,
            ),
            CrmExtensions.item(HistoryOption, tr("Historial de importaciones de monday"), CrmExtensions.count(tenant, "SELECT count(*) FROM crm_import_jobs WHERE source = 'monday'"), UninstallDefault.Delete),
            CrmExtensions.item(
                null, tr("Envios a monday en cola y enlaces (se borran)"),
                CrmExtensions.count(tenant, "SELECT count(*) FROM outbound_events WHERE sink_id = ?", MondayConnectionService), "delete",
            ),
            CrmExtensions.item(
                null, tr("Conexion compartida de monday (NO se borra: otras extensiones de monday la usan; quitala desde Importar si ya no la quieres)"),
                CrmExtensions.count(tenant, "SELECT count(*) FROM ${IntegrationConnections.Table} WHERE service = ?", MondayConnectionService), "keep",
            ),
            CrmExtensions.item(null, tr("Leads importados (se conservan)"), CrmExtensions.count(tenant, "SELECT count(*) FROM crm_leads WHERE import_key LIKE 'monday:%'"), "keep"),
        )
    }

    override fun uninstall(context: ExtensionContext, choices: Map<String, String>) {
        val tenant = context.tenant
        // El token vive en la tabla de la extension: se borra su fila.
        CrmExtensions.update(tenant, "DELETE FROM ${MondaySettingsStore.Table} WHERE id = 1")
        // Los envios en cola, los enlaces con monday y la configuracion por tablero se borran. Los leads y su import_key quedan,
        // y la conexion compartida tambien: otras extensiones de monday de la empresa pueden estar usandola.
        OutboundQueue.forget(tenant, MondayConnectionService)
        CrmExtensions.update(tenant, "DELETE FROM $MondayOutboundTable")
        if (choices[HistoryOption] == UninstallDefault.Delete) {
            // Las filas de cada importacion se borran con ella (ON DELETE CASCADE).
            CrmExtensions.update(tenant, "DELETE FROM crm_import_jobs WHERE source = 'monday'")
        }
        if (choices[CreatedFieldsOption] == UninstallDefault.Delete) {
            val ids = createdFieldIds(context)
            // Cada campo sale con sus valores en los leads; los leads no se tocan.
            CrmFields.all(tenant).filter { it.id.toString() in ids }.forEach { CrmFields.remove(tenant, it) }
        } else {
            // Se conservan: pasan a ser campos de la empresa.
            CrmExtensions.update(tenant, "UPDATE crm_field_definitions SET extension_id = NULL WHERE extension_id = ?", CrmExtensionIds.MondayImport)
        }
    }

    private fun createdFieldIds(context: ExtensionContext): Set<String> =
        context.tenant.prepareStatement("SELECT id::text FROM crm_field_definitions WHERE extension_id = ?").use { query ->
            query.setString(1, CrmExtensionIds.MondayImport)
            query.executeQuery().use { rows -> buildSet { while (rows.next()) add(rows.getString(1)) } }
        }
}

/** Punto de entrada que el servidor instancia al cargar el paquete de la extension. */
class MondayServerExtensionProvider : ServerExtensionProvider {
    override fun create(): ServerExtension = MondayServerExtension
}
