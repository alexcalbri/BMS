package com.ideasdeveloper.bms.server.activity

import com.ideasdeveloper.bms.server.activity.ActivityLogTesting.throughAgent
import com.ideasdeveloper.bms.server.modules.ModuleAccessService
import com.ideasdeveloper.bms.server.modules.ModulePermissions
import java.lang.reflect.Proxy
import java.sql.Connection
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The snippets of ACTIVITY_LOG_MODULE_GUIDE.md, verbatim where possible, so the guide modules
 * follow keeps compiling and doing what it says. Runs on a fake connection, like module tests.
 */
class ActivityLogGuideExamplesTest {
    private data class Lead(val id: UUID, val name: String, val email: String, val stageName: String = "Nuevo")

    private val LeadLabels = mapOf("name" to "Nombre", "email" to "Correo", "stage" to "Etapa")
    private val statements = mutableListOf<String>()

    // A fake tenant: records the SQL it is given, as module tests' fakes do.
    private val tenant = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(Connection::class.java)) { _, method, args ->
        when (method.name) {
            "prepareStatement" -> {
                statements += args[0] as String
                Proxy.newProxyInstance(javaClass.classLoader, arrayOf(java.sql.PreparedStatement::class.java)) { _, m, _ ->
                    when (m.name) { "executeUpdate" -> 1; "close" -> Unit; else -> null }
                }
            }
            "getAutoCommit" -> true
            else -> null
        }
    } as Connection

    private fun insertLead(tenant: Connection, lead: Lead) {
        tenant.prepareStatement("INSERT INTO crm_leads").use { it.executeUpdate() }
    }

    private fun createLead(access: ModuleAccessService, permissions: ModulePermissions, lead: Lead) {
        tenant.autoCommit = false
        insertLead(tenant, lead)
        access.activity.record(tenant, permissions.activityActor, ActivityEntry(
            moduleId = "crm", entityType = "lead", entityId = lead.id.toString(), entityLabel = lead.name,
            action = ActivityActions.Created, summary = "Creo el lead ${lead.name}.",
            changes = ActivityChanges.created(mapOf("name" to lead.name, "email" to lead.email), LeadLabels),
        ))
        tenant.commit()
    }

    private fun updateLead(actor: ActivityActor, id: UUID, old: Lead, new: Lead) {
        val before = mapOf("name" to old.name, "email" to old.email, "stage" to old.stageName)
        val after = mapOf("name" to new.name, "email" to new.email, "stage" to new.stageName)
        val changes = ActivityChanges.diff(before, after, LeadLabels)
        if (changes.isNotEmpty()) ActivityLog.record(tenant, actor, ActivityEntry("crm", "lead", id.toString(), new.name,
            ActivityActions.Updated, "Modifico el lead ${new.name}.", changes))
    }

    private fun deleteLead(actor: ActivityActor, id: UUID, old: Lead) {
        ActivityLog.record(tenant, actor, ActivityEntry("crm", "lead", id.toString(), old.name, ActivityActions.Deleted,
            "Elimino el lead ${old.name}.", ActivityChanges.deleted(mapOf("name" to old.name, "email" to old.email), LeadLabels)))
        tenant.prepareStatement("DELETE FROM crm_leads WHERE id = ?").use { it.setObject(1, id); it.executeUpdate() }
    }

    @Test
    fun guideSnippetsRecordWhatTheGuideSays() {
        val id = UUID.randomUUID()
        val old = Lead(id, "Ana", "a@x.com")
        val permissions = ActivityLogTesting.permissions(granted = setOf("crm.leads.edit")).throughAgent("Claude")
        val access = ActivityLogTesting.accessWithoutDatabase()

        val created = ActivityLogTesting.rows { createLead(access, permissions, old) }
        assertEquals(ActivityActions.Created, created.single().action)
        assertEquals("a@x.com", created.single().change("email")!!.new)

        val rows = ActivityLogTesting.rows { updateLead(permissions.activityActor, id, old, old.copy(email = "b@x.com")) }
        assertEquals(ActivityActions.Updated, rows.single().action)
        assertTrue(rows.single().display.startsWith("Agente de "))
        assertEquals("b@x.com", rows.single().change("email")!!.new)

        statements.clear()
        val deleted = ActivityLogTesting.rows { deleteLead(permissions.activityActor, id, old) }
        assertEquals("Ana", deleted.single().change("name")!!.old)
        // The row was taken before the DELETE ran (the DELETE is the only statement that reached the fake).
        assertEquals(listOf("DELETE FROM crm_leads WHERE id = ?"), statements)

        val oldSettings = mapOf("url" to "https://a", "apiKey" to "uno")
        val newSettings = mapOf("url" to "https://b", "apiKey" to "dos")
        val changes = ActivityChanges.diff(oldSettings, newSettings, mapOf("url" to "URL", "apiKey" to "Clave API"), secrets = setOf("apiKey"))
        assertTrue(changes.last().secret)
        assertEquals(null, changes.last().new)

        val actorKinds = listOf(
            ActivityActor.import(permissions.activityActor),
            ActivityActor.automation("Bienvenida", startedBy = permissions.activityActor),
            ActivityActor.job("Recordatorios"),
            ActivityActor.integration("Sitio web"),
        ).map { it.kind }
        assertEquals(listOf("import", "automation", "job", "integration"), actorKinds)
        assertEquals(ActivityChange("file", "Archivo", null, "contrato.pdf (24 KB)"), ActivityChange("file", "Archivo", null, "contrato.pdf (24 KB)"))
    }
}
