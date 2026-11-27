package com.ideasdeveloper.bms.server.activity

import com.ideasdeveloper.bms.ActivityLogChange
import com.ideasdeveloper.bms.ActivityLogRow
import com.ideasdeveloper.bms.server.activity.ActivityLogTesting.throughAgent
import com.ideasdeveloper.bms.server.admins.AuditActor
import java.lang.reflect.Proxy
import java.sql.Connection
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ActivityLogTest {
    private val ana = UUID.fromString("00000000-0000-0000-0000-0000000000a1")
    private val luis = UUID.fromString("00000000-0000-0000-0000-0000000000b2")

    // Any SQL on it fails: captured rows must never touch the connection.
    private val noDatabase = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(Connection::class.java)) { _, method, _ ->
        throw UnsupportedOperationException("no database: ${method.name}")
    } as Connection

    @Test
    fun anActorIsStoredAsCompactJsonAndRestoredExactlyForBackgroundJobs() {
        val person = ActivityActor.user(ana, "Ana Ruiz")
        val actors = listOf(
            person,
            person.viaAgent("Claude"),
            ActivityActor.businessOwner(ana, "Dueno Acme"),
            ActivityActor.switcher(ana, "Dueno Acme", ActivityActor.Kinds.ServerAdmin, luis, "Luis Mora"),
            ActivityActor.serverOwner(null, "Server Owner"),
            ActivityActor.groupAgent(ActivityActor.businessOwner(ana, "Dueno Acme"), ActivityActor.user(luis, "Luis Mora"), "Casa", "Claude"),
        )
        actors.forEach { assertEquals(it, ActivityActor.restore(it.toStoredJson()), it.display) }
        // An import restored from it reads exactly like the one started live.
        assertEquals("Importacion (Agente de Ana Ruiz)", ActivityActor.restoreImport(person.viaAgent("Claude").toStoredJson()).display)
        assertEquals("Importacion (Luis Mora (desde el servidor))", ActivityActor.restoreImport(actors[3].toStoredJson()).display)
        assertEquals(ActivityActor.import(actors[3]), ActivityActor.restoreImport(actors[3].toStoredJson()))
        // Nothing usable stored (old jobs): the fallback, or an anonymous import.
        assertNull(ActivityActor.restore(null))
        assertNull(ActivityActor.restore(""))
        assertNull(ActivityActor.restore("not json"))
        assertNull(ActivityActor.restore("""{"kind":"hacker","name":"x"}"""))
        assertEquals("Importacion", ActivityActor.restoreImport(null).display)
        assertEquals("Importacion (Ana Ruiz)", ActivityActor.restoreImport("[]") { person }.display)
        assertTrue(!person.toStoredJson().contains("password", ignoreCase = true))
    }

    @Test
    fun displaysNameTheRealPerson() {
        assertEquals("Ana Ruiz", ActivityActor.user(ana, "Ana Ruiz").display)
        assertEquals("Agente de Ana Ruiz", ActivityActor.user(ana, "Ana Ruiz").viaAgent("Claude").display)
        assertEquals("Claude", ActivityActor.user(ana, "Ana Ruiz").viaAgent("Claude").agentName)
        assertEquals("agent", ActivityActor.user(ana, "Ana Ruiz").viaAgent("Claude").origin)

        val switcher = ActivityActor.switcher(ana, "Dueno Acme", ActivityActor.Kinds.ServerAdmin, luis, "Luis Mora")
        assertEquals(ActivityActor.Kinds.BusinessOwner, switcher.kind)
        assertEquals(ana, switcher.userId)
        assertEquals("Dueno Acme", switcher.name)
        assertEquals("Luis Mora (desde el servidor)", switcher.display)
        assertEquals(ActivityActor.Kinds.ServerAdmin, switcher.realKind)
        assertEquals(luis, switcher.realUserId)
        assertEquals("Agente de Luis Mora (desde el servidor)", switcher.viaAgent("Claude").display)

        // A business owner's agent of company "origen" acting in another company of the group.
        val target = ActivityActor.businessOwner(luis, "Dueno Destino")
        val origin = ActivityActor.businessOwner(ana, "Ana Ruiz")
        val group = ActivityActor.groupAgent(target, origin, "origen", "Claude")
        assertEquals(luis, group.userId)
        assertEquals("Agente de Ana Ruiz (empresa origen)", group.display)
        assertEquals(ana, group.realUserId)
        assertEquals("Ana Ruiz (empresa origen)", group.realName)
        assertEquals("Claude", group.agentName)

        assertEquals("Importacion (Ana Ruiz)", ActivityActor.import(ActivityActor.user(ana, "Ana Ruiz")).display)
        assertEquals(ana, ActivityActor.import(ActivityActor.user(ana, "Ana Ruiz")).userId)
        assertEquals("Automatizacion Bienvenida", ActivityActor.automation("Bienvenida").display)
        assertEquals("Automatizacion Bienvenida (Ana Ruiz)", ActivityActor.automation("Bienvenida", ActivityActor.user(ana, "Ana Ruiz")).display)
        assertEquals("Cloudbeds", ActivityActor.integration("Cloudbeds").display)
        assertEquals("system", ActivityActor.system().kind)
        assertEquals("job", ActivityActor.job("Recordatorios").kind)

        val owner = ActivityActor.fromAudit(AuditActor.owner("duena", luis), "Luisa")
        assertEquals("Luisa (desde el servidor)", owner.display)
        assertEquals("server_owner", owner.kind)
        assertEquals("system", ActivityActor.fromAudit(AuditActor.system()).kind)
    }

    @Test
    fun diffKeepsChangedFieldsMasksSecretsAndCutsLongValues() {
        val long = "x".repeat(800)
        val changes = ActivityChanges.diff(
            before = mapOf("name" to "Ana", "email" to "a@x.com", "notes" to "corto", "api_key" to "old-secret", "pin" to "1", "active" to true),
            after = mapOf("name" to "Ana", "email" to "b@x.com", "notes" to long, "api_key" to "new-secret", "pin" to "1", "active" to false),
            labels = mapOf("name" to "Nombre", "email" to "Correo", "notes" to "Notas", "api_key" to "Clave", "pin" to "PIN", "active" to "Activo"),
        )
        assertEquals(listOf("email", "notes", "api_key", "active"), changes.map { it.field })
        assertEquals("a@x.com", changes[0].old)
        assertEquals("b@x.com", changes[0].new)
        assertEquals(500, changes[1].new!!.length)
        assertTrue(changes[1].new!!.endsWith("…"))
        val secret = changes[2]
        assertTrue(secret.secret)
        assertNull(secret.old)
        assertNull(secret.new)
        assertEquals("Si", changes[3].old)
        assertEquals("No", changes[3].new)
        // Explicit secrets too, whatever their name.
        val explicit = ActivityChanges.diff(mapOf("webhook" to "a"), mapOf("webhook" to "b"), mapOf("webhook" to "Webhook"), secrets = setOf("webhook"))
        assertTrue(explicit.single().secret)
        assertEquals(listOf("name"), ActivityChanges.created(mapOf("name" to "Ana", "extra" to 1), mapOf("name" to "Nombre")).map { it.field })
        assertEquals("Ana", ActivityChanges.deleted(mapOf("name" to "Ana"), mapOf("name" to "Nombre")).single().old)
    }

    @Test
    fun recordIsCapturedWithoutTheDatabaseAndNormalised() {
        val permissions = ActivityLogTesting.permissions(actor = ActivityLogTesting.user("Ana Ruiz", ana)).throughAgent("Claude")
        val rows = ActivityLogTesting.rows {
            ActivityLog.record(
                noDatabase, permissions.activityActor,
                ActivityEntry(
                    "crm", "lead", "42", "Lead\u0000 Uno", ActivityActions.Updated, "Modifico el lead.",
                    listOf(ActivityChange("password", "Contrasena", "a", "b"), ActivityChange("notes", "Notas", null, "y".repeat(900))),
                ),
            )
        }
        val row = rows.single()
        assertTrue(row.display.startsWith("Agente de "))
        assertEquals("Lead  Uno", row.entry.entityLabel)
        assertTrue(row.change("password")!!.secret)
        assertNull(row.change("password")!!.new)
        assertEquals(500, row.change("notes")!!.new!!.length)
        assertFailsWith<IllegalArgumentException> {
            ActivityLogTesting.rows { ActivityLog.record(noDatabase, permissions.activityActor, ActivityEntry("CRM!", "lead", null, "", "updated", "x")) }
        }
        // Outside a capture the connection is used, and its failure is the caller's.
        assertFailsWith<UnsupportedOperationException> {
            ActivityLog.record(noDatabase, permissions.activityActor, ActivityEntry("crm", "lead", null, "", "updated", "x"))
        }
    }

    @Test
    fun csvEscapesQuotesAndFormulas() {
        val row = ActivityLogRow(
            id = 1, occurredAt = "2026-11-26T10:00:00Z", companyCode = "acme", actorKind = "user", actorName = "Ana", actorDisplay = "=HYPERLINK(\"x\")",
            moduleId = "crm", moduleName = "CRM", entityType = "lead", entityId = "7", entityLabel = "Lead \"A\"", action = "updated",
            actionLabel = "Modificado", summary = "Cambio, con coma", changes = listOf(ActivityLogChange("email", "Correo", "a", "b"), ActivityLogChange("pwd", "Clave", secret = true)),
        )
        val csv = ActivityLogReader.csv(listOf(row), withCompany = true)
        val line = csv.lines()[1]
        assertTrue(line.contains("\"'=HYPERLINK(\"\"x\"\")\""), line)
        assertTrue(line.contains("\"Lead \"\"A\"\"\""), line)
        assertTrue(line.contains("Correo: a -> b; Clave: (cambiado)"), line)
        assertTrue(line.startsWith("\"2026-11-26T10:00:00Z\",\"acme\""), line)
    }

    @Test
    fun filtersParseDatesAndBoundThePageSize() {
        val filter = ActivityFilter.of(mapOf("q" to "lead", "from" to "2026-01-02", "pageSize" to "5000", "page" to "-3")::get)
        assertEquals("lead", filter.text)
        assertEquals(java.time.LocalDate.of(2026, 1, 2), filter.from)
        assertEquals(ActivityLogReader.MaxPageSize, filter.size)
        assertEquals(0, filter.pageIndex)
        assertFailsWith<ActivityFilterException> { ActivityFilter.of(mapOf("to" to "ayer")::get) }
    }

    @Test
    fun repeatedFailedSignInsBecomeOneRowWithTheCount() {
        var now = Instant.parse("2026-11-26T10:00:00Z")
        val written = mutableListOf<Triple<String?, String, Int>>()
        val failures = FailedSignIns(window = Duration.ofMinutes(5), maxRowsPerAddress = 3, clock = { now }) { login, address, count, _, _ ->
            written += Triple(login, address, count)
        }
        repeat(100) { failures.failed("ana", "10.0.0.1") }
        assertEquals(listOf(Triple<String?, String, Int>("ana", "10.0.0.1", 1)), written)
        now = now.plus(Duration.ofMinutes(6))
        failures.failed("ana", "10.0.0.1")
        // The 99 counted in the first window, then the first of the new window.
        assertEquals(Triple<String?, String, Int>("ana", "10.0.0.1", 99), written[1])
        assertEquals(Triple<String?, String, Int>("ana", "10.0.0.1", 1), written[2])
        // Many different logins from one address: a few rows, then one bucket for the rest.
        written.clear()
        (1..50).forEach { failures.failed("user$it", "10.0.0.9") }
        assertEquals(3, written.size)
        now = now.plus(Duration.ofMinutes(6))
        failures.sweep()
        assertEquals(Triple<String?, String, Int>(null, "10.0.0.9", 47), written.last())
        // A success writes what was pending at once.
        written.clear()
        failures.failed("eva", "10.0.0.2")
        failures.failed("eva", "10.0.0.2")
        failures.succeeded("eva", "10.0.0.2")
        assertEquals(listOf(Triple<String?, String, Int>("eva", "10.0.0.2", 1), Triple<String?, String, Int>("eva", "10.0.0.2", 1)), written)
    }

    @Test
    fun testKitListsWritePathsOfAModule() {
        val tools = ActivityLogTesting.writeTools(com.ideasdeveloper.bms.modules.agentes.AgentesServerModule)
        assertTrue("agentes_revoke_agent" in tools, tools.toString())
        assertTrue("agentes_list_agents" !in tools)
        val routes = ActivityLogTesting.writeRoutes(com.ideasdeveloper.bms.modules.agentes.AgentesServerModule)
        assertTrue(routes.any { it.startsWith("POST /modules/agentes") }, routes.toString())
        assertTrue(routes.none { it.startsWith("GET ") })
    }
}
