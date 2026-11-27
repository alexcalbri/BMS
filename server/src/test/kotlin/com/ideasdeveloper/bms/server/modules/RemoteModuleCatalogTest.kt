package com.ideasdeveloper.bms.server.modules

import java.net.InetAddress
import java.net.URI
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RemoteModuleCatalogTest {
    private val catalog = """{"modules":[{"id":"agentes","displayName":"Agentes IA","description":"MCP","version":"0.1.0"},""" +
        """{"id":"crm","displayName":"CRM","description":"Leads","version":"0.9.0"}]}"""
    private val publicAddress: InetAddress = InetAddress.getByAddress(byteArrayOf(140.toByte(), 82, 112, 3))
    private val sha = "0123456789abcdef0123456789abcdef01234567"

    /** Transporte de prueba: responde por URL y guarda cada peticion con su IP anclada y sus cabeceras. */
    private class FakeTransport(private val answer: (String) -> CatalogHttpResponse) : CatalogTransport {
        val requests = mutableListOf<Triple<String, InetAddress, Map<String, String>>>()
        override fun get(uri: URI, address: InetAddress, headers: Map<String, String>): CatalogHttpResponse {
            requests += Triple(uri.toString(), address, headers)
            return answer(uri.toString())
        }
    }

    private fun publicOnly(host: String): InetAddress = publicAddress

    // GitHub answers 403 once its hourly limit runs out; the screen polls while a module installs.
    @Test
    fun readingsAreCachedAndSurviveTheRateLimit() {
        val hits = AtomicInteger()
        var limited = false
        val transport = FakeTransport {
            hits.incrementAndGet()
            if (limited) CatalogHttpResponse(403, """{"message":"API rate limit exceeded"}""") else CatalogHttpResponse(200, catalog)
        }
        var now = 0L
        RemoteModuleCatalog(clock = { now }, resolve = ::publicOnly, transport = transport).use { remote ->
            val url = "https://catalog.example/catalog.json"
            assertEquals(listOf("agentes", "crm"), remote.modules(url).map { it.definition.id })
            repeat(20) { remote.modules(url) }
            assertEquals(1, hits.get())

            // After the cache expires, a rate-limited answer keeps the last good reading.
            limited = true
            now += 6 * 60_000L
            assertEquals(listOf("agentes", "crm"), remote.modules(url).map { it.definition.id })
            assertEquals(2, hits.get())

            // Saving the catalog URL reads it again right away.
            limited = false
            remote.forget(url)
            remote.modules(url)
            assertEquals(3, hits.get())
        }
    }

    // The token is a credential: only GitHub over HTTPS may receive it.
    @Test
    fun githubTokenGoesOnlyToGitHub() {
        assertEquals("Bearer t", GitHubAuth.header("t", "https://api.github.com/repos/o/r/contents/modules?ref=develop"))
        assertEquals("Bearer t", GitHubAuth.header(" t ", "https://codeload.github.com/o/r/zip/develop"))
        assertEquals("Bearer t", GitHubAuth.header("t", "https://raw.githubusercontent.com/o/r/develop/modules/crm/module.json"))
        assertNull(GitHubAuth.header("t", "https://raw.githubusercontent.com.evil.example/o/r/develop/x"))
        assertNull(GitHubAuth.header("t", "http://api.github.com/repos/o/r"))
        assertNull(GitHubAuth.header("t", "https://api.github.com.evil.example/x"))
        assertNull(GitHubAuth.header("t", "https://example.com/catalog.json"))
        assertNull(GitHubAuth.header("", "https://api.github.com/x"))
        assertNull(GitHubAuth.header(null, "https://api.github.com/x"))
    }

    @Test
    fun catalogsOutsideGitHubNeverReceiveTheToken() {
        val transport = FakeTransport { CatalogHttpResponse(200, catalog) }
        RemoteModuleCatalog("secret", resolve = ::publicOnly, transport = transport).use { it.modules("https://catalog.example/catalog.json") }
        assertEquals(1, transport.requests.size)
        assertNull(transport.requests.single().third["Authorization"])
    }

    // C3: the catalog URL is https only; private or local addresses are refused before connecting.
    @Test
    fun catalogUrlMustBeHttpsToAPublicAddress() {
        listOf(
            "http://catalog.example/catalog.json",
            "ftp://catalog.example/catalog.json",
            "https://user:pass@catalog.example/catalog.json",
            "https://catalog.example:8443/catalog.json",
            "https:///catalog.json",
            "file:///etc/passwd",
        ).forEach { url -> assertFailsWith<CatalogUrlException>(url) { CatalogUrlPolicy.validate(url) } }
        assertEquals("catalog.example", CatalogUrlPolicy.validate(" https://catalog.example/catalog.json ").host)

        val transport = FakeTransport { CatalogHttpResponse(200, catalog) }
        // Real validation: loopback, private and link-local (cloud metadata) addresses never get a request.
        RemoteModuleCatalog(transport = transport).use { remote ->
            listOf(
                "https://127.0.0.1/catalog.json",
                "https://localhost/catalog.json",
                "https://10.0.0.5/catalog.json",
                "https://169.254.169.254/latest/meta-data",
                "https://[::1]/catalog.json",
                "http://catalog.example/catalog.json",
            ).forEach { url -> assertEquals(emptyList(), remote.modules(url), url) }
        }
        assertEquals(0, transport.requests.size)
    }

    @Test
    fun connectionsGoToTheValidatedAddress() {
        val transport = FakeTransport { CatalogHttpResponse(200, catalog) }
        RemoteModuleCatalog(resolve = ::publicOnly, transport = transport).use { it.modules("https://catalog.example/catalog.json") }
        assertEquals(publicAddress, transport.requests.single().second)
    }

    @Test
    fun redirectsAreValidatedAndPinnedAgain() {
        // A redirect to a private address is refused: the private host is never contacted.
        val resolveReal: (String) -> InetAddress = { host -> if (host == "catalog.example") publicAddress else com.ideasdeveloper.bms.server.net.PinnedHost.resolveValidated(host) }
        val toPrivate = FakeTransport { url ->
            if (url.startsWith("https://catalog.example/")) CatalogHttpResponse(302, "", "https://127.0.0.1/catalog.json") else CatalogHttpResponse(200, catalog)
        }
        RemoteModuleCatalog(resolve = resolveReal, transport = toPrivate).use { assertEquals(emptyList(), it.modules("https://catalog.example/catalog.json")) }
        assertEquals(listOf("https://catalog.example/catalog.json"), toPrivate.requests.map { it.first })

        // A redirect to http is refused.
        val toHttp = FakeTransport { url ->
            if (url.startsWith("https://")) CatalogHttpResponse(301, "", "http://catalog.example/catalog.json") else CatalogHttpResponse(200, catalog)
        }
        RemoteModuleCatalog(resolve = ::publicOnly, transport = toHttp).use { assertEquals(emptyList(), it.modules("https://catalog.example/catalog.json")) }
        assertEquals(1, toHttp.requests.size)

        // Endless redirects stop.
        val loop = FakeTransport { CatalogHttpResponse(302, "", "/again") }
        RemoteModuleCatalog(resolve = ::publicOnly, transport = loop).use { assertEquals(emptyList(), it.modules("https://catalog.example/catalog.json")) }
        assertEquals(RemoteModuleCatalog.MaxRedirects + 1, loop.requests.size)

        // A valid https redirect to a public host is followed (resolved again).
        val moved = FakeTransport { url ->
            if (url == "https://catalog.example/catalog.json") CatalogHttpResponse(301, "", "https://mirror.example/catalog.json") else CatalogHttpResponse(200, catalog)
        }
        val resolved = mutableListOf<String>()
        RemoteModuleCatalog(resolve = { host -> resolved += host; publicAddress }, transport = moved).use {
            assertEquals(listOf("agentes", "crm"), it.modules("https://catalog.example/catalog.json").map { remote -> remote.definition.id })
        }
        assertEquals(listOf("catalog.example", "mirror.example"), resolved)
    }

    // C2: a GitHub catalog is read at one commit and the package download uses that commit, not the branch.
    @Test
    fun githubCatalogIsPinnedToTheCommitOfTheBranch() {
        val transport = FakeTransport { url ->
            when (url) {
                "https://api.github.com/repos/alexcalbri/BMS/commits/master" -> CatalogHttpResponse(200, "$sha\n")
                "https://api.github.com/repos/alexcalbri/BMS/contents/modules?ref=$sha" ->
                    CatalogHttpResponse(200, """[{"type":"dir","name":"crm","url":"https://api.github.com/repos/alexcalbri/BMS/contents/modules/crm?ref=$sha"}]""")
                "https://raw.githubusercontent.com/alexcalbri/BMS/$sha/modules/crm/module.json" ->
                    CatalogHttpResponse(200, """{"id":"crm","displayName":"CRM","description":"Leads","version":"0.9.0"}""")
                else -> CatalogHttpResponse(404, "Not Found")
            }
        }
        RemoteModuleCatalog("tok", resolve = ::publicOnly, transport = transport).use { remote ->
            val crm = remote.modules("https://github.com/alexcalbri/BMS/tree/master/modules").single()
            assertEquals(GitHubLocation("alexcalbri", "BMS", sha, "modules"), crm.source)
            assertTrue(crm.source!!.pinned)
            assertEquals("https://codeload.github.com/alexcalbri/BMS/zip/$sha", crm.source!!.archiveUrl)
        }
        // Nothing is read from the mutable branch except the commit lookup.
        assertEquals(1, transport.requests.count { "master" in it.first })
        assertTrue(transport.requests.all { it.third["Authorization"] == "Bearer tok" })
    }

    @Test
    fun aCatalogAlreadyPinnedToACommitIsNotResolvedAgain() {
        val transport = FakeTransport { url ->
            when (url) {
                "https://api.github.com/repos/o/r/contents/modules?ref=$sha" -> CatalogHttpResponse(200, "[]")
                else -> CatalogHttpResponse(404, "Not Found")
            }
        }
        RemoteModuleCatalog(resolve = ::publicOnly, transport = transport).use { it.modules("https://github.com/o/r/tree/$sha/modules") }
        assertEquals(listOf("https://api.github.com/repos/o/r/contents/modules?ref=$sha"), transport.requests.map { it.first })
    }

    @Test
    fun anUnresolvableBranchListsNothing() {
        val transport = FakeTransport { CatalogHttpResponse(404, "Not Found") }
        RemoteModuleCatalog(resolve = ::publicOnly, transport = transport).use {
            assertEquals(emptyList(), it.modules("https://github.com/o/r/tree/gone/modules"))
        }
        assertEquals(1, transport.requests.size)
    }

    @Test
    fun githubLocationPartsCannotEscapeTheFixedUrls() {
        assertNull(GitHubLocation.parse("https://github.com/o/r/tree/main/modules?x=1"))
        assertNull(GitHubLocation.parse("https://github.com/o/r/tree/main/../../x"))
        assertNull(GitHubLocation.parse("https://github.com/o@evil.example/r/tree/main/modules"))
        assertNull(GitHubLocation.parse("https://github.com/o/r/tree/main/mod#ules"))
        assertNull(GitHubLocation.parse("https://api.github.com/repos/o/r/contents/modules?ref=a..b"))
        assertEquals(GitHubLocation("o", "r", "release-1.0", "catalog/modules"), GitHubLocation.parse("https://github.com/o/r/tree/release-1.0/catalog/modules/"))
    }

    @Test
    fun legacyPackageUrlsMustBeHttps() {
        val body = """{"modules":[{"id":"crm","displayName":"CRM","description":"x","version":"0.1.0","packageUrl":"http://x.example/crm.zip"},""" +
            """{"id":"agentes","displayName":"A","description":"x","version":"0.1.0","packageUrl":"https://x.example/a.zip"}]}"""
        val transport = FakeTransport { CatalogHttpResponse(200, body) }
        RemoteModuleCatalog(resolve = ::publicOnly, transport = transport).use {
            assertEquals(listOf("agentes"), it.modules("https://catalog.example/catalog.json").map { remote -> remote.definition.id })
        }
    }

    @Test
    fun moduleDownloadsOnlyFollowRedirectsInsideGitHub() {
        assertTrue(GitHubAuth.isGitHubHttps("https://codeload.github.com/o/r/zip/$sha"))
        assertTrue(!GitHubAuth.isGitHubHttps("http://codeload.github.com/o/r/zip/$sha"))
        assertTrue(!GitHubAuth.isGitHubHttps("https://evil.example/o/r.zip"))
        assertTrue(!GitHubAuth.isGitHubHttps("https://x@codeload.github.com/o/r.zip"))
        assertTrue(!GitHubAuth.isGitHubHttps("https://codeload.github.com:8443/o/r.zip"))
    }
}
