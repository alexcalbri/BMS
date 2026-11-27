package com.ideasdeveloper.bms.server.app

import com.ideasdeveloper.bms.server.auth.infrastructure.database.LoginDatabases
import com.ideasdeveloper.bms.server.modules.ModuleAccessService
import com.ideasdeveloper.bms.server.modules.ModuleRegistry
import com.ideasdeveloper.bms.server.modules.moduleRoutes
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.jetbrains.exposed.v1.jdbc.Database
import org.postgresql.ds.PGSimpleDataSource
import kotlin.test.Test
import kotlin.test.assertEquals

/** B7: before signing in only what the app's compatibility check needs. */
class PublicDisclosureTest {
    @Test
    fun theAnonymousVersionHasOnlyTheCoreAndAppVersions() {
        val full = VersionResponse(coreVersion = "0.12.0", latestCoreVersion = "0.13.0", latestAppVersion = "0.10.0",
            moduleUpdates = listOf(ModuleVersionUpdate("crm", "CRM", "1.0.0", "1.1.0")))
        val shown = Json { encodeDefaults = true }.parseToJsonElement(Json { encodeDefaults = true }.encodeToString(VersionResponse.serializer(), full.publicView())).jsonObject
        assertEquals("\"0.12.0\"", shown["coreVersion"].toString())
        assertEquals("\"0.10.0\"", shown["latestAppVersion"].toString())
        assertEquals("null", shown["latestCoreVersion"].toString())
        assertEquals("[]", shown["moduleUpdates"].toString())
    }

    @Test
    fun theModuleListNeedsASession() = testApplication {
        val nowhere = PGSimpleDataSource().apply { setURL("jdbc:postgresql://127.0.0.1:1/none") }
        val databases = LoginDatabases(nowhere, Database.connect(nowhere), "jdbc:postgresql://127.0.0.1:1/none", "[]", "x", "", "jdbc:postgresql://127.0.0.1:1/", 60)
        application {
            install(ContentNegotiation) { json() }
            routing { moduleRoutes(ModuleRegistry(emptyList()), ModuleAccessService(nowhere, databases)) }
        }
        assertEquals(HttpStatusCode.Unauthorized, client.get("/modules").status)
    }
}
