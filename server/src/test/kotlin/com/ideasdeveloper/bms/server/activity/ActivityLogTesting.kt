package com.ideasdeveloper.bms.server.activity

import com.ideasdeveloper.bms.server.auth.infrastructure.database.LoginDatabases
import com.ideasdeveloper.bms.server.modules.ModuleAccessService
import com.ideasdeveloper.bms.server.modules.ModulePermissions
import com.ideasdeveloper.bms.server.modules.ServerModule
import io.ktor.server.routing.HttpMethodRouteSelector
import io.ktor.server.routing.RoutingNode
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import java.lang.reflect.Proxy
import java.sql.SQLException
import java.util.UUID
import javax.sql.DataSource

/**
 * Test kit of the activity log for module tests (ACTIVITY_LOG_MODULE_GUIDE.md). Module tests run on
 * fakes (a Proxy connection, plain objects): [capture] keeps every ActivityLog.record of the block
 * in memory instead of writing it, so no database is needed. [writeTools] and [writeRoutes] list a
 * module's write paths, so a test can assert that each one is covered.
 */
object ActivityLogTesting {
    /** One captured row: who and what, exactly as ActivityLog.record received them (normalised). */
    data class Captured(val actor: ActivityActor, val entry: ActivityEntry) {
        val action: String get() = entry.action
        val display: String get() = actor.display
        fun change(field: String): ActivityChange? = entry.changes.firstOrNull { it.field == field }
    }

    /** Runs [block] on this thread and returns its result with the rows it recorded (nothing is written). */
    fun <T> capture(block: () -> T): Pair<T, List<Captured>> {
        val rows = java.util.Collections.synchronizedList(mutableListOf<Captured>())
        val result = ActivityLog.capturing({ actor, entry -> rows += Captured(actor, entry) }) { block() }
        return result to rows.toList()
    }

    /** The rows [block] recorded on this thread. */
    fun rows(block: () -> Unit): List<Captured> = capture(block).second

    /** Like [capture] for every thread (Ktor testApplication routes run their handlers elsewhere). */
    fun <T> captureEverywhere(block: () -> T): Pair<T, List<Captured>> {
        val rows = java.util.Collections.synchronizedList(mutableListOf<Captured>())
        val result = ActivityLog.capturing({ actor, entry -> rows += Captured(actor, entry) }, everywhere = true) { block() }
        return result to rows.toList()
    }

    /** A user acting in the app ("Ana Ruiz"). */
    fun user(name: String = "Ana Ruiz", id: UUID = UUID.fromString("00000000-0000-0000-0000-00000000a0a0")): ActivityActor = ActivityActor.user(id, name)

    /**
     * Permissions as Core builds them for a request, with [actor] as activityActor. Use
     * `agent("Claude")` for a call through an AI agent (display "Agente de Ana Ruiz").
     */
    fun permissions(
        granted: Set<String> = emptySet(),
        businessOwner: Boolean = false,
        actor: ActivityActor = user(),
        companyCode: String = "acme",
    ): ModulePermissions = ModulePermissions(companyCode, "", businessOwner, granted, actor.userId, activityActor = actor)

    /** The same permissions acting through the AI agent [agentName]. */
    fun ModulePermissions.throughAgent(agentName: String = "Claude"): ModulePermissions = withActivityActor(activityActor.viaAgent(agentName))

    /** MCP tools of [module] that change data (not readOnly), as "<module>_<tool>". */
    fun writeTools(module: ServerModule): List<String> =
        module.mcp?.tools.orEmpty().filterNot { it.readOnly }.map { "${module.definition.id}_${it.name}" }.sorted()

    /** Routes of [module] that can change data (every method but GET/HEAD/OPTIONS), as "METHOD /path". */
    fun writeRoutes(module: ServerModule): List<String> {
        val keys = mutableListOf<String>()
        testApplication {
            application {
                routing {
                    module.routes(this, accessWithoutDatabase())
                    fun walk(node: RoutingNode) {
                        if (node.selector is HttpMethodRouteSelector) {
                            com.ideasdeveloper.bms.server.admins.routeKey(node)?.let { keys += it }
                        }
                        node.children.forEach(::walk)
                    }
                    walk(this as RoutingNode)
                }
            }
            startApplication()
        }
        return keys.filterNot { it.startsWith("GET ") || it.startsWith("HEAD ") || it.startsWith("OPTIONS ") }.distinct().sorted()
    }

    /** A ModuleAccessService whose databases always fail: enough to mount routes or call code that never reaches it. */
    fun accessWithoutDatabase(): ModuleAccessService {
        val noDatabase = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(DataSource::class.java)) { _, method, _ ->
            when (method.name) {
                "getConnection" -> throw SQLException("no database in this test", "08001")
                "toString" -> "NoDatabase"
                "hashCode" -> 0
                "equals" -> false
                else -> null
            }
        } as DataSource
        val login = LoginDatabases(
            central = noDatabase,
            centralDatabase = org.jetbrains.exposed.v1.jdbc.Database.connect(noDatabase),
            centralUrl = "jdbc:postgresql://127.0.0.1:1/none",
            tenantConfiguration = "[]",
            runtimeUser = "none",
            runtimePassword = "none",
            tenantJdbcUrlPrefix = "jdbc:postgresql://127.0.0.1:1/",
            lifetimeSeconds = 3600,
        )
        return ModuleAccessService(noDatabase, login)
    }
}
