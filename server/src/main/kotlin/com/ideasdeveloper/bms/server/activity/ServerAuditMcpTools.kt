package com.ideasdeveloper.bms.server.activity

import com.ideasdeveloper.bms.ActivityLogPage
import com.ideasdeveloper.bms.ServerAuditPage
import com.ideasdeveloper.bms.server.admins.AuditFilter
import com.ideasdeveloper.bms.server.admins.ServerActor
import com.ideasdeveloper.bms.server.admins.ServerActorLookup
import com.ideasdeveloper.bms.server.admins.ServerAdminException
import com.ideasdeveloper.bms.server.admins.ServerAudit
import com.ideasdeveloper.bms.server.admins.ServerPermission
import com.ideasdeveloper.bms.server.admins.ServerPermissionException
import com.ideasdeveloper.bms.server.admins.parseAuditDay
import com.ideasdeveloper.bms.server.company.application.ProvisioningAudit
import com.ideasdeveloper.bms.server.company.application.auditDetails
import com.ideasdeveloper.bms.server.modules.McpSchema
import com.ideasdeveloper.bms.server.modules.McpTool
import com.ideasdeveloper.bms.server.modules.McpToolCall
import com.ideasdeveloper.bms.server.modules.McpToolException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.util.UUID
import javax.sql.DataSource

/**
 * The two read-only server tools on the audit (ACTIVITY_LOG.md §7): list_server_audit (the
 * provisioning audit) and list_company_activity (the companies' activity logs). They belong to the
 * server's own MCP (module "servidor"): the owner's agent gets them with the other server tools, and
 * they are the ONLY tools a server administrator's agent ever gets ([AdminAgentToolNames]).
 *
 * Every call reads the caller's rights again (active owner, or active administrator holding
 * server.audit.view, without a pending password change) and applies the same scope as the routes;
 * every call is audited in provisioning_audit_log with the filters used.
 */
class ServerAuditMcpTools(
    private val central: DataSource,
    private val audit: () -> ServerAudit,
    private val activity: () -> ServerActivity,
    // The live server actor of a central user (ServerOwnerAuthorizer.liveActor).
    private val liveActor: (UUID) -> ServerActorLookup,
) {
    companion object {
        // Not a module permission: the server's tools are granted by Core (UserModuleAccess / the admin agent path).
        const val Permission = "server_owner"
        const val ListServerAudit = "list_server_audit"
        const val ListCompanyActivity = "list_company_activity"

        /** Qualified names ("servidor_<name>") of the only tools an administrator's agent may use. */
        val AdminAgentToolNames: Set<String> = setOf("servidor_$ListServerAudit", "servidor_$ListCompanyActivity")

        const val AuditAction = "server_audit.agent_read"
    }

    private val json = Json { encodeDefaults = true }

    /** The caller's server actor now, holding server.audit.view; refuses anything else. */
    fun actorOf(call: McpToolCall): ServerActor {
        val userId = call.permissions.userId ?: throw McpToolException("No se pudo identificar al usuario.")
        val actor = (liveActor(userId) as? ServerActorLookup.Found)?.actor
            ?: throw McpToolException("Tu usuario ya no puede leer la auditoria del servidor.")
        if (!actor.has(ServerPermission.AuditView)) throw McpToolException("Necesitas el permiso ${ServerPermission.AuditView.id}.")
        return actor
    }

    private fun record(actor: ServerActor, call: McpToolCall, tool: String, company: String?, filters: Map<String, String>) {
        central.connection.use { connection ->
            ProvisioningAudit.insert(
                connection, actor.audit, AuditAction, company, null,
                auditDetails("tool" to tool, "agent" to call.agentName.take(80), "filters" to filters.entries.map { "${it.key}=${it.value}" }),
            )
        }
    }

    private fun <T> refusals(block: () -> T): T = try {
        block()
    } catch (failure: ServerPermissionException) {
        throw McpToolException(failure.message.orEmpty())
    } catch (failure: ServerAdminException) {
        throw McpToolException(failure.message.orEmpty())
    } catch (failure: ActivityFilterException) {
        throw McpToolException(failure.message.orEmpty())
    } catch (failure: ActivityNotFoundException) {
        throw McpToolException(failure.message.orEmpty())
    }

    val tools: List<McpTool> = listOf(
        McpTool(
            name = ListServerAudit,
            title = "Auditoria del servidor",
            description = "Lista la auditoria del servidor (empresas creadas, modulos, administradores, entradas a empresas...), lo mas reciente primero. " +
                "Filtra por empresa, accion, quien y fechas. Un administrador solo ve sus empresas y sus propias filas.",
            permission = Permission,
            readOnly = true,
            inputSchema = McpSchema.obj(
                mapOf(
                    "company" to McpSchema.string("Codigo de la empresa."),
                    "action" to McpSchema.string("Texto de la accion (p. ej. company.created)."),
                    "actor" to McpSchema.string("Usuario, nombre o correo de quien actuo."),
                    "from" to McpSchema.date("Desde este dia (incluido)."),
                    "to" to McpSchema.date("Hasta este dia (incluido)."),
                    "page" to McpSchema.integer("Pagina, desde 0.", 0, 1_000),
                    "page_size" to McpSchema.integer("Filas por pagina (50 por defecto).", 1, ServerAudit.MaxPageSize),
                ),
            ),
        ) { call ->
            refusals {
                val actor = actorOf(call)
                val company = call.string("company", 63).ifEmpty { null }
                company?.let { actor.requireCompany(ServerPermission.AuditView, it) }
                val filter = AuditFilter(
                    company = company,
                    action = call.string("action", 100).ifEmpty { null },
                    actor = call.string("actor", 200).ifEmpty { null },
                    from = parseAuditDay(call.string("from", 10)),
                    to = parseAuditDay(call.string("to", 10)),
                    page = call.int("page", 0, 0..1_000),
                    pageSize = call.int("page_size", ServerAudit.DefaultPageSize, 1..ServerAudit.MaxPageSize),
                )
                val page = audit().page(actor, filter)
                record(actor, call, ListServerAudit, company, buildMap {
                    filter.action?.let { put("accion", it) }
                    filter.actor?.let { put("quien", it) }
                    filter.from?.let { put("desde", it.toString()) }
                    filter.to?.let { put("hasta", it.toString()) }
                    put("pagina", filter.page.toString())
                })
                json.encodeToJsonElement(ServerAuditPage.serializer(), page) as JsonElement
            }
        },
        McpTool(
            name = ListCompanyActivity,
            title = "Actividad en empresas",
            description = "Lista el registro de actividad de una empresa (company) o, sin company, lo mas reciente de todas las empresas a tu alcance: " +
                "quien (actor_display), modulo, registro, accion, resumen y cambios. Filtra por usuario, modulo, tipo e id de registro, accion, texto y fechas.",
            permission = Permission,
            readOnly = true,
            inputSchema = McpSchema.obj(
                mapOf(
                    "company" to McpSchema.string("Codigo de la empresa; sin el, todas las de tu alcance."),
                    "user" to McpSchema.string("Id de usuario, o texto que coincide con su nombre o usuario."),
                    "module" to McpSchema.string("Id del modulo (core para la administracion de la empresa)."),
                    "entity_type" to McpSchema.string("Tipo de registro."),
                    "entity_id" to McpSchema.string("Id del registro."),
                    "action" to McpSchema.string("Accion: created, updated, deleted, settings_changed, signed_in..."),
                    "text" to McpSchema.string("Texto del resumen, el registro o quien lo hizo."),
                    "from" to McpSchema.date("Desde este dia (incluido)."),
                    "to" to McpSchema.date("Hasta este dia (incluido)."),
                    "page" to McpSchema.integer("Pagina, desde 0.", 0, 1_000),
                    "page_size" to McpSchema.integer("Filas por pagina (50 por defecto).", 1, 200),
                ),
            ),
        ) { call ->
            refusals {
                val actor = actorOf(call)
                val company = call.string("company", 63).ifEmpty { null }
                val filter = ActivityFilter(
                    user = call.string("user", 200).ifEmpty { null },
                    module = call.string("module", 63).ifEmpty { null },
                    entityType = call.string("entity_type", 100).ifEmpty { null },
                    entityId = call.string("entity_id", 200).ifEmpty { null },
                    action = call.string("action", 64).ifEmpty { null },
                    text = call.string("text", 200).ifEmpty { null },
                    from = ActivityFilter.day(call.string("from", 10)),
                    to = ActivityFilter.day(call.string("to", 10)),
                    page = call.int("page", 0, 0..1_000),
                    pageSize = call.int("page_size", ActivityLogReader.DefaultPageSize, 1..ActivityLogReader.MaxPageSize),
                )
                val page = activity().page(actor, company, filter)
                record(actor, call, ListCompanyActivity, company, filter.describe())
                json.encodeToJsonElement(ActivityLogPage.serializer(), page) as JsonElement
            }
        },
    )
}
