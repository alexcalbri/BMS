package com.ideasdeveloper.bms.server.modules

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModuleRequirementsTest {
    @Test
    fun coreLowerThanMinimumIsBlocked() {
        val problem = ModuleRequirementGate.coreProblem("0.6.0", "0.5.0")
        assertEquals("MODULE_NEEDS_CORE", problem?.code)
        assertEquals(
            "Este modulo necesita el Core 0.6.0 o superior; el servidor tiene 0.5.0. Actualiza el servidor primero.",
            problem?.message,
        )
    }

    @Test
    fun coreEqualOrHigherPasses() {
        assertNull(ModuleRequirementGate.coreProblem("0.5.0", "0.5.0"))
        assertNull(ModuleRequirementGate.coreProblem("0.5.0", "0.10.0"))
    }

    @Test
    fun missingOrBlankMinimumsDoNotBlock() {
        assertNull(ModuleRequirementGate.coreProblem(null, "0.1.0"))
        assertNull(ModuleRequirementGate.coreProblem("  ", "0.1.0"))
        assertNull(ModuleRequirementGate.appProblem(null, "0.1.0"))
    }

    @Test
    fun malformedMinimumReadsAsZero() {
        assertNull(ModuleRequirementGate.coreProblem("abc", "0.5.0"))
    }

    @Test
    fun appLowerIsBlockedAndMissingHeaderOnlyWarns() {
        val problem = ModuleRequirementGate.appProblem("0.6.1", "0.6.0")
        assertEquals("MODULE_NEEDS_APP", problem?.code)
        assertEquals("Este modulo necesita la app 0.6.1 o superior; tu app es 0.6.0. Actualizala antes de instalar.", problem?.message)
        assertNull(ModuleRequirementGate.appProblem("0.6.1", "0.6.1"))
        assertNull(ModuleRequirementGate.appProblem("0.6.1", null))
        assertTrue(ModuleRequirementGate.missingAppVersionWarning("0.6.1", null) != null)
        assertNull(ModuleRequirementGate.missingAppVersionWarning("0.6.1", "0.6.1"))
        assertNull(ModuleRequirementGate.missingAppVersionWarning(null, null))
    }

    @Test
    fun catalogReadsMinimumsWhenPresentAndNullWhenAbsent() {
        val catalog = """{"modules":[{"id":"crm","displayName":"CRM","description":"x","version":"0.1.0","minCoreVersion":"0.5.0","minAppVersion":"0.6.0"},""" +
            """{"id":"agentes","displayName":"A","description":"x","version":"0.1.0"}]}"""
        // The catalog is read over pinned https (RemoteModuleCatalog); the test answers in memory.
        val address = java.net.InetAddress.getByAddress(byteArrayOf(140.toByte(), 82, 112, 3))
        RemoteModuleCatalog(resolve = { address }, transport = { _, _, _ -> CatalogHttpResponse(200, catalog) }).use { remote ->
            val byId = remote.modules("https://catalog.example/catalog.json").associateBy { it.definition.id }
            assertEquals("0.5.0", byId.getValue("crm").definition.minCoreVersion)
            assertEquals("0.6.0", byId.getValue("crm").definition.minAppVersion)
            assertNull(byId.getValue("agentes").definition.minCoreVersion)
            assertNull(byId.getValue("agentes").definition.minAppVersion)
        }
    }

    @Test
    fun packageWithTooNewMinimumCoreIsRejected() {
        val root = Files.createTempDirectory("modreq")
        try {
            val moduleDir = Files.createDirectories(root.resolve("crm"))
            Files.writeString(moduleDir.resolve("module.json"), """{"id":"crm","version":"1.0.0","minCoreVersion":"9.0.0"}""")
            val builder = ModulePackageBuilder(root.resolve("pkgs"), root.resolve("compiler"), "", "java", coreVersion = "0.6.0")
            val failure = assertFailsWith<ModuleBuildException> {
                builder.buildFromDirectory("crm", moduleDir, root.resolve("work")) { }
            }
            assertTrue(failure.message!!.contains("Core 9.0.0"))
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
