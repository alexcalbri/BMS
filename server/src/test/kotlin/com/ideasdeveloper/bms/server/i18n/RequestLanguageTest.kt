package com.ideasdeveloper.bms.server.i18n

import com.ideasdeveloper.bms.server.auth.api.authRoutes
import com.ideasdeveloper.bms.server.auth.application.ScopedLoginService
import com.ideasdeveloper.bms.server.auth.domain.LoginScope
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RequestLanguageTest {
    @AfterTest
    fun forget() = SessionLanguages.clear()

    private fun io.ktor.server.testing.ApplicationTestBuilder.app() = application {
        installI18n()
        install(ContentNegotiation) { json() }
        routing {
            get("/lang") {
                // Same pattern as the module routes: work on IO, then answer.
                val inside = withContext(Dispatchers.IO) {
                    delay(2)
                    I18n.language() + ":" + tr("La sesion no es valida.")
                }
                call.respondText(I18n.language() + "|" + inside)
            }
            get("/session") {
                // What ModuleAccessService does once it knows the user (inside withContext(IO)).
                withContext(Dispatchers.IO) { I18n.overrideRequestLanguage("en") }
                call.respondText(tr("La empresa es requerida."))
            }
            authRoutes(ScopedLoginService(LoginScope { null }, { null }, { null }), includeSession = false)
        }
    }

    @Test
    fun acceptLanguageSetsTheRequestLanguageAcrossDispatchers() = testApplication {
        app()
        assertEquals("en|en:The session is not valid.", client.get("/lang") { header(HttpHeaders.AcceptLanguage, "en-US,en;q=0.9") }.bodyAsText())
        assertEquals("es|es:La sesion no es valida.", client.get("/lang") { header(HttpHeaders.AcceptLanguage, "es-MX") }.bodyAsText())
        // Without the header (or with an unsupported language): Spanish, exactly as before.
        assertEquals("es|es:La sesion no es valida.", client.get("/lang").bodyAsText())
        assertEquals("es|es:La sesion no es valida.", client.get("/lang") { header(HttpHeaders.AcceptLanguage, "fr") }.bodyAsText())
    }

    @Test
    fun storedUserLanguageOfTheSessionWinsOverAcceptLanguage() = testApplication {
        app()
        val token = "t".repeat(43)
        val key = SessionLanguages.userKey("acme", UUID.randomUUID())
        SessionLanguages.rememberSession(token, key)
        SessionLanguages.rememberUser(key, "es")
        val spanish = client.get("/lang") {
            header(HttpHeaders.AcceptLanguage, "en")
            header(HttpHeaders.Authorization, "Bearer $token")
        }.bodyAsText()
        assertEquals("es|es:La sesion no es valida.", spanish)
        // A user who follows the company default: Accept-Language (the app sends the effective language).
        SessionLanguages.rememberUser(key, null)
        val english = client.get("/lang") {
            header(HttpHeaders.AcceptLanguage, "en")
            header(HttpHeaders.Authorization, "Bearer $token")
        }.bodyAsText()
        assertEquals("en|en:The session is not valid.", english)
    }

    @Test
    fun languageResolvedWithTheSessionAppliesToTheRestOfTheRequest() = testApplication {
        app()
        assertEquals("The company is required.", client.get("/session").bodyAsText())
    }

    @Test
    fun coreErrorsAreAnsweredInTheRequestLanguage() = testApplication {
        app()
        val english = client.post("/auth/login") {
            header(HttpHeaders.AcceptLanguage, "en")
            contentType(ContentType.Application.Json)
            setBody("""{"username":"ana","password":"secreta"}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, english.status)
        assertTrue(english.bodyAsText().contains("Could not sign in with the credentials provided."), english.bodyAsText())
        val spanish = client.post("/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"ana","password":"secreta"}""")
        }
        assertTrue(spanish.bodyAsText().contains("No se pudo iniciar sesión con las credenciales proporcionadas."), spanish.bodyAsText())
    }

    @Test
    fun resolveCachesTheStoredLanguageAndToleratesOldSchemas() {
        val key = SessionLanguages.userKey(null, UUID.randomUUID())
        var reads = 0
        assertEquals("en", SessionLanguages.resolve(key) { reads++; "en" })
        assertEquals("en", SessionLanguages.resolve(key) { reads++; "es" })
        assertEquals(1, reads)
        val other = SessionLanguages.userKey("acme", UUID.randomUUID())
        assertEquals(null, SessionLanguages.resolve(other) { throw java.sql.SQLException("column \"language\" does not exist") })
    }
}
