package com.ideasdeveloper.bms.modules.compras

import com.ideasdeveloper.bms.server.activity.ActivityActions
import com.ideasdeveloper.bms.server.activity.ActivityActor
import com.ideasdeveloper.bms.server.activity.ActivityLogTesting
import com.ideasdeveloper.bms.server.activity.ActivityLogTesting.throughAgent
import com.ideasdeveloper.bms.server.i18n.I18nCatalogCheck
import com.ideasdeveloper.bms.server.modules.McpToolCall
import com.ideasdeveloper.bms.server.modules.ModulePermissions
import com.ideasdeveloper.bms.server.security.SecretBox
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.ByteArrayInputStream
import java.io.File
import java.math.BigDecimal
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Compras escribe su registro de actividad (ACTIVITY_LOG.md §4) en la MISMA transaccion que cada cambio: solicitudes, cotizaciones,
 * adjuntos, aprobacion, departamentos, aprobadores, moneda, importacion y herramientas del agente. Corre sobre un PostgreSQL
 * DESECHABLE (BMS_ACTIVITY_PG_URL o BMS_SERVER_ADMINS_PG_URL, jdbc:postgresql://host:port/postgres?user=postgres): crea y borra la
 * base bms_al_compras. Sin esa variable las pruebas con base de datos no hacen nada.
 */
class ComprasActivityPostgresTest {
    private val url: String? = (System.getenv("BMS_ACTIVITY_PG_URL") ?: System.getenv("BMS_SERVER_ADMINS_PG_URL"))?.takeIf { it.isNotBlank() }
    private val root = I18nCatalogCheck.repoRoot()
    private val database = "bms_al_compras"
    private val ana = ActivityLogTesting.user("Ana Ruiz", UUID.fromString("00000000-0000-0000-0000-00000000a0a0"))
    private val beto = ActivityLogTesting.user("Beto Paz", UUID.fromString("00000000-0000-0000-0000-00000000b0b0"))
    private val access = ActivityLogTesting.accessWithoutDatabase()
    private val secrets = object : SecretBox {
        override val available = true
        override fun seal(tenant: Connection, plain: String) = SecretBox.SealedPrefix + plain.reversed()
        override fun open(tenant: Connection, value: String) = value.removePrefix(SecretBox.SealedPrefix).reversed()
    }

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
            File(root, "modules/compras/migrations").listFiles()!!.filter { it.name.endsWith(".sql") }.sortedBy { it.name }.forEach { db.run(it.readText()) }
            listOf(ana, beto).forEach { user ->
                db.run("INSERT INTO application_users (id, postgres_role, is_active, display_name, email) VALUES ('${user.userId}', 'role_${user.name.substringBefore(' ').lowercase()}', TRUE, '${user.name}', '${user.name.substringBefore(' ').lowercase()}@example.com')")
            }
            db.run("INSERT INTO tenant_modules (module_id, display_name, status, enabled_at) VALUES ('compras', 'Compras', 'enabled', now())")
            db.run("INSERT INTO application_permissions (permission_id, module_id, title) VALUES ('${ComprasModule.Approve}', 'compras', 'Aprobar'), ('${ComprasModule.View}', 'compras', 'Ver') ON CONFLICT DO NOTHING")
            db.run("INSERT INTO application_user_permissions (user_id, permission_id) VALUES ('${beto.userId}', '${ComprasModule.Approve}'), ('${beto.userId}', '${ComprasModule.View}')")
            block(db)
        }
    }

    private fun permissions(who: ActivityActor, vararg granted: String): ModulePermissions = ActivityLogTesting.permissions(granted = granted.toSet(), actor = who)

    private fun actorOf(permissions: ModulePermissions) = Actor(permissions.userId, permissions.activityActor.name, permissions, permissions.activityActor)

    private val manager get() = actorOf(permissions(ana, ComprasModule.View, ComprasModule.Create, ComprasModule.ViewAll, ComprasModule.Manage, ComprasModule.Import))
    private val approver get() = actorOf(permissions(beto, ComprasModule.View, ComprasModule.Approve))

    private fun draft(title: String, amount: String = "500.00") = RequestDraft(title = title, estimatedAmount = BigDecimal(amount), currency = "USD", description = "Para el hotel")

    @Test
    fun theWriteToolsAndRoutesAreTheOnesTheTestsCover() {
        // Si un modulo anade una herramienta o ruta de escritura, esta prueba falla hasta que se cubra aqui.
        assertEquals(listOf("compras_approve_request", "compras_create_request", "compras_reject_request", "compras_submit_request"), ActivityLogTesting.writeTools(ComprasServerModule))
        // POST /files solo sube un archivo pendiente (el vinculo a la solicitud deja su fila) y POST /{screen} ejecuta las acciones de las pantallas.
        val routes = ActivityLogTesting.writeRoutes(ComprasServerModule)
        assertEquals(2, routes.size, routes.toString())
        assertTrue(routes.all { it.startsWith("POST ") }, routes.toString())
    }

    @Test
    fun requestLifecycleLeavesOneRowPerChangeWithWhoDidIt() = withTenant { db ->
        val ana = manager
        val request = ComprasRepository.create(db, ana, draft("Sabanas"))
        var rows = rows(db)
        assertEquals(listOf(ActivityActions.Created), rows.map { it.action })
        assertEquals("Ana Ruiz", rows.single().display)
        assertEquals(this.ana.userId, rows.single().userId)
        assertEquals("solicitud", rows.single().type)
        assertEquals(request.id.toString(), rows.single().entityId)
        assertTrue(rows.single().has("title", null, "Sabanas"), rows.single().changes)

        ComprasRepository.update(db, ana, request.id, draft("Sabanas king", "650.00"))
        val edit = rows(db).last()
        assertEquals(ActivityActions.Updated, edit.action)
        assertTrue(edit.has("title", "Sabanas", "Sabanas king") && edit.has("estimatedAmount", "500", "650"), edit.changes)
        assertFalse(edit.changes.contains("\"field\": \"currency\""), edit.changes)

        val quote = ComprasRepository.addQuote(db, ana, request.id, "Ferreteria Sol", BigDecimal("120"), "USD", "entrega en 3 dias")
        ComprasRepository.updateQuote(db, ana, request.id, quote.id, "Ferreteria Sol", BigDecimal("110"), "USD")
        ComprasRepository.selectQuote(db, ana, request.id, quote.id)
        ComprasRepository.deleteQuote(db, ana, request.id, quote.id)
        val quoteRows = rows(db, "entity_type = 'cotizacion'")
        assertEquals(listOf(ActivityActions.Created, ActivityActions.Updated, ActivityActions.Deleted), quoteRows.map { it.action })
        assertEquals("Ferreteria Sol", quoteRows.last().label)
        assertTrue(quoteRows.last().has("amount", "110", null), quoteRows.last().changes)
        assertTrue(rows(db).any { it.summary.startsWith("Elijo la cotizacion") && it.type == "solicitud" })
        assertEquals(0, count(db, "SELECT count(*) FROM compras_quotes"))

        // Adjuntos: la fila nombra el archivo y su tamano, nunca el contenido ni donde se guardo.
        val pending = UUID.randomUUID()
        db.run("INSERT INTO compras_attachments (id, kind, file_name, size_bytes, storage_backend, storage_ref, uploaded_by, uploaded_by_id) VALUES ('$pending', 'support', 'contrato.pdf', 24576, 'local', 'secreto/ruta/abc', 'Ana', '${this.ana.userId}')")
        ComprasAttachments.link(db, ana, request.id, listOf(pending))
        ComprasAttachments.remove(db, ana, request.id, pending)
        val files = rows(db, "action IN ('attachment_added', 'attachment_removed')")
        assertEquals(listOf(ActivityActions.AttachmentAdded, ActivityActions.AttachmentRemoved), files.map { it.action })
        assertTrue(files.first().changes.contains("contrato.pdf (24 KB)"), files.first().changes)
        assertTrue(files.none { it.changes.contains("secreto") || it.summary.contains("secreto") })

        // Flujo de aprobacion: quien aprueba es quien figura.
        db.run("INSERT INTO compras_approvers (user_id, max_amount, limit_currency) VALUES ('${beto.userId}', 1000, 'USD')")
        ComprasRepository.transition(db, ana, request.id, RequestStatus.PendingApproval)
        ComprasRepository.transition(db, approver, request.id, RequestStatus.Approved)
        ComprasRepository.transition(db, ana, request.id, RequestStatus.InProgress)
        val statuses = rows(db, "action = 'status_changed'")
        assertEquals(listOf("Ana Ruiz", "Beto Paz", "Ana Ruiz"), statuses.map { it.display })
        assertTrue(statuses[1].has("status", "Pendiente de aprobacion", "Aprobada") || statuses[1].changes.contains("Aprobada"), statuses[1].changes)
        assertTrue(statuses[1].changes.contains("approvedAmount"), statuses[1].changes)

        val second = ComprasRepository.create(db, ana, draft("Toallas"))
        ComprasRepository.transition(db, ana, second.id, RequestStatus.PendingApproval)
        ComprasRepository.transition(db, approver, second.id, RequestStatus.Rejected, "Falta presupuesto")
        val rejected = rows(db, "entity_id = '${second.id}' AND action = 'status_changed'").last()
        assertEquals("Beto Paz", rejected.display)
        assertTrue(rejected.changes.contains("Falta presupuesto"), rejected.changes)

        // Cada fila lleva el modulo y el origen de una persona en la app.
        assertTrue(rows(db).all { it.module == "compras" && it.origin == "app" })
    }

    @Test
    fun settingsAreRecordedInTheSameTransactionAndDeletesLeaveTheirRow() = withTenant { db ->
        val ana = manager
        ComprasRepository.setDefaultCurrency(db, ana, "EUR")
        ComprasRepository.setDefaultCurrency(db, ana, "EUR")
        assertEquals(1, rows(db, "entity_type = 'settings'").size)
        assertTrue(rows(db, "entity_type = 'settings'").single().has("defaultCurrency", "USD", "EUR"))

        val department = ComprasRepository.saveDepartment(db, ana, null, "Eventos")
        ComprasRepository.saveDepartment(db, ana, department, "Eventos y bodas")
        ComprasRepository.setDepartmentActive(db, ana, department, false)
        ComprasRepository.setDepartmentActive(db, ana, department, false)
        ComprasRepository.setDepartmentActive(db, ana, department, true)
        assertEquals(
            listOf(ActivityActions.Created, ActivityActions.Updated, ActivityActions.Archived, ActivityActions.Restored),
            rows(db, "entity_type = 'departamento'").map { it.action },
        )

        ComprasRepository.saveApprover(db, ana, beto.userId!!, BigDecimal("1000"), "USD", true)
        ComprasRepository.saveApprover(db, ana, beto.userId!!, BigDecimal("2500"), "USD", true)
        ComprasRepository.removeApprover(db, ana, beto.userId!!)
        ComprasRepository.removeApprover(db, ana, beto.userId!!)
        val approvers = rows(db, "entity_type = 'aprobador'")
        assertEquals(listOf(ActivityActions.Created, ActivityActions.Updated, ActivityActions.Deleted), approvers.map { it.action })
        assertTrue(approvers.last().has("maxAmount", "2500", null), approvers.last().changes)
        assertEquals(0, count(db, "SELECT count(*) FROM compras_approvers"))
    }

    @Test
    fun aFailedLogRowCancelsTheSettingChange() = withTenant { db ->
        // Si la fila del registro no se puede escribir, el cambio no queda: van en la misma transaccion.
        db.run(
            "CREATE FUNCTION al_refuse() RETURNS trigger LANGUAGE plpgsql AS \$\$ BEGIN RAISE EXCEPTION 'sin registro'; END; \$\$",
        )
        db.run("CREATE TRIGGER al_refuse_settings BEFORE INSERT ON activity_log FOR EACH ROW WHEN (NEW.entity_type IN ('departamento', 'aprobador', 'settings')) EXECUTE FUNCTION al_refuse()")
        val ana = manager
        assertFailsWith<java.sql.SQLException> { ComprasRepository.setDefaultCurrency(db, ana, "EUR") }
        assertEquals("USD", ComprasRepository.settings(db).defaultCurrency)
        val before = ComprasRepository.departments(db).size
        assertFailsWith<java.sql.SQLException> { ComprasRepository.saveDepartment(db, ana, null, "Eventos") }
        assertEquals(before, ComprasRepository.departments(db).size)
        assertFailsWith<java.sql.SQLException> { ComprasRepository.saveApprover(db, ana, beto.userId!!, BigDecimal("100"), "USD", true) }
        assertEquals(0, ComprasRepository.approvers(db).size)
        // El autocommit vuelve a estar como antes y la conexion sigue sirviendo.
        assertTrue(db.autoCommit)
        assertEquals(0, rows(db).size)

        db.run("DROP TRIGGER al_refuse_settings ON activity_log")
        db.run("CREATE TRIGGER al_refuse_requests BEFORE INSERT ON activity_log FOR EACH ROW WHEN (NEW.entity_type = 'solicitud') EXECUTE FUNCTION al_refuse()")
        assertFailsWith<java.sql.SQLException> { ComprasRepository.create(db, ana, draft("Sabanas")) }
        assertEquals(0, count(db, "SELECT count(*) FROM compras_requests"))
        assertEquals(0, count(db, "SELECT count(*) FROM compras_events"))
    }

    @Test
    fun anAgentToolWritesTheRowAsAgentOfTheUser() = withTenant { db ->
        val agent = permissions(ana, ComprasModule.View, ComprasModule.Create, ComprasModule.Manage).throughAgent("Claude")
        val create = ComprasMcpTools.tools.first { it.name == "create_request" }
        val call = McpToolCall(
            JsonObject(mapOf("title" to JsonPrimitive("Sabanas"), "estimated_amount" to JsonPrimitive("500"), "currency" to JsonPrimitive("USD"))),
            agent, db, access, "Claude", ComprasModule.id,
        )
        create.handler(call)
        val row = rows(db).single()
        assertEquals("Agente de Ana Ruiz", row.display)
        assertEquals("Claude", row.agent)
        assertEquals("agent", row.origin)
        assertEquals(ana.userId, row.userId)
        assertEquals(ActivityActions.Created, row.action)

        // Enviarla a aprobacion por agente tambien lo dice.
        val submit = ComprasMcpTools.tools.first { it.name == "submit_request" }
        submit.handler(McpToolCall(JsonObject(mapOf("id" to JsonPrimitive(row.entityId))), agent, db, access, "Claude", ComprasModule.id))
        val moved = rows(db, "action = 'status_changed'").single()
        assertTrue(moved.display.startsWith("Agente de "), moved.display)
        assertEquals("Claude", moved.agent)
    }

    @Test
    fun importedRecordsAreAttributedToTheImportAndTheAgentThatStartedIt() = withTenant { db ->
        val agentManager = permissions(ana, ComprasModule.View, ComprasModule.Create, ComprasModule.Manage, ComprasModule.Import).throughAgent("Claude")
        val importer = actorOf(agentManager)
        val files = access.files("acme", ComprasModule.id)
        val ctx = ComprasImportContext(db, "acme", importer, files, null, secrets)
        val item = ImportedRequest(
            key = "monday:900", title = "Aire acondicionado", description = "Sala", department = "Climatizacion", estimatedAmount = BigDecimal("900"), currency = "USD",
            requiredDate = null, priority = Priority.High, status = RequestStatus.Draft, requesterName = "Ana Ruiz", requesterEmail = null, decisionReason = null,
            createdAt = Instant.parse("2026-01-10T10:00:00Z"),
            quotes = listOf(ImportedQuote("monday:900:q1", "Frio Total", BigDecimal("850"), "USD", true, "Ana", "", emptyList())),
            files = listOf(ImportedFile("monday:900:f1", "proforma.pdf", "application/pdf", "proforma") { ByteArrayInputStream(ByteArray(2048) { 7 }) }),
        )
        val result = ComprasImportEngine.apply(ctx, importer, item)
        assertEquals(ImportOutcome.Created, result.outcome, result.problems.toString())
        val written = rows(db)
        assertTrue(written.all { it.kind == "import" && it.display == "Importacion (Agente de Ana Ruiz)" && it.agent == "Claude" && it.origin == "import" }, written.toString())
        assertEquals(listOf("departamento", "solicitud", "cotizacion", "solicitud"), written.map { it.type })
        assertEquals(listOf(ActivityActions.Created, ActivityActions.Created, ActivityActions.Created, ActivityActions.AttachmentAdded), written.map { it.action })
        assertTrue(written[1].has("title", null, "Aire acondicionado") && written[1].has("status", null, "Borrador") && written[1].has("requester", null, "Ana Ruiz"), written[1].changes)
        assertTrue(written.last().changes.contains("proforma.pdf (2 KB)"), written.last().changes)
        assertEquals(ana.userId, written.first().userId)

        // Repetir la importacion no escribe nada nuevo.
        val again = ComprasImportEngine.apply(ctx, importer, item)
        assertEquals(ImportOutcome.Existing, again.outcome)
        assertEquals(written.size, rows(db).size)
    }

    @Test
    fun anImportThatFailsLeavesNoRowsBehind() = withTenant { db ->
        db.run("CREATE FUNCTION al_refuse() RETURNS trigger LANGUAGE plpgsql AS \$\$ BEGIN RAISE EXCEPTION 'sin registro'; END; \$\$")
        db.run("CREATE TRIGGER al_refuse_quotes BEFORE INSERT ON activity_log FOR EACH ROW WHEN (NEW.entity_type = 'cotizacion') EXECUTE FUNCTION al_refuse()")
        val importer = manager
        val ctx = ComprasImportContext(db, "acme", importer, access.files("acme", ComprasModule.id), null, secrets)
        val item = ImportedRequest(
            key = "monday:901", title = "Pintura", description = "", department = null, estimatedAmount = BigDecimal("300"), currency = "USD", requiredDate = null,
            priority = Priority.Medium, status = RequestStatus.Draft, requesterName = "Ana Ruiz", requesterEmail = null, decisionReason = null, createdAt = null,
            quotes = listOf(ImportedQuote("monday:901:q1", "Pinturas SA", BigDecimal("280"), "USD", false, "Ana")),
        )
        val result = ComprasImportEngine.apply(ctx, importer, item)
        assertEquals(ImportOutcome.Failed, result.outcome)
        assertEquals(0, count(db, "SELECT count(*) FROM compras_requests"))
        assertEquals(0, rows(db).size)
        assertNotNull(result.problems.firstOrNull())
    }
}
