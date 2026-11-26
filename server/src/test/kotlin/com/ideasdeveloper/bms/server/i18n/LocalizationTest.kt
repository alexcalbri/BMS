package com.ideasdeveloper.bms.server.i18n

import com.ideasdeveloper.bms.server.modules.ModuleActionDefinition
import com.ideasdeveloper.bms.server.modules.ModuleDefinition
import com.ideasdeveloper.bms.server.modules.ModuleFieldDefinition
import com.ideasdeveloper.bms.server.modules.ModulePermissionDefinition
import com.ideasdeveloper.bms.server.modules.ModuleViewDefinition
import com.ideasdeveloper.bms.ui.UiBoard
import com.ideasdeveloper.bms.ui.UiBoardColumn
import com.ideasdeveloper.bms.ui.UiButton
import com.ideasdeveloper.bms.ui.UiCard
import com.ideasdeveloper.bms.ui.UiColumn
import com.ideasdeveloper.bms.ui.UiField
import com.ideasdeveloper.bms.ui.UiForm
import com.ideasdeveloper.bms.ui.UiJson
import com.ideasdeveloper.bms.ui.UiList
import com.ideasdeveloper.bms.ui.UiNavigate
import com.ideasdeveloper.bms.ui.UiScreen
import com.ideasdeveloper.bms.ui.UiSuggestions
import com.ideasdeveloper.bms.ui.UiTable
import com.ideasdeveloper.bms.ui.UiTimeline
import com.ideasdeveloper.bms.ui.UiTimelineEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class LocalizationTest {
    init {
        I18n.register(
            "localization-test", "en",
            mapOf(
                "Abierto" to "Open", "Cerrado" to "Closed", "Modulo de prueba" to "Test module", "Ver prueba" to "See test",
                "Estado de prueba" to "Test status", "Permiso de prueba" to "Test permission", "Descripcion de prueba" to "Test description",
                "Seccion de prueba" to "Test section", "Ayuda de prueba" to "Test help", "Accion de prueba" to "Test action",
            ),
        )
    }

    private val form = UiForm(
        id = "f",
        fields = listOf(
            UiField("status", "Estado", type = "select", options = listOf("Abierto", "Cerrado", "Otro")),
            UiField("name", "Nombre"),
            UiField("kind", "Tipo", type = "dropdown", options = listOf("Abierto"), optionLabels = listOf("Propio")),
        ),
    )
    private val screen = UiScreen(
        title = "Pantalla",
        body = listOf(
            UiColumn(listOf(UiCard(listOf(form, UiTable(emptyList(), emptyList()), UiTable(emptyList(), emptyList(), emptyText = "Nada aqui"))))),
            UiList(emptyList()),
            UiTimeline(emptyList()),
            UiBoard(listOf(UiBoardColumn("a", "A", footer = UiButton("Ver", UiNavigate("x")))), emptyText = "Sin elementos."),
        ),
    )

    @Test
    fun sourceLanguageLeavesPayloadsUntouched() {
        assertSame(screen, screen.localized())
        assertSame(screen, localizedPayload(screen))
        // Older clients: no optionLabels in the JSON when nobody set them.
        assertFalse(UiJson.encodeToString(UiScreen.serializer(), screen.localized()).contains("optionLabels\":[\"Open"))
    }

    @Test
    fun optionLabelsAreFilledWhileValuesStayInTheSource() = I18n.withLanguage("en") {
        val localized = screen.localized()
        val fields = ((localized.body[0] as UiColumn).children[0] as UiCard).children.filterIsInstance<UiForm>().single().fields
        assertEquals(listOf("Abierto", "Cerrado", "Otro"), fields[0].options)
        assertEquals(listOf("Open", "Closed", "Otro"), fields[0].optionLabels)
        // Labels are not guessed: the module translates them with tr where it creates them.
        assertEquals("Estado", fields[0].label)
        assertNull(fields[1].optionLabels)
        // Labels set by the module are kept.
        assertEquals(listOf("Propio"), fields[2].optionLabels)
        assertTrue(UiJson.encodeToString(UiScreen.serializer(), localized).contains("\"optionLabels\":[\"Open\",\"Closed\",\"Otro\"]"))
    }

    @Test
    fun defaultEmptyTextsAreTranslatedButModuleTextsAreNot() = I18n.withLanguage("en") {
        val localized = screen.localized()
        val card = (localized.body[0] as UiColumn).children[0] as UiCard
        val tables = card.children.filterIsInstance<UiTable>()
        assertEquals("No data.", tables[0].emptyText)
        assertEquals("Nada aqui", tables[1].emptyText)
        assertEquals("No data.", (localized.body[1] as UiList).emptyText)
        assertEquals("No activity.", (localized.body[2] as UiTimeline).emptyText)
        assertEquals("No items.", (localized.body[3] as UiBoard).emptyText)
        assertEquals("Pantalla", localized.title)
    }

    @Test
    fun localizedPayloadDispatchesOnTheRuntimeType() = I18n.withLanguage("en") {
        val node: Any = form
        assertEquals(listOf("Open", "Closed", "Otro"), (localizedPayload(node) as UiForm).fields[0].optionLabels)
        val list = localizedPayload(listOf(screen))
        assertEquals("No data.", (list[0].body[1] as UiList).emptyText)
        val suggestions = UiSuggestions(heading = "Ya existe")
        assertSame(suggestions, localizedPayload(suggestions))
        assertEquals("texto", localizedPayload("texto"))
    }

    @Test
    fun noteTranslationIsAnOptionalContractField() {
        val entry = UiTimelineEntry("Ana", "Llamar manana", translation = "Call tomorrow")
        val json = UiJson.encodeToString(UiTimelineEntry.serializer(), entry)
        assertTrue(json.contains("\"translation\":\"Call tomorrow\""))
        assertEquals(entry, UiJson.decodeFromString(UiTimelineEntry.serializer(), json))
        // Not sent when there is none, so older payloads stay byte-identical.
        assertFalse(UiJson.encodeToString(UiTimelineEntry.serializer(), UiTimelineEntry("Ana", "Hola")).contains("translation"))
        // Older servers' payloads decode with no translation.
        assertNull(UiJson.decodeFromString(UiTimelineEntry.serializer(), """{"title":"Ana"}""").translation)
    }

    private val definition = ModuleDefinition(
        id = "prueba",
        displayName = "Modulo de prueba",
        description = "Descripcion de prueba",
        views = listOf(
            ModuleViewDefinition(
                "home", "Ver prueba", "/x", "list",
                fields = listOf(ModuleFieldDefinition("status", "Estado de prueba", "select", options = listOf("Abierto"), help = "Ayuda de prueba", section = "Seccion de prueba")),
                actions = listOf(ModuleActionDefinition("a", "Accion de prueba", "/a", "POST", "prueba.view")),
            ),
        ),
        permissions = listOf(ModulePermissionDefinition("prueba.view", "Permiso de prueba", "Descripcion de prueba")),
    )

    @Test
    fun moduleDefinitionsAreTranslatedWhenSent() {
        assertSame(definition, definition.localized())
        I18n.withLanguage("en") {
            val english = definition.localized()
            assertEquals("Test module", english.displayName)
            assertEquals("Test description", english.description)
            assertEquals("See test", english.views[0].title)
            assertEquals("Test status", english.views[0].fields[0].label)
            assertEquals("Test help", english.views[0].fields[0].help)
            assertEquals("Test section", english.views[0].fields[0].section)
            // Select options are values.
            assertEquals(listOf("Abierto"), english.views[0].fields[0].options)
            assertEquals("Test action", english.views[0].actions[0].title)
            assertEquals("Test permission", english.permissions[0].title)
            assertEquals("prueba.view", english.permissions[0].id)
        }
    }

    @Test
    fun definitionTextsListWhatCoreTranslates() {
        assertEquals(
            setOf(
                "Modulo de prueba", "Descripcion de prueba", "Ver prueba", "Estado de prueba", "Ayuda de prueba",
                "Seccion de prueba", "Accion de prueba", "Permiso de prueba",
            ),
            definitionTexts(definition),
        )
    }
}
