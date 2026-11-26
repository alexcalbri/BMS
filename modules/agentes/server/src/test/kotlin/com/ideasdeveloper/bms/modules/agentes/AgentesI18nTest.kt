package com.ideasdeveloper.bms.modules.agentes

import com.ideasdeveloper.bms.server.i18n.I18n
import com.ideasdeveloper.bms.server.i18n.I18nCatalogCheck
import com.ideasdeveloper.bms.server.i18n.loadModuleCatalog
import com.ideasdeveloper.bms.server.modules.McpTool
import com.ideasdeveloper.bms.server.modules.RegisteredMcpTool
import com.ideasdeveloper.bms.server.modules.VerifiedUser
import io.ktor.http.Parameters
import kotlinx.serialization.json.JsonPrimitive
import java.util.UUID
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AgentesI18nTest {
    // Tests build no ModuleRegistry, so the module's catalog is loaded by hand (idempotent).
    @BeforeTest
    fun loadCatalog() = loadModuleCatalog(AgentesServerModule)

    @Test
    fun catalogIsComplete() = I18nCatalogCheck.assertModule("agentes", AgentesServerModule.definition)

    private val client = OAuthClient("id", "<b>Evil</b>", listOf("https://claude.ai/cb"), "none")
    private val request = OAuthRequest(client, "https://claude.ai/cb", "st", "c".repeat(43))
    private val params = Parameters.build { append("client_id", "id"); append("state", "st") }

    @Test
    fun signInPageFollowsTheRequestLanguage() {
        val spanish = OAuthPages.signIn(request, params, null, "acme")
        val english = I18n.withLanguage("en") { OAuthPages.signIn(request, params, "Wrong password.", "acme") }
        assertTrue("<html lang=\"es\">" in spanish)
        assertTrue("quiere conectarse a BMS en tu nombre" in spanish)
        assertTrue("<html lang=\"en\">" in english)
        assertTrue("wants to connect to BMS on your behalf" in english, english)
        assertTrue("<label>Password" in english)
        assertTrue("Connect to BMS" in english)
        // The client name is still escaped and the carried parameters and form fields do not change.
        assertFalse("<b>Evil</b>" in english)
        assertTrue("&lt;b&gt;Evil&lt;/b&gt;" in english)
        listOf("name=\"step\" value=\"signin\"", "name=\"client_id\" value=\"id\"", "name=\"state\" value=\"st\"", "name=\"username\"", "name=\"password\"", "name=\"company\" value=\"acme\"")
            .forEach { field -> assertTrue(field in spanish && field in english, field) }
    }

    @Test
    fun consentPageShowsTranslatedExpirationsButPostsTheSameValues() {
        val tools = listOf(
            RegisteredMcpTool("clientes", "Clientes", McpTool("search", "Buscar clientes", "Busca.", "clientes.view", readOnly = true) { JsonPrimitive("ok") }),
            RegisteredMcpTool("clientes", "Clientes", McpTool("create", "Crear cliente", "Crea.", "clientes.create") { JsonPrimitive("ok") }),
        )
        val user = VerifiedUser(UUID.randomUUID(), "ana", "acme", serverOwner = false)
        val english = I18n.withLanguage("en") { OAuthPages.consent("ticket-1", request, user, tools, null) }
        assertTrue("Authorize agent" in english)
        assertTrue("Modules it can use" in english)
        assertTrue("<option value=\"90 dias\" selected>90 days</option>" in english, english)
        assertTrue("<option value=\"Sin vencimiento\">No expiry</option>" in english)
        assertTrue("ana · company acme" in english)
        assertTrue(">read</span>" in english && ">write</span>" in english)
        // Tool titles stay in Spanish; the form keeps its ticket, module checkbox and step values.
        assertTrue("Buscar clientes" in english)
        assertTrue("name=\"ticket\" value=\"ticket-1\"" in english && "name=\"m_clientes\"" in english && "name=\"step\" value=\"consent\"" in english)
        val spanish = OAuthPages.consent("ticket-1", request, user, tools, null)
        assertTrue("<option value=\"90 dias\" selected>90 dias</option>" in spanish)
    }

    @Test
    fun errorPageAndValidationMessagesAreEnglish() {
        val page = I18n.withLanguage("en") { OAuthPages.error(com.ideasdeveloper.bms.server.i18n.tr("Solicitud invalida.")) }
        assertTrue("Could not connect" in page && "Invalid request." in page)
        val failure = assertFailsWith<AgentesValidationException> { I18n.withLanguage("en") { AgentesScreens.parseNewToken(mapOf("name" to ""), listOf("crm")) } }
        assertEquals("Enter a name of up to 80 characters.", failure.message)
        val spanish = assertFailsWith<AgentesValidationException> { AgentesScreens.parseNewToken(mapOf("name" to ""), listOf("crm")) }
        assertEquals("Escribe un nombre de hasta 80 caracteres.", spanish.message)
    }
}
