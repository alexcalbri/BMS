package com.ideasdeveloper.bms.server.app

import com.ideasdeveloper.bms.server.auth.api.authRoutes
import com.ideasdeveloper.bms.server.auth.application.ScopedLoginService
import com.ideasdeveloper.bms.server.auth.domain.LoginSuccessResponse
import com.ideasdeveloper.bms.server.auth.twofactor.TwoFactorRequiredException
import com.ideasdeveloper.bms.server.auth.twofactor.TwoFactorSignInException
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receiveChannel
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.jvm.javaio.toInputStream
import io.ktor.utils.io.writeFully
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RequestBodyLimitTest {
    // A body without Content-Length (chunked).
    private fun streamed(size: Int) = object : OutgoingContent.WriteChannelContent() {
        override suspend fun writeTo(channel: ByteWriteChannel) {
            channel.writeFully(ByteArray(size) { 'a'.code.toByte() })
        }
    }

    @Test
    fun bodiesAreLimitedExceptOnUploadRoutes() = testApplication {
        application {
            install(RequestBodyLimit) { defaultBytes = 100 }
            routing {
                post("/echo") { call.respondText(call.receiveText().length.toString()) }
                post("/modules/crm/ui/files") { call.respondText(call.receiveChannel().toInputStream().readAllBytes().size.toString()) }
                post("/modules/otro/ui/files") { call.respondText(call.receiveChannel().toInputStream().readAllBytes().size.toString()) }
            }
        }
        assertEquals("50", client.post("/echo") { setBody("a".repeat(50)) }.bodyAsText())
        // Declared too large: refused before reading.
        assertEquals(HttpStatusCode.PayloadTooLarge, client.post("/echo") { setBody("a".repeat(500)) }.status)
        // Not declared: cut while reading.
        assertTrue(client.post("/echo") { setBody(streamed(5_000)) }.status.value >= 400)
        // Upload routes keep their own limits.
        assertEquals("5000", client.post("/modules/crm/ui/files") { setBody(streamed(5_000)) }.bodyAsText())
        assertEquals("500", client.post("/modules/otro/ui/files") { setBody("a".repeat(500)) }.bodyAsText())
    }

    @Test
    fun theCrmPastedCsvImportGetsTheLargerLimitOfItsPathOnly() = testApplication {
        application {
            install(RequestBodyLimit) { defaultBytes = 100; pathLimits = mapOf("/modules/crm/ui/import" to 1_000) }
            routing {
                post("/modules/crm/ui/{screen}") { call.respondText(call.receiveText().length.toString()) }
            }
        }
        assertEquals("900", client.post("/modules/crm/ui/import") { setBody("a".repeat(900)) }.bodyAsText())
        assertEquals(HttpStatusCode.PayloadTooLarge, client.post("/modules/crm/ui/import") { setBody("a".repeat(1_100)) }.status)
        assertTrue(client.post("/modules/crm/ui/import") { setBody(streamed(5_000)) }.status.value >= 400)
        // Every other screen of the same route keeps the default.
        assertEquals(HttpStatusCode.PayloadTooLarge, client.post("/modules/crm/ui/lead") { setBody("a".repeat(900)) }.status)
        // The production value matches the Nginx location of the same path (25 MB).
        assertEquals(25L * 1024 * 1024, LargerBodyPaths["/modules/crm/ui/import"])
    }

    /** Nginx (scripts/ubuntu/nginx-site.sh) never refuses what the Core accepts on a route, and keeps a smaller default. */
    @Test
    fun nginxLimitsAreAtLeastTheCoreLimitsOfEachRoute() {
        val script = java.io.File(com.ideasdeveloper.bms.server.i18n.I18nCatalogCheck.repoRoot(), "scripts/ubuntu/nginx-site.sh").readText()
        fun megabytes(value: String) = value.removeSuffix("m").toLong() * 1024 * 1024
        fun nginxLimit(path: String): Long {
            val line = script.lineSequence().single { it.contains("bms_nginx_location '= $path'") }
            return megabytes(Regex("client_max_body_size ([0-9]+m);").find(line)!!.groupValues[1])
        }
        val default = megabytes(Regex("BMS_NGINX_DEFAULT_BODY=([0-9]+m)").find(script)!!.groupValues[1])
        assertTrue(default <= DefaultRequestBodyBytes, "Nginx default $default")
        LargerBodyPaths.forEach { (path, bytes) -> assertTrue(nginxLimit(path) >= bytes, path) }
        assertTrue(nginxLimit("/companies/me/backups/import") >= com.ideasdeveloper.bms.server.company.application.BackupImportLimits.MaxBytes + 64 * 1024)
        assertTrue(nginxLimit("/server/extensions/upload") >= com.ideasdeveloper.bms.server.extensions.packages.ExtensionPackageService.MaxZipBytes)
        // Module attachments: no Nginx limit (the module and the company quota decide).
        assertTrue(script.contains("printf 'client_max_body_size 0;"))
    }

    @Test
    fun everyUploadRouteIsListed() {
        listOf(
            "POST /companies/me/backups/import", "POST /server/extensions/upload", "POST /modules/crm/ui/files",
            "POST /modules/tareas/ui/files", "POST /modules/propietarios/ui/files", "POST /modules/compras/ui/files",
            "POST /modules/mantenimiento/ui/files",
        ).forEach { assertTrue(isUploadRoute(it), it) }
        listOf("POST /auth/login", "POST /modules/crm/ui/lead", "PUT /companies/me", "GET /modules/crm/ui/files").forEach { assertTrue(!isUploadRoute(it), it) }
    }

    @Test
    fun theLoginAnswersTwoFactorRequiredWithAChallenge() = testApplication {
        application {
            install(ContentNegotiation) { json() }
            routing {
                authRoutes(
                    ScopedLoginService({ throw TwoFactorRequiredException("t".repeat(43), 300) }, { null }, { null }),
                    includeSession = false,
                    completeTwoFactor = { token, code, _ ->
                        if (code == "123456") LoginSuccessResponse("u", "ana", "session", 60, scope = "server") else throw TwoFactorSignInException("TWO_FACTOR_INVALID_CODE", "x", 4).also { assertEquals(43, token.length) }
                    },
                )
            }
        }
        val first = client.post("/auth/login") { contentType(ContentType.Application.Json); setBody("""{"username":"ana","password":"x"}""") }
        assertEquals(HttpStatusCode.Unauthorized, first.status)
        val body = Json.parseToJsonElement(first.bodyAsText()).jsonObject
        assertEquals("TWO_FACTOR_REQUIRED", body["code"]!!.jsonPrimitive.content)
        assertEquals("t".repeat(43), body["challengeToken"]!!.jsonPrimitive.content)
        assertTrue(body["message"]!!.jsonPrimitive.content.isNotBlank())
        val wrong = client.post("/auth/login/two-factor") { contentType(ContentType.Application.Json); setBody("""{"challengeToken":"${"t".repeat(43)}","code":"000000"}""") }
        assertEquals(HttpStatusCode.Unauthorized, wrong.status)
        assertEquals("4", Json.parseToJsonElement(wrong.bodyAsText()).jsonObject["attemptsLeft"]!!.jsonPrimitive.content)
        val ok = client.post("/auth/login/two-factor") { contentType(ContentType.Application.Json); setBody("""{"challengeToken":"${"t".repeat(43)}","code":"123456"}""") }
        assertEquals(HttpStatusCode.OK, ok.status)
        assertEquals("session", Json.parseToJsonElement(ok.bodyAsText()).jsonObject["accessToken"]!!.jsonPrimitive.content)
    }
}
