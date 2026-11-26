package com.ideasdeveloper.bms.extensions.inmobiliaria

import com.ideasdeveloper.bms.server.i18n.I18n
import com.ideasdeveloper.bms.server.i18n.I18nCatalogCheck
import com.ideasdeveloper.bms.server.i18n.loadExtensionCatalog
import com.ideasdeveloper.bms.server.i18n.msg
import java.math.BigDecimal
import java.time.YearMonth
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class InmobiliariaI18nTest {
    // Tests build no ExtensionRegistry, so the extension's catalog is loaded by hand (idempotent).
    @BeforeTest
    fun loadCatalog() = loadExtensionCatalog(InmobiliariaServerExtension)

    @Test
    fun catalogIsComplete() = I18nCatalogCheck.assertExtension("inmobiliaria", InmobiliariaServerExtension.manifest)

    @Test
    fun enumLabelsStaySpanishAndDisplayFollowsTheLanguage() {
        assertEquals("En venta", PropertyStatus.ForSale.label)
        assertEquals("En venta", PropertyStatus.ForSale.display)
        I18n.withLanguage("en") {
            assertEquals("En venta", PropertyStatus.ForSale.label)
            assertEquals("For sale", PropertyStatus.ForSale.display)
            assertEquals("Under negotiation", PropertyStatus.Negotiation.display)
            assertEquals("House", PropertyKind.House.display)
            assertEquals("Won", DealOutcome.Won.display)
            assertEquals("Buyer's lawyer", PartyRole.BuyerLawyer.display)
            // Parsing keeps working against the Spanish labels and the ids.
            assertEquals(PropertyStatus.Sold, PropertyStatus.parse("Vendida"))
            assertEquals(PropertyKind.Land, PropertyKind.parse("land"))
        }
    }

    @Test
    fun formatsAndWarningsAreTranslated() {
        assertEquals("Ene 2027", InmobiliariaFormat.month(YearMonth.of(2027, 1)))
        val shares = listOf(BigDecimal("40"), BigDecimal("40"))
        assertEquals("Los porcentajes de las partes suman 80 % y deberian sumar 100 %.", InmobiliariaMath.shareWarning(shares))
        I18n.withLanguage("en") {
            assertEquals("Jan 2027", InmobiliariaFormat.month(YearMonth.of(2027, 1)))
            assertEquals("The percentages of the parties add up to 80 % and should add up to 100 %.", InmobiliariaMath.shareWarning(shares))
        }
    }

    @Test
    fun validationErrorsAreTranslated() {
        assertEquals(
            "El precio debe ser un monto positivo con hasta 2 decimales (por ejemplo 125000.50).",
            assertFailsWith<InmobiliariaException> { InmobiliariaInput.parseMoney("abc", msg("El precio")) }.message,
        )
        I18n.withLanguage("en") {
            assertEquals(
                "The price must be a positive amount with up to 2 decimals (for example 125000.50).",
                assertFailsWith<InmobiliariaException> { InmobiliariaInput.parseMoney("abc", msg("El precio")) }.message,
            )
            assertEquals(
                "Unknown property type.",
                assertFailsWith<InmobiliariaException> { InmobiliariaForms.parseProperty(mapOf("title" to "X", "kind" to "nope")) { null } }.message,
            )
        }
    }

    @Test
    fun filterValuesStaySpanishAndNeverChangeWithTheLanguage() {
        I18n.withLanguage("en") {
            assertEquals("Todos", InmobiliariaFilters.All)
            assertEquals("Mis listados", InmobiliariaFilters.MineListings)
            assertEquals("Ninguno", InmobiliariaDealForms.NoAgent)
        }
    }
}
