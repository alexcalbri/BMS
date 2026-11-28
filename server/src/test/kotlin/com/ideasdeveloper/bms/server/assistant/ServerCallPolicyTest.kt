package com.ideasdeveloper.bms.server.assistant

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The policy of the server workspace, case by case (GLOBAL_AGENT.md, "Server workspace policy"): no database, the opt-in of the
 * companies and their names are given.
 */
class ServerCallPolicyTest {
    // acme opted in (allowed and switch on); beta did not; "servidor" is the workspace.
    private val policy = ServerCallPolicy(optedIn = { it == "acme" }, companyName = { mapOf("acme" to "Acme SA", "beta" to "Beta SL")[it] })

    private fun args(vararg pairs: Pair<String, Any?>) = JsonObject(pairs.associate { (key, value) ->
        key to when (value) {
            null -> JsonNull
            is Boolean -> JsonPrimitive(value)
            is JsonObject, is JsonArray -> value as kotlinx.serialization.json.JsonElement
            else -> JsonPrimitive(value.toString())
        }
    })

    private fun evaluate(tool: String, vararg pairs: Pair<String, Any?>) = policy.evaluate("servidor_$tool", args(*pairs))

    private fun assertRuns(policy: AssistantCallPolicy) {
        assertFalse(policy.confirm || policy.outbound || policy.destructive, "should run by itself")
        assertTrue(policy.rows.isEmpty())
    }

    @Test
    fun activityOfACompanyThatOptedInRunsByItself() {
        assertRuns(evaluate("list_company_activity", "company" to "acme"))
        assertRuns(evaluate("list_company_activity", "company" to "  acme  ", "page" to 1))
    }

    @Test
    fun activityOfACompanyThatDidNotOptInWaitsForACardNamingItAndIsOutbound() {
        val held = evaluate("list_company_activity", "company" to "beta")
        assertTrue(held.confirm && held.outbound && !held.destructive)
        assertEquals(listOf("Que pasa" to "Lee la actividad de Beta SL (beta) (no ha activado el asistente) y la envia al proveedor de IA."), held.rows)
        // A company that does not exist is held too (and named by its code).
        assertEquals("Lee la actividad de nadie (no ha activado el asistente) y la envia al proveedor de IA.", evaluate("list_company_activity", "company" to "nadie").rows.single().second)
        // The server workspace is not special for the policy: it opts in through the same lookup.
        assertTrue(evaluate("list_company_activity", "company" to "servidor").confirm)
    }

    @Test
    fun activityOfAllTheCompaniesAlwaysWaitsForACard() {
        listOf(
            args(), args("company" to ""), args("company" to "   "), args("company" to null), args("company" to JsonObject(emptyMap())), args("user" to "ana"),
        ).forEach { given ->
            val held = policy.evaluate("servidor_list_company_activity", given)
            assertTrue(held.confirm && held.outbound, given.toString())
            assertEquals("Lee la actividad de todas las empresas a tu alcance y la envia al proveedor de IA.", held.rows.single().second)
        }
    }

    @Test
    fun extensionsFollowTheSameRuleWithTheirOwnArgumentAndText() {
        assertRuns(evaluate("list_extensions", "code" to "acme"))
        val held = evaluate("list_extensions", "code" to "beta")
        assertTrue(held.confirm && held.outbound)
        assertEquals("Lee las extensiones y los ajustes de Beta SL (beta) (no ha activado el asistente) y los envia al proveedor de IA.", held.rows.single().second)
        val none = evaluate("list_extensions")
        assertTrue(none.confirm && none.outbound)
        assertEquals("Lee las extensiones y los ajustes de todas las empresas y los envia al proveedor de IA.", none.rows.single().second)
    }

    @Test
    fun theCodeIsReadAsTheToolReadsItSoItCannotBeUsedToSlipByTheCheck() {
        // Longer than 63 characters is cut as the tool cuts it: the opted-in company followed by padding is not "acme".
        assertTrue(evaluate("list_company_activity", "company" to "acme" + "x".repeat(70)).confirm)
        // A name the lookup does not know is never allowed.
        assertTrue(evaluate("list_company_activity", "company" to "ACME").confirm)
    }

    @Test
    fun aPolicyThatCannotBeWorkedOutHoldsTheCall() {
        val broken = ServerCallPolicy(optedIn = { error("no database") }, companyName = { null })
        // The hook itself lets the exception go; the toolbox (ModuleAssistantToolbox.policy) turns it into "wait for the card, outbound".
        val failure = runCatching { broken.evaluate("servidor_list_company_activity", args("company" to "acme")) }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
    }

    @Test
    fun aLogoUrlOnACompanyWriteMakesTheCallOutbound() {
        listOf("update_company", "create_company").forEach { tool ->
            val flagged = evaluate(tool, "code" to "acme", "logo_url" to "https://example.com/logo.png")
            assertTrue(flagged.outbound && !flagged.confirm && !flagged.destructive, tool)
            assertTrue(evaluate(tool, "code" to "acme", "logo_url" to "").outbound, "$tool: even clearing it names the argument")
            assertRuns(evaluate(tool, "code" to "acme", "name" to "Otro"))
        }
    }

    @Test
    fun applyingAnExtensionToTheGroupIsOutboundOnlyWithConfirmAndNotWhenUninstalling() {
        fun apply(vararg pairs: Pair<String, Any?>) = evaluate("apply_extension_to_group", "code" to "acme", "extension_id" to "wp", *pairs)
        assertRuns(apply())
        assertRuns(apply("confirm" to false))
        assertTrue(apply("confirm" to true).outbound)
        assertTrue(apply("confirm" to "true", "operation" to "apply").outbound)
        assertTrue(apply("confirm" to true, "operation" to "").outbound)
        assertRuns(apply("confirm" to true, "operation" to "uninstall"))
        assertRuns(apply("operation" to "apply"))
    }

    @Test
    fun deactivatingACompanyIsDestructiveAndActivatingIsNot() {
        assertTrue(evaluate("set_company_active", "code" to "acme", "active" to false).destructive)
        assertTrue(evaluate("set_company_active", "code" to "acme", "active" to "false").destructive)
        // The tool reads a missing or unreadable `active` as false, so the card says destructive too.
        assertTrue(evaluate("set_company_active", "code" to "acme").destructive)
        assertTrue(evaluate("set_company_active", "code" to "acme", "active" to "quizas").destructive)
        assertRuns(evaluate("set_company_active", "code" to "acme", "active" to true))
    }

    @Test
    fun everyOtherToolHasNoPolicy() {
        assertRuns(evaluate("list_companies"))
        assertRuns(evaluate("list_server_audit", "company" to "beta"))
        assertRuns(evaluate("delete_company", "code" to "acme"))
        assertRuns(policy.evaluate("demo_add_note", buildJsonObject { put("texto", "x") }))
        // Only the server's own tools: a module tool of the same short name is another tool.
        assertRuns(policy.evaluate("crm_list_company_activity", buildJsonObject { put("company", "beta") }))
    }
}
