package com.ideasdeveloper.bms.extensions.inmobiliaria_monday

import com.ideasdeveloper.bms.modules.crm.CrmActor
import com.ideasdeveloper.bms.modules.crm.CrmExtensionActionResult
import com.ideasdeveloper.bms.modules.crm.CrmExtensionPermissions
import com.ideasdeveloper.bms.modules.crm.CrmExtensionSection
import com.ideasdeveloper.bms.modules.crm.CrmExtensions
import com.ideasdeveloper.bms.modules.crm.CrmImportSource
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
import com.ideasdeveloper.bms.server.modules.ModuleJob
import com.ideasdeveloper.bms.ui.UiButton
import com.ideasdeveloper.bms.ui.UiCard
import com.ideasdeveloper.bms.ui.UiNavigate
import com.ideasdeveloper.bms.ui.UiNode
import com.ideasdeveloper.bms.ui.UiNotice
import com.ideasdeveloper.bms.ui.UiText
import com.ideasdeveloper.bms.ui.UiTextStyle
import com.ideasdeveloper.bms.ui.UiTone
import java.sql.Connection
import java.time.Duration
import java.time.LocalDate

/**
 * Extension "inmobiliaria_monday": migra desde monday los tableros de listados y de deals a la extension Inmobiliaria y se
 * desinstala al terminar. Aporta una pantalla oculta al CRM ([CrmScreenProvider]), una tarjeta en Importar ([CrmImportSource],
 * solo para abrir esa pantalla: sus jobs no pasan por el pipeline generico) y un trabajo en segundo plano que lee y aplica.
 */
object InmobiliariaMondayServerExtension : ServerExtension, CrmScreenProvider, CrmImportSource {
    const val ExtensionId = "inmobiliaria_monday"

    /** La extension destino de los datos importados. */
    const val TargetId = "inmobiliaria"
    const val HistoryOption = "import_history"

    // Debe coincidir con extension.json (el cargador compara id, modulo y version).
    override val manifest = ExtensionManifest(
        id = ExtensionId,
        module = "crm",
        displayName = "Importacion de monday para Inmobiliaria",
        description = "Migra desde monday los tableros de listados y de deals hacia la extension Inmobiliaria: lee el tablero con el token de la empresa, mapea sus columnas y aplica la importacion. Se desinstala al terminar.",
        version = "1.1.0",
        minModuleVersion = "0.30.0", // the bilingual release (minCoreVersion 0.10.0: tr)
        source = ExtensionSource(
            ExtensionSourceType.GitHub,
            "https://github.com/alexcalbri/bms/tree/0000000000000000000000000000000000000000/extensions/inmobiliaria_monday",
        ),
        permissions = listOf(
            ExtensionPermissions.NetworkOutboundPrefix + "api.monday.com",
            ExtensionPermissions.SecretsWrite,
            CrmExtensionPermissions.ItemsWrite,
        ),
        // El token se guarda en ext_inmobiliaria_monday__settings y se edita en la pantalla de la extension.
        settings = emptyList(),
        migrations = listOf("migrations/V001__inmobiliaria_monday.sql"),
        uninstall = ExtensionUninstallDefinition(
            listOf(ExtensionUninstallOption(HistoryOption, "Historial de importaciones de monday", UninstallDefault.Delete)),
        ),
    )

    override val migrationPaths = listOf("extensions/inmobiliaria_monday/migrations/V001__inmobiliaria_monday.sql")

    override val runtimeTables = listOf(InmobiliariaMondayStore.SettingsTable, InmobiliariaMondayStore.JobsTable, InmobiliariaMondayStore.RowsTable)

    // Cada corrida se acota a 50 s (el trabajo corre una vez por minuto en un hilo compartido con los demas).
    override val jobs = listOf(
        ModuleJob("import", Duration.ofMinutes(1), longRunning = true, run = { context -> InmobiliariaMondayRunner.run(context.tenant, Duration.ofSeconds(50)) }),
    )

    // ---- CrmScreenProvider ----

    override val extensionId: String get() = ExtensionId

    override val sections get() = listOf(
        CrmExtensionSection(InmobiliariaMondayScreens.Import, tr("Importar desde monday"), CrmScreens.Settings, inMenu = false),
    )

    override val mcpTools: List<McpTool> get() = InmobiliariaMondayMcpTools.tools

    override fun screen(context: CrmScreens.Context, screen: String, params: Map<String, String>, submitted: Map<String, String>?): List<UiNode> =
        if (screen == InmobiliariaMondayScreens.Import) InmobiliariaMondayScreens.screen(context, params)
        else listOf(UiText(tr("Esa pantalla no existe."), tone = UiTone.Warning))

    override fun action(
        context: CrmScreens.Context,
        actor: CrmActor,
        screen: String,
        op: String,
        params: Map<String, String>,
        values: Map<String, String>,
    ): CrmExtensionActionResult =
        if (screen == InmobiliariaMondayScreens.Import) InmobiliariaMondayScreens.action(context, actor, op, params, values)
        else CrmExtensionActionResult(screen, UiNotice(tr("Esa accion no existe."), UiTone.Warning), params - "op")

    // ---- CrmImportSource: solo la tarjeta de Importar; esta extension no usa el pipeline generico de jobs del CRM ----

    override val id: String get() = ExtensionId
    override val label: String get() = tr("monday (Inmobiliaria)")
    override val readingLabel: String get() = tr("Leyendo monday")

    override fun importCards(context: CrmScreens.Context): List<UiNode> {
        val unavailable = InmobiliariaMondayService.unavailableReason(context.tenant)
        return listOf(
            UiCard(
                listOf(
                    UiText(tr("Migra los tableros de listados y de deals de monday a Inmobiliaria."), UiTextStyle.Caption),
                    if (unavailable != null) UiText(unavailable, tone = UiTone.Warning)
                    else UiButton(tr("Importar desde monday"), UiNavigate(InmobiliariaMondayScreens.Import), style = "secondary"),
                ),
                title = tr("monday - Inmobiliaria"),
            ),
        )
    }

    // Lo propio de esta extension corre en su ModuleJob; el del CRM no tiene nada que leer ni aplicar aqui.
    override fun readQueued(tenant: Connection) = Unit

    override fun runPending(tenant: Connection, limit: Int, today: LocalDate, files: com.ideasdeveloper.bms.server.modules.ModuleFiles?, budget: Duration): Int = 0

    // ---- Desinstalacion ----

    override fun uninstallPreview(context: ExtensionContext): List<UninstallItem> {
        val tenant = context.tenant
        return listOf(
            CrmExtensions.item(null, tr("Token de la API de monday (se borra)"), CrmExtensions.count(tenant, "SELECT count(*) FROM ${InmobiliariaMondayStore.SettingsTable} WHERE coalesce(token, '') <> ''"), "delete"),
            CrmExtensions.item(HistoryOption, tr("Historial de importaciones de monday y sus filas leidas (si lo conservas: contiene datos de clientes, como nombres, correos y telefonos de propietarios y compradores)"), CrmExtensions.count(tenant, "SELECT count(*) FROM ${InmobiliariaMondayStore.JobsTable}"), UninstallDefault.Delete),
            CrmExtensions.item(null, tr("Propiedades y transacciones importadas (se conservan en Inmobiliaria)"), CrmExtensions.count(tenant, "SELECT count(*) FROM ${InmobiliariaMondayStore.RowsTable} WHERE state IN ('applied', 'failed')"), "keep"),
        )
    }

    override fun uninstall(context: ExtensionContext, choices: Map<String, String>) {
        val tenant = context.tenant
        // El token se borra siempre.
        CrmExtensions.update(tenant, "DELETE FROM ${InmobiliariaMondayStore.SettingsTable} WHERE id = 1")
        if (choices[HistoryOption] == UninstallDefault.Delete) {
            // Las filas leidas se borran con su importacion (ON DELETE CASCADE).
            CrmExtensions.update(tenant, "DELETE FROM ${InmobiliariaMondayStore.JobsTable}")
        }
    }
}

/** Punto de entrada que el servidor instancia al cargar el paquete de la extension. */
class InmobiliariaMondayServerExtensionProvider : ServerExtensionProvider {
    override fun create(): ServerExtension = InmobiliariaMondayServerExtension
}
