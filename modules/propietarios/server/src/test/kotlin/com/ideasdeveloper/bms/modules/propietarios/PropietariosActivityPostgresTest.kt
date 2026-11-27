package com.ideasdeveloper.bms.modules.propietarios

import com.ideasdeveloper.bms.server.activity.ActivityActions
import com.ideasdeveloper.bms.server.activity.ActivityActor
import com.ideasdeveloper.bms.server.activity.ActivityLogTesting
import com.ideasdeveloper.bms.server.activity.ActivityLogTesting.throughAgent
import com.ideasdeveloper.bms.server.i18n.I18nCatalogCheck
import com.ideasdeveloper.bms.server.modules.McpToolCall
import com.ideasdeveloper.bms.ui.UiFileRef
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.ByteArrayInputStream
import java.io.File
import java.math.BigDecimal
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.time.LocalDate
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Propietarios escribe su registro de actividad (ACTIVITY_LOG.md §4) en la MISMA transaccion que cada cambio: desarrollos, lotes,
 * traspasos, cotitulares, edicion de titularidades, adjuntos, la importacion y las herramientas del agente. Corre sobre un
 * PostgreSQL DESECHABLE (BMS_ACTIVITY_PG_URL o BMS_SERVER_ADMINS_PG_URL, jdbc:postgresql://host:port/postgres?user=postgres): crea y
 * borra la base bms_al_propietarios. Sin esa variable las pruebas con base de datos no hacen nada.
 */
class PropietariosActivityPostgresTest {
    private val url: String? = (System.getenv("BMS_ACTIVITY_PG_URL") ?: System.getenv("BMS_SERVER_ADMINS_PG_URL"))?.takeIf { it.isNotBlank() }
    private val root = I18nCatalogCheck.repoRoot()
    private val database = "bms_al_propietarios"
    private val ana = ActivityLogTesting.user("Ana Ruiz", UUID.fromString("00000000-0000-0000-0000-00000000a0a0"))
    private val access = ActivityLogTesting.accessWithoutDatabase()
    private val today = LocalDate.of(2026, 11, 27)

    private fun migration(path: String) = File(root, path).readText().trim().removePrefix("BEGIN;").removeSuffix("COMMIT;")
    private fun urlOf(base: String, name: String) = base.replace(Regex("/[^/?]+(\\?|$)"), "/$name$1")
    private fun Connection.run(sql: String) = createStatement().use { it.execute(sql) }

    /** Una fila del registro tal como quedo en la base. */
    private data class Row(
        val kind: String, val userId: UUID?, val display: String, val agent: String?, val module: String, val type: String, val entityId: String?,
        val label: String, val action: String, val summary: String, val changes: String, val origin: String?,
    ) {
        fun has(field: String, old: String?, new: String?) = changes.contains("\"field\": \"$field\"") &&
            (old == null || changes.contains("\"old\": \"$old\"")) && (new == null || changes.contains("\"new\": \"$new\""))
    }

    private fun rows(db: Connection, where: String = "TRUE"): List<Row> = db.createStatement().use { st ->
        st.executeQuery(
            "SELECT actor_kind, actor_user_id, actor_display, agent_name, module_id, entity_type, entity_id, entity_label, action, summary, changes::text, origin " +
                "FROM activity_log WHERE $where ORDER BY id",
        ).use { rs ->
            buildList {
                while (rs.next()) {
                    add(Row(rs.getString(1), rs.getObject(2, UUID::class.java), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7),
                        rs.getString(8), rs.getString(9), rs.getString(10), rs.getString(11), rs.getString(12)))
                }
            }
        }
    }

    private fun count(db: Connection, sql: String): Int = db.createStatement().use { st -> st.executeQuery(sql).use { it.next(); it.getInt(1) } }

    private fun withTenant(block: (Connection) -> Unit) {
        val base = url ?: return
        DriverManager.getConnection(base).use { admin ->
            admin.run("DROP DATABASE IF EXISTS $database WITH (FORCE)")
            admin.run("CREATE DATABASE $database")
        }
        DriverManager.getConnection(urlOf(base, database)).use { db ->
            listOf("V001__create_application_users", "V002__create_application_sessions", "V003__add_application_user_profile", "V004__add_application_user_code", "V005__create_notifications", "V006__add_application_user_language")
                .forEach { db.run(migration("database/core/migrations/$it.sql")) }
            listOf("V001__create_business_owner", "V003__create_tenant_modules", "V004__create_application_permissions", "V005__create_application_user_credentials", "V006__create_extensions", "V011__create_activity_log")
                .forEach { db.run(migration("database/tenant/migrations/$it.sql")) }
            File(root, "modules/clientes/migrations").listFiles()!!.filter { it.name.endsWith(".sql") }.sortedBy { it.name }.forEach { db.run(it.readText()) }
            File(root, "modules/propietarios/migrations").listFiles()!!.filter { it.name.endsWith(".sql") }.sortedBy { it.name }.forEach { db.run(it.readText()) }
            db.run("INSERT INTO application_users (id, postgres_role, is_active, display_name, email) VALUES ('${ana.userId}', 'role_ana', TRUE, 'Ana Ruiz', 'ana@example.com')")
            db.run("INSERT INTO tenant_modules (module_id, display_name, status, enabled_at) VALUES ('propietarios', 'Propietarios', 'enabled', now())")
            block(db)
        }
    }

    private fun customer(db: Connection, name: String, email: String = ""): UUID {
        val id = UUID.randomUUID()
        db.prepareStatement("INSERT INTO customers (id, display_name, primary_email, primary_phone, status) VALUES (?, ?, ?, '', 'active')").use { st ->
            st.setObject(1, id); st.setString(2, name); st.setString(3, email); st.executeUpdate()
        }
        return id
    }

    private fun lotInput(developmentId: UUID, number: String = "L-12") =
        LotInput(developmentId, number, "R-1", "Casa del lago", "Casa", LotStatus.Disponible, BigDecimal("250.50"), null, null, "", "Esquina")

    @Test
    fun theWriteToolsAndRoutesAreTheOnesTheTestsCover() {
        // Si un modulo anade una herramienta o ruta de escritura, esta prueba falla hasta que se cubra aqui.
        assertEquals(listOf("propietarios_add_lot", "propietarios_register_ownership_change"), ActivityLogTesting.writeTools(PropietariosServerModule))
        val routes = ActivityLogTesting.writeRoutes(PropietariosServerModule)
        assertEquals(2, routes.size, routes.toString())
        assertTrue(routes.all { it.startsWith("POST ") }, routes.toString())
    }

    @Test
    fun developmentsAndLotsLeaveTheirRowsWithFieldDiffsAndDeletesKeepTheirKeyData() = withTenant { db ->
        val developmentId = PropietariosService.createDevelopment(db, ana, DevelopmentInput(name = "Valle Verde", location = "Cartago"), ana.userId)
        PropietariosService.updateDevelopment(db, ana, developmentId, DevelopmentInput(name = "Valle Verde", location = "Heredia"))
        PropietariosService.updateDevelopment(db, ana, developmentId, DevelopmentInput(name = "Valle Verde", location = "Heredia"))
        val development = rows(db, "entity_type = 'desarrollo'")
        assertEquals(listOf(ActivityActions.Created, ActivityActions.Updated), development.map { it.action })
        assertTrue(development.last().has("location", "Cartago", "Heredia"), development.last().changes)

        val lotId = PropietariosService.createLot(db, ana, lotInput(developmentId), "Ana Ruiz", ana.userId, today)
        PropietariosService.updateLot(db, ana, lotId, lotInput(developmentId).copy(areaM2 = BigDecimal("300"), status = LotStatus.Reservado), "Ana Ruiz", today)
        val lot = rows(db, "entity_type = 'lote'")
        assertEquals(listOf(ActivityActions.Created, ActivityActions.Updated), lot.map { it.action })
        assertEquals(lotId.toString(), lot.first().entityId)
        assertEquals("L-12", lot.first().label)
        assertTrue(lot.last().has("areaM2", "250.5", "300") && lot.last().changes.contains("\"field\": \"status\""), lot.last().changes)

        PropietariosService.archiveLot(db, ana, lotId, "Ana Ruiz", today)
        PropietariosService.archiveDevelopment(db, ana, developmentId)
        assertEquals(listOf(ActivityActions.Archived), rows(db, "entity_type = 'desarrollo' AND action = 'archived'").map { it.action })
        assertEquals(ActivityActions.Archived, rows(db, "entity_type = 'lote'").last().action)
        assertTrue(rows(db).all { it.module == "propietarios" && it.display == "Ana Ruiz" && it.kind == "user" && it.origin == "app" })
    }

    @Test
    fun transferAddOwnerEditOwnershipAndRemovalEachWriteTheirRow() = withTenant { db ->
        val developmentId = PropietariosService.createDevelopment(db, ana, DevelopmentInput(name = "Valle Verde"), ana.userId)
        val lotId = PropietariosService.createLot(db, ana, lotInput(developmentId), "Ana Ruiz", ana.userId, today)
        val carla = customer(db, "Carla Mora", "carla@example.com")
        val diego = customer(db, "Diego Solis", "diego@example.com")

        // Traspaso: el lote disponible pasa a vendido y el registro lo dice con los titulares antes y despues.
        PropietariosService.transfer(db, ana, lotId, today, listOf(NewOwner(carla, BigDecimal("50"))), "Escritura 55", "Ana Ruiz", ana.userId)
        val transfer = rows(db, "action = 'ownership_transferred'").single()
        assertEquals("L-12", transfer.label)
        assertTrue(transfer.has("owners", null, "Carla Mora (50 %)") || transfer.changes.contains("Carla Mora"), transfer.changes)
        assertTrue(transfer.has("status", "Disponible", "Vendido"), transfer.changes)
        assertTrue(transfer.changes.contains("Escritura 55"), transfer.changes)

        // Cotitular.
        PropietariosService.addOwner(db, ana, lotId, NewOwner(diego, BigDecimal("25")), null, "", "Ana Ruiz", ana.userId, today)
        val added = rows(db, "action = 'owner_added'").single()
        assertTrue(added.summary.contains("Diego Solis"), added.summary)
        assertTrue(added.changes.contains("Diego Solis"), added.changes)

        // Edicion de la titularidad: porcentaje y fin.
        val ownershipId = UUID.fromString(db.createStatement().use { st -> st.executeQuery("SELECT id FROM propietarios_ownerships WHERE customer_id = '$diego'").use { it.next(); it.getString(1) } })
        PropietariosService.editOwnership(db, ana, ownershipId, BigDecimal("30"), null, null, "ajuste", "Ana Ruiz", today)
        PropietariosService.editOwnership(db, ana, ownershipId, BigDecimal("30"), null, null, "ajuste", "Ana Ruiz", today)
        val edit = rows(db, "entity_type = 'titularidad'")
        assertEquals(1, edit.size)
        assertEquals(ActivityActions.Updated, edit.single().action)
        assertEquals("Diego Solis", edit.single().label)
        assertTrue(edit.single().has("sharePercent", "25", "30") && edit.single().has("notes", null, "ajuste"), edit.single().changes)

        // Baja de todos los titulares.
        PropietariosService.transfer(db, ana, lotId, today.plusDays(1), emptyList(), "", "Ana Ruiz", ana.userId)
        val removal = rows(db, "action = 'ownership_removed'").single()
        assertTrue(removal.changes.contains("Carla Mora") && removal.changes.contains("Diego Solis"), removal.changes)
        assertEquals(0, count(db, "SELECT count(*) FROM propietarios_ownerships WHERE ends_on IS NULL"))
    }

    @Test
    fun attachmentsAreRecordedWithNameAndSizeNeverTheContent() = withTenant { db ->
        val developmentId = PropietariosService.createDevelopment(db, ana, DevelopmentInput(name = "Valle Verde"), ana.userId)
        val lotId = PropietariosService.createLot(db, ana, lotInput(developmentId), "Ana Ruiz", ana.userId, today)
        val pending = UUID.randomUUID()
        db.run("INSERT INTO propietarios_attachments (id, name, content_type, size, backend, ref, uploaded_by) VALUES ('$pending', 'plano.pdf', 'application/pdf', 4096, 'local', 'ruta/secreta/xyz', '${ana.userId}')")
        PropietariosService.attachFiles(db, ana, lotId, listOf(UiFileRef(pending.toString(), "plano.pdf", "", 4096)), ana.userId, "Ana Ruiz", today)
        PropietariosService.removeFiles(db, ana, lotId, listOf(pending), "Ana Ruiz", today)
        val files = rows(db, "action IN ('attachment_added', 'attachment_removed')")
        assertEquals(listOf(ActivityActions.AttachmentAdded, ActivityActions.AttachmentRemoved), files.map { it.action })
        assertTrue(files.first().changes.contains("plano.pdf (4 KB)"), files.first().changes)
        assertTrue(files.last().has("file", "plano.pdf", null), files.last().changes)
        assertTrue(files.none { it.changes.contains("secreta") || it.summary.contains("secreta") })
    }

    @Test
    fun aFailedLogRowCancelsEveryChange() = withTenant { db ->
        val developmentId = PropietariosService.createDevelopment(db, ana, DevelopmentInput(name = "Valle Verde"), ana.userId)
        val carla = customer(db, "Carla Mora", "carla@example.com")
        db.run("CREATE FUNCTION al_refuse() RETURNS trigger LANGUAGE plpgsql AS \$\$ BEGIN RAISE EXCEPTION 'sin registro'; END; \$\$")
        db.run("CREATE TRIGGER al_refuse_lots BEFORE INSERT ON activity_log FOR EACH ROW WHEN (NEW.entity_type = 'lote') EXECUTE FUNCTION al_refuse()")
        assertFailsWith<SQLException> { PropietariosService.createLot(db, ana, lotInput(developmentId), "Ana Ruiz", ana.userId, today) }
        assertEquals(0, count(db, "SELECT count(*) FROM propietarios_lots"))
        assertEquals(0, count(db, "SELECT count(*) FROM propietarios_lot_history"))
        assertTrue(db.autoCommit)

        db.run("DROP TRIGGER al_refuse_lots ON activity_log")
        val lotId = PropietariosService.createLot(db, ana, lotInput(developmentId), "Ana Ruiz", ana.userId, today)
        db.run("CREATE TRIGGER al_refuse_lots BEFORE INSERT ON activity_log FOR EACH ROW WHEN (NEW.entity_type = 'lote') EXECUTE FUNCTION al_refuse()")
        assertFailsWith<SQLException> { PropietariosService.transfer(db, ana, lotId, today, listOf(NewOwner(carla, null)), "", "Ana Ruiz", ana.userId) }
        assertEquals(0, count(db, "SELECT count(*) FROM propietarios_ownerships"))
        assertEquals("disponible", db.createStatement().use { st -> st.executeQuery("SELECT status FROM propietarios_lots WHERE id = '$lotId'").use { it.next(); it.getString(1) } })
    }

    @Test
    fun anAgentToolWritesTheRowAsAgentOfTheUser() = withTenant { db ->
        val developmentId = PropietariosService.createDevelopment(db, ana, DevelopmentInput(name = "Valle Verde"), ana.userId)
        val carla = customer(db, "Carla Mora", "carla@example.com")
        val agent = ActivityLogTesting.permissions(granted = setOf(PropietariosModule.View, PropietariosModule.Manage), businessOwner = true, actor = ana).throughAgent("Claude")

        val addLot = PropietariosMcpTools.tools.first { it.name == "add_lot" }
        addLot.handler(McpToolCall(JsonObject(mapOf("development_id" to JsonPrimitive(developmentId.toString()), "lot_number" to JsonPrimitive("L-77"))), agent, db, access, "Claude", PropietariosModule.id))
        val created = rows(db, "entity_type = 'lote'").single()
        assertEquals("Agente de Ana Ruiz", created.display)
        assertEquals("Claude", created.agent)
        assertEquals("agent", created.origin)
        assertEquals(ana.userId, created.userId)

        val transfer = PropietariosMcpTools.tools.first { it.name == "register_ownership_change" }
        val owners = JsonArray(listOf(JsonObject(mapOf("customer_id" to JsonPrimitive(carla.toString()), "share_percent" to JsonPrimitive("100")))))
        transfer.handler(
            McpToolCall(
                JsonObject(mapOf("lot_id" to JsonPrimitive(created.entityId), "date" to JsonPrimitive(today.toString()), "owners" to owners)),
                agent, db, access, "Claude", PropietariosModule.id,
            ),
        )
        val moved = rows(db, "action = 'ownership_transferred'").single()
        assertTrue(moved.display.startsWith("Agente de "), moved.display)
        assertEquals("Claude", moved.agent)
    }

    @Test
    fun importedLotsOwnersCustomersAndFilesAreAttributedToTheImportAndTheAgentThatStartedIt() = withTenant { db ->
        val importer = ActivityActor.import(ana.viaAgent("Claude"))
        val existing = customer(db, "Diego Solis", "diego@example.com")
        val row = ImportedLot(
            key = "monday:55", lotNumber = "L-90", developmentName = "Altos del Sol", registerNumber = "R-9", propertyName = "Casa roja", propertyType = "Casa",
            areaM2 = BigDecimal("300"), notes = "Esquina",
            owners = listOf(
                ImportedOwner("Diego Solis", "diego@example.com", sharePercent = BigDecimal("50")),
                ImportedOwner("Elena Vega", "", "8888-0000", BigDecimal("50")),
            ),
        )
        val developmentId = PropietariosImportApply.ensureDevelopment(db, "monday:board:1", "Tablero", null, ana.userId, importer)
        val outcome = PropietariosImportApply.apply(db, access, developmentId, row, "Importacion de monday", ana.userId, today, importer)
        assertTrue(outcome is LotImportOutcome.Created, outcome.toString())
        val written = rows(db)
        assertTrue(written.all { it.kind == "import" && it.display == "Importacion (Agente de Ana Ruiz)" && it.agent == "Claude" && it.origin == "import" }, written.toString())
        assertEquals(setOf("desarrollo", "lote", "cliente"), written.map { it.type }.toSet())
        // El desarrollo "Tablero" que pide el runner y "Altos del Sol" que trae la fila.
        assertEquals(listOf("Tablero", "Altos del Sol"), written.filter { it.type == "desarrollo" }.map { it.label })
        val lotCreated = written.single { it.type == "lote" && it.action == ActivityActions.Created }
        assertTrue(lotCreated.has("lotNumber", null, "L-90") && lotCreated.has("development", null, "Altos del Sol") && lotCreated.has("areaM2", null, "300"), lotCreated.changes)
        // Solo se crea el cliente que no existia; el existente se reutiliza.
        assertEquals(listOf("Elena Vega"), written.filter { it.type == "cliente" }.map { it.label })
        assertEquals(1, count(db, "SELECT count(*) FROM customers WHERE id = '$existing'"))
        assertTrue(written.single { it.action == "owner_added" }.changes.contains("Diego Solis") )

        // Repetir la fila no escribe nada; cambiar un dato escribe la diferencia.
        val total = written.size
        PropietariosImportApply.apply(db, access, developmentId, row, "Importacion de monday", ana.userId, today, importer)
        assertEquals(total, rows(db).size)
        PropietariosImportApply.apply(db, access, developmentId, row.copy(areaM2 = BigDecimal("320")), "Importacion de monday", ana.userId, today, importer)
        val updated = rows(db).last()
        assertEquals(ActivityActions.Updated, updated.action)
        assertTrue(updated.has("areaM2", "300", "320"), updated.changes)

        // Archivo importado: una fila con nombre y tamano, una sola vez por clave.
        val lotId = UUID.fromString(created(written))
        val files = access.files("acme", PropietariosModule.id)
        val bytes = ByteArray(3000) { 1 }
        assertTrue(PropietariosImportApply.attachFile(db, files, lotId, "monday:asset:1", "escritura.pdf", "application/pdf", ByteArrayInputStream(bytes), ana.userId, importer))
        assertFalse(PropietariosImportApply.attachFile(db, files, lotId, "monday:asset:1", "escritura.pdf", "application/pdf", ByteArrayInputStream(bytes), ana.userId, importer))
        val attached = rows(db, "action = 'attachment_added'").single()
        assertEquals("L-90", attached.label)
        assertTrue(attached.changes.contains("escritura.pdf (2 KB)"), attached.changes)
    }

    private fun created(written: List<Row>): String = written.single { it.type == "lote" && it.action == ActivityActions.Created }.entityId!!

    @Test
    fun anAttachmentWhoseLogRowFailsLeavesNoRowBehind() = withTenant { db ->
        val importer = ActivityActor.import(ana)
        val developmentId = PropietariosService.createDevelopment(db, ana, DevelopmentInput(name = "Valle Verde"), ana.userId)
        val lotId = PropietariosService.createLot(db, ana, lotInput(developmentId), "Ana Ruiz", ana.userId, today)
        db.run("CREATE FUNCTION al_refuse() RETURNS trigger LANGUAGE plpgsql AS \$\$ BEGIN RAISE EXCEPTION 'sin registro'; END; \$\$")
        db.run("CREATE TRIGGER al_refuse_files BEFORE INSERT ON activity_log FOR EACH ROW WHEN (NEW.action = 'attachment_added') EXECUTE FUNCTION al_refuse()")
        assertFailsWith<SQLException> {
            PropietariosImportApply.attachFile(db, access.files("acme", PropietariosModule.id), lotId, "monday:asset:2", "plano.pdf", "application/pdf", ByteArrayInputStream(ByteArray(10)), ana.userId, importer)
        }
        assertEquals(0, count(db, "SELECT count(*) FROM propietarios_attachments"))
    }
}
