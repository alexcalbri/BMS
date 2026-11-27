package com.ideasdeveloper.bms.extensions.mantenimiento_monday

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
class MantenimientoMondayActivityTest {
    private val ana: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000a1")
    private val jobId: UUID = UUID.fromString("00000000-0000-0000-0000-00000000b001")

    // Nunca se usa: el registro de actividad de la prueba captura las filas en lugar de escribirlas.
    private val unusedConnection: Connection = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(Connection::class.java)) { _, method, _ ->
        error("no database in this test: ${method.name}")
    } as Connection

    private fun job(created: Int, updated: Int, skipped: Int, failed: Int, filesDone: Int, filesFailed: Int) = MondayJob(
        id = jobId, boardId = "123", boardName = "Tablero de mantenimiento", target = MondayDestination.Orders, defaultKind = MondayDestination.KindCorrective,
        status = MondayJobStatus.Done, cursor = null, pages = 1, retries = 0, summary = null, mapping = MondayMapping(), total = created + updated + skipped + failed,
        applied = created + updated + skipped + failed, created = created, updated = updated, skipped = skipped, failed = failed, filesDone = filesDone,
        filesFailed = filesFailed, message = "", createdBy = ana, createdAt = java.time.Instant.EPOCH,
    )

    @Test
    fun importSummaryHasTheTotalsAndWhoStartedIt() {
        val starter = ActivityActor.import(ActivityLogTesting.user("Ana Ruiz", ana))
        val row = ActivityLogTesting.rows {
            MantenimientoMondayActivity.total(unusedConnection, starter, job(5, 1, 0, 2, 4, 1))
        }.single()
        assertEquals(ActivityActions.Imported, row.action)
        assertEquals("mantenimiento", row.entry.moduleId)
        assertEquals("Importacion (Ana Ruiz)", row.display)
        assertEquals("creados 5, actualizados 1, omitidos 0, con error 2", row.change("records")!!.new)
    }

    @Test
    fun importSummaryThroughAnAgentNamesTheAgent() {
        val starter = ActivityActor.import(ActivityLogTesting.user("Ana Ruiz", ana).viaAgent("Claude"))
        val row = ActivityLogTesting.rows {
            MantenimientoMondayActivity.total(unusedConnection, starter, job(1, 0, 0, 0, 0, 0))
        }.single()
        assertTrue(row.display.startsWith("Importacion (Agente de "), row.display)
    }

    @Test
    fun tokenChangeIsRecordedAsASecretWithoutItsValue() {
        val row = ActivityLogTesting.rows {
            MantenimientoMondayActivity.token(unusedConnection, ActivityLogTesting.user("Ana Ruiz", ana), "Guardo el token de la API de monday.")
        }.single()
        assertEquals(ActivityActions.SettingsChanged, row.action)
        val change = row.change("mondayToken")!!
        assertTrue(change.secret)
        assertEquals(null, change.old)
        assertEquals(null, change.new)
    }
}
