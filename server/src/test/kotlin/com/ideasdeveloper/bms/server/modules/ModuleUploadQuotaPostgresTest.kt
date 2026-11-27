package com.ideasdeveloper.bms.server.modules

import com.ideasdeveloper.bms.server.auth.infrastructure.database.LoginDatabases
import com.ideasdeveloper.bms.server.auth.infrastructure.security.SessionTokenGenerator
import com.ideasdeveloper.bms.server.i18n.I18nCatalogCheck
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.install
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.jdbc.Database
import org.postgresql.ds.PGSimpleDataSource
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The upload routes of CRM, Tareas, Propietarios, Compras and Mantenimiento through the real routes and Core's storage
 * guard (STORAGE_QUOTA.md): over the quota or under the free-disk floor the upload is refused before the body is read,
 * a limit reached inside the store (uploads in progress) gets the same {code, message} answer, and no file is left.
 * Runs only with BMS_TEST_PG_URL (or BMS_ACTIVITY_PG_URL) of a DISPOSABLE PostgreSQL: it creates and drops bms_upq_*.
 */
class ModuleUploadQuotaPostgresTest {
    private val url: String? = (System.getenv("BMS_TEST_PG_URL") ?: System.getenv("BMS_ACTIVITY_PG_URL"))?.takeIf { it.isNotBlank() }
    private val root = I18nCatalogCheck.repoRoot()
    private val central = "bms_upq_central"
    private val company = "bms_upq_acme"
    private val ownerId = UUID.randomUUID()
    private val token = "Q".repeat(43)
    private val opened = mutableListOf<LoginDatabases>()
    private val modules = listOf(
        "crm" to com.ideasdeveloper.bms.modules.crm.CrmServerModule,
        "tareas" to com.ideasdeveloper.bms.modules.tareas.TareasServerModule,
        "propietarios" to com.ideasdeveloper.bms.modules.propietarios.PropietariosServerModule,
        "compras" to com.ideasdeveloper.bms.modules.compras.ComprasServerModule,
        "mantenimiento" to com.ideasdeveloper.bms.modules.mantenimiento.MantenimientoServerModule,
    )

    private fun urlOf(database: String) = url!!.replace(Regex("/[^/?]+(\\?|$)"), "/$database$1")
    private fun connect(database: String): Connection = DriverManager.getConnection(urlOf(database))
    private fun source(database: String) = PGSimpleDataSource().apply { setURL(urlOf(database)) }
    private fun Connection.run(sql: String) = createStatement().use { it.execute(sql) }
    private val prefix get() = urlOf("X").substringBefore("/X") + "/"
    private val pgUser get() = Regex("user=([^&]+)").find(url!!)?.groupValues?.get(1) ?: "postgres"
    private fun sqlFiles(dir: String) = File(root, dir).listFiles()!!.filter { it.name.endsWith(".sql") }.sortedBy { it.name }
    private fun sql(file: File) = file.readText().trim().removePrefix("BEGIN;").removeSuffix("COMMIT;")

    @AfterTest
    fun close() {
        opened.forEach { it.close() }
        if (url == null) return
        DriverManager.getConnection(url).use { admin -> listOf(central, company).forEach { admin.run("DROP DATABASE IF EXISTS $it WITH (FORCE)") } }
    }

    private fun build() {
        DriverManager.getConnection(url!!).use { admin ->
            listOf(central, company).forEach { name ->
                admin.run("DROP DATABASE IF EXISTS $name WITH (FORCE)")
                admin.run("CREATE DATABASE $name")
            }
        }
        connect(central).use { db ->
            sqlFiles("database/core/migrations").forEach { db.run(sql(it)) }
            sqlFiles("database/control/migrations").forEach { db.run(sql(it)) }
            db.run("INSERT INTO companies (id, code, name, database_name, owner_group) VALUES (gen_random_uuid(), 'acme', 'Empresa acme', '$company', gen_random_uuid())")
        }
        connect(company).use { db ->
            sqlFiles("database/core/migrations").forEach { db.run(sql(it)) }
            sqlFiles("database/tenant/migrations").forEach { db.run(sql(it)) }
            (listOf("clientes") + modules.map { it.first }).forEach { id -> sqlFiles("modules/$id/migrations").forEach { db.run(sql(it)) } }
            modules.forEach { (id, _) ->
                db.run("INSERT INTO tenant_modules (module_id, display_name, status, enabled_at) VALUES ('$id', '$id', 'enabled', CURRENT_TIMESTAMP) ON CONFLICT (module_id) DO UPDATE SET status = 'enabled'")
            }
            db.run("INSERT INTO application_users (id, postgres_role, is_active, display_name, email) VALUES ('$ownerId', 'ana', TRUE, 'Ana Ruiz', 'ana@empresa.com')")
            db.run("INSERT INTO business_owner (user_id) VALUES ('$ownerId')")
            db.prepareStatement("INSERT INTO application_sessions (token_hash, user_id, expires_at) VALUES (?, ?, CURRENT_TIMESTAMP + INTERVAL '1 hour')").use { insert ->
                insert.setString(1, SessionTokenGenerator().hash(token))
                insert.setObject(2, ownerId)
                insert.executeUpdate()
            }
        }
    }

    private fun access(files: CompanyFiles): ModuleAccessService {
        val databases = LoginDatabases(source(central), Database.connect(source(central)), urlOf(central), "[]", pgUser, "", prefix, 3600).also { opened += it }
        return ModuleAccessService(source(central), databases, companyFiles = files)
    }

    private fun filesUnder(directory: Path): Long =
        if (!Files.exists(directory)) 0 else Files.walk(directory).use { paths -> paths.filter { Files.isRegularFile(it) }.count() }

    private fun uploads(files: CompanyFiles, body: ByteArray, check: (module: String, response: HttpResponse, text: String) -> Unit) {
        val access = access(files)
        testApplication {
            application {
                install(io.ktor.server.plugins.contentnegotiation.ContentNegotiation) { json() }
                routing { modules.forEach { (_, module) -> module.routes(this, access) } }
            }
            modules.forEach { (id, _) ->
                val response = client.post("/modules/$id/ui/files") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                    header("X-Company-Code", "acme")
                    header("X-File-Name", "contrato.pdf")
                    contentType(ContentType.Application.Pdf)
                    setBody(body)
                }
                check(id, response, response.bodyAsText())
            }
        }
    }

    private fun code(text: String) = Json.parseToJsonElement(text).jsonObject["code"]?.jsonPrimitive?.content

    @Test
    fun uploadsOverTheQuotaOrUnderTheDiskFloorAreRefusedAndLeaveNoFile() {
        if (url == null) return
        build()
        val storage = Files.createTempDirectory("bms-upq")
        try {
            // A sanity pass: within the limits every module stores the file.
            uploads(CompanyFiles.local(storage, StorageGuard(storage, { null }, minFreeBytes = 0, freeSpace = { Long.MAX_VALUE })), ByteArray(64) { 7 }) { id, response, text ->
                assertEquals(HttpStatusCode.OK, response.status, "$id: $text")
            }
            assertEquals(modules.size.toLong(), filesUnder(storage.resolve("acme")))

            // The pre-checks below run with no upload slot: had the route entered the store (read the body), the answer
            // would be STORAGE_BUSY, so QUOTA_EXCEEDED / DISK_FULL prove the refusal came before the body was read.
            // Quota: 1 KB for the company and a 4 KB upload with its Content-Length.
            uploads(CompanyFiles.local(storage, StorageGuard(storage, { 1024L }, minFreeBytes = 0, maxConcurrentUploads = 0, freeSpace = { Long.MAX_VALUE })), ByteArray(4096)) { id, response, text ->
                assertEquals(HttpStatusCode.PayloadTooLarge, response.status, "$id: $text")
                assertEquals(StorageQuotaException.QuotaExceeded, code(text), "$id: $text")
            }
            // Free-disk floor.
            uploads(CompanyFiles.local(storage, StorageGuard(storage, { null }, minFreeBytes = 1024, maxConcurrentUploads = 0, freeSpace = { 10L })), ByteArray(16)) { id, response, text ->
                assertEquals(HttpStatusCode.fromValue(507), response.status, "$id: $text")
                assertEquals(StorageQuotaException.DiskFull, code(text), "$id: $text")
            }
            // Refused inside the store, after the module's own checks (no upload slot left): same answer, no file.
            uploads(CompanyFiles.local(storage, StorageGuard(storage, { null }, minFreeBytes = 0, maxConcurrentUploads = 0, freeSpace = { Long.MAX_VALUE })), ByteArray(16)) { id, response, text ->
                assertEquals(HttpStatusCode.TooManyRequests, response.status, "$id: $text")
                assertEquals(StorageQuotaException.Busy, code(text), "$id: $text")
            }
            assertEquals(modules.size.toLong(), filesUnder(storage.resolve("acme")))
            assertTrue(Files.walk(storage).use { paths -> paths.noneMatch { it.fileName.toString().endsWith(".part") } })
        } finally {
            Files.walk(storage).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
        }
    }
}
