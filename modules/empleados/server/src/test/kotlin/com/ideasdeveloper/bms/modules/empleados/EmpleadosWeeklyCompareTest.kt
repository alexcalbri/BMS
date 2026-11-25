package com.ideasdeveloper.bms.modules.empleados

import java.time.LocalTime
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Comparacion antes de reemplazar el horario habitual (`EmpleadosRepository.weeklyUnchanged`). */
class EmpleadosWeeklyCompareTest {
    private fun slot(day: Int, from: Int, to: Int) = Triple(day, LocalTime.of(from, 0), LocalTime.of(to, 0))

    private fun saved(vararg slots: Triple<Int, LocalTime, LocalTime>, site: UUID? = null) = slots.map { it to site }

    @Test
    fun sameSlotsInAnyOrderAreUnchanged() {
        val current = saved(slot(2, 9, 17), slot(1, 9, 17))
        assertTrue(EmpleadosRepository.weeklyUnchanged(current, listOf(slot(1, 9, 17), slot(2, 9, 17))))
    }

    @Test
    fun differentHoursOrCountChange() {
        val current = saved(slot(1, 9, 17))
        assertFalse(EmpleadosRepository.weeklyUnchanged(current, listOf(slot(1, 9, 18))))
        assertFalse(EmpleadosRepository.weeklyUnchanged(current, listOf(slot(1, 9, 17), slot(2, 9, 17))))
        assertFalse(EmpleadosRepository.weeklyUnchanged(current, emptyList()))
    }

    @Test
    fun savedSlotWithSiteIsReplacedBecauseImportClearsIt() {
        val current = saved(slot(1, 9, 17), site = UUID.randomUUID())
        assertFalse(EmpleadosRepository.weeklyUnchanged(current, listOf(slot(1, 9, 17))))
    }
}
