package com.ideasdeveloper.bms.modules.propietarios

import java.math.BigDecimal
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Comparacion antes de escribir en la importacion: titularidades ya guardadas y lote sin cambios. */
class PropietariosImportCompareTest {
    private val development = UUID.randomUUID()

    private fun lot(area: String? = "100.00") = Lot(
        id = UUID.randomUUID(), developmentId = development, developmentName = "Valle", lotNumber = "A1", registerNumber = "R1",
        propertyName = "Casa", propertyType = "Residencial", status = LotStatus.Vendido, areaM2 = area?.let { BigDecimal(it) },
        latitude = null, longitude = null, mapUrl = "", notes = "", archived = false,
    )

    private fun same(old: Lot) = LotInput(
        old.developmentId, old.lotNumber, old.registerNumber, old.propertyName, old.propertyType, old.status,
        old.areaM2, old.latitude, old.longitude, old.mapUrl, old.notes,
    )

    @Test
    fun ownershipsAlreadyStoredAreSkipped() {
        val fresh = PropietariosImportApply.notStored(listOf("a", "b", "c"), { it }, setOf("b"))
        assertEquals(listOf("a", "c"), fresh)
        assertTrue(PropietariosImportApply.notStored(listOf("a"), { it }, setOf("a")).isEmpty())
        assertEquals(listOf("a"), PropietariosImportApply.notStored(listOf("a"), { it }, emptySet()))
    }

    @Test
    fun lotWithSameDataIsNotRewritten() {
        val old = lot()
        assertFalse(PropietariosImportApply.lotChanged(old, same(old)))
    }

    @Test
    fun lotWithAnyDifferentFieldIsRewritten() {
        val old = lot()
        assertTrue(PropietariosImportApply.lotChanged(old, same(old).copy(status = LotStatus.Construido)))
        assertTrue(PropietariosImportApply.lotChanged(old, same(old).copy(areaM2 = BigDecimal("120.00"))))
        assertTrue(PropietariosImportApply.lotChanged(old, same(old).copy(notes = "nota")))
    }
}
