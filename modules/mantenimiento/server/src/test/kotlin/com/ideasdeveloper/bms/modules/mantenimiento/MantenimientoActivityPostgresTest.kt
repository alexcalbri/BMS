package com.ideasdeveloper.bms.modules.mantenimiento

import com.ideasdeveloper.bms.server.activity.ActivityActions
import com.ideasdeveloper.bms.server.activity.ActivityActor
import com.ideasdeveloper.bms.server.activity.ActivityLogTesting
import com.ideasdeveloper.bms.server.activity.ActivityLogTesting.throughAgent
import com.ideasdeveloper.bms.server.i18n.I18nCatalogCheck
import com.ideasdeveloper.bms.server.modules.McpToolCall
import com.ideasdeveloper.bms.server.modules.ModulePermissions
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.ByteArrayInputStream
import java.io.File
import java.math.BigDecimal
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Mantenimiento escribe su registro de actividad (ACTIVITY_LOG.md §4) en la MISMA transaccion que cada cambio: ordenes (alta,
 * edicion, asignar, tomar, estado, notas, trabajo, cierre), lista de comprobacion, adjuntos, propiedades, activos, planes, ajustes,
 * la generacion de ordenes por un plan, la importacion y las herramientas del agente. Corre sobre un PostgreSQL DESECHABLE
 * (BMS_ACTIVITY_PG_URL o BMS_SERVER_ADMINS_PG_URL, jdbc:postgresql://host:port/postgres?user=postgres): crea y borra la base
 * bms_al_mantenimiento. Sin esa variable las pruebas con base de datos no hacen nada.
 */
class MantenimientoActivityPostgresTest {
    private val url: String? = (System.getenv("BMS_ACTIVITY_PG_URL") ?: System.getenv("BMS_SERVER_ADMINS_PG_URL"))?.takeIf { it.isNotBlank() }
    private val root = I18nCatalogCheck.repoRoot()
    private val database = "bms_al_mantenimiento"
    private val ana = ActivityLogTesting.user("Ana Ruiz", UUID.fromString("00000000-0000-0000-0000-00000000a0a0"))
    private val luis = ActivityLogTesting.user("Luis Mora", UUID.fromString("00000000-0000-0000-0000-00000000b0b0"))
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
            File(root, "modules/mantenimiento/migrations").listFiles()!!.filter { it.name.endsWith(".sql") }.sortedBy { it.name }.forEach { db.run(it.readText()) }
            listOf(ana, luis).forEach { user ->
                db.run("INSERT INTO application_users (id, postgres_role, is_active, display_name, email) VALUES ('${user.userId}', 'role_${user.name.substringBefore(' ').lowercase()}', TRUE, '${user.name}', '${user.name.substringBefore(' ').lowercase()}@example.com')")
            }
            db.run("INSERT INTO tenant_modules (module_id, display_name, status, enabled_at) VALUES ('mantenimiento', 'Mantenimiento', 'enabled', now())")
            db.run("INSERT INTO application_permissions (permission_id, module_id, title) VALUES ('${MantenimientoModule.Manage}', 'mantenimiento', 'Gestionar'), ('${MantenimientoModule.Execute}', 'mantenimiento', 'Ejecutar') ON CONFLICT DO NOTHING")
            db.run("INSERT INTO application_user_permissions (user_id, permission_id) VALUES ('${ana.userId}', '${MantenimientoModule.Manage}'), ('${luis.userId}', '${MantenimientoModule.Execute}')")
            block(db)
        }
    }

    private fun managerPermissions(): ModulePermissions =
        ActivityLogTesting.permissions(granted = setOf(MantenimientoModule.View, MantenimientoModule.Manage, MantenimientoModule.Execute), actor = ana)

    private fun workerPermissions(): ModulePermissions =
        ActivityLogTesting.permissions(granted = setOf(MantenimientoModule.View, MantenimientoModule.Execute), actor = luis)

    private fun actorOf(permissions: ModulePermissions) = MantenimientoActor(permissions.userId, permissions.activityActor.name, permissions.activityActor)

    private fun scopeOf(permissions: ModulePermissions) = MantenimientoScope.from(permissions)

    private fun order(db: Connection, title: String = "Fuga en el bano", kind: OrderKind = OrderKind.Corrective): UUID {
        val manager = managerPermissions()
        return MantenimientoRepository.createOrder(db, OrderInput(kind = kind, title = title, description = "Gotea", priority = OrderPriority.High, department = "Hotel"), actorOf(manager)).id
    }

    @Test
    fun theWriteToolsAndRoutesAreTheOnesTheTestsCover() {
        // Si un modulo anade una herramienta o ruta de escritura, esta prueba falla hasta que se cubra aqui.
        assertEquals(
            listOf("mantenimiento_add_note", "mantenimiento_close_order", "mantenimiento_create_order", "mantenimiento_update_order"),
            ActivityLogTesting.writeTools(MantenimientoServerModule),
        )
        val routes = ActivityLogTesting.writeRoutes(MantenimientoServerModule)
        assertTrue(routes.isNotEmpty() && routes.all { it.startsWith("POST ") }, routes.toString())
    }

    @Test
    fun theTareasPublicationActsAsAnAutomationOfTheUserWhoConfiguredIt() {
        val granted = ActivityLogTesting.permissions(granted = setOf("tareas.manage"), actor = ana)
        val acting = MantenimientoBridge.acting(granted, MantenimientoTareasSync.PublisherAutomation)
        assertEquals("automation", acting.activityActor.kind)
        assertEquals("Automatizacion Publicacion de mantenimiento en Tareas (Ana Ruiz)", acting.activityActor.display)
        assertEquals(ana.userId, acting.activityActor.userId)
        // Cuando una persona pulsa el boton (pedir la compra) sigue figurando ella.
        assertEquals("Ana Ruiz", MantenimientoBridge.acting(granted, null).activityActor.display)
    }

    @Test
    fun orderLifecycleWritesOneRowPerChange() = withTenant { db ->
        val manager = managerPermissions()
        val actor = actorOf(manager)
        val id = order(db)
        val created = rows(db).single()
        assertEquals(ActivityActions.Created, created.action)
        assertEquals("orden", created.type)
        assertEquals(id.toString(), created.entityId)
        assertEquals("Ana Ruiz", created.display)
        assertEquals(ana.userId, created.userId)
        assertTrue(created.has("title", null, "Fuga en el bano") && created.has("priority", null, "Alta") && created.has("status", null, "Abierta"), created.changes)

        // Edicion con diferencias (y sin fila si no cambia nada).
        val input = OrderInput(title = "Fuga en el bano 2", description = "Gotea", priority = OrderPriority.Urgent, department = "Hotel", dueDate = today.plusDays(3))
        MantenimientoRepository.updateOrder(db, id, input, actor)
        MantenimientoRepository.updateOrder(db, id, input, actor)
        val edits = rows(db, "action = 'updated'")
        assertEquals(1, edits.size)
        assertTrue(edits.single().has("title", "Fuga en el bano", "Fuga en el bano 2") && edits.single().has("priority", "Alta", "Urgente"), edits.single().changes)
        assertTrue(edits.single().has("dueDate", null, today.plusDays(3).toString()), edits.single().changes)

        // Asignar (assignOrder) y quitar el responsable.
        MantenimientoRepository.assignOrder(db, id, luis.userId, actor)
        MantenimientoRepository.assignOrder(db, id, luis.userId, actor)
        MantenimientoRepository.assignOrder(db, id, null, actor)
        val assigned = rows(db, "action = 'assigned'")
        assertEquals(2, assigned.size)
        assertTrue(assigned.first().has("assignee", null, "Luis Mora"), assigned.first().changes)
        assertTrue(assigned.last().has("assignee", "Luis Mora", null), assigned.last().changes)

        // Tomar la orden: quien la toma figura como actor y como responsable.
        val worker = workerPermissions()
        assertTrue(MantenimientoRepository.takeOrder(db, id, actorOf(worker)))
        val taken = rows(db, "action = 'assigned'").last()
        assertEquals("Luis Mora", taken.display)
        assertEquals(luis.userId, taken.userId)
        assertTrue(taken.has("assignee", null, "Luis Mora"), taken.changes)
        assertFalse(MantenimientoRepository.takeOrder(db, id, actorOf(worker)))
        assertEquals(3, rows(db, "action = 'assigned'").size)

        // Cambios de estado: cada uno con su fila y quien lo hizo.
        MantenimientoRepository.changeStatus(db, id, OrderStatus.InProgress, actorOf(worker), scopeOf(worker))
        MantenimientoRepository.changeStatus(db, id, OrderStatus.OnHold, actor, scopeOf(manager), "Falta pieza")
        val statuses = rows(db, "action = 'status_changed'")
        assertEquals(listOf("Luis Mora", "Ana Ruiz"), statuses.map { it.display })
        assertTrue(statuses[0].has("status", "Abierta", "En curso"), statuses[0].changes)
        assertTrue(statuses[1].has("status", "En curso", "En espera") && statuses[1].has("reason", null, "Falta pieza"), statuses[1].changes)
        assertFailsWith<MantenimientoValidationException> { MantenimientoRepository.changeStatus(db, id, OrderStatus.Requested, actor, scopeOf(manager)) }
        assertEquals(2, rows(db, "action = 'status_changed'").size)

        // Nota, trabajo y cierre.
        MantenimientoRepository.addNote(db, id, "Llamar al plomero", actorOf(worker))
        val note = rows(db, "action = 'commented'").single()
        assertEquals("Luis Mora", note.display)
        assertTrue(note.has("note", null, "Llamar al plomero"), note.changes)
        MantenimientoRepository.updateWork(db, id, BigDecimal("45.5"), "USD", "Cambio de sifon", actorOf(worker).activity)
        val work = rows(db, "action = 'updated'").last()
        assertTrue(work.has("costAmount", null, "45.5") && work.has("resolution", null, "Cambio de sifon"), work.changes)
        MantenimientoRepository.closeOrder(db, id, "Listo", BigDecimal("50"), actorOf(worker), scopeOf(worker))
        val closed = rows(db, "action = 'status_changed'").last()
        assertEquals("Luis Mora", closed.display)
        assertTrue(closed.has("status", "En espera", "Completada") || closed.changes.contains("Completada"), closed.changes)
        assertTrue(rows(db).all { it.module == "mantenimiento" && it.origin == "app" })
    }

    @Test
    fun checklistAttachmentsAndPurchaseLinkAreRecorded() = withTenant { db ->
        val manager = managerPermissions()
        val id = order(db)
        val item = MantenimientoRepository.addCheckItem(db, id, "Cerrar la llave de paso", manager.activityActor)
        MantenimientoRepository.setCheckDone(db, id, item, true, ana.userId, manager.activityActor)
        MantenimientoRepository.setCheckDone(db, id, item, true, ana.userId, manager.activityActor)
        MantenimientoRepository.setCheckDone(db, id, item, false, ana.userId, manager.activityActor)
        MantenimientoRepository.removeCheckItem(db, id, item, manager.activityActor)
        val checks = rows(db, "entity_type = 'checklist'")
        assertEquals(listOf(ActivityActions.Created, ActivityActions.Updated, ActivityActions.Updated, ActivityActions.Deleted), checks.map { it.action })
        assertTrue(checks[1].has("done", "No", "Si"), checks[1].changes)
        assertTrue(checks.last().has("text", "Cerrar la llave de paso", null), checks.last().changes)
        assertEquals(0, count(db, "SELECT count(*) FROM mantenimiento_order_checklist"))

        // Adjuntos: nombre y tamano, nunca el contenido ni donde se guardo.
        val pending = UUID.randomUUID()
        db.run("INSERT INTO mantenimiento_attachments (id, name, content_type, size, backend, ref, uploaded_by) VALUES ('$pending', 'foto.jpg', 'image/jpeg', 8192, 'local', 'ruta/secreta/abc', '${ana.userId}')")
        MantenimientoRepository.attach(db, id, listOf(pending), ana.userId, manager.activityActor)
        MantenimientoRepository.markDeleted(db, id, listOf(pending), manager.activityActor)
        val files = rows(db, "action IN ('attachment_added', 'attachment_removed')")
        assertEquals(listOf(ActivityActions.AttachmentAdded, ActivityActions.AttachmentRemoved), files.map { it.action })
        assertTrue(files.first().changes.contains("foto.jpg (8 KB)"), files.first().changes)
        assertTrue(files.none { it.changes.contains("secreta") || it.summary.contains("secreta") })

        // La solicitud de compra que se crea desde la orden queda enlazada con su fila.
        assertEquals(MantenimientoRepository.PurchaseReservation.Reserved, MantenimientoRepository.reservePurchase(db, id))
        assertTrue(MantenimientoRepository.savePurchase(db, id, UUID.randomUUID(), "C-00012", manager.activityActor))
        val link = rows(db, "summary LIKE 'Enlazo la solicitud de compra%'").single()
        assertTrue(link.has("purchase", null, "C-00012"), link.changes)
    }

    @Test
    fun propertiesAssetsPlansAndSettingsLeaveTheirRowsAndTheLastOneKeepsKeyData() = withTenant { db ->
        val manager = managerPermissions()
        MantenimientoService.addProperty(db, manager, "Hotel Norte")
        MantenimientoService.addProperty(db, manager, "Hotel Sur")
        val north = MantenimientoRepository.listProperties(db).first { it.name == "Hotel Norte" }
        MantenimientoService.renameProperty(db, manager, north.id, "Hotel Norte Plaza")
        MantenimientoService.moveProperty(db, manager, MantenimientoRepository.listProperties(db).first { it.name == "Hotel Sur" }.id, up = true)
        MantenimientoService.archiveProperty(db, manager, north.id, true)
        MantenimientoService.archiveProperty(db, manager, north.id, false)
        val properties = rows(db, "entity_type = 'propiedad'").map { it.action }
        assertEquals(
            listOf(ActivityActions.Created, ActivityActions.Created, ActivityActions.Updated, ActivityActions.Updated, ActivityActions.Archived, ActivityActions.Restored),
            properties,
        )

        val assetId = MantenimientoService.saveAsset(db, manager, null, mapOf("name" to "Caldera 1", "property" to "Hotel Sur", "category" to "Agua caliente"))
        MantenimientoService.saveAsset(db, manager, assetId, mapOf("name" to "Caldera 1", "property" to "Hotel Sur", "category" to "Calefaccion"))
        MantenimientoService.archiveAsset(db, manager, assetId, true)
        val assets = rows(db, "entity_type = 'activo'")
        assertEquals(listOf(ActivityActions.Created, ActivityActions.Updated, ActivityActions.Archived), assets.map { it.action })
        assertTrue(assets[1].has("category", "Agua caliente", "Calefaccion"), assets[1].changes)

        val planId = MantenimientoService.savePlan(db, manager, null, mapOf("title" to "Revisar caldera", "property" to "Hotel Sur", "next_due" to "2026-12-01", "lead_days" to "5"))
        MantenimientoService.savePlan(db, manager, planId, mapOf("title" to "Revisar caldera", "property" to "Hotel Sur", "next_due" to "2026-12-15", "lead_days" to "5"))
        MantenimientoService.changePlanChecklist(db, manager, planId, null) { items, _ -> items + "Purgar radiadores" }
        MantenimientoService.setPlanActive(db, manager, planId, false)
        MantenimientoService.setPlanActive(db, manager, planId, true)
        val plans = rows(db, "entity_type = 'plan'")
        assertEquals(listOf(ActivityActions.Created, ActivityActions.Updated, ActivityActions.Updated, ActivityActions.Disabled, ActivityActions.Enabled), plans.map { it.action })
        assertTrue(plans[1].has("nextDue", "2026-12-01", "2026-12-15"), plans[1].changes)
        assertTrue(plans[2].has("checklist", null, "Purgar radiadores"), plans[2].changes)

        MantenimientoService.saveDueSoon(db, manager, "3")
        MantenimientoService.saveDueSoon(db, manager, "3")
        val settings = rows(db, "entity_type = 'settings'").single()
        assertEquals(ActivityActions.SettingsChanged, settings.action)
        assertTrue(settings.has("dueSoonDays", "1", "3"), settings.changes)
    }

    @Test
    fun aPlanThatGeneratesAnOrderIsAScheduledJobWithTheOrdersKeyData() = withTenant { db ->
        val manager = managerPermissions()
        val planId = MantenimientoService.savePlan(db, manager, null, mapOf("title" to "Cambiar filtros", "next_due" to today.plusDays(2).toString(), "lead_days" to "7"))
        val plan = MantenimientoRepository.getPlan(db, planId)!!
        val generated = MantenimientoRepository.generateOrder(db, plan, today)
        assertNotNull(generated)
        val row = rows(db, "entity_type = 'orden'").single()
        assertEquals("job", row.kind)
        assertEquals("Tarea programada Plan preventivo", row.display)
        assertEquals("automation", row.origin)
        assertTrue(row.has("title", null, "Cambiar filtros") && row.has("kind", null, "Preventiva"), row.changes)
        assertEquals(null, row.userId)
    }

    @Test
    fun aFailedLogRowCancelsTheChange() = withTenant { db ->
        val manager = managerPermissions()
        db.run("CREATE FUNCTION al_refuse() RETURNS trigger LANGUAGE plpgsql AS \$\$ BEGIN RAISE EXCEPTION 'sin registro'; END; \$\$")
        db.run("CREATE TRIGGER al_refuse_orders BEFORE INSERT ON activity_log FOR EACH ROW WHEN (NEW.entity_type = 'orden') EXECUTE FUNCTION al_refuse()")
        assertFailsWith<SQLException> { order(db) }
        assertEquals(0, count(db, "SELECT count(*) FROM mantenimiento_orders"))
        assertEquals(0, count(db, "SELECT count(*) FROM mantenimiento_order_notes"))
        db.run("DROP TRIGGER al_refuse_orders ON activity_log")

        val id = order(db)
        db.run("CREATE TRIGGER al_refuse_status BEFORE INSERT ON activity_log FOR EACH ROW WHEN (NEW.action = 'status_changed') EXECUTE FUNCTION al_refuse()")
        assertFailsWith<SQLException> { MantenimientoRepository.changeStatus(db, id, OrderStatus.InProgress, actorOf(manager), scopeOf(manager)) }
        assertEquals("open", db.createStatement().use { st -> st.executeQuery("SELECT status FROM mantenimiento_orders WHERE id = '$id'").use { it.next(); it.getString(1) } })
        assertEquals(0, count(db, "SELECT count(*) FROM mantenimiento_order_notes WHERE kind = 'status'"))

        db.run("CREATE TRIGGER al_refuse_props BEFORE INSERT ON activity_log FOR EACH ROW WHEN (NEW.entity_type = 'propiedad') EXECUTE FUNCTION al_refuse()")
        assertFailsWith<SQLException> { MantenimientoService.addProperty(db, manager, "Hotel Este") }
        assertEquals(0, count(db, "SELECT count(*) FROM mantenimiento_properties"))
        assertTrue(db.autoCommit)
    }

    @Test
    fun anAgentToolWritesTheRowAsAgentOfTheUser() = withTenant { db ->
        val agent = managerPermissions().throughAgent("Claude")
        val create = MantenimientoMcpTools.tools.first { it.name == "create_order" }
        create.handler(McpToolCall(JsonObject(mapOf("title" to JsonPrimitive("Luz fundida"), "priority" to JsonPrimitive("low"))), agent, db, access, "Claude", MantenimientoModule.id))
        val created = rows(db).single()
        assertEquals("Agente de Ana Ruiz", created.display)
        assertEquals("Claude", created.agent)
        assertEquals("agent", created.origin)
        assertEquals(ana.userId, created.userId)
        assertEquals(ActivityActions.Created, created.action)

        val update = MantenimientoMcpTools.tools.first { it.name == "update_order" }
        update.handler(McpToolCall(JsonObject(mapOf("id" to JsonPrimitive(created.entityId), "status" to JsonPrimitive("in_progress"))), agent, db, access, "Claude", MantenimientoModule.id))
        val addNote = MantenimientoMcpTools.tools.first { it.name == "add_note" }
        addNote.handler(McpToolCall(JsonObject(mapOf("id" to JsonPrimitive(created.entityId), "body" to JsonPrimitive("Foco nuevo en bodega"))), agent, db, access, "Claude", MantenimientoModule.id))
        val close = MantenimientoMcpTools.tools.first { it.name == "close_order" }
        close.handler(McpToolCall(JsonObject(mapOf("id" to JsonPrimitive(created.entityId), "resolution" to JsonPrimitive("Cambiado"))), agent, db, access, "Claude", MantenimientoModule.id))
        val all = rows(db)
        assertEquals(listOf("created", "status_changed", "commented", "updated", "status_changed"), all.map { it.action })
        assertTrue(all.all { it.display == "Agente de Ana Ruiz" && it.agent == "Claude" && it.origin == "agent" }, all.toString())
    }

    @Test
    fun importedPlansOrdersPropertiesAssetsAndFilesAreRecordedWithTheirKeyData() = withTenant { db ->
        val importer = ActivityActor.import(ana.viaAgent("Claude"))
        val users = MantenimientoImportApply.assignableUsers(db)
        val plan = ImportedPlan(
            key = "monday:p1", title = "Revisar extintores", description = "Cada trimestre", propertyName = "Hotel Norte", assetName = "Extintores", frequency = PlanFrequency.Quarterly,
            nextDue = today.plusDays(30), priority = OrderPriority.High, assigneeEmail = "luis@example.com",
            checklist = listOf(ImportedCheck("c1", "Revisar presion"), ImportedCheck("c2", "Revisar fecha")),
        )
        assertTrue(MantenimientoImportApply.applyPlan(db, plan, users, ana.userId, today, importer) is ImportOutcome.Created)
        val written = rows(db)
        assertTrue(written.all { it.kind == "import" && it.display == "Importacion (Agente de Ana Ruiz)" && it.agent == "Claude" && it.origin == "import" }, written.toString())
        assertEquals(listOf("propiedad", "activo", "plan"), written.map { it.type })
        val created = written.last()
        assertEquals(ActivityActions.Created, created.action)
        assertTrue(created.has("title", null, "Revisar extintores") && created.has("property", null, "Hotel Norte") && created.has("asset", null, "Extintores") && created.has("frequency", null, "Trimestral"), created.changes)
        assertTrue(created.changes.contains("Revisar presion") && created.changes.contains("2 elemento(s) de lista"), created.changes)

        // Repetir no escribe; cambiar un dato escribe la diferencia.
        val total = written.size
        MantenimientoImportApply.applyPlan(db, plan, users, ana.userId, today, importer)
        assertEquals(total, rows(db).size)
        MantenimientoImportApply.applyPlan(db, plan.copy(priority = OrderPriority.Urgent), users, ana.userId, today, importer)
        val updated = rows(db).last()
        assertEquals(ActivityActions.Updated, updated.action)
        assertTrue(updated.has("priority", "Alta", "Urgente"), updated.changes)

        // Una orden con lista, notas y archivo.
        val order = ImportedOrder(
            key = "monday:o1", kind = OrderKind.Corrective, status = OrderStatus.InProgress, title = "Aire acondicionado", description = "No enfria", propertyName = "Hotel Norte",
            department = "Hotel", priority = OrderPriority.Urgent, dueDate = today.plusDays(1), assigneeEmail = "luis@example.com",
            checklist = listOf(ImportedCheck("k1", "Revisar gas", true)),
            notes = listOf(ImportedNote("n1", "Pedro", Instant.parse("2026-11-20T10:00:00Z"), "Lo vi ayer"), ImportedNote("n2", "Pedro", null, "Pedir repuesto")),
        )
        val outcome = MantenimientoImportApply.applyOrder(db, order, users, ana.userId, today, importer)
        assertTrue(outcome is ImportOutcome.Created, outcome.toString())
        val orderRow = rows(db, "entity_type = 'orden'").single()
        assertEquals(ActivityActions.Created, orderRow.action)
        assertTrue(orderRow.has("title", null, "Aire acondicionado") && orderRow.has("status", null, "En curso") && orderRow.has("assignee", null, "Luis Mora"), orderRow.changes)
        assertTrue(orderRow.changes.contains("1 elemento(s) de lista y 2 nota(s)"), orderRow.changes)
        assertEquals(1, count(db, "SELECT count(*) FROM activity_log WHERE entity_type = 'activo'"))

        val orderId = (outcome as ImportOutcome.Created).id
        val files = access.files("acme", MantenimientoModule.id)
        val bytes = ByteArray(5000) { 3 }
        assertTrue(MantenimientoImportApply.attachFile(db, files, orderId, "monday:asset:9", "reporte.pdf", "application/pdf", ByteArrayInputStream(bytes), ana.userId, importer))
        assertFalse(MantenimientoImportApply.attachFile(db, files, orderId, "monday:asset:9", "reporte.pdf", "application/pdf", ByteArrayInputStream(bytes), ana.userId, importer))
        val attachment = rows(db, "action = 'attachment_added'").single()
        assertEquals("Aire acondicionado", attachment.label)
        assertTrue(attachment.changes.contains("reporte.pdf (4 KB)"), attachment.changes)

        // Importar otra vez la orden con otro estado escribe la diferencia, y nada si es igual.
        val before = rows(db).size
        MantenimientoImportApply.applyOrder(db, order, users, ana.userId, today, importer)
        assertEquals(before, rows(db).size)
        MantenimientoImportApply.applyOrder(db, order.copy(status = OrderStatus.Done), users, ana.userId, today, importer)
        val changed = rows(db).last()
        assertEquals(ActivityActions.Updated, changed.action)
        assertTrue(changed.has("status", "En curso", "Completada"), changed.changes)
    }

    @Test
    fun anImportedRecordWhoseLogRowFailsLeavesNothingBehind() = withTenant { db ->
        val importer = ActivityActor.import(ana)
        db.run("CREATE FUNCTION al_refuse() RETURNS trigger LANGUAGE plpgsql AS \$\$ BEGIN RAISE EXCEPTION 'sin registro'; END; \$\$")
        db.run("CREATE TRIGGER al_refuse_orders BEFORE INSERT ON activity_log FOR EACH ROW WHEN (NEW.entity_type = 'orden') EXECUTE FUNCTION al_refuse()")
        val order = ImportedOrder(key = "monday:o2", kind = OrderKind.Corrective, status = OrderStatus.Open, title = "Puerta", propertyName = "Hotel Oeste")
        assertFailsWith<SQLException> { MantenimientoImportApply.applyOrder(db, order, MantenimientoImportApply.assignableUsers(db), ana.userId, today, importer) }
        assertEquals(0, count(db, "SELECT count(*) FROM mantenimiento_orders"))
        // Tampoco queda la propiedad que esa fila habia creado.
        assertEquals(0, count(db, "SELECT count(*) FROM mantenimiento_properties"))
        assertEquals(0, rows(db).size)
    }
}
