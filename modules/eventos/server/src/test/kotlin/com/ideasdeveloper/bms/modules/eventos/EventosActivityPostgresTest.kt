package com.ideasdeveloper.bms.modules.eventos

import com.ideasdeveloper.bms.server.activity.ActivityActions
import com.ideasdeveloper.bms.server.activity.ActivityActor
import com.ideasdeveloper.bms.server.activity.ActivityLogTesting
import com.ideasdeveloper.bms.server.activity.ActivityLogTesting.throughAgent
import com.ideasdeveloper.bms.server.i18n.I18nCatalogCheck
import com.ideasdeveloper.bms.server.modules.McpToolCall
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.math.BigDecimal
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Eventos writes its activity rows (ACTIVITY_LOG.md §4): eventos, inscripciones, lista de espera, abonos, clientes creados
 * desde eventos, configuracion, el cierre automatico y la importacion. Corre sobre un PostgreSQL DESECHABLE
 * (BMS_ACTIVITY_PG_URL o BMS_SERVER_ADMINS_PG_URL): crea y borra la base bms_al_eventos. Sin esa variable no hace nada.
 */
class EventosActivityPostgresTest {
    private val url: String? = (System.getenv("BMS_ACTIVITY_PG_URL") ?: System.getenv("BMS_SERVER_ADMINS_PG_URL"))?.takeIf { it.isNotBlank() }
    private val root = I18nCatalogCheck.repoRoot()
    private val database = "bms_al_eventos"
    private val ana = ActivityLogTesting.user("Ana Ruiz", UUID.fromString("00000000-0000-0000-0000-00000000a0a0"))
    private val access = ActivityLogTesting.accessWithoutDatabase()
    private val future: Instant = Instant.now().plus(30, ChronoUnit.DAYS)

    private fun migration(path: String) = File(root, path).readText().trim().removePrefix("BEGIN;").removeSuffix("COMMIT;")
    private fun urlOf(base: String, name: String) = base.replace(Regex("/[^/?]+(\\?|$)"), "/$name$1")
    private fun Connection.run(sql: String) = createStatement().use { it.execute(sql) }

    /** Base desechable: Core y tenant V011, la tabla de clientes completa y las migraciones del modulo. */
    private fun withTenant(block: (Connection) -> Unit) {
        val base = url ?: return
        DriverManager.getConnection(base).use { admin ->
            admin.run("DROP DATABASE IF EXISTS $database WITH (FORCE)")
            admin.run("CREATE DATABASE $database")
        }
        DriverManager.getConnection(urlOf(base, database)).use { db ->
            listOf("V001__create_application_users", "V002__create_application_sessions", "V003__add_application_user_profile", "V004__add_application_user_code")
                .forEach { db.run(migration("database/core/migrations/$it.sql")) }
            listOf("V001__create_business_owner", "V003__create_tenant_modules", "V004__create_application_permissions", "V005__create_application_user_credentials", "V011__create_activity_log")
                .forEach { db.run(migration("database/tenant/migrations/$it.sql")) }
            File(root, "modules/clientes/migrations").listFiles()!!.filter { it.name.endsWith(".sql") }.sortedBy { it.name }.forEach { db.run(it.readText()) }
            File(root, "modules/eventos/migrations").listFiles()!!.filter { it.name.endsWith(".sql") }.sortedBy { it.name }.forEach { db.run(it.readText()) }
            db.run("INSERT INTO application_users (id, postgres_role, is_active, display_name, email) VALUES ('${ana.userId}', 'role_ana', TRUE, 'Ana Ruiz', 'ana@example.com')")
            block(db)
        }
    }

    private fun manager() = ActivityLogTesting.permissions(granted = setOf(EventosModule.Manage, EventosModule.Register), actor = ana)

    private fun publicEvent(title: String, capacity: Int = 2) = EventInput(
        kind = EventKind.Public, title = title, space = "Sala A", startsAt = future, endsAt = future.plus(2, ChronoUnit.HOURS),
        capacity = capacity, pricePublic = BigDecimal("10"), priceMember = BigDecimal("5"),
    )

    @Test
    fun eventCreateEditAndCancelWriteRows() = withTenant { db ->
        val p = manager()
        val (event, created) = ActivityLogTesting.capture { EventosService.createEvent(db, access, p, publicEvent("Taller de cerámica"), publish = true) }
        assertEquals(ActivityActions.Created, created.single().action)
        assertEquals("Ana Ruiz", created.single().display)
        assertEquals("Taller de cerámica", created.single().entry.entityLabel)

        val edited = publicEvent("Taller de cerámica avanzado")
        val (_, updated) = ActivityLogTesting.capture { EventosService.updateEvent(db, access, p, event.id, edited) }
        assertEquals(ActivityActions.Updated, updated.single().action)
        assertEquals("Taller de cerámica", updated.single().change("title")!!.old)
        assertEquals("Taller de cerámica avanzado", updated.single().change("title")!!.new)

        val (_, cancelled) = ActivityLogTesting.capture { EventosService.changeStatus(db, access, p, event.id, EventStatus.Cancelled, "Lluvia") }
        val row = cancelled.single()
        assertEquals(ActivityActions.StatusChanged, row.action)
        assertEquals("Publicado", row.change("status")!!.old)
        assertEquals("Cancelado", row.change("status")!!.new)
        assertEquals("Lluvia", row.change("reason")!!.new)
    }

    @Test
    fun registrationNewCustomerPaidAndCancelAreRecorded() = withTenant { db ->
        val p = manager()
        val event = EventosService.createEvent(db, access, p, publicEvent("Concierto"), publish = true)
        val (result, rows) = ActivityLogTesting.capture {
            EventosService.register(db, access, p, RegistrationInput(eventId = event.id, customerId = null), NewCustomerInput("Marta Gil", "marta@example.com"))
        }
        assertNotNull(result.registration)
        assertEquals(setOf("customer", "registration"), rows.map { it.entry.entityType }.toSet())
        assertEquals(ActivityActions.Created, rows.first { it.entry.entityType == "registration" }.action)

        val registrationId = result.registration.id
        val (_, paid) = ActivityLogTesting.capture { EventosService.setPaid(db, p, registrationId, paid = true, note = "Efectivo") }
        assertEquals("No", paid.single().change("paid")!!.old)
        assertEquals("Si", paid.single().change("paid")!!.new)

        val (_, cancelled) = ActivityLogTesting.capture { EventosService.cancelRegistration(db, access, p, registrationId) }
        assertTrue(cancelled.isNotEmpty())
        assertEquals("Cancelado", cancelled.first { it.entry.entityType == "registration" }.change("status")!!.new)
    }

    @Test
    fun attendanceOfAnEventThatAlreadyStartedIsRecorded() = withTenant { db ->
        val p = manager()
        // Un evento que ya empezo (hace una hora): la asistencia solo se registra desde el dia del evento.
        val started = Instant.now().minus(1, ChronoUnit.HOURS)
        val event = EventosService.createEvent(db, access, p, publicEvent("Charla de hoy").copy(startsAt = started, endsAt = started.plus(3, ChronoUnit.HOURS)), publish = true)
        val registration = EventosService.register(db, access, p, RegistrationInput(eventId = event.id, customerId = null), NewCustomerInput("Marta Gil", "marta@example.com")).registration!!

        val (_, present) = ActivityLogTesting.capture { EventosService.setAttendance(db, access, p, registration.id, attended = true) }
        val row = present.single()
        assertEquals(ActivityActions.Updated, row.action)
        assertEquals("registration", row.entry.entityType)
        assertEquals("Ana Ruiz", row.display)
        assertEquals("No", row.change("attended")!!.old)
        assertEquals("Si", row.change("attended")!!.new)

        // Marcarla otra vez igual no escribe fila; quitarla si.
        val (_, same) = ActivityLogTesting.capture { EventosService.setAttendance(db, access, p, registration.id, attended = true) }
        assertTrue(same.isEmpty())
        val (_, absent) = ActivityLogTesting.capture { EventosService.setAttendance(db, access, p, registration.id, attended = false) }
        assertEquals("Si", absent.single().change("attended")!!.old)
        assertEquals("No", absent.single().change("attended")!!.new)

        // Un evento que aun no empieza la rechaza y no deja fila.
        val later = EventosService.createEvent(db, access, p, publicEvent("Charla futura"), publish = true)
        val future = EventosService.register(db, access, p, RegistrationInput(eventId = later.id, customerId = null), NewCustomerInput("Beto Paz", "beto@example.com")).registration!!
        val (refused, none) = ActivityLogTesting.capture { runCatching { EventosService.setAttendance(db, access, p, future.id, attended = true) } }
        assertTrue(refused.exceptionOrNull() is EventosValidationException)
        assertTrue(none.isEmpty())
    }

    @Test
    fun paymentCreateAndDeleteWriteRowsAndDeleteIsPersisted() = withTenant { db ->
        val p = manager()
        val customer = EventosRepository.findOrCreateCustomer(db, NewCustomerInput("Familia Ruiz", "")).customerId
        val event = EventosService.createEvent(db, access, p, EventInput(kind = EventKind.Private, title = "Boda", startsAt = future, clientCustomerId = customer, agreedAmount = BigDecimal("800")), publish = true)
        val (payment, created) = ActivityLogTesting.capture { EventosService.addPayment(db, access, p, event.id, LocalDate.now(), BigDecimal("200"), "transferencia", "Anticipo") }
        assertEquals("Abono de Boda", created.single().entry.entityLabel)
        assertEquals(ActivityActions.Created, created.single().action)

        // Sin captura: la fila del borrado se escribe de verdad y el abono ya no existe.
        EventosService.deletePayment(db, p, event.id, payment.id)
        db.prepareStatement("SELECT count(*) FROM eventos_payments WHERE id = ?").use { st ->
            st.setObject(1, payment.id)
            st.executeQuery().use { rows -> rows.next(); assertEquals(0, rows.getInt(1)) }
        }
        db.prepareStatement("SELECT action, actor_display FROM activity_log WHERE entity_id = ? ORDER BY id").use { st ->
            st.setString(1, payment.id.toString())
            st.executeQuery().use { rows ->
                // La fila de alta se capturo arriba (no se escribio); en la base solo queda la del borrado.
                assertTrue(rows.next()); assertEquals("deleted", rows.getString(1)); assertEquals("Ana Ruiz", rows.getString(2))
                assertTrue(!rows.next())
            }
        }
    }

    @Test
    fun settingsChangeWritesSettingsRow() = withTenant { db ->
        val (_, rows) = ActivityLogTesting.capture { EventosService.saveSettings(db, manager(), "eur", listOf("Sala A"), listOf("Cultura")) }
        val row = rows.single()
        assertEquals(ActivityActions.SettingsChanged, row.action)
        assertEquals("EUR", row.change("currency")!!.new)
        assertEquals("Sala A", row.change("spaces")!!.new)
    }

    @Test
    fun closingFinishedEventsIsAJobRow() = withTenant { db ->
        val p = manager()
        val event = EventosService.createEvent(db, access, p, publicEvent("Charla"), publish = true)
        db.run("UPDATE eventos_events SET starts_at = now() - interval '2 days', ends_at = now() - interval '1 day' WHERE id = '${event.id}'")
        val (closed, rows) = ActivityLogTesting.capture { EventosRepository.closeFinished(db) }
        assertEquals(1, closed)
        val row = rows.single()
        assertEquals(ActivityActions.StatusChanged, row.action)
        assertEquals("Realizado", row.change("status")!!.new)
        assertTrue(row.display.startsWith("Tarea programada "), row.display)
    }

    @Test
    fun agentToolWritesRowAttributedToTheUserBehindTheAgent() = withTenant { db ->
        val p = manager()
        val event = EventosService.createEvent(db, access, p, publicEvent("Yoga"), publish = true)
        val customer = EventosRepository.findOrCreateCustomer(db, NewCustomerInput("Carlos Paz", "")).customerId
        val tool = EventosMcpTools.mcp.tools.first { it.name == "register_customer" }
        val arguments = JsonObject(mapOf("event_id" to JsonPrimitive(event.id.toString()), "customer_id" to JsonPrimitive(customer.toString())))
        val call = McpToolCall(arguments, p.throughAgent("Claude"), db, access, "Claude", EventosModule.id)
        val (_, rows) = ActivityLogTesting.capture { tool.handler(call) }
        val registration = rows.first { it.entry.entityType == "registration" }
        assertEquals("Agente de Ana Ruiz", registration.display)
    }

    @Test
    fun importedRowsAreAttributedToTheImport() = withTenant { db ->
        val row = ImportedEvent(
            key = "monday:1", title = "Boda Ruiz", startsAt = LocalDateTime.of(2026, 12, 20, 18, 0), status = EventStatus.Draft,
            clientName = "Familia Ruiz", agreedAmount = BigDecimal("800"),
            payments = listOf(ImportedPayment("monday:pay:1", LocalDate.of(2026, 12, 1), BigDecimal("100"))),
        )
        val (outcome, rows) = ActivityLogTesting.capture {
            EventosImportApply.apply(access, db, row, emptyList(), "Ana Ruiz", LocalDate.of(2026, 12, 1), ActivityActor.import(ana))
        }
        assertTrue(outcome is ImportOutcome.Created)
        assertEquals(setOf("customer", "event", "payment"), rows.map { it.entry.entityType }.toSet())
        assertTrue(rows.all { it.display == "Importacion (Ana Ruiz)" })
        assertEquals("Boda Ruiz", rows.first { it.entry.entityType == "event" }.entry.entityLabel)
    }

    @Test
    fun importActorNamesTheStarterAndTheTotalRowSaysWhoAndWhere() = withTenant { db ->
        val activity = EventosImportApply.importActor(db, ana.userId)
        assertEquals("Importacion (Ana Ruiz)", activity.display)
        assertEquals(ana.userId, activity.userId)
        // Si la fila del usuario ya no existe, el nombre es "Usuario desconocido".
        assertEquals("Importacion (Usuario desconocido)", EventosImportApply.importActor(db, UUID.fromString("00000000-0000-0000-0000-0000000000ff")).display)
        assertEquals("Importacion", EventosImportApply.importActor(db, null).display)

        val (_, rows) = ActivityLogTesting.capture { EventosImportApply.recordImportTotal(db, activity, null, "Monday", "Eventos 2026", 3, 2, 1, 0) }
        val row = rows.single()
        assertEquals(ActivityActions.Imported, row.action)
        assertEquals("Importacion (Ana Ruiz)", row.display)
        assertEquals("3", row.change("created")!!.new)
        assertEquals("2", row.change("updated")!!.new)
        assertTrue(row.entry.summary.contains("Monday") && row.entry.summary.contains("Eventos 2026"), row.entry.summary)

        // Escrita de verdad, queda en activity_log con quien la empezo.
        EventosImportApply.recordImportTotal(db, activity, null, "Monday", "", 1, 0, 0, 0)
        db.prepareStatement("SELECT actor_kind, actor_user_id, actor_display, action FROM activity_log WHERE module_id = 'eventos' AND entity_type = 'import'").use { st ->
            st.executeQuery().use { result ->
                assertTrue(result.next())
                assertEquals("import", result.getString(1)); assertEquals(ana.userId, result.getObject(2, UUID::class.java))
                assertEquals("Importacion (Ana Ruiz)", result.getString(3)); assertEquals("imported", result.getString(4))
            }
        }
    }
}
