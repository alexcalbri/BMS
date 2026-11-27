package com.ideasdeveloper.bms.server.admins

import com.ideasdeveloper.bms.server.assistant.assistantServerRoutes
import com.ideasdeveloper.bms.server.company.api.companyRoutes
import com.ideasdeveloper.bms.server.company.api.storageRoutes
import com.ideasdeveloper.bms.server.auth.api.twoFactorPolicyRoutes
import com.ideasdeveloper.bms.server.auth.api.twoFactorResetRoutes
import com.ideasdeveloper.bms.server.extensions.ExtensionActors
import com.ideasdeveloper.bms.server.extensions.ExtensionConnections
import com.ideasdeveloper.bms.server.extensions.ExtensionNotifier
import com.ideasdeveloper.bms.server.extensions.ExtensionRegistry
import com.ideasdeveloper.bms.server.extensions.ExtensionService
import com.ideasdeveloper.bms.server.extensions.ExtensionStorage
import com.ideasdeveloper.bms.server.extensions.extensionRoutes
import com.ideasdeveloper.bms.server.extensions.packages.BuiltExtension
import com.ideasdeveloper.bms.server.extensions.packages.ExtensionPackageService
import com.ideasdeveloper.bms.server.extensions.packages.ExtensionPackageStore
import com.ideasdeveloper.bms.server.extensions.packages.StoredExtensionPackage
import com.ideasdeveloper.bms.server.extensions.packages.UnsignedAuthorizationStore
import com.ideasdeveloper.bms.server.extensions.packages.extensionPackageRoutes
import com.ideasdeveloper.bms.server.modules.ModuleAccessDecision
import com.ideasdeveloper.bms.server.modules.ModuleRegistry
import com.ideasdeveloper.bms.server.modules.UserSessionLookup
import com.ideasdeveloper.bms.server.security.MasterKeySecretBox
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.HttpMethodRouteSelector
import io.ktor.server.routing.RoutingNode
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.sql.SQLException
import java.util.UUID
import java.util.concurrent.Executor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Route-authorization matrix (SERVER_ADMINS.md §4): every server-level route is mounted, enumerated
 * from the routing tree and must be classified in [ServerRoutePolicies]; then, for each one, an
 * administrator without the permission gets 403, with it gets through, out of scope gets 403,
 * owner-only routes refuse an administrator holding every permission, and an administrator who must
 * change the password is stopped. No database: actors come from a fake resolver and the services
 * behind the gate fail (503/500/4xx), which proves the request passed the gate.
 */
class ServerRouteMatrixTest {
    private val inScope = "acme"
    private val outOfScope = "otra"

    private fun admin(permissions: Set<ServerPermission>, mustChange: Boolean = false) = ServerActor(
        UUID.randomUUID(), "ana", ServerActorKind.Admin, permissions, false, setOf(inScope), "owner_role", mustChange,
    )

    // Tokens: owner, gone, all (every permission), allco (every permission but server.workspace, every
    // company), none, must, p:<permission id>.
    private val resolver = ServerActorResolver { token ->
        when {
            token == "owner" -> ServerActorLookup.Found(ServerActor(UUID.randomUUID(), "owner_role", ServerActorKind.Owner, emptySet(), true, emptySet(), "owner_role"))
            token == "all" -> ServerActorLookup.Found(admin(ServerPermission.entries.toSet()))
            token == "allco" -> ServerActorLookup.Found(
                ServerActor(UUID.randomUUID(), "ana", ServerActorKind.Admin, ServerPermission.entries.toSet() - ServerPermission.Workspace, true, emptySet(), "owner_role"),
            )
            token == "none" -> ServerActorLookup.Found(admin(emptySet()))
            token == "must" -> ServerActorLookup.PasswordChangeRequired(admin(ServerPermission.entries.toSet(), mustChange = true))
            token.startsWith("p:") -> ServerActorLookup.Found(admin(setOfNotNull(ServerPermission.of(token.removePrefix("p:")))))
            // Only the permission, with every company in scope.
            token.startsWith("allp:") -> ServerActorLookup.Found(
                ServerActor(UUID.randomUUID(), "ana", ServerActorKind.Admin, setOfNotNull(ServerPermission.of(token.removePrefix("allp:"))), true, emptySet(), "owner_role"),
            )
            else -> ServerActorLookup.None
        }
    }

    private val failingConnections = object : ExtensionConnections {
        override fun central() = throw SQLException("no database in this test", "08001")
        override fun tenant(companyCode: String) = throw SQLException("no database in this test", "08001")
    }

    private fun extensionService() = ExtensionService(
        connections = failingConnections,
        storage = ExtensionStorage(MasterKeySecretBox(null)),
        registry = ExtensionRegistry(emptyList()),
        modules = ModuleRegistry(emptyList()),
        notifier = ExtensionNotifier { _, _, _, _, _ -> },
        auditor = { _, _, _, _, _ -> },
    )

    private fun packageService() = ExtensionPackageService(
        store = object : ExtensionPackageStore {
            override fun find(id: String): StoredExtensionPackage? = null
            override fun all(): List<StoredExtensionPackage> = emptyList()
            override fun upsert(built: BuiltExtension) = Unit
            override fun markRemoved(id: String) = false
            override fun loadable(): List<StoredExtensionPackage> = emptyList()
            override fun markInstalled(ids: Collection<String>) = Unit
        },
        packagesRoot = Files.createTempDirectory("matrix"),
        bundledIds = { emptySet() },
        catalogSource = { emptyList() },
        builder = null,
        authorizations = object : UnsignedAuthorizationStore {
            override fun isAuthorized(packageSha256: String) = false
            override fun authorize(packageSha256: String, extensionId: String, sourceUrl: String, actor: String, acknowledgement: String) = Unit
        },
        requestRestart = {},
        audit = { _, _, _, _, _ -> },
        executor = Executor { it.run() },
    )

    private class Answer(val status: HttpStatusCode, val code: String?)

    private fun Answer.blocked() = status == HttpStatusCode.Forbidden &&
        code in setOf("SERVER_PERMISSION_REQUIRED", "SERVER_OWNER_REQUIRED", "PASSWORD_CHANGE_REQUIRED", "forbidden", "server_owner_required")

    private fun Answer.through() = !blocked() && status != HttpStatusCode.Unauthorized

    private fun serverLevel(key: String): Boolean {
        val path = key.substringAfter(' ')
        return path == "/auth/password" || path.startsWith("/server") ||
            (path.startsWith("/companies") && !path.startsWith("/companies/me"))
    }

    private fun nodes(node: RoutingNode): Sequence<RoutingNode> = sequenceOf(node) + node.children.asSequence().flatMap { nodes(it) }

    @Test
    fun everyServerRouteIsClassifiedAndEnforced() = testApplication {
        var root: RoutingNode? = null
        application {
            install(ContentNegotiation) { json() }
            routing {
                root = this as RoutingNode
                companyRoutes(resolver, provisioning = null, selfService = null, coreUpdates = null)
                extensionRoutes(
                    extensionService(),
                    ExtensionActors(
                        { UserSessionLookup.Denied(ModuleAccessDecision.Unauthorized("invalid_session", "x")) },
                        resolver,
                        { _, _ -> null },
                    ),
                )
                extensionPackageRoutes(packageService(), uploadEnabled = true, resolver = resolver)
                serverAdminRoutes(resolver, admins = null, audit = null)
                storageRoutes(resolver, storage = null)
                twoFactorPolicyRoutes(resolver, central = null)
                twoFactorResetRoutes(resolver, resets = null)
                assistantServerRoutes(resolver, admin = null)
            }
        }
        startApplication()
        val mounted = nodes(root!!).filter { it.selector is HttpMethodRouteSelector }.mapNotNull { routeKey(it) }.filter(::serverLevel).toSet()

        // (0) Deny by default: every mounted server-level route is classified, and the table lists no ghost route.
        val unclassified = mounted - ServerRoutePolicies.all.keys
        assertTrue(unclassified.isEmpty(), "Server routes without a policy in ServerRoutePolicies: $unclassified")
        val ghosts = ServerRoutePolicies.all.keys - mounted
        assertTrue(ghosts.isEmpty(), "Policies for routes that do not exist: $ghosts")
        assertTrue(mounted.size >= 69, "Expected every server route, got ${mounted.size}")

        suspend fun call(key: String, token: String?, company: String = inScope): Answer {
            val method = HttpMethod.parse(key.substringBefore(' '))
            val path = key.substringAfter(' ')
                .replace("{code}", company).replace("{operationId}", "1").replace(Regex("\\{[a-zA-Z]+}"), "x")
            val response = client.request(path) {
                this.method = method
                token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
                header("X-Company-Code", company)
                if (method != HttpMethod.Get && method != HttpMethod.Delete) {
                    contentType(ContentType.Application.Json)
                    setBody("{}")
                }
            }
            val code = runCatching { Json.parseToJsonElement(response.bodyAsText()).jsonObject["code"]?.jsonPrimitive?.content }.getOrNull()
            return Answer(response.status, code)
        }

        for ((key, policy) in ServerRoutePolicies.all) {
            val owner = call(key, "owner")
            assertTrue(owner.through(), "$key: the owner must pass the gate (${owner.status} ${owner.code})")
            val nobody = call(key, "gone")
            assertTrue(!nobody.through(), "$key: a caller without a server session must be refused (${nobody.status})")
            val must = call(key, "must")
            when (policy) {
                RoutePolicy.OwnerOnly -> {
                    // (d) every permission does not open an owner-only route
                    val all = call(key, "all")
                    assertTrue(all.blocked() && all.code in setOf("SERVER_OWNER_REQUIRED", "server_owner_required"), "$key: owner only, but an administrator got ${all.status} ${all.code}")
                    assertTrue(must.blocked(), "$key: must-change administrator got ${must.status}")
                }
                is RoutePolicy.AnyServerUser -> {
                    assertTrue(call(key, "none").through(), "$key: any server user")
                    assertEquals(policy.duringPasswordChange, must.through(), "$key: during password change")
                }
                is RoutePolicy.Requires -> {
                    // (a) without the permission
                    val without = call(key, "none")
                    assertTrue(without.blocked(), "$key: an administrator without ${policy.permission.id} got ${without.status} ${without.code}")
                    // (b) with it (and only it); a route that affects every company also needs every company in scope.
                    val with = call(key, "p:${policy.permission.id}")
                    if (policy.allCompanies) {
                        assertTrue(with.blocked(), "$key: a scoped administrator must not reach a server-wide route (${with.status} ${with.code})")
                        val everywhere = call(key, "allp:${policy.permission.id}")
                        assertTrue(everywhere.through(), "$key: an all-company administrator with ${policy.permission.id} was refused (${everywhere.status} ${everywhere.code})")
                    } else {
                        assertTrue(with.through(), "$key: an administrator with ${policy.permission.id} was refused (${with.status} ${with.code})")
                    }
                    assertTrue(must.blocked() && must.code == "PASSWORD_CHANGE_REQUIRED", "$key: must change the password first, got ${must.status} ${must.code}")
                    if (policy.company != CompanyFrom.None) {
                        // (c) out-of-scope company, even with every permission
                        val out = call(key, "all", company = outOfScope)
                        assertTrue(out.blocked(), "$key: out-of-scope company got ${out.status} ${out.code}")
                        assertTrue(call(key, "owner", company = outOfScope).through(), "$key: the owner has no scope")
                    }
                    if (policy.company != CompanyFrom.None) {
                        // The server workspace is never a company of a scope: an administrator with every
                        // company and every company permission is refused there unless the policy names
                        // server.workspace (path and X-Company-Code alike); the owner is not affected.
                        listOf("servidor", "SERVIDOR").forEach { workspace ->
                            val allCompanies = call(key, "allco", company = workspace)
                            assertTrue(allCompanies.blocked(), "$key: an all-company administrator reached the workspace '$workspace' (${allCompanies.status} ${allCompanies.code})")
                        }
                        assertTrue(call(key, "owner", company = "servidor").through(), "$key: the owner still reaches the workspace")
                    }
                    if (policy.workspace != null) {
                        val workspace = "servidor"
                        assertTrue(call(key, "p:${policy.permission.id}", company = workspace).blocked(), "$key: the workspace needs ${policy.workspace.id}")
                        assertTrue(call(key, "p:${policy.workspace.id}", company = workspace).through(), "$key: ${policy.workspace.id} opens the workspace")
                    }
                }
            }
        }
    }

    @Test
    fun everyPermissionOfTheContractExistsOnceOnTheServer() {
        assertEquals(com.ideasdeveloper.bms.ServerPermissionIds.All, ServerPermission.entries.map { it.id })
        val infos = ServerPermission.entries.map { it.info() }
        assertEquals(
            setOf(
                "server.companies.owner_password", "server.companies.enter", "server.modules.authorize_unsigned",
                "server.extensions.authorize_unsigned", "server.workspace", "server.core.update", "server.users.two_factor_reset",
                "server.assistant.manage",
            ),
            infos.filter { it.risk == "high" }.map { it.id }.toSet(),
        )
        // No permission is about administrators or server owners.
        assertTrue(ServerPermission.entries.none { "admin" in it.id || "owner." in it.id })
    }

    @Test
    fun theTableMatchesTheSpecification() {
        // Owner only (SERVER_ADMINS.md §3): no permission exists for these.
        val ownerOnly = listOf(
            "DELETE /companies/{code}", "PUT /companies/{code}/link", "PUT /server/modules/catalog",
            "PUT /companies/{code}/group", "DELETE /companies/{code}/group", "POST /companies/{code}/group/review",
            "POST /server/extensions/upload", "POST /server/extensions/from-url",
            "POST /server/extensions/staged/{token}/authorize-unsigned", "POST /server/extensions/staged/{token}/install",
        )
        ownerOnly.forEach { assertEquals(RoutePolicy.OwnerOnly, ServerRoutePolicies.all[it], it) }
        val expected = mapOf(
            "PUT /companies/{code}/owner-password" to ServerPermission.CompaniesOwnerPassword,
            "POST /server/modules/{moduleId}/authorize-unsigned" to ServerPermission.ModulesAuthorizeUnsigned,
            "POST /server/extensions/{id}/authorize-unsigned" to ServerPermission.ExtensionsAuthorizeUnsigned,
            "GET /server/logs" to ServerPermission.LogsView,
            "GET /server/audit" to ServerPermission.AuditView,
            "PUT /server/name" to ServerPermission.Settings,
            "POST /server/core-update" to ServerPermission.CoreUpdate,
            "DELETE /server/modules/{moduleId}" to ServerPermission.ModulesRemove,
            "POST /companies" to ServerPermission.CompaniesCreate,
            "PUT /companies/{code}/status" to ServerPermission.CompaniesStatus,
        )
        expected.forEach { (key, permission) -> assertEquals(permission, (ServerRoutePolicies.all[key] as RoutePolicy.Requires).permission, key) }
        // Company routes are scoped by the company of the path.
        ServerRoutePolicies.all.filter { (key, policy) -> "{code}" in key && policy is RoutePolicy.Requires }
            .forEach { (key, policy) -> assertEquals(CompanyFrom.Path, (policy as RoutePolicy.Requires).company, key) }
        ServerRoutePolicies.all.filter { (key, _) -> key.contains("/companies/current") }
            .forEach { (key, policy) -> assertEquals(CompanyFrom.Header, (policy as RoutePolicy.Requires).company, key) }
    }

    @Test
    fun theWorkspaceIsNeverInAnAdministratorsScope() {
        val everything = ServerActor(UUID.randomUUID(), "ana", ServerActorKind.Admin, ServerPermission.entries.toSet(), true, emptySet(), "owner_role")
        assertTrue(everything.mayActOn("acme"))
        assertTrue(!everything.mayActOn("servidor") && !everything.mayActOn(" Servidor "))
        val listed = ServerActor(UUID.randomUUID(), "ana", ServerActorKind.Admin, ServerPermission.entries.toSet(), false, setOf("servidor"), "owner_role")
        assertTrue(!listed.mayActOn("servidor"), "a stored scope row for the workspace does not count")
        assertTrue(ServerActor(UUID.randomUUID(), "o", ServerActorKind.Owner, emptySet(), true, emptySet(), "o").mayActOn("servidor"))
        assertTrue(!com.ideasdeveloper.bms.server.extensions.ServerAdminScope(null).includes("servidor"))
        // The refusal about a related company does not name it.
        val refusal = ServerPermissionException.outOfScope(ServerPermission.CompaniesEdit)
        assertEquals("SERVER_PERMISSION_REQUIRED", refusal.code)
        assertTrue(refusal.companyCode == null && "otra" !in refusal.message.orEmpty())
        // server.workspace allows creating staff accounts: it is high risk and says so.
        assertEquals("high", ServerPermission.Workspace.info().risk)
    }

    @Test
    fun theAssistantsServerWideRoutesNeedEveryCompany() {
        listOf("GET /server/assistant/settings", "PUT /server/assistant/settings", "PUT /server/assistant/key", "DELETE /server/assistant/key").forEach { key ->
            assertTrue((ServerRoutePolicies.all[key] as RoutePolicy.Requires).allCompanies, key)
        }
        assertEquals(CompanyFrom.Path, (ServerRoutePolicies.all["PUT /server/assistant/companies/{code}"] as RoutePolicy.Requires).company)
    }

    @Test
    fun noPermissionOpensAdministratorManagement() {
        val manage = ServerRoutePolicies.all.filterKeys { it.contains("/server/admins") || it.contains("/server/admin-permissions") }
        assertTrue(manage.size >= 9)
        assertTrue(manage.values.all { it == RoutePolicy.OwnerOnly }, "$manage")
        assertEquals(RoutePolicy.OwnerOnly, ServerRoutePolicies.of("GET /server/a-route-nobody-classified"))
        assertEquals(RoutePolicy.OwnerOnly, ServerRoutePolicies.of(null))
    }
}
