package com.ideasdeveloper.bms.server.assistant

import com.ideasdeveloper.bms.server.activity.ServerAuditMcpTools
import com.ideasdeveloper.bms.server.company.application.ServerMcpTools
import com.ideasdeveloper.bms.server.extensions.ExtensionMcpTools
import com.ideasdeveloper.bms.server.modules.RegisteredMcpTool
import com.ideasdeveloper.bms.server.modules.ServerMcpModuleId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The contract every tool the in-app assistant can offer must keep (GLOBAL_AGENT.md, "Approval is bound to what runs"):
 * a tool that sends something out of the company, or deletes what cannot be recovered, shows a preview of what it will do, so
 * the user approves what runs. The registry is the real one (every module and extension on the classpath, ServiceLoader), so a
 * new tool fails here until it is flagged and previewed, or listed with its reason.
 */
class ToolRegistryContractTest {
    private val registry = RealToolRegistry.registry

    // What the assistant can offer: every module tool but the server's own (never offered, see AssistantTools.visible).
    private val tools: Map<String, RegisteredMcpTool> = registry.mcp().filter { it.moduleId != ServerMcpModuleId }.flatMap { it.tools }.associateBy { it.qualifiedName }

    /** Tools flagged `outbound` or `destructive` that show no preview, and why that is acceptable. Empty on purpose. */
    private val withoutPreview = emptyMap<String, String>()

    /**
     * Tools that send data out of the company or change where/what is sent, on purpose flagged `outbound` (each has a preview).
     * Review (B4): every non-read-only tool of modules/ and extensions/ and of the server's outbound code was checked for mail
     * senders, HTTP clients and webhook/provider calls.
     */
    private val flagged = setOf(
        "crm_send_message", "crm_run_automation", "crm_press_lead_button", "crm_save_automation", "crm_save_message_template",
        "crm_import_from_url", "crm_update_mail_settings", "crm_update_user_notifications",
        "crm_update_meta", "crm_meta_connect_page", "crm_meta_connect_whatsapp", "crm_meta_fill_names",
        "crm_update_wordpress", "crm_wordpress_test", "crm_wordpress_sync_agents", "crm_encuentra24_sync",
        "crm_update_cloudbeds", "crm_cloudbeds_test", "crm_close_property_sale", "crm_create_reservation",
        "hotspot_update_settings", "hotspot_set_integration", "hotspot_unifi_choose_console", "hotspot_unifi_choose_site",
    )

    /** Outbound configuration of monday, added to every module that uses MondayOutboundMcp (prefix + name). */
    private val mondayFlagged = listOf("outbound_configure", "outbound_retry")

    /**
     * Non-read-only tools that touch an external service or notify people and are NOT flagged, with the reason. A tool here
     * must exist and must not be flagged; a decision to flag one moves it to [flagged].
     */
    private val notFlagged = mapOf(
        "crm_link_property" to "asks the company's own WordPress site for one property (GET with the signed secret, only the listing id) and stores it in the lead; sends no company data",
        "crm_assign_room" to "asks Cloudbeds for availability to validate the room (dates and guests only) and stores the choice in the lead; sends no lead data",
        "crm_update_encuentra24" to "stores the slug and the switch; no request is made, contacts come in only through encuentra24_sync (flagged) or the job",
        "crm_wordpress_map_agent" to "local link between a site agent and a CRM user",
        "crm_create_lead" to "creates a lead; enabled automations of the 'lead created' trigger may mail the lead (transitive, configured by the company; see GLOBAL_AGENT.md)",
        "crm_update_lead" to "changes a lead; automations of the 'field changed' trigger may mail the lead (transitive, configured by the company)",
        "crm_move_lead" to "changes the stage; automations of the 'stage changed' trigger may mail the lead (transitive, configured by the company)",
        "crm_add_lead_note" to "a note; a mention notifies the mentioned company user (internal)",
        "crm_resolve_assignment" to "assigns a lead; notifies the assigned company user (internal)",
        "hotspot_my_wifi_code" to "returns the user's own WiFi code; sends nothing",
        "hotspot_save_network" to "local network configuration of the portal; no external call",
        "hotspot_add_access_grant" to "local access list; no external call",
        "agentes_mark_notifications_read" to "marks the user's own notifications read; local",
    )

    @Test
    fun theRegistryHasTheToolsOfEveryModuleAndExtension() {
        assertTrue(tools.size > 120, "tools: ${tools.size}")
        listOf("crm_send_message", "hotspot_update_settings", "clientes_create_customer").forEach { assertTrue(it in tools, it) }
    }

    @Test
    fun everyOutboundOrDestructiveToolShowsAPreviewOrIsListedWithItsReason() {
        val missing = tools.values.filter { (it.tool.outbound || it.tool.destructive) && it.tool.preview == null }.map { it.qualifiedName }.sorted()
        assertEquals(withoutPreview.keys.sorted(), missing, "outbound or destructive tools without a preview (add one, or list it with its reason)")
        assertTrue(withoutPreview.values.all { it.length > 20 })
    }

    @Test
    fun theToolsThatSendDataOutAreFlaggedAndPreviewed() {
        val expected = flagged + tools.keys.filter { name -> mondayFlagged.any { name.endsWith("_$it") } }
        val problems = expected.sorted().flatMap { name ->
            val tool = tools[name]?.tool ?: return@flatMap listOf("$name is not in the registry")
            listOfNotNull("$name is not flagged outbound".takeIf { !tool.outbound }, "$name has no preview".takeIf { tool.preview == null })
        }
        assertTrue(problems.isEmpty(), problems.toString())
        // Everything else flagged outbound is a tool this list does not know yet: it must be added here on purpose.
        val unknown = tools.values.filter { it.tool.outbound && it.qualifiedName !in expected }.map { it.qualifiedName }
        assertTrue(unknown.isEmpty(), "outbound tools not in the reviewed list: $unknown")
        // Every monday outbound tool of every module that has them is configured, retried and previewed.
        // (The monday tools come from the extensions: checked only where the extensions are on the classpath, see RealToolRegistry.)
        if (RealToolRegistry.extensionsComplete) {
            assertTrue(tools.keys.count { it.endsWith("_outbound_configure") } >= 1, "no outbound_configure tool in: ${tools.keys.filter { "outbound" in it }}")
        }
    }

    @Test
    fun theToolsDecidedNotToFlagExistAndAreNotFlagged() {
        notFlagged.forEach { (name, reason) ->
            val tool = tools[name]
            assertTrue(tool != null, "$name is not in the registry")
            assertTrue(!tool.tool.outbound, "$name is flagged: move it out of notFlagged")
            assertTrue(!tool.tool.readOnly, "$name is read only: it does not belong in the audit of writers")
            assertTrue(reason.length > 10, "$name needs a reason")
        }
    }

    // ---- The server's own tools (the server owner's assistant in the server workspace, Core 0.14.1) ----

    // Built as the server builds them (nothing runs at construction: every service is only reached when a tool runs).
    private val noDatabase = java.lang.reflect.Proxy.newProxyInstance(javaClass.classLoader, arrayOf(javax.sql.DataSource::class.java)) { _, _, _ ->
        throw java.sql.SQLException("no database", "08001")
    } as javax.sql.DataSource
    private val serverTools: Map<String, RegisteredMcpTool> = (
        ServerMcpTools.toolsWith({ error("not used") }) { role -> ExtensionMcpTools.tools({ error("not used") }, { null }, role) } +
            ServerAuditMcpTools(noDatabase, { error("not used") }, { error("not used") }, { error("not used") }).tools
        ).map { RegisteredMcpTool(ServerMcpModuleId, "Servidor", it) }.associateBy { it.qualifiedName }

    private val serverReadOnly = setOf(
        "servidor_list_companies", "servidor_list_modules", "servidor_get_module_catalog", "servidor_list_extensions",
        "servidor_preview_extension_uninstall", "servidor_list_extension_packages", "servidor_list_server_audit", "servidor_list_company_activity",
    )
    private val serverDestructive = setOf("servidor_delete_company", "servidor_remove_module", "servidor_uninstall_extension", "servidor_apply_extension_to_group")
    // Changing a setting of an extension, or turning it on, changes what the company sends to an outside service.
    private val serverOutbound = setOf("servidor_configure_extension", "servidor_set_extension_enabled")

    @Test
    fun everyNonReadOnlyServerToolHasAPreviewAndTheClassificationIsTheReviewedOne() {
        assertTrue(serverTools.size >= 20, "server tools: ${serverTools.keys}")
        assertEquals(serverReadOnly, serverTools.values.filter { it.tool.readOnly }.map { it.qualifiedName }.toSet())
        // A tool that changes the server shows what it will do (the company's name, what is deleted, which owner is created).
        val noPreview = serverTools.values.filter { !it.tool.readOnly && it.tool.preview == null }.map { it.qualifiedName }
        assertEquals(emptyList(), noPreview, "server tools that change something and show no preview")
        assertEquals(serverDestructive, serverTools.values.filter { it.tool.destructive }.map { it.qualifiedName }.toSet())
        assertEquals(serverOutbound, serverTools.values.filter { it.tool.outbound }.map { it.qualifiedName }.toSet())
        // None of them is both read only and flagged.
        assertTrue(serverTools.values.none { it.tool.readOnly && (it.tool.destructive || it.tool.outbound || it.tool.preview != null) })
        // Creating a company is not destructive nor outbound (it sends nothing and deletes nothing) but its preview says who gets control.
        assertTrue(!serverTools.getValue("servidor_create_company").tool.destructive && serverTools.getValue("servidor_create_company").tool.preview != null)
    }

    @Test
    fun theIrreversibleServerToolsOfTheDenyListExistAreDestructiveAndNeverOfferedToTheAssistant() {
        // Renaming or dropping one of them must show up here, not silently offer the replacement to the assistant.
        assertEquals(setOf("servidor_delete_company", "servidor_remove_module"), AssistantTools.ServerDenyList)
        assertTrue(AssistantTools.ServerDenyList.all { it in serverTools && serverTools.getValue(it).tool.destructive })
        // Every destructive server tool is either on the deny list or one the assistant may offer behind a card with a preview (reviewed list).
        val destructiveButOffered = serverDestructive - AssistantTools.ServerDenyList
        assertEquals(setOf("servidor_uninstall_extension", "servidor_apply_extension_to_group"), destructiveButOffered)
        assertTrue(destructiveButOffered.all { serverTools.getValue(it).tool.preview != null })
    }

    @Test
    fun theWorkspacePolicyHoldsBackReadsAndFlagsWritesOfToolsThatExistAndMeanIt() {
        // Reads of other companies' data wait for a card in the assistant (not for MCP agents): they stay plain read-only tools with no preview of their own.
        ServerCallPolicy.HeldReads.forEach { name ->
            val tool = serverTools.getValue("servidor_$name").tool
            assertTrue(tool.readOnly && !tool.outbound && !tool.destructive, name)
        }
        // Writes whose single call is flagged (logo URL, group apply with confirm, deactivation) have a preview and are not read only.
        ServerCallPolicy.FlaggedWrites.forEach { name ->
            val tool = serverTools.getValue("servidor_$name").tool
            assertTrue(!tool.readOnly && tool.preview != null, name)
        }
        // Their tool-level flags stay as reviewed (the flag of a CALL is added by the policy): update_company and set_company_active are neither outbound
        // nor destructive as tools, apply_extension_to_group is destructive (uninstall) and becomes outbound only for apply with confirm=true.
        assertTrue(listOf("servidor_update_company", "servidor_create_company", "servidor_set_company_active").none { serverTools.getValue(it).tool.outbound || serverTools.getValue(it).tool.destructive })
        assertTrue(serverTools.getValue("servidor_apply_extension_to_group").tool.destructive && !serverTools.getValue("servidor_apply_extension_to_group").tool.outbound)
        // The calls it flags really are (the policy's own cases are in ServerCallPolicyTest).
        val policy = ServerCallPolicy({ true }, { null })
        fun flagged(tool: String, vararg pairs: Pair<String, kotlinx.serialization.json.JsonElement>) = policy.evaluate(tool, kotlinx.serialization.json.JsonObject(mapOf(*pairs)))
        val text = { value: String -> kotlinx.serialization.json.JsonPrimitive(value) }
        assertTrue(flagged("servidor_update_company", "logo_url" to text("https://x.example/l.png")).outbound)
        assertTrue(flagged("servidor_apply_extension_to_group", "confirm" to kotlinx.serialization.json.JsonPrimitive(true)).outbound)
        assertTrue(flagged("servidor_set_company_active", "active" to kotlinx.serialization.json.JsonPrimitive(false)).destructive)
    }

    @Test
    fun theServerToolsAreNotPartOfWhatACompanyAssistantCanOffer() {
        assertTrue(tools.keys.none { it.startsWith("servidor_") })
        assertTrue(serverTools.keys.all { it.startsWith("servidor_") })
    }
}
