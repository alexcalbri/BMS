package com.ideasdeveloper.bms.modules.empleados

import com.ideasdeveloper.bms.server.i18n.I18n
import com.ideasdeveloper.bms.server.i18n.I18nCatalogCheck
import com.ideasdeveloper.bms.server.i18n.loadModuleCatalog
import com.ideasdeveloper.bms.server.i18n.localized
import com.ideasdeveloper.bms.server.i18n.tr
import java.time.LocalDate
import java.util.UUID
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class EmpleadosI18nTest {
    private val today = LocalDate.of(2026, 11, 18)
    private val viewer = EmpleadosScope(UUID.randomUUID(), null, canShifts = false, canManage = false, owner = false)
    private val manager = EmpleadosScope(UUID.randomUUID(), null, canShifts = true, canManage = true, owner = false)

    // Tests build no ModuleRegistry, so the module's catalog is loaded by hand (idempotent).
    @BeforeTest
    fun loadCatalog() = loadModuleCatalog(EmpleadosServerModule)

    @Test
    fun catalogIsComplete() = I18nCatalogCheck.assertModule(
        "empleados",
        EmpleadosServerModule.definition,
        // Option values and catalog nouns the screens translate by lookup, not by a literal next to the call.
        extraTexts = listOf(
            "Todos", "Activos", "Bajas", "Sin sede", "Sin departamento", "Sin usuario",
            "Lunes", "Martes", "Miércoles", "Jueves", "Viernes", "Sábado", "Domingo", "sede", "departamento",
        ),
    )

    @Test
    fun navigationIsInEnglishAndTheIdsStayTheSame() {
        val spanish = EmpleadosScreens.navigation(manager, "mine", import = true)
        val english = I18n.withLanguage("en") { EmpleadosScreens.navigation(manager, "mine", import = true) }
        assertEquals(listOf("Mi horario", "Equipo", "Semana", "Ausencias", "Administrar", "Importar"), spanish.items.map { it.label })
        assertEquals(listOf("My schedule", "Team", "Week", "Absences", "Manage", "Import"), english.items.map { it.label })
        assertEquals(spanish.items.map { it.id }, english.items.map { it.id })
        assertEquals(EmpleadosScreens.navigation(viewer, "mine").items.map { it.id }, I18n.withLanguage("en") { EmpleadosScreens.navigation(viewer, "mine") }.items.map { it.id })
    }

    @Test
    fun statusLabelsAreTranslatedWhereShownAndParsedInSpanish() {
        I18n.withLanguage("en") {
            assertEquals("Vacaciones", AbsenceKind.Vacation.label)
            assertEquals("Vacation", tr(AbsenceKind.Vacation.label))
            assertEquals("Sick leave", tr(AbsenceKind.Sick.label))
            assertEquals("Pending", tr(AbsenceStatus.Pending.label))
            assertEquals("Off", tr(ShiftKind.Off.label))
            // The app and the server compare the Spanish source label, whatever the language.
            assertEquals(AbsenceKind.Sick, AbsenceKind.entries.firstOrNull { it.label == "Enfermedad" })
            assertEquals(ShiftKind.Off, ShiftKind.fromLabel("Libre"))
        }
    }

    @Test
    fun validationMessagesAreInEnglishAndTheSpanishOnesStayTheSame() {
        val input = EmployeeInput(fullName = "Ana Ruiz", jobTitle = "x".repeat(121))
        assertEquals(listOf("El puesto admite hasta 120 caracteres."), input.validate(today))
        assertEquals(listOf("The job title allows up to 120 characters."), I18n.withLanguage("en") { input.validate(today) })
    }

    @Test
    fun dayAndWeeklyTextsAreInEnglish() {
        I18n.withLanguage("en") {
            assertEquals("Mon 23 Nov", EmpleadosNotify.dayLabel(LocalDate.of(2026, 11, 23)))
            assertEquals("no usual schedule", EmpleadosNotify.weeklyText(emptyList(), emptyMap()))
            assertEquals("Shifts this week", tr("Turnos de la semana"))
        }
        // The same texts in Spanish keep today's output.
        assertEquals("lun 23 nov", EmpleadosNotify.dayLabel(LocalDate.of(2026, 11, 23)))
        assertEquals("sin horario habitual", EmpleadosNotify.weeklyText(emptyList(), emptyMap()))
    }

    @Test
    fun theModuleDefinitionIsTranslatedByCoreFromTheCatalog() {
        val english = I18n.withLanguage("en") { EmpleadosServerModule.definition.localized() }
        assertEquals("Employees", english.displayName)
        assertEquals("View employees", english.permissions.first { it.id == EmpleadosModule.View }.title)
        assertEquals("Manage profiles", english.permissions.first { it.id == EmpleadosModule.Manage }.title)
    }
}
