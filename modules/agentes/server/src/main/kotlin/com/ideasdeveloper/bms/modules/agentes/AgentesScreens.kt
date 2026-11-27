package com.ideasdeveloper.bms.modules.agentes

import com.ideasdeveloper.bms.server.i18n.I18n
import com.ideasdeveloper.bms.server.i18n.msg
import com.ideasdeveloper.bms.server.i18n.tr
import com.ideasdeveloper.bms.server.i18n.trIn
import com.ideasdeveloper.bms.server.modules.ModulePermissions
import com.ideasdeveloper.bms.server.modules.RegisteredMcpTool
import com.ideasdeveloper.bms.ui.UiBadge
import com.ideasdeveloper.bms.ui.UiButton
import com.ideasdeveloper.bms.ui.UiCard
import com.ideasdeveloper.bms.ui.UiField
import com.ideasdeveloper.bms.ui.UiForm
import com.ideasdeveloper.bms.ui.UiList
import com.ideasdeveloper.bms.ui.UiListItem
import com.ideasdeveloper.bms.ui.UiNavItem
import com.ideasdeveloper.bms.ui.UiNavigate
import com.ideasdeveloper.bms.ui.UiNavigation
import com.ideasdeveloper.bms.ui.UiNode
import com.ideasdeveloper.bms.ui.UiNotice
import com.ideasdeveloper.bms.ui.UiRequest
import com.ideasdeveloper.bms.ui.UiScreen
import com.ideasdeveloper.bms.ui.UiText
import com.ideasdeveloper.bms.ui.UiTextStyle
import com.ideasdeveloper.bms.ui.UiTimeline
import com.ideasdeveloper.bms.ui.UiTimelineEntry
import com.ideasdeveloper.bms.ui.UiTone
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.sql.Connection
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

object AgentesScreens {
    const val UiRoute = "/modules/agentes/ui"
    const val McpRoute = "/modules/agentes/mcp"

    const val Connect = "agentes.connect"
    const val Manage = "agentes.manage"

    private const val ModuleFieldPrefix = "m_"
    private val timeFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())
    private val prettyJson = Json { prettyPrint = true }

    // Expiration choices of a new token.
    val expirations = linkedMapOf<String, Duration?>(
        msg("30 dias") to Duration.ofDays(30),
        msg("90 dias") to Duration.ofDays(90),
        msg("1 año") to Duration.ofDays(365),
        msg("Sin vencimiento") to null,
    )

    private data class Section(val id: String, val label: String, val permission: String)

    private val sections = listOf(
        Section("mine", msg("Mis agentes"), Connect),
        Section("team", msg("Agentes de la empresa"), Manage),
    )

    /**
     * What a screen needs: the signed-in user, the tools that user could give an agent right now
     * (their permissions in enabled modules) and the public URL of the MCP endpoint.
     */
    class Context(
        val tenant: Connection,
        val permissions: ModulePermissions,
        val owner: AgentOwner,
        val userTools: List<RegisteredMcpTool>,
        val endpoint: String,
        // Who is really behind the session (switchers); stored on the connections created from this screen.
        val opener: TokenOpener = TokenOpener.Own,
    ) {
        val canManage get() = permissions.has(Manage)

        // Modules with at least one tool for the user, in name order.
        val toolModules get() = userTools.groupBy { it.moduleId }.map { (id, tools) -> id to tools.first().moduleName }.sortedBy { it.second }

        fun owns(token: AgentToken) = token.userId == owner.userId && token.serverOwner == owner.serverOwner
    }

    fun home(permissions: ModulePermissions): String = if (permissions.has(Connect)) "mine" else "team"

    fun isScreen(screen: String) = screen in setOf("mine", "team", "new", "token")

    fun permissionFor(screen: String): String = if (screen == "team") Manage else if (screen == "token") "" else Connect

    fun build(screen: String, context: Context, params: Map<String, String>, notice: UiNotice? = null, submitted: Map<String, String>? = null): UiScreen {
        val required = permissionFor(screen)
        val allowed = if (screen == "token") context.permissions.has(Connect) || context.canManage else context.permissions.has(required)
        if (!allowed) return frame(context, screen, tr("Agentes IA"), UiNotice(tr("No tienes permiso para ver esta seccion."), UiTone.Danger), emptyList())
        return when (screen) {
            "mine" -> mine(context, notice)
            "team" -> team(context, notice)
            "new" -> newToken(context, notice, submitted)
            else -> token(context, params["id"], notice)
        }
    }

    private fun mine(context: Context, notice: UiNotice?): UiScreen {
        val tokens = AgentTokens.list(context.tenant, context.owner.userId).filter { it.serverOwner == context.owner.serverOwner }
        val body = listOf(
            UiText(
                tr("Conecta tu propio modelo o agente de IA (Claude, ChatGPT, Cursor, un agente propio...) por MCP. El agente actua en tu nombre y solo puede usar las funciones que tienes permitidas; si te quitan un permiso, tu agente lo pierde de inmediato."),
                UiTextStyle.Body,
            ),
            UiCard(
                title = tr("Conectar desde claude.ai u otra app con OAuth"),
                children = listOf(
                    UiText(
                        tr("Agrega un conector personalizado con esta URL. La app te pedira iniciar sesion en BMS y elegir que puede usar; no necesitas copiar ningun token."),
                        UiTextStyle.Body,
                    ),
                    UiForm(id = "oauth", fields = listOf(UiField("url", tr("URL del conector"))), values = mapOf("url" to context.endpoint), minFieldWidth = 420),
                ),
            ),
            UiCard(
                title = tr("Mis agentes conectados"),
                children = listOf(tokenList(tokens, showUser = false, emptyText = tr("Aun no conectaste ningun agente."))),
                actions = listOf(UiButton(tr("Conectar un agente"), UiNavigate("new"))),
            ),
            UiCard(
                title = tr("Lo que tu agente puede hacer hoy"),
                children = listOf(toolList(context.userTools)),
            ),
        )
        return frame(context, "mine", tr("Mis agentes"), notice, body)
    }

    private fun team(context: Context, notice: UiNotice?): UiScreen {
        val tokens = AgentTokens.list(context.tenant, null)
        val active = tokens.count { it.isActive() }
        val body = listOf(
            UiText(tr("Agentes que los usuarios de la empresa conectaron. Cada uno usa los permisos de su usuario. Activos: {0}.", active), UiTextStyle.Body),
            tokenList(tokens, showUser = true, emptyText = tr("Ningun usuario conecto un agente.")),
        )
        return frame(context, "team", tr("Agentes de la empresa"), notice, body)
    }

    private fun newToken(context: Context, notice: UiNotice?, submitted: Map<String, String>?): UiScreen {
        if (context.userTools.isEmpty()) {
            return frame(
                context, "mine", tr("Conectar un agente"),
                notice ?: UiNotice(tr("Aun no tienes funciones que un agente pueda usar. Pide al administrador permisos en los modulos que necesitas."), UiTone.Warning),
                emptyList(),
            )
        }
        val moduleFields = context.toolModules.map { (id, name) ->
            UiField("$ModuleFieldPrefix$id", trIn(I18n.moduleNamespace(id), name), "boolean", section = tr("Modulos que el agente puede usar"))
        }
        val defaults = mapOf("expires" to "90 dias", "readOnly" to "false", "groupScope" to "false") + context.toolModules.associate { "$ModuleFieldPrefix${it.first}" to "true" }
        val form = UiForm(
            id = "token",
            fields = listOf(
                UiField("name", tr("Nombre del agente"), required = true, placeholder = tr("Ej. Claude de ventas"), help = tr("Para reconocerlo en la lista y en la actividad.")),
            ) + moduleFields + listOf(
                UiField("readOnly", tr("Solo lectura: puede consultar, pero no crear ni modificar"), "boolean", section = tr("Limites")),
            ) + listOfNotNull(
                // Only the business owner may give an agent the other companies of the owner group.
                UiField(
                    "groupScope", tr("Tambien en las demas empresas de mi grupo (mismo dueno y Agentes IA activo en cada una)"), "boolean", section = tr("Limites"),
                ).takeIf { context.permissions.businessOwner && !context.owner.serverOwner },
            ) + listOf(
                UiField("expires", tr("Vence en"), "select", options = expirations.keys.toList(), section = tr("Limites")),
            ),
            values = defaults + submitted.orEmpty(),
            submit = UiButton(tr("Crear token"), UiRequest("$UiRoute/new", params = mapOf("op" to "create"), formId = "token")),
        )
        val body = listOf(
            UiText(
                tr("Si marcas todos los modulos, el agente tambien podra usar los que se activen despues. En ningun caso podra hacer algo que tu usuario no tenga permitido."),
                UiTextStyle.Caption,
            ),
            form,
        )
        return frame(context, "mine", tr("Conectar un agente"), notice, body)
    }

    /** Shown once after creating a token: the only time its value is visible. */
    fun created(context: Context, token: AgentToken, value: String): UiScreen {
        val config = buildJsonObject {
            putJsonObject("mcpServers") {
                putJsonObject("bms") {
                    put("type", "http")
                    put("url", context.endpoint)
                    putJsonObject("headers") { put("Authorization", "Bearer $value") }
                }
            }
        }
        val form = UiForm(
            id = "connection",
            fields = listOf(
                // Solo lectura con boton de copiar: son valores para copiar, no para editar.
                UiField("url", tr("URL del servidor MCP"), readOnly = true),
                UiField("token", tr("Token (cabecera Authorization: Bearer ...)"), readOnly = true),
                UiField("claude", "Claude Code", "textarea", readOnly = true),
                UiField("json", tr("Configuracion JSON (Cursor, VS Code, Claude Desktop con mcp-remote y otros)"), "textarea", readOnly = true),
            ),
            values = mapOf(
                "url" to context.endpoint,
                "token" to value,
                "claude" to "claude mcp add --transport http bms ${context.endpoint} --header \"Authorization: Bearer $value\"",
                "json" to prettyJson.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), config),
            ),
            minFieldWidth = 420,
        )
        val body = listOf(
            UiText(tr("Copia el token ahora: por seguridad no se vuelve a mostrar. Si lo pierdes, revoca este agente y crea otro."), UiTextStyle.Body, UiTone.Warning),
            form,
            UiButton(tr("Ver el agente"), UiNavigate("token", mapOf("id" to token.id.toString())), style = "secondary"),
        )
        return frame(context, "mine", tr("Agente \"{0}\" creado", token.name), UiNotice(tr("Token creado."), UiTone.Success), body)
    }

    private fun token(context: Context, rawId: String?, notice: UiNotice?): UiScreen {
        val token = rawId?.let { runCatching { UUID.fromString(it) }.getOrNull() }?.let { AgentTokens.find(context.tenant, it) }
            ?.takeIf { context.owns(it) || context.canManage }
            ?: return frame(context, home(context.permissions), tr("Agente"), UiNotice(tr("Ese agente no existe o no es tuyo."), UiTone.Warning), emptyList())
        val details = UiList(
            listOf(
                UiListItem(tr("Usuario"), trailing = token.userName),
                UiListItem(tr("Modulos"), trailing = token.allowedModules?.sorted()?.joinToString().orEmpty().ifEmpty { tr("Todos los que el usuario puede usar") }),
                UiListItem(tr("Acceso"), trailing = if (token.readOnly) tr("Solo lectura") else tr("Lectura y escritura")),
                UiListItem(tr("Empresas"), trailing = if (token.groupScope) tr("Esta y las demas de su grupo") else tr("Solo esta empresa")),
                UiListItem(tr("Conexion"), trailing = if (token.oauthClient != null) tr("OAuth (el token se renueva solo)") else tr("Token {0}...{1}...", AgentTokens.Prefix, token.hint)),
                UiListItem(tr("Origen"), trailing = originLabel(token)),
                UiListItem(tr("Creado"), trailing = format(token.createdAt)),
                UiListItem(tr("Vence"), trailing = token.expiresAt?.let(::format) ?: tr("Nunca")),
                UiListItem(tr("Ultimo uso"), trailing = token.lastUsedAt?.let(::format) ?: tr("Sin uso")),
            ) + listOfNotNull(token.revokedAt?.let { UiListItem(tr("Revocado"), trailing = tr("{0} por {1}", format(it), token.revokedBy.orEmpty())) }),
        )
        val activity = UiTimeline(
            AgentTokens.activity(context.tenant, token.id, 50).map { entry ->
                UiTimelineEntry(
                    title = entry.tool,
                    // What the log stored: the generic texts are translated when shown, the rest stays as written.
                    body = tr(entry.detail),
                    caption = format(entry.createdAt),
                    tone = when (entry.outcome) {
                        "ok" -> UiTone.Success
                        "denied" -> UiTone.Danger
                        else -> UiTone.Warning
                    },
                )
            },
            emptyText = tr("El agente aun no uso ninguna funcion."),
        )
        val revoke = UiButton(
            tr("Revocar acceso"),
            UiRequest("$UiRoute/token", params = mapOf("op" to "revoke", "id" to token.id.toString()), confirm = tr("El agente deja de funcionar de inmediato. Continuar?")),
            style = "danger",
        ).takeIf { token.revokedAt == null }
        val body = listOf(
            UiCard(title = tr("Detalles"), children = listOf(status(token), details), actions = listOfNotNull(revoke)),
            UiCard(title = tr("Actividad reciente"), children = listOf(activity)),
        )
        return frame(context, if (context.owns(token)) "mine" else "team", token.name, notice, body)
    }

    // Who connected it: older connections (before 0.7.2) cannot be told and say so.
    private fun originLabel(token: AgentToken): String = when (token.openerKind) {
        TokenOpener.Self -> tr("El propio usuario")
        TokenOpener.Server -> tr("Desde el servidor (selector de empresas)")
        TokenOpener.Company -> tr("Desde la empresa {0} (mismo dueno)", token.openerCompany.orEmpty())
        else -> tr("Origen desconocido (conectado antes de la version 0.7.2)")
    }

    private fun tokenList(tokens: List<AgentToken>, showUser: Boolean, emptyText: String) = UiList(
        tokens.map { token ->
            val scope = listOf(
                token.allowedModules?.sorted()?.joinToString() ?: tr("Todos los modulos"),
                if (token.readOnly) tr("solo lectura") else tr("lectura y escritura"),
                if (token.groupScope) tr("empresas del grupo") else null,
                if (token.oauthClient != null) "OAuth" else tr("token"),
                if (token.openerKind == null) tr("origen desconocido") else null,
                tr("ultimo uso: {0}", token.lastUsedAt?.let(::format) ?: tr("nunca")),
            ).filterNotNull().joinToString(" · ")
            UiListItem(
                title = token.name,
                subtitle = if (showUser) tr("{0} · {1}", token.userName, scope) else scope,
                badges = listOf(statusBadge(token)),
                action = UiNavigate("token", mapOf("id" to token.id.toString())),
            )
        },
        emptyText = emptyText,
    )

    private fun toolList(tools: List<RegisteredMcpTool>) = UiList(
        tools.sortedWith(compareBy({ it.moduleName }, { it.tool.title })).map { registered ->
            UiListItem(
                title = tr("{0}: {1}", tr(registered.moduleName), registered.tool.title),
                subtitle = registered.tool.description.substringBefore(". ").take(140),
                badges = listOf(if (registered.tool.readOnly) UiBadge(tr("Consulta"), UiTone.Info) else UiBadge(tr("Modifica"), UiTone.Warning)),
            )
        },
        emptyText = tr("Ninguna: no tienes permisos en modulos que ofrezcan funciones para agentes."),
    )

    private fun status(token: AgentToken): UiNode = UiText(
        when {
            token.revokedAt != null -> tr("Revocado")
            !token.isActive() -> tr("Vencido")
            else -> tr("Activo")
        },
        UiTextStyle.Subtitle,
        if (token.isActive()) UiTone.Success else UiTone.Danger,
    )

    private fun statusBadge(token: AgentToken) = when {
        token.revokedAt != null -> UiBadge(tr("Revocado"), UiTone.Danger)
        !token.isActive() -> UiBadge(tr("Vencido"), UiTone.Warning)
        else -> UiBadge(tr("Activo"), UiTone.Success)
    }

    private fun format(instant: Instant): String = timeFormat.format(instant)

    fun frame(context: Context, selected: String, title: String, notice: UiNotice?, body: List<UiNode>) = UiScreen(
        title = title,
        navigation = UiNavigation(
            title = tr(AgentesModule.displayName),
            items = sections.filter { context.permissions.has(it.permission) }.map { UiNavItem(it.id, tr(it.label), UiNavigate(it.id)) },
            selected = selected,
        ),
        notice = notice,
        body = listOf<UiNode>(UiText(title, UiTextStyle.Title)) + body,
    )

    /** Reads the "new agent" form: name, allowed modules (null = all), read-only and expiration. */
    fun parseNewToken(values: Map<String, String>, toolModules: List<String>, now: Instant = Instant.now()): NewTokenRequest {
        val name = values["name"].orEmpty().trim()
        if (name.isEmpty() || name.length > 80) throw AgentesValidationException(tr("Escribe un nombre de hasta 80 caracteres."))
        // The activity log shows it as the agent of a person ("Agente de Ana"): it may not fake the log's own markers.
        if (com.ideasdeveloper.bms.server.activity.ActivityNames.hasMarker(name)) {
            throw AgentesValidationException(tr("El nombre no puede contener \"Agente de\", \"desde el servidor\", \"Importacion (\" ni \"Automatizacion\"."))
        }
        val chosen = toolModules.filter { values["$ModuleFieldPrefix$it"] == "true" }.toSet()
        if (chosen.isEmpty()) throw AgentesValidationException(tr("Elige al menos un modulo."))
        val expiration = values["expires"].orEmpty()
        if (expiration !in expirations) throw AgentesValidationException(tr("Elige cuando vence el token."))
        return NewTokenRequest(
            name = name,
            allowedModules = chosen.takeIf { it.size < toolModules.size },
            readOnly = values["readOnly"] == "true",
            expiresAt = expirations[expiration]?.let { now.plus(it) },
            groupScope = values["groupScope"] == "true",
        )
    }
}

data class NewTokenRequest(
    val name: String,
    val allowedModules: Set<String>?,
    val readOnly: Boolean,
    val expiresAt: Instant?,
    // Asked for the other companies of the owner group; only honoured for a business owner.
    val groupScope: Boolean = false,
)

class AgentesValidationException(message: String) : Exception(message)
