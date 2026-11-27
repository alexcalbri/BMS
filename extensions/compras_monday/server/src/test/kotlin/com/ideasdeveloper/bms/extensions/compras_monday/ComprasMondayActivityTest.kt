package com.ideasdeveloper.bms.extensions.compras_monday

import com.ideasdeveloper.bms.server.activity.ActivityActions
import com.ideasdeveloper.bms.server.activity.ActivityActor
import com.ideasdeveloper.bms.server.activity.ActivityLogTesting
import java.lang.reflect.Proxy
import java.sql.Connection
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** La importacion de monday y el token dejan su fila en el registro de actividad (la conexion no se usa: se captura). */
class ComprasMondayActivityTest {
    private val ana: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000a1")
    private val jobId: UUID = UUID.fromString("00000000-0000-0000-0000-00000000b001")

    // Nunca se usa: el registro de actividad de la prueba captura las filas en lugar de escribirlas.
    private val unusedConnection: Connection = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(Connection::class.java)) { _, method, _ ->
        error("no database in this test: ${method.name}")
    } as Connection

    @Test
    fun importSummaryIsAttributedToTheImportAndWhoStartedIt() {
        val starter = ActivityActor.import(ActivityLogTesting.user("Ana Ruiz", ana))
        val row = ActivityLogTesting.rows {
            ComprasMondayImport.recordImportSummary(unusedConnection, jobId, "Tablero de compras", starter, 3, 1, 0)
        }.single()
        assertEquals(ActivityActions.Imported, row.action)
        assertEquals("Importacion (Ana Ruiz)", row.display)
        assertEquals("compras", row.entry.moduleId)
        assertEquals("creadas 3, ya existian 1, con error 0", row.change("totals")!!.new)
    }

    @Test
    fun importSummaryThroughAnAgentNamesTheAgent() {
        val starter = ActivityActor.import(ActivityLogTesting.user("Ana Ruiz", ana).viaAgent("Claude"))
        val row = ActivityLogTesting.rows {
            ComprasMondayImport.recordImportSummary(unusedConnection, jobId, "Tablero de compras", starter, 1, 0, 0)
        }.single()
        assertTrue(row.display.startsWith("Importacion (Agente de "), row.display)
    }

    @Test
    fun tokenChangeIsRecordedAsASecretWithoutItsValue() {
        val row = ActivityLogTesting.rows {
            ComprasMondayImport.recordTokenChange(unusedConnection, ActivityLogTesting.user("Ana Ruiz", ana), "Guardo el token de monday.")
        }.single()
        assertEquals(ActivityActions.SettingsChanged, row.action)
        val change = row.change("mondayToken")!!
        assertTrue(change.secret)
        assertEquals(null, change.old)
        assertEquals(null, change.new)
    }
}
