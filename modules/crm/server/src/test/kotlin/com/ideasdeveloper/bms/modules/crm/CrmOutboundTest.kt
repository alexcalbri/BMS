package com.ideasdeveloper.bms.modules.crm

import com.ideasdeveloper.bms.server.extensions.ExtensionManifest
import com.ideasdeveloper.bms.server.extensions.ServerExtension
import com.ideasdeveloper.bms.server.modules.McpTool
import com.ideasdeveloper.bms.server.modules.ModuleFiles
import com.ideasdeveloper.bms.server.outbound.OutboundDelivery
import com.ideasdeveloper.bms.server.outbound.OutboundEvent
import com.ideasdeveloper.bms.server.outbound.OutboundFieldType
import com.ideasdeveloper.bms.server.outbound.OutboundPerson
import com.ideasdeveloper.bms.server.outbound.OutboundResult
import com.ideasdeveloper.bms.server.outbound.OutboundSink
import com.ideasdeveloper.bms.server.outbound.OutboundValue
import com.ideasdeveloper.bms.ui.UiNode
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.math.BigDecimal
import java.sql.Connection
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CrmOutboundTest {
    private val stageId = UUID.randomUUID()
    private val userA = UUID.randomUUID()
    private val userB = UUID.randomUUID()

    private val plan = CrmField(UUID.randomUUID(), "cf_plan", "Plan", CrmFieldType.Dropdown, listOf("Basico", "Pro"))
    private val colors = CrmField(UUID.randomUUID(), "cf_colores", "Colores", CrmFieldType.Dropdown, listOf("Rojo", "Azul"), multiple = true)
    private val budget = CrmField(UUID.randomUUID(), "cf_presupuesto", "Presupuesto", CrmFieldType.Number)
    private val signed = CrmField(UUID.randomUUID(), "cf_firmado", "Firmado", CrmFieldType.Boolean)
    private val day = CrmField(UUID.randomUUID(), "cf_fecha", "Fecha", CrmFieldType.Date)
    private val contract = CrmField(UUID.randomUUID(), "cf_contrato", "Contrato", CrmFieldType.Files)
    private val button = CrmField(UUID.randomUUID(), "cf_boton", "Boton", CrmFieldType.Button)
    private val fields = listOf(plan, colors, budget, signed, day, contract, button)

    private fun lead(custom: JsonObject = JsonObject(emptyMap())) = CrmLead(
        id = UUID.randomUUID(), customerId = UUID.randomUUID(), name = "Ana", email = "ana@x.com", phone = "+5071",
        stageId = stageId, interestId = null, ownerUserIds = listOf(userA), source = "web", language = "es",
        message = "Hola", details = "", targetStart = null, targetEnd = null, estimatedValue = BigDecimal("100.00"),
        followUpOn = null, stageChangedAt = Instant.EPOCH, closedAt = null, createdAt = Instant.parse("2026-03-01T10:00:00Z"),
        customFields = custom,
    )

    private fun input(lead: CrmLead) = CrmLeadInput(
        name = lead.name, email = lead.email, phone = lead.phone, stageId = lead.stageId, interestId = lead.interestId,
        ownerUserIds = null, source = lead.source, language = lead.language, message = lead.message, details = lead.details,
        targetStart = lead.targetStart, targetEnd = lead.targetEnd, estimatedValue = lead.estimatedValue, followUpOn = lead.followUpOn,
    )

    // ---- Claves que cambiaron ----

    @Test
    fun nothingChangedGivesNoKeys() {
        val lead = lead()
        assertEquals(emptyList(), CrmOutbound.changedKeys(lead, input(lead), fields))
    }

    @Test
    fun valueWithOtherScaleIsNotAChange() {
        val lead = lead()
        assertEquals(emptyList(), CrmOutbound.changedKeys(lead, input(lead).copy(estimatedValue = BigDecimal("100")), fields))
    }

    @Test
    fun builtInFieldsUseTheirOutboundKeys() {
        val lead = lead()
        val other = UUID.randomUUID()
        val changed = input(lead).copy(
            name = "Ana B", email = "b@x.com", phone = "+5072", stageId = other, interestId = other, source = "ads", language = "en",
            message = "Nuevo", details = "x", estimatedValue = null, followUpOn = LocalDate.of(2026, 4, 1),
            targetStart = LocalDate.of(2026, 5, 1),
        )
        assertEquals(
            listOf("name", "email", "phone", "stage", "interest", "followUp", "source", "language", "message", "details", "estimatedValue", "stay"),
            CrmOutbound.changedKeys(lead, changed, fields),
        )
    }

    @Test
    fun ownersChangeOnlyWhenTheListDiffersAndWasSent() {
        val lead = lead()
        assertEquals(emptyList(), CrmOutbound.changedKeys(lead, input(lead).copy(ownerUserIds = null), fields))
        assertEquals(emptyList(), CrmOutbound.changedKeys(lead, input(lead).copy(ownerUserIds = listOf(userA)), fields))
        assertEquals(listOf("owners"), CrmOutbound.changedKeys(lead, input(lead).copy(ownerUserIds = listOf(userA, userB)), fields))
        // La lista con la que se dibujo el formulario manda: si no cambio respecto a ella, el formulario no toco a los responsables.
        assertEquals(emptyList(), CrmOutbound.changedKeys(lead, input(lead).copy(ownerUserIds = listOf(userB)), fields, ownersBefore = listOf(userB)))
    }

    @Test
    fun customFieldsUseThePrefixAndSkipFilesAndButtons() {
        val lead = lead(JsonObject(mapOf("cf_plan" to JsonPrimitive("Basico"), "cf_presupuesto" to JsonPrimitive("10"))))
        val changed = input(lead).copy(
            custom = mapOf("cf_plan" to JsonPrimitive("Pro"), "cf_presupuesto" to JsonPrimitive("10"), "cf_contrato" to JsonPrimitive("x"), "cf_firmado" to JsonPrimitive(true)),
        )
        assertEquals(listOf("custom:cf_plan", "custom:cf_firmado"), CrmOutbound.changedKeys(lead, changed, fields))
        // Un valor que se borra tambien cuenta.
        assertEquals(listOf("custom:cf_plan"), CrmOutbound.changedKeys(lead, input(lead).copy(custom = mapOf("cf_plan" to null)), fields))
    }

    // ---- Campos personalizados ----

    @Test
    fun customFieldTypesMapToOutboundTypes() {
        assertEquals(OutboundFieldType.Status, CrmOutboundSource.customType(plan))
        assertEquals(OutboundFieldType.Options, CrmOutboundSource.customType(colors))
        assertEquals(OutboundFieldType.Number, CrmOutboundSource.customType(budget))
        assertEquals(OutboundFieldType.Boolean, CrmOutboundSource.customType(signed))
        assertEquals(OutboundFieldType.Date, CrmOutboundSource.customType(day))
        assertEquals(OutboundFieldType.LongText, CrmOutboundSource.customType(CrmField(UUID.randomUUID(), "k", "K", CrmFieldType.Textarea)))
        assertEquals(OutboundFieldType.Email, CrmOutboundSource.customType(CrmField(UUID.randomUUID(), "k", "K", CrmFieldType.Email)))
        assertEquals(OutboundFieldType.Phone, CrmOutboundSource.customType(CrmField(UUID.randomUUID(), "k", "K", CrmFieldType.Phone)))
        assertEquals(OutboundFieldType.Url, CrmOutboundSource.customType(CrmField(UUID.randomUUID(), "k", "K", CrmFieldType.Url)))
        assertEquals(OutboundFieldType.Text, CrmOutboundSource.customType(CrmField(UUID.randomUUID(), "k", "K", CrmFieldType.Text)))
        assertNull(CrmOutboundSource.customType(contract))
        assertNull(CrmOutboundSource.customType(button))
    }

    @Test
    fun leadFieldsListBuiltInsThenCustomWithOptions() {
        val stages = listOf(CrmStage(stageId, "Nuevo", StageKind.Open, StageTone.Info, 0))
        val defs = CrmOutboundSource.leadFields(stages, listOf(CrmInterest(UUID.randomUUID(), "Venta", 0)), fields)
        val keys = defs.map { it.key }
        assertEquals(
            listOf("name", "email", "phone", "stage", "interest", "owners", "followUp", "source", "language", "message", "details", "estimatedValue", "stay", "createdAt"),
            keys.take(14),
        )
        assertEquals(listOf("custom:cf_plan", "custom:cf_colores", "custom:cf_presupuesto", "custom:cf_firmado", "custom:cf_fecha"), keys.drop(14))
        assertEquals(listOf("Nuevo"), defs.first { it.key == "stage" }.options)
        assertEquals(listOf("Basico", "Pro"), defs.first { it.key == "custom:cf_plan" }.options)
        assertEquals(OutboundFieldType.Timeline, defs.first { it.key == "stay" }.type)
        assertEquals(OutboundFieldType.Person, defs.first { it.key == "owners" }.type)
    }

    @Test
    fun customValuesComeOutTyped() {
        assertEquals(OutboundValue.Text("Pro"), CrmOutboundSource.customValue(plan, JsonPrimitive("Pro")))
        assertEquals(OutboundValue.Options(listOf("Rojo", "Azul")), CrmOutboundSource.customValue(colors, JsonArray(listOf(JsonPrimitive("Rojo"), JsonPrimitive("Azul")))))
        assertEquals(OutboundValue.Number(BigDecimal("12.5")), CrmOutboundSource.customValue(budget, JsonPrimitive("12.5")))
        assertEquals(OutboundValue.Bool(true), CrmOutboundSource.customValue(signed, JsonPrimitive(true)))
        assertEquals(OutboundValue.Date(LocalDate.of(2026, 7, 4)), CrmOutboundSource.customValue(day, JsonPrimitive("2026-07-04")))
        assertNull(CrmOutboundSource.customValue(budget, JsonPrimitive("abc")))
        assertNull(CrmOutboundSource.customValue(plan, null))
        assertNull(CrmOutboundSource.customValue(contract, JsonPrimitive("x")))
    }

    @Test
    fun recordHasTitleAndOnlyFieldsWithValue() {
        val lead = lead(JsonObject(mapOf("cf_plan" to JsonPrimitive("Pro"))))
        val record = CrmOutboundSource.recordOf(lead, "Nuevo", null, listOf(OutboundPerson(userA.toString(), "Luz", "luz@x.com")), fields, importKey = "monday:9")
        assertEquals("Ana", record.title)
        assertEquals("monday:9", record.importKey)
        assertEquals(OutboundValue.Text("Nuevo"), record.values["stage"])
        assertEquals(OutboundValue.Text("Pro"), record.values["custom:cf_plan"])
        assertEquals(OutboundValue.Date(LocalDate.of(2026, 3, 1)), record.values["createdAt"])
        assertTrue("interest" !in record.values && "details" !in record.values && "stay" !in record.values)
        assertEquals("ana@x.com", CrmOutboundSource.recordOf(lead.copy(name = ""), null, null, emptyList(), fields).title)
    }

    // ---- Mapeo de la importacion invertido ----

    @Test
    fun importMappingIsInvertedToOutboundKeys() {
        val columns = mapOf(
            "Name" to "name", "Email" to "email", "Phone" to "phone", "Status" to "stage", "Person" to "owner", "Check-in" to "targetStart",
            "Rango" to "dates", "Follow" to "followUpOn", "Plan" to "crm:cf_plan", "Notas" to "note", "Doc" to "cliente:doc", "Otro" to "",
        )
        assertEquals(
            mapOf(
                "Name" to "name", "Email" to "email", "Phone" to "phone", "Status" to "stage", "Person" to "owners", "Check-in" to "stay",
                "Rango" to "stay", "Follow" to "followUp", "Plan" to "custom:cf_plan",
            ),
            CrmOutboundSource.invertMapping(columns),
        )
        assertEquals(emptyMap(), CrmOutboundSource.invertMapping(emptyMap()))
    }

    // ---- Extensiones que reciben los avisos ----

    private class FakeSink(override val sinkId: String, override val moduleId: String, permissions: List<String>) : ServerExtension, OutboundSink {
        override val manifest: ExtensionManifest = CrmExtensions.manifest(sinkId, "prueba", permissions)
        override fun wants(tenant: Connection, event: OutboundEvent) = true
        override fun deliver(delivery: OutboundDelivery): OutboundResult = OutboundResult.Skipped("prueba")
    }

    @Test
    fun onlySinksOfTheCrmWithTheExportPermissionAreKept() {
        val allowed = FakeSink("monday", "crm", listOf(CrmExtensionPermissions.LeadsExport))
        val withoutPermission = FakeSink("otro", "crm", listOf(CrmExtensionPermissions.LeadsUpdate))
        val otherModule = FakeSink("membresia_monday", "membresia", listOf(CrmExtensionPermissions.LeadsExport))
        val notASink = object : ServerExtension {
            override val manifest: ExtensionManifest = CrmExtensions.manifest("meta", "prueba", listOf(CrmExtensionPermissions.LeadsExport))
        }
        try {
            CrmModuleExtensions.link(listOf(allowed, withoutPermission, otherModule, notASink))
            assertEquals(listOf<OutboundSink>(allowed), CrmModuleExtensions.outboundSinks())
        } finally {
            CrmModuleExtensions.link(emptyList())
        }
        assertEquals(emptyList(), CrmModuleExtensions.outboundSinks())
    }

    @Test
    fun exportPermissionIsDeclaredByTheModule() {
        assertTrue(CrmExtensionPermissions.LeadsExport in CrmExtensionPermissions.all)
        assertEquals("crm.leads.export", CrmExtensionPermissions.LeadsExport)
    }

    // ---- Personas sin usuario ----

    @Test
    fun peopleMissingFromUsersKeepAPlaceholderWithoutEmail() {
        val found = mapOf(userA to OutboundPerson(userA.toString(), "Luz", "luz@x.com"))
        assertEquals(OutboundPerson(userA.toString(), "Luz", "luz@x.com"), CrmOutbound.personOrPlaceholder(userA, found))
        val missing = CrmOutbound.personOrPlaceholder(userB, found)
        assertEquals(userB.toString(), missing.userId)
        assertEquals("Usuario ${userB.toString().take(8)}", missing.name)
        assertNull(missing.email)
    }

    // ---- Importacion: solo lo que cambio ----

    @Test
    fun reimportedRowThatChangedNothingGivesNoKeys() {
        val before = lead(JsonObject(mapOf("cf_plan" to JsonPrimitive("Pro"))))
        assertEquals(emptyList(), CrmOutbound.diffKeys(before, before.copy(), fields))
    }

    @Test
    fun reimportedRowGivesTheKeysItChanged() {
        val before = lead(JsonObject(mapOf("cf_plan" to JsonPrimitive("Basico"), "cf_presupuesto" to JsonPrimitive("5"))))
        val after = before.copy(
            message = "Nuevo", followUpOn = LocalDate.of(2026, 5, 2), ownerUserIds = listOf(userA, userB),
            customFields = JsonObject(mapOf("cf_plan" to JsonPrimitive("Pro"))),
        )
        assertEquals(listOf("owners", "followUp", "message", "custom:cf_plan", "custom:cf_presupuesto"), CrmOutbound.diffKeys(before, after, fields))
    }

    // ---- Herramientas MCP de un proveedor que tambien es fuente de importacion ----

    private val pingTool = McpTool("fake_ping", "Ping", "Prueba", "crm.leads.view") { JsonNull }

    private class Both(tool: McpTool) : ServerExtension, CrmScreenProvider, CrmImportSource {
        override val manifest: ExtensionManifest = CrmExtensions.manifest("fake", "prueba", emptyList())
        override val extensionId = "fake"
        override val id = "fake"
        override val label = "Fake"
        override val readingLabel = "Leyendo"
        override val sections = emptyList<CrmExtensionSection>()
        override val mcpTools = listOf(tool)
        override fun screen(context: CrmScreens.Context, screen: String, params: Map<String, String>, submitted: Map<String, String>?): List<UiNode> = emptyList()
        override fun action(context: CrmScreens.Context, actor: CrmActor, screen: String, op: String, params: Map<String, String>, values: Map<String, String>) =
            CrmExtensionActionResult("home", null)
        override fun readQueued(tenant: Connection) = Unit
        override fun runPending(tenant: Connection, limit: Int, today: LocalDate, files: ModuleFiles?, budget: Duration) = 0
    }

    @Test
    fun toolsTheSameObjectGivesAsImportSourceAreNotRegisteredTwice() {
        val both = Both(pingTool)
        assertEquals(setOf("fake_ping"), CrmScreenProviders.sourceToolNames(both))
        try {
            CrmModuleExtensions.link(listOf(both))
            // Solo por el camino de la fuente de importacion, y una sola vez.
            assertTrue(CrmScreenProviders.mcpTools.none { it.name == "fake_ping" })
            assertEquals(1, CrmImportSources.mcpTools.count { it.name == "fake_ping" })
        } finally {
            CrmModuleExtensions.link(emptyList())
        }
    }

    @Test
    fun guardOnlyWrapsToolsOfAnExtension() {
        assertTrue(CrmImportSources.guarded(null, pingTool) === pingTool)
        assertEquals("fake_ping", CrmImportSources.guarded("fake", pingTool).name)
    }
}
