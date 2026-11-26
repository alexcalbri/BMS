package com.ideasdeveloper.bms.modules.crm

import com.ideasdeveloper.bms.server.i18n.I18n
import com.ideasdeveloper.bms.server.i18n.I18nCatalogCheck
import com.ideasdeveloper.bms.server.i18n.TextTranslation
import com.ideasdeveloper.bms.server.i18n.TextTranslationStore
import com.ideasdeveloper.bms.server.i18n.loadModuleCatalog
import com.ideasdeveloper.bms.server.i18n.localized
import com.ideasdeveloper.bms.server.i18n.tr
import com.ideasdeveloper.bms.server.modules.ModuleNotifier
import com.ideasdeveloper.bms.server.modules.NotificationService
import com.ideasdeveloper.bms.server.modules.NotificationStore
import com.ideasdeveloper.bms.server.modules.StoredNotification
import com.ideasdeveloper.bms.server.i18n.localizedPayload
import com.ideasdeveloper.bms.server.modules.ModulePermissions
import com.ideasdeveloper.bms.ui.UiButton
import com.ideasdeveloper.bms.ui.UiCard
import com.ideasdeveloper.bms.ui.UiForm
import com.ideasdeveloper.bms.ui.UiNode
import com.ideasdeveloper.bms.ui.UiSuggestions
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.lang.reflect.Proxy
import java.sql.Connection
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Spanish is the source and the key; English comes from the catalog (I18N.md). Labels are shown translated, values stay Spanish. */
class CrmI18nTest {
    // Tests build no ModuleRegistry, so the module's catalog is loaded by hand (idempotent).
    @BeforeTest
    fun loadCatalog() = loadModuleCatalog(CrmServerModule)

    @Test
    fun catalogIsComplete() = I18nCatalogCheck.assertModule("crm", CrmServerModule.definition)

    private val tenant: Connection = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(Connection::class.java)) { _, _, _ -> error("not used") } as Connection

    private fun <T> english(block: () -> T): T = I18n.withLanguage("en", block)

    // ---- Labels and values ----

    @Test
    fun enumLabelsAreTranslatedWhenShownButTheValueStaysSpanish() {
        // The label is what the app posts back and what the server parses: it never changes with the language.
        assertEquals("Lista desplegable", CrmFieldType.Dropdown.label)
        assertEquals(CrmFieldType.Dropdown, english { CrmFieldType.byLabel("Lista desplegable") })
        assertEquals("Dropdown list", english { tr(CrmFieldType.Dropdown.label) })
        assertEquals("Open", english { tr(StageKind.Open.label) })
        assertEquals(StageKind.Won, english { StageKind.fromLabel("Ganada") })
        assertEquals("Blue", english { tr(StageTone.Info.label) })
        assertEquals("Change to a stage", english { tr(CrmTriggerType.StageChanged.label) })
        assertEquals("A field is filled", english { tr(CrmConditionKind.FieldFilled.label) })
        assertEquals("Notify users", english { tr(CrmActionKind.Notify.label) })
        assertEquals("Lead assignees", english { tr(CrmNotifyWho.Assigned.label) })
        assertEquals("Google Cloud Translation", english { tr(CrmTranslate.providerLabels.getValue("google")) })
        assertEquals("AI via API (Claude)", english { tr(CrmTranslate.providerLabels.getValue("anthropic")) })
        // The merge of the translation form reads the Spanish label.
        val merged = english { CrmTranslate.merge(CrmTranslationSettings(), mapOf("provider" to "IA por API (Claude)", "apiKey" to "k"), emptyList()) }
        assertEquals("anthropic", merged.provider)
    }

    @Test
    fun selectsGetOptionLabelsThroughCoreWhileOptionsStaySpanish() {
        val field = com.ideasdeveloper.bms.ui.UiField(
            "type", "Tipo", type = "dropdown", options = CrmFieldType.entries.map { it.label },
        )
        val localized = english { field.localized() }
        assertEquals(field.options, localized.options)
        assertEquals("Dropdown list", localized.optionLabels!![CrmFieldType.entries.indexOf(CrmFieldType.Dropdown)])
        assertEquals("Yes / No", localized.optionLabels!![CrmFieldType.entries.indexOf(CrmFieldType.Boolean)])
    }

    @Test
    fun theDefinitionIsTranslatedByCoreFromTheCatalog() {
        val english = english { CrmServerModule.definition.localized() }
        assertEquals("Use the CRM", english.permissions.first { it.id == CrmScreens.View }.title)
        assertEquals("Delete leads", english.permissions.first { it.id == CrmScreens.Delete }.title)
    }

    // ---- Texts of screens and summaries ----

    @Test
    fun customerSuggestionsAreInEnglish() {
        val customer = CrmRepository.CustomerSuggestion(UUID.randomUUID(), "Ana", "a@x.com", "", 2, false)
        val spanish = CrmScreens.customerSuggestions(listOf(customer))
        val english = english { CrmScreens.customerSuggestions(listOf(customer)) }
        assertEquals("Clientes existentes", spanish.heading)
        assertEquals("Existing customers", english.heading)
        assertTrue("2 open leads" in english.items.single().subtitle)
        assertTrue("2 leads abiertos" in spanish.items.single().subtitle)
        assertEquals(spanish.items.single().values, english.items.single().values)
        assertEquals(UiSuggestions::class, english::class)
    }

    @Test
    fun followUpBadgesAndAutomationSummariesAreInEnglish() {
        val today = LocalDate.of(2026, 11, 26)
        assertEquals("Seguimiento hoy", followUpState(today, today)?.first)
        assertEquals("Follow-up today", english { followUpState(today, today)?.first })
        assertEquals("Follow-up overdue 2026-11-20", english { followUpState(LocalDate.of(2026, 11, 20), today)?.first })
        val stage = CrmStage(UUID.randomUUID(), "Nuevo", StageKind.Open, StageTone.Info, 0)
        val refs = CrmAutomationRefs(stages = listOf(stage), fields = listOf(CrmField(UUID.randomUUID(), "fecha_ingreso", "Fecha de ingreso", CrmFieldType.Date)))
        val date = CrmTrigger(CrmTriggerType.Date, field = "fecha_ingreso", offsetDays = -1, hour = 9)
        assertEquals("1 dia(s) antes de Fecha de ingreso, a las 09:00", CrmAutomationSummary.trigger(date, refs))
        assertEquals("1 day(s) before Fecha de ingreso, at 09:00", english { CrmAutomationSummary.trigger(date, refs) })
        assertEquals("Pasar a Nuevo", CrmAutomationSummary.action(CrmAction(CrmActionKind.SetStage, stageId = stage.id), refs))
        assertEquals("Move to Nuevo", english { CrmAutomationSummary.action(CrmAction(CrmActionKind.SetStage, stageId = stage.id), refs) })
    }

    // ---- Errors ----

    @Test
    fun validationErrorsAreInEnglishAndSpanishIsUnchanged() {
        val spanish = assertFailsWith<CrmValidationException> { CrmForms.parseStage(mapOf("name" to "")) }
        assertEquals("El nombre de la etapa debe tener entre 1 y 60 caracteres.", spanish.message)
        val english = english { assertFailsWith<CrmValidationException> { CrmForms.parseStage(mapOf("name" to "")) } }
        assertEquals("The stage name must be between 1 and 60 characters.", english.message)
        val note = english { assertFailsWith<CrmValidationException> { CrmForms.note(mapOf("note" to " ")) } }
        assertEquals("Write the note.", note.message)
        val section = english { assertFailsWith<CrmValidationException> { CrmFieldSections.parse(mapOf("sectionName" to ""), emptyList(), null) } }
        assertEquals("Write the name of the section.", section.message)
        val bulk = english { assertFailsWith<CrmActionNotice> { CrmBoardActions.parseCards(null) } }
        assertEquals("Tick at least one lead.", bulk.message)
    }

    // ---- Stored text and values the server parses ----

    @Test
    fun storedActivityAndRunTextStayInSpanish() {
        val old = CrmLead(
            id = UUID.randomUUID(), customerId = UUID.randomUUID(), name = "Ana", email = "", phone = "", stageId = UUID.randomUUID(), interestId = null,
            ownerUserIds = emptyList(), source = "", language = "", message = "uno", details = "", targetStart = null, targetEnd = null, estimatedValue = null,
            followUpOn = null, stageChangedAt = Instant.EPOCH, closedAt = null, createdAt = Instant.EPOCH, customFields = JsonObject(emptyMap()),
        )
        val input = CrmLeadInput(
            name = "Ana", email = "", phone = "", stageId = old.stageId, interestId = null, ownerUserIds = null, source = "", language = "",
            message = "dos", details = "", targetStart = null, targetEnd = null, estimatedValue = null, followUpOn = null,
        )
        assertEquals("Mensaje modificado", english { CrmRepository.leadChanges(old, input) })
        // The follow-up of a lead is written to the history in Spanish whatever the language of who edits it.
        assertEquals("Archivado automaticamente al hacer check-out.", english { CrmExit.ActivityText })
    }

    // ---- Notices of staff in the language of each recipient ----

    private class Rows : NotificationStore {
        val rows = mutableListOf<StoredNotification>()
        override fun insert(notification: StoredNotification) { rows += notification }
        override fun list(userId: UUID, limit: Int, offset: Int, unreadOnly: Boolean) = rows.filter { it.userId == userId }
        override fun unreadCount(userId: UUID) = 0
        override fun markRead(userId: UUID, id: UUID, at: Instant) = false
        override fun markAllRead(userId: UUID, at: Instant) = 0
        override fun deleteCreatedBefore(before: Instant, limit: Int) = 0
    }

    @Test
    fun automationNoticesAreWrittenInTheLanguageOfEachRecipient() {
        val ana = UUID.randomUUID()
        val luis = UUID.randomUUID()
        val rows = Rows()
        val notifier = ModuleNotifier("crm", NotificationService({ rows })) { _, userId -> if (userId == ana) "en" else "es" }
        val lead = CrmLead(
            id = UUID.randomUUID(), customerId = UUID.randomUUID(), name = "Maria", email = "", phone = "", stageId = UUID.randomUUID(), interestId = null,
            ownerUserIds = emptyList(), source = "", language = "", message = "", details = "", targetStart = null, targetEnd = null, estimatedValue = null,
            followUpOn = null, stageChangedAt = Instant.EPOCH, closedAt = null, createdAt = Instant.EPOCH, customFields = JsonObject(emptyMap()),
        )
        val rule = CrmAutomation(UUID.randomUUID(), "Bienvenida", true, CrmTrigger(CrmTriggerType.Manual))
        val told = CrmAutomationNotices.deliver(tenant, notifier, lead, listOf(ana, luis)) {
            CrmAutomationNotices.missingTexts(rule, lead, listOf("correo del lead"), null, ran = true)
        }
        assertEquals(2, told)
        val forAna = rows.rows.single { it.userId == ana }
        val forLuis = rows.rows.single { it.userId == luis }
        assertEquals("Missing data: Bienvenida", forAna.title)
        assertEquals("The lead Maria is missing correo del lead. The automation did not run.", forAna.text)
        assertEquals("Faltan datos: Bienvenida", forLuis.title)
        assertEquals("Al lead Maria le falta correo del lead. La automatizacion no se ejecuto.", forLuis.text)
        // What the author of a rule typed goes as written.
        rows.rows.clear()
        CrmAutomationNotices.deliver(tenant, notifier, lead, listOf(ana)) { "Mi aviso" to "Mi texto" }
        assertEquals("Mi aviso", rows.rows.single().title)
    }

    // ---- Dynamic translation (I18N.md section 8) ----

    private class Cache : TextTranslationStore {
        val rows = mutableMapOf<Pair<String, String>, String>()
        val keptSources = mutableListOf<Boolean>()
        override fun find(tenant: Connection, language: String, hashes: Collection<String>) =
            hashes.mapNotNull { hash -> rows[language to hash]?.let { hash to it } }.toMap()
        override fun saveAutomatic(tenant: Connection, language: String, translations: Map<String, String>, keepSource: Boolean) {
            keptSources += keepSource
            translations.forEach { (source, translated) -> rows[language to TextTranslation.hash(source)] = translated }
        }
        override fun saveManual(tenant: Connection, language: String, source: String, translated: String) {
            rows[language to TextTranslation.hash(source)] = translated
        }
        override fun deleteManual(tenant: Connection, language: String, source: String) {
            rows.remove(language to TextTranslation.hash(source))
        }
        override fun findManual(tenant: Connection, language: String, hashes: Collection<String>) = find(tenant, language, hashes)
    }

    private class Provider : CrmHttp {
        val asked = mutableListOf<String>()
        override fun post(url: String, headers: Map<String, String>, body: String): CrmHttpReply {
            val content = Json.parseToJsonElement(body).jsonObject.getValue("messages").jsonArray[0].jsonObject.getValue("content").jsonPrimitive.content
            val text = Regex("<body>(.*)</body>", RegexOption.DOT_MATCHES_ALL).find(content)!!.groupValues[1]
            asked += text
            val answer = "<subject>.</subject><body>EN $text</body>"
            return CrmHttpReply(200, """{"content":[{"type":"text","text":"$answer"}]}""")
        }
    }

    @Test
    fun theCrmProviderTranslatesStageNamesForCoreAndAnsweringNullWithoutAKey() {
        val http = Provider()
        val configured = CrmTranslationSettings(provider = "anthropic", apiKey = "i18n-test-key")
        val translator = CrmTranslate.textTranslator(http) { configured }
        assertEquals(listOf("EN Nuevo", "EN Ganado"), translator.translate(tenant, listOf("Nuevo", "Ganado"), "es", "en"))
        assertEquals(listOf("Nuevo", "Ganado"), http.asked)
        // No provider or no key: Core shows the originals.
        assertNull(CrmTranslate.textTranslator(http) { CrmTranslationSettings() }.translate(tenant, listOf("Nuevo"), "es", "en"))
        assertNull(CrmTranslate.textTranslator(http) { CrmTranslationSettings(provider = "anthropic") }.translate(tenant, listOf("Nuevo"), "es", "en"))
    }

    @Test
    fun stageNamesAndNotesGoThroughCoreOnlyForAnotherLanguageAndManualWins() {
        val http = Provider()
        val cache = Cache()
        // Core opens the company's database for the background fill and closes it: a connection that accepts that.
        val openable = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(Connection::class.java)) { _, _, _ -> null } as Connection
        val service = TextTranslation({ openable }, { "es" }, cache, waitMillis = 3_000)
        service.registerTextTranslator(CrmModule.id, CrmTranslate.textTranslator(http) { CrmTranslationSettings(provider = "anthropic", apiKey = "i18n-test-key", translateNotes = true) })
        service.setManual(tenant, "Nuevo", "en", "Brand new")
        // One batch for the whole screen; the manual translation is not asked again.
        val names = service.translate("acme", tenant, listOf("Nuevo", "Contactado", "Ganado"), "en")
        assertEquals(listOf("Brand new", "EN Contactado", "EN Ganado"), names)
        assertEquals(listOf("Contactado", "Ganado"), http.asked)
        // Notes keep the original and carry the translation separately.
        val notes = service.noteTranslations("acme", tenant, listOf("Llamar manana", "Contactado"), "en")
        assertEquals(listOf("EN Llamar manana", "EN Contactado"), notes)
        // The company language shows the originals and asks nobody.
        http.asked.clear()
        assertEquals(listOf("Nuevo"), service.translate("acme", tenant, listOf("Nuevo"), "es"))
        assertTrue(http.asked.isEmpty())
    }

    @Test
    fun theNotesSwitchIsOffByDefaultAndNamesDoNotDependOnIt() {
        val http = Provider()
        val cache = Cache()
        val openable = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(Connection::class.java)) { _, _, _ -> null } as Connection
        val service = TextTranslation({ openable }, { "es" }, cache, waitMillis = 3_000)
        val off = CrmTranslationSettings(provider = "anthropic", apiKey = "i18n-test-key")
        assertFalse(off.translateNotes)
        service.registerTextTranslator(CrmModule.id, CrmTranslate.textTranslator(http) { off })
        assertEquals(listOf<String?>(null), service.noteTranslations("acme", tenant, listOf("Llamar manana"), "en"))
        assertTrue(http.asked.isEmpty())
        // Names are translated all the same.
        assertEquals(listOf("EN Nuevo"), service.translate("acme", tenant, listOf("Nuevo"), "en"))
        assertEquals(listOf("Nuevo"), http.asked)
        // The switch needs a ready provider; on, notes go and no source text is kept.
        assertFalse(CrmTranslate.textTranslator(http) { CrmTranslationSettings(translateNotes = true) }.notesAllowed(tenant))
        val on = CrmTranslate.merge(off, mapOf("translateNotes" to "true"), emptyList())
        assertTrue(on.translateNotes)
        assertFalse(CrmTranslate.merge(on, mapOf("translateNotes" to "false"), emptyList()).translateNotes)
        assertTrue(CrmTranslate.merge(on, emptyMap(), emptyList()).translateNotes)
        val enabled = TextTranslation({ openable }, { "es" }, cache, waitMillis = 3_000)
        enabled.registerTextTranslator(CrmModule.id, CrmTranslate.textTranslator(http) { on })
        assertEquals(listOf<String?>("EN Llamar"), enabled.noteTranslations("acme", tenant, listOf("Llamar"), "en"))
        assertEquals(listOf(false), cache.keptSources.takeLast(1))
    }

    @Test
    fun importedNotesAreNeverSentToTheProvider() {
        val at = java.time.Instant.now()
        val staff = CrmActivity("note", "Llamar manana", null, null, "Ana", at, UUID.randomUUID(), UUID.randomUUID())
        val imported = CrmActivity("note", "Cliente dice que su pasaporte es X", null, null, "monday", at, UUID.randomUUID(), null)
        val system = CrmActivity("stage", "Nuevo -> Ganado", null, null, "Sistema", at, UUID.randomUUID(), null)
        val blank = CrmActivity("note", "  ", null, null, "Ana", at, UUID.randomUUID(), UUID.randomUUID())
        val noted = CrmTexts.staffNotes(listOf(imported, staff, system, blank))
        assertEquals(listOf(staff), noted)
        val http = Provider()
        val openable = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(Connection::class.java)) { _, _, _ -> null } as Connection
        val service = TextTranslation({ openable }, { "es" }, Cache(), waitMillis = 3_000)
        service.registerTextTranslator(CrmModule.id, CrmTranslate.textTranslator(http) { CrmTranslationSettings(provider = "anthropic", apiKey = "i18n-test-key", translateNotes = true) })
        assertEquals(listOf<String?>("EN Llamar manana"), service.noteTranslations("acme", tenant, noted.map { it.body }, "en"))
        assertEquals(listOf("Llamar manana"), http.asked)
    }

    @Test
    fun withoutCoreOrInAnyFailureTheStageNamesAreShownAsTheyAre() {
        // CrmTexts never fails a screen: no access to Core (as in these tests) returns the originals.
        assertEquals(listOf("Nuevo"), english { CrmTexts.labels(tenant, "acme", listOf("Nuevo")) })
        assertEquals(listOf<String?>(null), english { CrmTexts.notes(tenant, "acme", listOf("Nota")) })
        assertEquals(listOf("en"), CrmTexts.otherLanguages("acme"))
    }

    // ---- Screens ----

    private val offline: Connection = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(Connection::class.java)) { _, _, _ -> throw java.sql.SQLException("offline") } as Connection
    private val stages = listOf(CrmStage(UUID.randomUUID(), "Nuevo", StageKind.Open, StageTone.Info, 0), CrmStage(UUID.randomUUID(), "Ganado", StageKind.Won, StageTone.Success, 1))

    private fun context(vararg granted: String) = CrmScreens.Context(
        offline, ModulePermissions("acme", "token", false, granted.toSet(), userId = UUID.randomUUID()),
        CrmScope(null, true, true, false, emptySet()), stages, emptyList(), emptyList(),
    )

    private fun forms(nodes: List<UiNode>): List<UiForm> = nodes.flatMap { node ->
        when (node) {
            is UiForm -> listOf(node)
            is UiCard -> forms(node.children)
            else -> emptyList()
        }
    }

    @Test
    fun theNewLeadScreenIsInEnglishButOptionsAndParamsStaySpanish() {
        val everything = context(CrmScreens.View, CrmScreens.Manage, CrmScreens.Reception, CrmScreens.Settings)
        val spanish = CrmScreens.build("new", everything, emptyMap())
        val english = english { localizedPayload(CrmScreens.build("new", everything, emptyMap())) }
        assertEquals("Nuevo lead", spanish.title)
        assertEquals("New lead", english.title)
        assertEquals(listOf("New lead", "Leads", "Unqualified", "Follow-ups", "Settings"), english.navigation!!.items.map { it.label })
        assertEquals(spanish.navigation!!.items.map { it.id }, english.navigation!!.items.map { it.id })
        val form = forms(english.body).single()
        assertEquals("Stage", form.fields.first { it.key == "stage" }.label)
        assertEquals("Customer data", form.fields.first { it.key == "name" }.section)
        // What the app posts back is the stored name, whatever the language.
        assertEquals(listOf("Nuevo", "Ganado"), form.fields.first { it.key == "stage" }.options)
        assertEquals("Create lead", (form.submit as UiButton).label)
        assertEquals(
            (forms(spanish.body).single().submit as UiButton).action,
            (form.submit as UiButton).action,
        )
    }

    @Test
    fun aScreenWithoutPermissionAnswersInEnglish() {
        val viewOnly = context(CrmScreens.View)
        val english = english { CrmScreens.build("settings", viewOnly, emptyMap()) }
        assertEquals("You do not have permission to view this section.", english.notice?.text)
        assertEquals("Settings", english.title)
    }
}
