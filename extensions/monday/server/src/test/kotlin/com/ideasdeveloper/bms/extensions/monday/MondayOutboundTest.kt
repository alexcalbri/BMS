package com.ideasdeveloper.bms.extensions.monday

import com.ideasdeveloper.bms.modules.crm.CrmExtensionPermissions
import com.ideasdeveloper.bms.modules.crm.CrmModuleExtensions
import com.ideasdeveloper.bms.modules.crm.CrmScreenProvider
import com.ideasdeveloper.bms.modules.crm.CrmScreens
import com.ideasdeveloper.bms.server.extensions.ExtensionManifest
import com.ideasdeveloper.bms.server.outbound.ExtensionConnectionPermission
import com.ideasdeveloper.bms.server.outbound.OutboundSink
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** El envio a monday de la extension: manifiesto, contratos que implementa, herramientas MCP y eleccion del token. Sin base de datos ni red. */
class MondayOutboundTest {
    @Test
    fun theManifestInCodeMatchesExtensionJson() {
        val text = repoFile("extensions/monday/extension.json").readText()
        val fromDisk = Json { ignoreUnknownKeys = true }.decodeFromString(ExtensionManifest.serializer(), text)
        val manifest = MondayServerExtension.manifest
        assertEquals(fromDisk, manifest)
        assertEquals("1.2.0", manifest.version)
        assertTrue(CrmExtensionPermissions.LeadsExport in manifest.permissions)
        assertTrue(ExtensionConnectionPermission in manifest.permissions)
        assertEquals("connections.monday", ExtensionConnectionPermission)
        assertEquals(listOf("migrations/V001__settings.sql", "migrations/V002__outbound.sql"), manifest.migrations)
        assertEquals(manifest.migrations.map { "extensions/monday/$it" }, MondayServerExtension.migrationPaths)
        assertTrue(manifest.migrations.all { repoFile("extensions/monday/$it").isFile })
        assertEquals(listOf("ext_monday__settings", "ext_monday__outbound"), MondayServerExtension.runtimeTables)
        assertTrue(MondayServerExtension.runtimeTables.all { it.startsWith("ext_monday__") })
        assertTrue(repoFile("extensions/monday/migrations/V002__outbound.sql").readText().contains("CREATE TABLE $MondayOutboundTable"))
    }

    @Test
    fun theExtensionIsAnOutboundSinkAndAScreenProviderOfTheCrm() {
        val extension: Any = MondayServerExtension
        assertTrue(extension is OutboundSink)
        assertTrue(extension is CrmScreenProvider)
        assertEquals("monday", MondayServerExtension.sinkId)
        assertEquals("crm", MondayServerExtension.moduleId)
        assertEquals(MondayServerExtension.manifest.id, MondayServerExtension.extensionId)
        assertEquals(MondayServerExtension.manifest.id, MondayServerExtension.sinkId)
        // El CRM solo le manda avisos si declara el permiso de exportar leads.
        assertEquals(listOf<Any>(MondayServerExtension), CrmModuleExtensions.exportSinks(listOf(MondayServerExtension)))
    }

    @Test
    fun theSectionHasTheExtensionPrefixAndNeedsTheCrmSettingsPermission() {
        val section = MondayServerExtension.sections.single()
        assertEquals("monday_outbound", section.id)
        assertTrue(section.id.startsWith(MondayServerExtension.extensionId + "_"))
        assertEquals(CrmScreens.Settings, section.permission)
        assertEquals(MondayOutboundScreens.Section, section.id)
    }

    @Test
    fun mcpToolsKeepTheImportOnesAddTheOutboundOnesAndFitTheNameLimit() {
        val tools = MondayServerExtension.mcpTools
        val names = tools.map { it.name }
        assertEquals(names.size, names.toSet().size)
        // Las de importacion siguen donde estaban.
        assertTrue("list_monday_boards" in names)
        assertTrue("import_monday" in names)
        val outbound = tools.filter { it.name.startsWith(MondayServerExtension.ToolPrefix) }
        assertTrue(outbound.size >= 4)
        assertTrue("monday_outbound_status" in names)
        assertTrue("monday_outbound_configure" in names)
        // Todas piden el permiso de configuracion del CRM, como la pantalla.
        assertTrue(tools.all { it.permission == CrmScreens.Settings })
        // El agente las ve como "<modulo>_<nombre>": maximo 64 caracteres.
        assertTrue(tools.all { "crm_${it.name}".length <= 64 }, tools.map { it.name }.filter { "crm_$it".length > 64 }.toString())
    }

    @Test
    fun theOwnTokenWinsOverTheSharedConnection() {
        assertEquals("propio", MondayOutbound.resolveToken({ "propio" }, { "compartido" }))
        assertEquals("compartido", MondayOutbound.resolveToken({ null }, { "compartido" }))
        assertEquals("compartido", MondayOutbound.resolveToken({ "  " }, { "compartido" }))
        assertNull(MondayOutbound.resolveToken({ null }, { null }))
        assertNull(MondayOutbound.resolveToken({ "" }, { " " }))
    }

    @Test
    fun theSharedConnectionIsNotReadWhenTheOwnTokenExists() {
        var sharedRead = false
        assertEquals("propio", MondayOutbound.resolveToken({ "propio" }, { sharedRead = true; "compartido" }))
        assertTrue(!sharedRead)
    }

    // El directorio de trabajo de las pruebas es el del proyecto Gradle: se sube hasta encontrar el archivo.
    private fun repoFile(relative: String): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            val candidate = File(dir, relative)
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        error("No se encontro $relative")
    }
}
