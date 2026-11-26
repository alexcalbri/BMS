package com.ideasdeveloper.bms.modules.clientes

import com.ideasdeveloper.bms.server.i18n.I18n
import com.ideasdeveloper.bms.server.i18n.I18nCatalogCheck
import com.ideasdeveloper.bms.server.i18n.loadModuleCatalog
import com.ideasdeveloper.bms.server.i18n.localized
import com.ideasdeveloper.bms.server.i18n.localizedPayload
import com.ideasdeveloper.bms.server.i18n.tr
import com.ideasdeveloper.bms.server.modules.GroupCompany
import com.ideasdeveloper.bms.server.modules.ModulePermissions
import com.ideasdeveloper.bms.ui.UiButton
import com.ideasdeveloper.bms.ui.UiCard
import com.ideasdeveloper.bms.ui.UiList
import com.ideasdeveloper.bms.ui.UiNode
import com.ideasdeveloper.bms.ui.UiRequest
import com.ideasdeveloper.bms.ui.UiRow
import com.ideasdeveloper.bms.ui.UiText
import java.lang.reflect.Proxy
import java.sql.Connection
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ClientesI18nTest {
    private val acme = GroupCompany("acme", "Acme")
    private val beta = GroupCompany("beta", "Beta")
    private val gamma = GroupCompany("gamma", "Gamma")

    // Tests build no ModuleRegistry, so the module's catalog is loaded by hand (idempotent).
    @BeforeTest
    fun loadCatalog() = loadModuleCatalog(ClientesServerModule)

    @Test
    fun catalogIsComplete() = I18nCatalogCheck.assertModule("clientes", ClientesServerModule.definition)

    private fun texts(nodes: List<UiNode>): List<String> = nodes.flatMap { node ->
        when (node) {
            is UiText -> listOf(node.text)
            is UiButton -> listOf(node.label)
            is UiCard -> listOfNotNull(node.title) + texts(node.children + node.actions)
            is UiRow -> texts(node.children)
            else -> emptyList()
        }
    }

    private fun buttons(nodes: List<UiNode>): List<UiButton> = nodes.flatMap { node ->
        when (node) {
            is UiButton -> listOf(node)
            is UiCard -> buttons(node.children + node.actions)
            is UiRow -> buttons(node.children)
            else -> emptyList()
        }
    }

    private fun reviewItem(status: String, reasonCode: String) = MergeItem(
        id = UUID.randomUUID(), company = beta, keptId = UUID.randomUUID(), keptName = "Ana", keptCompany = "Acme", mergedId = UUID.randomUUID(),
        mergedName = "Ana M", email = "ana@x.com", status = status, reason = "codigo repetido", reasonCode = reasonCode, at = OffsetDateTime.now(),
    )

    @Test
    fun linksScreenIsInEnglishButParamsAndIdsStayTheSame() {
        val status = LinkStatus(listOf(acme, beta), listOf(gamma), emptyList(), 1)
        val review = reviewItem("review", "name_differs")
        val spanish = ClientesLinkScreens.linksScreen("acme", status, listOf(review), canSeeActivity = true)
        val english = I18n.withLanguage("en") { ClientesLinkScreens.linksScreen("acme", status, listOf(review), canSeeActivity = true) }
        assertEquals("Empresas vinculadas", spanish.title)
        assertEquals("Linked companies", english.title)
        val labels = buttons(english.body).map { it.label }
        assertEquals(listOf("Unlink", "Link", "Merge", "Keep separate", "Customers log"), labels)
        assertTrue(texts(english.body).contains("To review (1)"))
        // What the server reads back does not change with the language.
        val requestsEs = buttons(spanish.body).mapNotNull { it.action as? UiRequest }.map { it.params }
        val requestsEn = buttons(english.body).mapNotNull { it.action as? UiRequest }.map { it.params }
        assertEquals(requestsEs, requestsEn)
        val link = buttons(english.body).map { it.action }.filterIsInstance<UiRequest>().first { it.params["op"] == "link" }
        assertTrue(link.confirm!!.startsWith("Acme and Gamma will be linked: ALL customers of both companies will be shared."))
    }

    @Test
    fun theResponseHelperKeepsAScreenWithoutOptionsAndTranslatesTheDefaultEmptyTexts() {
        val screen = ClientesLinkScreens.activityScreen(emptyList(), businessOwner = true)
        assertSame(screen, localizedPayload(screen))
        I18n.withLanguage("en") {
            val english = localizedPayload(ClientesLinkScreens.activityScreen(listOf(ClientesActivity.CompanyEvent(OffsetDateTime.now(), "linked", "x", "Ana")), businessOwner = true))
            assertEquals("Customers log", english.title)
            assertEquals("Company linked", english.body.filterIsInstance<UiList>().single().items.single().title)
            assertEquals(screen.body.size, english.body.size)
            assertEquals("Linked companies", buttons(english.body).single().label)
            assertEquals("No events yet.", (ClientesLinkScreens.activityScreen(emptyList(), true).body.filterIsInstance<UiList>().single().emptyText))
        }
    }

    @Test
    fun reasonsStatusesAndJobLinesAreTranslatedWhileStoredTextStaysAsWritten() {
        I18n.withLanguage("en") {
            assertEquals("Same email, different name", ClientesLinkScreens.reasonLabel("name_differs", "x"))
            assertEquals("The customer could not be copied", ClientesLinkScreens.reasonLabel("copy", "x"))
            assertEquals("texto guardado", ClientesLinkScreens.reasonLabel("", "texto guardado"))
            val job = LinkJob(acme, beta, "running", 10, 2, 1, 50, "Un cliente no se pudo procesar; el detalle esta en el registro del servidor.")
            assertEquals(
                "Acme -> Beta: copying, 10 of 50 copied, 2 merged, 1 to review. Error: A customer could not be processed; the details are in the server log.",
                ClientesLinkScreens.jobLine(job),
            )
            assertEquals("Ana M and Ana", ClientesLinkScreens.reviewTitle(reviewItem("review", "code")))
        }
    }

    @Test
    fun profileTextsActivityTitlesAndRolesAreInEnglish() {
        I18n.withLanguage("en") {
            assertEquals("Linked company: customers are shared with Beta.", ClientesProfile.linkText(listOf("Acme", "Beta"), "Acme"))
            assertEquals("Company not linked: this customer is only seen in Acme.", ClientesProfile.linkText(listOf("Acme"), "Acme"))
            assertEquals("Crm: 1 record, last 2026-03-04", ClientesProfile.usageLine(ClientesProfile.ModuleUse("Crm", 1, "2026-03-04T10:00")))
            assertEquals("Crm: 3 records, last 2026-03-04", ClientesProfile.usageLine(ClientesProfile.ModuleUse("Crm", 3, "2026-03-04T10:00")))
            assertEquals("Customer created", ClientesActivity.title("created"))
            assertEquals("Customer data changed", ClientesActivity.title("updated"))
            assertEquals(listOf("Customer", "User: Ana Perez"), ClientesAccounts.roles(ClientesAccounts.Link(UUID.randomUUID(), ClientesAccounts.Account(UUID.randomUUID(), "Ana Perez", "a@x.com"), "x", OffsetDateTime.now()), showAccount = true))
        }
    }

    @Test
    fun errorsAreInEnglishAndTheBusyMessageStillMatches() {
        val noGrants = ModulePermissions("acme", "token", false, emptySet(), userId = UUID.randomUUID())
        val tenant = Proxy.newProxyInstance(Connection::class.java.classLoader, arrayOf(Connection::class.java)) { _, _, _ -> error("not used") } as Connection
        I18n.withLanguage("en") {
            val failure = assertFailsWith<ClientesSharingException> { ClientesAccounts.unlink(tenant, UUID.randomUUID(), noGrants, null to "x") }
            assertEquals("You do not have permission to link user accounts to customers.", failure.message)
            assertEquals("A copy is in progress, try again in a minute.", tr(ClientesLinking.BusyMessage))
            assertEquals("The email a@x.com is already used by the customer \"Ana\" (Beta). Use another email or merge the two customers before changing it.", ClientesSharing.emailConflictMessage("Ana", "Beta", "a@x.com"))
            assertEquals("A customer with that email already exists in a linked company.", ClientesSharing.emailConflictMessage("Ana", "Beta", "a@x.com", owner = false))
            assertEquals("You do not have permission to use this feature.", tr("No tienes permiso para usar esta funcion."))
        }
        // The same call in Spanish keeps today's text.
        val spanish = assertFailsWith<ClientesSharingException> { ClientesAccounts.unlink(tenant, UUID.randomUUID(), noGrants, null to "x") }
        assertEquals("No tienes permiso para enlazar cuentas de usuario a clientes.", spanish.message)
    }

    @Test
    fun theDefinitionIsTranslatedByCoreFromTheCatalog() {
        val english = I18n.withLanguage("en") { ClientesServerModule.definition.localized() }
        assertEquals("Customers", english.displayName)
        assertEquals("Manages the shared identity of customers.", english.description)
        assertEquals("View customers", english.permissions.first { it.id == "clientes.view" }.title)
        assertEquals("Customer code", english.views.single().fields.first { it.key == "customerCode" }.label)
        assertEquals("Restore customer", english.views.single().actions.first { it.id == "restore" }.title)
    }
}
