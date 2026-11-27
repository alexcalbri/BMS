package com.ideasdeveloper.bms.server.company.application

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CoreUpdateServiceTest {
    private val now = Instant.parse("2026-01-01T12:00:00Z")

    private class FakeStore(var last: CoreUpdateRow? = null, var active: Boolean = false) : CoreUpdateStore {
        var inserted: Triple<String, String, String>? = null
        var insertedCommit: String? = null

        override fun latest() = last

        override fun insertPending(targetVersion: String, fromVersion: String, requestedBy: String, targetCommit: String): CoreUpdateRow {
            if (active) throw CoreUpdateActiveException()
            inserted = Triple(targetVersion, fromVersion, requestedBy)
            insertedCommit = targetCommit
            return row(status = "pending", target = targetVersion, from = fromVersion, requestedAt = Instant.parse("2026-01-01T12:00:00Z"), commit = targetCommit)
        }
    }

    private val sha = "a".repeat(40)
    private val otherSha = "b".repeat(40)

    private fun service(
        store: FakeStore,
        current: String = "0.3.0",
        latest: String? = "0.4.0",
        commit: CoreCommit? = latest?.let { CoreCommit(sha, it.trim()) },
        signatureRequired: Boolean = false,
        requireApproved: Boolean = false,
    ) = CoreUpdateService(store, current, { latest }, { now }, { commit }, { signatureRequired }, "master", requireApproved)

    @Test
    fun `solo ofrece actualizar si la ultima es semver valida y mayor`() {
        assertTrue(CoreUpdateService.isUpdateAvailable("0.3.0", "0.4.0"))
        assertTrue(CoreUpdateService.isUpdateAvailable("0.3.9", "0.10.0"))
        assertFalse(CoreUpdateService.isUpdateAvailable("0.3.0", "0.3.0"))
        assertFalse(CoreUpdateService.isUpdateAvailable("0.4.0", "0.3.0"))
        assertFalse(CoreUpdateService.isUpdateAvailable("0.3.0", null))
        assertFalse(CoreUpdateService.isUpdateAvailable("0.3.0", ""))
        assertFalse(CoreUpdateService.isUpdateAvailable("0.3.0", "0.4"))
        assertFalse(CoreUpdateService.isUpdateAvailable("0.3.0", "0.4.0-beta"))
        assertFalse(CoreUpdateService.isUpdateAvailable("0.3.0", "latest"))
    }

    @Test
    fun `solicitar con catalogo no disponible no inserta`() {
        val store = FakeStore()
        assertEquals(CoreUpdateOutcome.CatalogUnavailable, service(store, latest = null).request("owner"))
        assertEquals(CoreUpdateOutcome.CatalogUnavailable, service(store, latest = "  ").request("owner"))
        assertNull(store.inserted)
    }

    @Test
    fun `solicitar con el servidor al dia responde UpToDate`() {
        val store = FakeStore()
        assertEquals(CoreUpdateOutcome.UpToDate, service(store, latest = "0.3.0").request("owner"))
        assertEquals(CoreUpdateOutcome.UpToDate, service(store, latest = "0.2.0").request("owner"))
        assertNull(store.inserted)
    }

    @Test
    fun `solicitar con version nueva inserta pending con versiones y solicitante`() {
        val store = FakeStore()
        val outcome = service(store).request("owner")
        val accepted = assertIs<CoreUpdateOutcome.Accepted>(outcome)
        assertEquals(Triple("0.4.0", "0.3.0", "owner"), store.inserted)
        assertEquals("pending", accepted.request.status)
        assertEquals("0.4.0", accepted.request.targetVersion)
        assertEquals("0.3.0", accepted.request.fromVersion)
    }

    @Test
    fun `solicitar fija el commit actual de la rama aunque la app no lo envie`() {
        val store = FakeStore()
        val accepted = assertIs<CoreUpdateOutcome.Accepted>(service(store).request("owner"))
        assertEquals(sha, store.insertedCommit)
        assertEquals(sha, accepted.request.targetCommit)
    }

    @Test
    fun `solicitar con el commit aprobado igual al de la rama inserta ese commit`() {
        val store = FakeStore()
        assertIs<CoreUpdateOutcome.Accepted>(service(store).request("owner", sha.uppercase()))
        assertEquals(sha, store.insertedCommit)
    }

    @Test
    fun `solicitar con un commit aprobado distinto responde CommitChanged sin insertar`() {
        val store = FakeStore()
        assertEquals(CoreUpdateOutcome.CommitChanged(sha), service(store).request("owner", otherSha))
        assertNull(store.inserted)
    }

    @Test
    fun `solicitar con un commit mal formado responde InvalidCommit sin insertar`() {
        val store = FakeStore()
        listOf("abc", "a".repeat(39), "g".repeat(40), "a".repeat(41), "$sha; rm -rf /", "master").forEach { commit ->
            assertEquals(CoreUpdateOutcome.InvalidCommit, service(store).request("owner", commit), commit)
        }
        assertNull(store.inserted)
    }

    @Test
    fun `con requireApprovedCommit la solicitud sin commit se rechaza`() {
        val store = FakeStore()
        assertEquals(CoreUpdateOutcome.InvalidCommit, service(store, requireApproved = true).request("owner"))
        assertIs<CoreUpdateOutcome.Accepted>(service(store, requireApproved = true).request("owner", sha))
    }

    @Test
    fun `solicitar sin commit legible o con otra version en el commit responde CommitUnavailable`() {
        val store = FakeStore()
        assertEquals(CoreUpdateOutcome.CommitUnavailable, service(store, commit = null).request("owner"))
        assertEquals(CoreUpdateOutcome.CommitUnavailable, service(store, commit = CoreCommit(sha, "0.3.5")).request("owner"))
        assertEquals(CoreUpdateOutcome.CommitUnavailable, service(store, commit = CoreCommit("xyz", "0.4.0")).request("owner"))
        assertNull(store.inserted)
    }

    @Test
    fun `estado muestra el commit, la rama, la firma y el resultado de la verificacion`() {
        val unsigned = service(FakeStore()).status()
        assertEquals(sha, unsigned.latestCommit)
        assertEquals("master", unsigned.ref)
        assertFalse(unsigned.signatureRequired)
        assertTrue(service(FakeStore(), signatureRequired = true).status().signatureRequired)
        // El commit solo se ofrece si declara la version del catalogo.
        assertNull(service(FakeStore(), commit = CoreCommit(sha, "0.3.9")).status().latestCommit)
        val done = service(FakeStore(last = row(status = "done", requestedAt = now, signature = "verified"))).status()
        assertEquals("verified", done.request!!.signatureStatus)
        assertEquals(sha, done.request!!.targetCommit)
    }

    @Test
    fun `GitHubCoreCommits lee el sha de la rama y la version de ese commit`() {
        val calls = mutableListOf<String>()
        val gradle = "plugins {}\nversion = \"0.4.0\"\n"
        val source = GitHubCoreCommits("master", "tok") { url, _, token ->
            calls += url
            assertEquals("tok", token)
            when {
                url == "https://api.github.com/repos/alexcalbri/BMS/commits/master" -> 200 to "$sha\n"
                url == "https://raw.githubusercontent.com/alexcalbri/BMS/$sha/server/build.gradle.kts" -> 200 to gradle
                else -> 404 to ""
            }
        }
        assertEquals(CoreCommit(sha, "0.4.0"), source())
        assertEquals(2, calls.size)
        assertNull(GitHubCoreCommits("master") { _, _, _ -> 200 to "not-a-sha" }())
        assertNull(GitHubCoreCommits("master") { url, _, _ -> if ("commits" in url) 200 to sha else 200 to "version = \"latest\"" }())
        assertNull(GitHubCoreCommits(null) { _, _, _ -> error("no debe llamar") }())
        // La misma linea que comprueba core-update.sh existe en el build real.
        val real = java.io.File(com.ideasdeveloper.bms.server.i18n.I18nCatalogCheck.repoRoot(), "server/build.gradle.kts").readText()
        assertTrue(GitHubCoreCommits.versionOf(real)!!.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+")))
    }

    @Test
    fun `la rama de CORE_UPDATE_REF se valida como en el ayudante root`() {
        assertEquals("master", CoreRelease.ref(null))
        assertEquals("master", CoreRelease.ref("  "))
        assertEquals("release/1.x", CoreRelease.ref("release/1.x"))
        assertNull(CoreRelease.ref("a..b"))
        assertNull(CoreRelease.ref("-x"))
        assertNull(CoreRelease.ref("main.lock"))
        assertNull(CoreRelease.ref("x?y"))
    }

    @Test
    fun `la firma se exige cuando existe allowed_signers`() {
        val dir = java.nio.file.Files.createTempDirectory("signers")
        try {
            val file = dir.resolve("allowed_signers")
            assertFalse(CoreRelease.signatureRequired(file))
            java.nio.file.Files.writeString(file, "owner ssh-ed25519 AAAA\n")
            assertTrue(CoreRelease.signatureRequired(file))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `solicitar con una activa responde InProgress`() {
        assertEquals(CoreUpdateOutcome.InProgress, service(FakeStore(active = true)).request("owner"))
    }

    @Test
    fun `stalePending solo si sigue pending pasados 3 minutos`() {
        val at = Instant.parse("2026-01-01T12:00:00Z")
        assertFalse(CoreUpdateService.isStalePending("pending", at, at.plusSeconds(180)))
        assertTrue(CoreUpdateService.isStalePending("pending", at, at.plusSeconds(181)))
        assertFalse(CoreUpdateService.isStalePending("running", at, at.plusSeconds(3600)))
        assertFalse(CoreUpdateService.isStalePending("error", at, at.plusSeconds(3600)))
    }

    @Test
    fun `estado mapea la solicitud mas reciente a DTO`() {
        val requestedAt = Instant.parse("2026-01-01T11:50:00Z")
        val store = FakeStore(
            last = row(
                status = "pending",
                requestedAt = requestedAt,
                startedAt = Instant.parse("2026-01-01T11:51:00Z"),
                message = "m",
                logTail = "log",
            )
        )
        val status = service(store).status()
        assertEquals("0.3.0", status.currentVersion)
        assertEquals("0.4.0", status.latestVersion)
        assertTrue(status.updateAvailable)
        val dto = status.request!!
        assertEquals("2026-01-01T11:50:00Z", dto.requestedAt)
        assertEquals("2026-01-01T11:51:00Z", dto.startedAt)
        assertNull(dto.finishedAt)
        assertEquals("m", dto.message)
        assertEquals("log", dto.logTail)
        assertTrue(dto.stalePending)
    }

    @Test
    fun `estado cachea el catalogo y conserva la ultima lectura buena si falla`() {
        var clock = now
        var fetches = 0
        var response: String? = "0.4.0"
        val service = CoreUpdateService(FakeStore(), "0.3.0", { fetches++; response }, { clock }, { null }, { false }, "master")
        assertEquals("0.4.0", service.status().latestVersion)
        clock = clock.plusSeconds(30)
        service.status()
        assertEquals(1, fetches)
        // Pasado el minuto se refresca; si falla, se conserva la ultima buena.
        clock = clock.plusSeconds(60)
        response = null
        assertEquals("0.4.0", service.status().latestVersion)
        assertEquals(2, fetches)
        // Pasados 10 minutos sin lectura buena, se descarta.
        clock = clock.plusSeconds(11 * 60)
        assertNull(service.status().latestVersion)
    }

    @Test
    fun `estado no consulta el catalogo con una solicitud activa`() {
        var fetches = 0
        val store = FakeStore(last = row(status = "running", target = "0.5.0", requestedAt = now))
        val status = CoreUpdateService(store, "0.3.0", { fetches++; "0.4.0" }, { now }, { fetches++; null }, { false }, "master").status()
        assertEquals(0, fetches)
        assertEquals("0.5.0", status.latestVersion)
        assertEquals(sha, status.latestCommit)
    }

    @Test
    fun `estado sin solicitudes ni catalogo`() {
        val status = service(FakeStore(), latest = null).status()
        assertNull(status.request)
        assertNull(status.latestVersion)
        assertFalse(status.updateAvailable)
    }

    private companion object {
        fun row(
            status: String,
            target: String = "0.4.0",
            from: String = "0.3.0",
            requestedAt: Instant,
            startedAt: Instant? = null,
            message: String? = null,
            logTail: String? = null,
            commit: String? = "a".repeat(40),
            signature: String? = null,
        ) = CoreUpdateRow(1, target, from, status, "owner", requestedAt, startedAt, null, message, logTail, commit, signature)
    }
}
