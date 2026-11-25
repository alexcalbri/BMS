package com.ideasdeveloper.bms.server.outbound.monday

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MondayOutboundConfigTest {
    private val schema = MondayBoardSchema(
        "100", "Ventas",
        listOf(MondayColumnInfo("email_1", "Correo electronico", "email"), MondayColumnInfo("st", "Etapa", "status"), MondayColumnInfo("f", "Archivos", "file"), MondayColumnInfo("k", "Clave", "text")),
        listOf(MondayGroupInfo("topics", "Nuevos")),
    )

    @Test
    fun `el nombre de la tabla se valida`() {
        assertFailsWith<IllegalArgumentException> { JdbcMondayOutboundConfigStore("users; DROP TABLE x") }
        assertFailsWith<IllegalArgumentException> { JdbcMondayOutboundConfigStore("ext_crm_monday__outbound; --") }
        JdbcMondayOutboundConfigStore("ext_crm_monday__outbound")
    }

    @Test
    fun `las columnas se leen y se escriben como JSON`() {
        val columns = mapOf("email" to MondayColumnRef("email_1", "email"))
        assertEquals(columns, JdbcMondayOutboundConfigStore.parseColumns(JdbcMondayOutboundConfigStore.columnsJson(columns)))
        assertEquals(emptyMap(), JdbcMondayOutboundConfigStore.parseColumns("no es json"))
    }

    @Test
    fun `el formulario arma la configuracion y descarta columnas que ya no existen`() {
        val values = mapOf(
            "enabled" to "true", "board" to "Ventas - Espacio [100]", "group" to "Nuevos [topics]", "maxFileMb" to "10", "phoneCountry" to "mx",
            "col:email" to "Correo electronico [email_1]", "col:stage" to "Borrada [zzz]", "col:amount" to "Etapa [st]",
            "filesColumn" to "Archivos [f]", "keyColumn" to MondayOutboundUi.NoneOption,
        )
        val result = MondayOutboundUi.applyConfigForm(values, LeadType, schema)
        assertEquals("100", result.config.boardId)
        assertEquals("topics", result.config.groupId)
        assertEquals("MX", result.config.phoneCountry)
        assertEquals(10, result.config.maxFileMb)
        assertEquals(setOf("email"), result.config.columns.keys)
        assertEquals("f", result.config.filesColumn)
        assertEquals(2, result.warnings.size)
    }

    @Test
    fun `la validacion da mensajes en espanol`() {
        assertTrue(assertFailsWith<MondayConfigException> { MondayOutboundUi.applyConfigForm(mapOf("enabled" to "true"), LeadType, null) }.message!!.contains("tablero"))
        assertTrue(assertFailsWith<MondayConfigException> { MondayOutboundUi.applyConfigForm(mapOf("maxFileMb" to "9999"), LeadType, null) }.message!!.contains("MB"))
        assertTrue(assertFailsWith<MondayConfigException> { MondayOutboundUi.applyConfigForm(mapOf("phoneCountry" to "MEX"), LeadType, null) }.message!!.contains("2 letras"))
    }

    @Test
    fun `sugerir empareja por titulo y solo con columnas compatibles`() {
        val suggested = MondayOutboundUi.suggest(LeadType, schema, mapOf("correo electronico" to "email", "Etapa" to "email"))
        assertEquals(mapOf("col:email" to "Correo electronico [email_1]"), suggested)
    }
}
