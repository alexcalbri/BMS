package com.ideasdeveloper.bms.extensions.membresia_monday

import com.ideasdeveloper.bms.modules.membresia.MembresiaImportContext
import com.ideasdeveloper.bms.modules.membresia.MembresiaImportReply
import com.ideasdeveloper.bms.modules.membresia.MembresiaImportSource
import com.ideasdeveloper.bms.modules.membresia.MembresiaModule
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
import com.ideasdeveloper.bms.server.modules.ModuleJobContext
import com.ideasdeveloper.bms.ui.UiNode
import java.sql.Connection
import java.sql.SQLException
import java.time.Duration

/**
 * Extension "membresia_monday": importa un tablero de monday como membresias (planes, clientes, membresias y visitas).
 * No tiene rutas publicas: es una fuente de importacion del modulo Membresias ([MembresiaImportSource]), que la muestra
 * en su pantalla Importar y suma sus herramientas MCP; la lectura y la aplicacion corren en el trabajo "membresia-monday-import".
 */
object MembresiaMondayServerExtension : ServerExtension, MembresiaImportSource {
    const val HistoryOption = "import_history"

    // Debe coincidir con extension.json (el cargador compara id, modulo y version).
    override val manifest = ExtensionManifest(
        id = MembresiaMondayId,
        module = "membresia",
        displayName = "Importacion de membresias desde monday",
        description = "Importa un tablero de monday como membresias: lee el tablero con el token de la empresa, mapea sus columnas y trae planes, clientes, membresias y visitas.",
        version = "1.1.0",
        minModuleVersion = "0.2.0",
        source = ExtensionSource(
            ExtensionSourceType.GitHub,
            "https://github.com/alexcalbri/bms/tree/0000000000000000000000000000000000000000/extensions/membresia_monday",
        ),
        permissions = listOf(
            ExtensionPermissions.NetworkOutboundPrefix + "api.monday.com",
            ExtensionPermissions.SecretsWrite,
            MembresiaModule.ImportWrite,
        ),
        // El token se guarda en ext_membresia_monday__settings y se edita en la pantalla Importar: ningun ajuste es obligatorio para activarla.
        settings = emptyList(),
        migrations = listOf("migrations/V001__settings.sql"),
        uninstall = ExtensionUninstallDefinition(
            listOf(ExtensionUninstallOption(HistoryOption, "Historial de importaciones de monday", UninstallDefault.Delete)),
        ),
    )

    override val migrationPaths = listOf("extensions/membresia_monday/migrations/V001__settings.sql")

    override val runtimeTables = listOf(MembresiaMondaySettings.Table, MembresiaMondayJobs.JobsTable, MembresiaMondayJobs.RowsTable)

    // ---- MembresiaImportSource ----

    // El Core no programa ServerExtension.jobs: el trabajo del modulo llama a esto cada minuto con un presupuesto de tiempo.
    override fun runPending(job: ModuleJobContext, budget: Duration) = MembresiaMondayJobs.runPending(job, budget)

    override val sourceId: String = MembresiaMondayId
    override val title: String = "Monday"

    override fun enabled(tenant: Connection, companyCode: String): Boolean = MembresiaMondaySettings.extensionEnabled(tenant)

    override fun screen(context: MembresiaImportContext, params: Map<String, String>): List<UiNode> =
        MembresiaMondayScreens.screen(context, params)

    override fun action(context: MembresiaImportContext, op: String, values: Map<String, String>): MembresiaImportReply =
        MembresiaMondayScreens.action(context, op, values)

    override val mcpTools: List<McpTool> get() = MembresiaMondayMcpTools.tools

    // ---- Desinstalacion ----

    private fun count(tenant: Connection, sql: String): Int = try {
        tenant.prepareStatement(sql).use { query -> query.executeQuery().use { if (it.next()) it.getInt(1) else 0 } }
    } catch (failure: SQLException) {
        0
    }

    override fun uninstallPreview(context: ExtensionContext): List<UninstallItem> {
        val tenant = context.tenant
        return listOf(
            UninstallItem(null, tr("Token de la API de monday (se borra)"), count(tenant, "SELECT count(*) FROM ${MembresiaMondaySettings.Table} WHERE coalesce(token, '') <> ''"), "delete"),
            UninstallItem(HistoryOption, tr("Importaciones de monday (trabajos)"), count(tenant, "SELECT count(*) FROM ${MembresiaMondayJobs.JobsTable}"), UninstallDefault.Delete),
            UninstallItem(HistoryOption, tr("Filas leidas de monday"), count(tenant, "SELECT count(*) FROM ${MembresiaMondayJobs.RowsTable}"), UninstallDefault.Delete),
            UninstallItem(null, tr("Membresias importadas (se conservan)"), count(tenant, "SELECT count(*) FROM membresia_memberships WHERE import_key LIKE 'monday:%'"), "keep"),
            UninstallItem(null, tr("Visitas importadas (se conservan)"), count(tenant, "SELECT count(*) FROM membresia_visits WHERE import_key LIKE 'monday:%'"), "keep"),
        )
    }

    override fun uninstall(context: ExtensionContext, choices: Map<String, String>) {
        val tenant = context.tenant
        // El token vive en la tabla de la extension: se borra su fila.
        tenant.prepareStatement("DELETE FROM ${MembresiaMondaySettings.Table} WHERE id = 1").use { it.executeUpdate() }
        if (choices[HistoryOption] == UninstallDefault.Delete) {
            // Las filas de cada importacion se borran con ella (ON DELETE CASCADE). Nunca se tocan clientes, planes, membresias ni visitas.
            tenant.prepareStatement("DELETE FROM ${MembresiaMondayJobs.JobsTable}").use { it.executeUpdate() }
        }
    }
}

/** Punto de entrada que el servidor instancia al cargar el paquete de la extension. */
class MembresiaMondayServerExtensionProvider : ServerExtensionProvider {
    override fun create(): ServerExtension = MembresiaMondayServerExtension
}
