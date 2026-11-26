package com.ideasdeveloper.bms.server.outbound.monday

import com.ideasdeveloper.bms.server.i18n.I18n
import com.ideasdeveloper.bms.server.outbound.OutboundFieldDef
import com.ideasdeveloper.bms.server.outbound.OutboundFieldType
import com.ideasdeveloper.bms.server.outbound.OutboundRecordType
import com.ideasdeveloper.bms.ui.UiCard
import com.ideasdeveloper.bms.ui.UiRequest
import kotlin.test.Test
import kotlin.test.assertEquals

/** The labels of a record type are translated with the catalog of the package that declares it (I18N.md section 2). */
class MondayOutboundNamespaceTest {
    private val owner = "i18n_outbound_owner"
    private val other = "i18n_outbound_other"

    private fun card(type: OutboundRecordType): UiCard =
        MondayOutboundUi.configCard(type, null, emptyList(), null, { op, params -> UiRequest("/test", params = params + ("op" to op)) }) as UiCard

    @Test
    fun recordTypeLabelUsesTheDeclaringNamespace() {
        I18n.register(owner, "en", mapOf("Tipo de prueba" to "Owner type"))
        I18n.register(other, "en", mapOf("Tipo de prueba" to "Other type"))
        val type = OutboundRecordType("t", "Tipo de prueba", listOf(OutboundFieldDef("a", "Campo", OutboundFieldType.Text)), namespace = owner)
        assertEquals("Envio a monday: Tipo de prueba", card(type).title)
        I18n.withLanguage("en") {
            assertEquals("Sending to monday: Owner type", card(type).title)
            assertEquals("Sending to monday: Other type", card(type.copy(namespace = other)).title)
        }
    }
}
