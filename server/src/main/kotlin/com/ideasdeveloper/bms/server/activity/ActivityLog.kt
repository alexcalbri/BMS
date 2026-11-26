package com.ideasdeveloper.bms.server.activity

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.math.BigDecimal
import java.sql.Connection
import java.sql.Types
import java.util.UUID

/**
 * Who did something, as the company activity log stores it (ACTIVITY_LOG.md §1.4, §2). Core resolves
 * it from the session, switcher row or agent token ([com.ideasdeveloper.bms.server.modules.ModulePermissions.activityActor]);
 * modules never build a person by hand. Non-human actors come from the factories in the companion.
 * Every text is Spanish (the stored language).
 */
data class ActivityActor(
    // user | business_owner | server_owner | server_admin | automation | job | import | integration | system
    val kind: String,
    // Tenant user (or the business owner a switcher session belongs to); a central id for server people in the workspace.
    val userId: UUID?,
    val name: String,
    val display: String,
    val agentName: String? = null,
    // The server person behind a switcher session, or the origin business owner of a group agent.
    val realKind: String? = null,
    val realUserId: UUID? = null,
    val realName: String? = null,
    // app | agent | api | automation | import | webhook; used when the entry gives none.
    val origin: String? = null,
) {
    /** The same person acting through the AI agent (or OAuth connection) [agentName]: "Agente de <usuario>". */
    fun viaAgent(agentName: String): ActivityActor = copy(
        agentName = agentName.trim().take(MaxText).ifEmpty { "Agente" },
        display = "Agente de " + display,
        origin = Origins.Agent,
    )

    /** The same actor with another origin (an api route, a webhook...). */
    fun withOrigin(origin: String): ActivityActor = copy(origin = origin)

    companion object {
        const val MaxText = 200

        /** A company user acting in the app. */
        fun user(userId: UUID, name: String): ActivityActor = person(Kinds.User, userId, name)

        /** The company's business owner acting in the app. */
        fun businessOwner(userId: UUID, name: String): ActivityActor = person(Kinds.BusinessOwner, userId, name)

        /** The server owner acting in the server workspace (central user id). */
        fun serverOwner(userId: UUID?, name: String): ActivityActor = person(Kinds.ServerOwner, userId, name)

        /** A server administrator acting in the server workspace (central user id). */
        fun serverAdmin(userId: UUID?, name: String): ActivityActor = person(Kinds.ServerAdmin, userId, name)

        /**
         * A switcher session: the business owner user it belongs to, opened by a server owner or
         * administrator ([realKind], [realUserId], [realName]): "<nombre real> (desde el servidor)".
         */
        fun switcher(ownerUserId: UUID, ownerName: String, realKind: String, realUserId: UUID?, realName: String): ActivityActor {
            val real = clean(realName).ifEmpty { if (realKind == Kinds.ServerOwner) "Dueno del servidor" else "Administrador del servidor" }
            return ActivityActor(
                Kinds.BusinessOwner, ownerUserId, clean(ownerName).ifEmpty { "Business owner" }, "$real (desde el servidor)",
                realKind = realKind, realUserId = realUserId, realName = real, origin = Origins.App,
            )
        }

        /**
         * A business owner's AI agent acting in another company of its owner group: the row keeps
         * [target] (that company's business owner, whose rights it used) as user, and [origin] (the
         * real person, of [originCompany]) as real actor: "Agente de Ana Ruiz (empresa origen)".
         */
        fun groupAgent(target: ActivityActor, origin: ActivityActor, originCompany: String, agentName: String): ActivityActor = ActivityActor(
            target.kind, target.userId, target.name,
            "Agente de ${origin.display} (empresa ${clean(originCompany)})",
            agentName = clean(agentName).ifEmpty { "Agente" },
            realKind = origin.realKind ?: origin.kind,
            realUserId = origin.realUserId ?: origin.userId,
            realName = "${origin.realName ?: origin.name} (${clean(originCompany)})",
            origin = Origins.Agent,
        )

        /** Automation [name]; [startedBy] when a person started it (a manual button). */
        fun automation(name: String, startedBy: ActivityActor? = null): ActivityActor =
            started(Kinds.Automation, "Automatizacion " + clean(name), clean(name), startedBy, Origins.Automation)

        /** A scheduled job of the server or a module. */
        fun job(name: String): ActivityActor = ActivityActor(Kinds.Job, null, clean(name), "Tarea programada " + clean(name), origin = Origins.Automation)

        /** An import started by [startedBy]: "Importacion (Ana Ruiz)". */
        fun import(startedBy: ActivityActor?): ActivityActor = started(Kinds.Import, "Importacion", "Importacion", startedBy, Origins.Import)

        /** An inbound integration ("Cloudbeds", "Sitio web"...), e.g. a webhook. */
        fun integration(name: String): ActivityActor = ActivityActor(Kinds.Integration, null, clean(name), clean(name), origin = Origins.Webhook)

        /**
         * Who an audited server-side change was made by ([com.ideasdeveloper.bms.server.admins.AuditActor]), for the
         * company's own log: the server owner or administrator "(desde el servidor)" with their central id,
         * a business owner by login, or the system.
         */
        fun fromAudit(audit: com.ideasdeveloper.bms.server.admins.AuditActor, name: String? = null): ActivityActor {
            val who = clean(name ?: audit.login).ifEmpty { audit.login }
            return when (audit.kind) {
                Kinds.ServerOwner, Kinds.ServerAdmin -> ActivityActor(audit.kind, audit.userId, who, "$who (desde el servidor)", origin = Origins.App)
                Kinds.BusinessOwner -> ActivityActor(Kinds.BusinessOwner, audit.userId, who, who, origin = Origins.App)
                else -> system()
            }
        }

        /** The server itself (unknown sign-in attempts, background repairs). */
        fun system(): ActivityActor = ActivityActor(Kinds.System, null, "Sistema", "Sistema")

        private fun person(kind: String, userId: UUID?, name: String): ActivityActor {
            val clean = clean(name).ifEmpty { "Usuario" }
            return ActivityActor(kind, userId, clean, clean, origin = Origins.App)
        }

        // The person who started it is kept as the user of the row, the display names both.
        private fun started(kind: String, label: String, name: String, startedBy: ActivityActor?, origin: String): ActivityActor =
            if (startedBy == null) ActivityActor(kind, null, name, label, origin = origin)
            else ActivityActor(
                kind, startedBy.userId, name, "$label (${startedBy.display})",
                agentName = startedBy.agentName,
                realKind = startedBy.realKind ?: startedBy.kind.takeIf { it != Kinds.User && it != Kinds.BusinessOwner },
                realUserId = startedBy.realUserId,
                realName = startedBy.realName ?: startedBy.name,
                origin = origin,
            )

        internal fun clean(text: String): String = ActivityText.clean(text).take(MaxText)
    }

    object Kinds {
        const val User = "user"
        const val BusinessOwner = "business_owner"
        const val ServerOwner = "server_owner"
        const val ServerAdmin = "server_admin"
        const val Automation = "automation"
        const val Job = "job"
        const val Import = "import"
        const val Integration = "integration"
        const val System = "system"
    }

    object Origins {
        const val App = "app"
        const val Agent = "agent"
        const val Api = "api"
        const val Automation = "automation"
        const val Import = "import"
        const val Webhook = "webhook"
    }
}

/** One old → new change of a field. Values are already cut to 500 characters; [secret] stores neither value. */
data class ActivityChange(
    val field: String,
    // Spanish label shown in screens ("Correo").
    val label: String,
    val old: String?,
    val new: String?,
    val secret: Boolean = false,
) {
    companion object {
        /** A secret setting that changed: only that it changed is stored. */
        fun secret(field: String, label: String): ActivityChange = ActivityChange(field, label, null, null, secret = true)

        /** A field change with its values formatted and cut like [ActivityChanges.diff] does. */
        fun of(field: String, label: String, old: Any?, new: Any?): ActivityChange =
            if (ActivityChanges.looksSecret(field)) secret(field, label)
            else ActivityChange(field, label, ActivityChanges.format(old), ActivityChanges.format(new))
    }
}

/**
 * What happened (ACTIVITY_LOG.md §3). [summary] is one Spanish sentence ("Creo el cliente Ana Ruiz.");
 * [origin] null takes the actor's.
 */
data class ActivityEntry(
    val moduleId: String,
    val entityType: String,
    val entityId: String?,
    val entityLabel: String = "",
    val action: String,
    val summary: String,
    val changes: List<ActivityChange> = emptyList(),
    val origin: String? = null,
)

/** Action ids used by Core and modules (others are allowed: [a-z][a-z0-9_]*). */
object ActivityActions {
    const val Created = "created"
    const val Updated = "updated"
    const val Deleted = "deleted"
    const val Archived = "archived"
    const val Restored = "restored"
    const val StatusChanged = "status_changed"
    const val Assigned = "assigned"
    const val Commented = "commented"
    const val AttachmentAdded = "attachment_added"
    const val AttachmentRemoved = "attachment_removed"
    const val SettingsChanged = "settings_changed"
    const val Imported = "imported"
    const val Exported = "exported"
    const val Viewed = "viewed"
    const val SignedIn = "signed_in"
    const val SignInFailed = "sign_in_failed"
    const val SignedOut = "signed_out"
    const val PasswordChanged = "password_changed"
    const val PermissionChanged = "permission_changed"
    const val Enabled = "enabled"
    const val Disabled = "disabled"
    const val Installed = "installed"
    const val Uninstalled = "uninstalled"
    const val SwitchedCompany = "switched_company"

    /** Spanish label of an action for screens and CSV; unknown ids come back as they are. */
    fun label(action: String): String = labels[action] ?: action

    private val labels = mapOf(
        Created to "Creado", Updated to "Modificado", Deleted to "Eliminado", Archived to "Archivado", Restored to "Restaurado",
        StatusChanged to "Cambio de estado", Assigned to "Asignado", Commented to "Comentario",
        AttachmentAdded to "Adjunto agregado", AttachmentRemoved to "Adjunto eliminado", SettingsChanged to "Cambio de ajustes",
        Imported to "Importacion", Exported to "Exportacion", Viewed to "Consulta", SignedIn to "Inicio de sesion",
        SignInFailed to "Inicio de sesion fallido", SignedOut to "Cierre de sesion", PasswordChanged to "Cambio de contrasena",
        PermissionChanged to "Cambio de permisos", Enabled to "Activado", Disabled to "Desactivado", Installed to "Instalado",
        Uninstalled to "Desinstalado", SwitchedCompany to "Cambio de empresa",
    )
}

/** Builds old → new lists and applies the secret and length rules (ACTIVITY_LOG.md §1.6). */
object ActivityChanges {
    const val MaxValueLength = 500
    private val secretName = Regex("(?i)(password|passwd|contrasena|secret|token|api_?key|apikey|clave|credential|private_?key|signing|pin\$|code_hash)")

    /**
     * The fields of [labels] (field -> Spanish label, in that order) whose value differs between
     * [before] and [after]. Fields in [secrets], or whose name looks like a secret (password, token,
     * api key...), are stored only as "changed". Values are formatted as text and cut to 500 characters.
     */
    fun diff(
        before: Map<String, Any?>,
        after: Map<String, Any?>,
        labels: Map<String, String>,
        secrets: Set<String> = emptySet(),
    ): List<ActivityChange> = labels.mapNotNull { (field, label) ->
        val old = format(before[field])
        val new = format(after[field])
        when {
            old == new -> null
            field in secrets || looksSecret(field) -> ActivityChange.secret(field, label)
            else -> ActivityChange(field, label, old, new)
        }
    }

    /** Every field of [labels] present in [values] as a new value (a created record). */
    fun created(values: Map<String, Any?>, labels: Map<String, String>, secrets: Set<String> = emptySet()): List<ActivityChange> =
        diff(emptyMap(), values, labels, secrets)

    /** Every field of [labels] present in [values] as an old value (a deleted record's key data). */
    fun deleted(values: Map<String, Any?>, labels: Map<String, String>, secrets: Set<String> = emptySet()): List<ActivityChange> =
        diff(values, emptyMap(), labels, secrets)

    fun looksSecret(field: String): Boolean = secretName.containsMatchIn(field)

    /** A value as stored: null, "Si"/"No", plain numbers, lists joined with ", ", everything cut to 500 characters. */
    fun format(value: Any?): String? {
        val text = when (value) {
            null -> return null
            is String -> value
            is Boolean -> if (value) "Si" else "No"
            is BigDecimal -> value.stripTrailingZeros().toPlainString()
            is Collection<*> -> value.joinToString(", ") { format(it).orEmpty() }
            is Array<*> -> value.joinToString(", ") { format(it).orEmpty() }
            is JsonPrimitive -> if (value is JsonNull) return null else value.content
            else -> value.toString()
        }
        return cut(ActivityText.clean(text, keepNewlines = true))
    }

    fun cut(text: String, max: Int = MaxValueLength): String = if (text.length <= max) text else text.take(max - 1) + "…"
}

internal object ActivityText {
    /** Without NUL (refused by PostgreSQL) and other control characters, trimmed. */
    fun clean(text: String, keepNewlines: Boolean = false): String = buildString(text.length) {
        for (char in text) {
            when {
                char == '\n' && keepNewlines -> append(char)
                char.isISOControl() -> append(' ')
                else -> append(char)
            }
        }
    }.trim()
}

/**
 * Writes the company activity log (tenant table activity_log, V011). Call [record] on the SAME
 * connection and transaction as the change, before committing: if the row cannot be written the
 * exception propagates and the change must fail with it (never catch it to carry on).
 */
object ActivityLog {
    const val Table = "activity_log"
    const val MaxSummaryLength = 500
    const val MaxChanges = 100
    private val modulePattern = Regex("[a-z][a-z0-9_]{0,62}")
    private val actionPattern = Regex("[a-z][a-z0-9_]{0,63}")

    // Test hooks (ActivityLogTesting): while set, rows go to them instead of the database.
    private val sink = ThreadLocal<((ActivityActor, ActivityEntry) -> Unit)?>()
    @Volatile private var globalSink: ((ActivityActor, ActivityEntry) -> Unit)? = null

    /**
     * Runs [block] with every [record] captured by [capture] instead of written: of this thread, or
     * with [everywhere] of every thread (route tests, whose handlers run on other threads). For tests.
     */
    fun <T> capturing(capture: (ActivityActor, ActivityEntry) -> Unit, everywhere: Boolean = false, block: () -> T): T {
        if (everywhere) {
            val previous = globalSink
            globalSink = capture
            return try {
                block()
            } finally {
                globalSink = previous
            }
        }
        val previous = sink.get()
        sink.set(capture)
        return try {
            block()
        } finally {
            sink.set(previous)
        }
    }

    /** Inserts one row; returns its id (0 while captured by a test). Throws on any failure. */
    fun record(connection: Connection, actor: ActivityActor, entry: ActivityEntry): Long {
        require(modulePattern.matches(entry.moduleId)) { "Invalid activity module id: ${entry.moduleId}" }
        require(actionPattern.matches(entry.action)) { "Invalid activity action: ${entry.action}" }
        (sink.get() ?: globalSink)?.let { capture ->
            capture(actor, normalized(entry))
            return 0
        }
        val clean = normalized(entry)
        return connection.prepareStatement(
            """
            INSERT INTO activity_log (actor_kind, actor_user_id, actor_name, actor_display, agent_name, real_actor_kind,
                real_actor_user_id, real_actor_name, module_id, entity_type, entity_id, entity_label, action, summary, changes, origin)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?)
            RETURNING id
            """.trimIndent()
        ).use { insert ->
            insert.setString(1, actor.kind)
            insert.setUuid(2, actor.userId)
            insert.setString(3, ActivityActor.clean(actor.name).ifEmpty { "Desconocido" })
            insert.setString(4, ActivityActor.clean(actor.display).ifEmpty { "Desconocido" })
            insert.setString(5, actor.agentName?.let(ActivityActor::clean))
            insert.setString(6, actor.realKind)
            insert.setUuid(7, actor.realUserId)
            insert.setString(8, actor.realName?.let(ActivityActor::clean))
            insert.setString(9, clean.moduleId)
            insert.setString(10, clean.entityType)
            insert.setString(11, clean.entityId)
            insert.setString(12, clean.entityLabel)
            insert.setString(13, clean.action)
            insert.setString(14, clean.summary)
            insert.setString(15, changesJson(clean.changes).toString())
            insert.setString(16, clean.origin ?: actor.origin)
            insert.executeQuery().use { rows -> rows.next(); rows.getLong(1) }
        }
    }

    private fun normalized(entry: ActivityEntry): ActivityEntry = entry.copy(
        entityType = ActivityText.clean(entry.entityType).take(100).ifEmpty { "registro" },
        entityId = entry.entityId?.let { ActivityText.clean(it).take(200) },
        entityLabel = ActivityChanges.cut(ActivityText.clean(entry.entityLabel)),
        summary = ActivityChanges.cut(ActivityText.clean(entry.summary), MaxSummaryLength).ifEmpty { entry.action },
        changes = entry.changes.take(MaxChanges).map { change ->
            if (change.secret || ActivityChanges.looksSecret(change.field)) ActivityChange.secret(clip(change.field), clip(change.label))
            else change.copy(
                field = clip(change.field),
                label = clip(change.label),
                old = change.old?.let { ActivityChanges.cut(ActivityText.clean(it, keepNewlines = true)) },
                new = change.new?.let { ActivityChanges.cut(ActivityText.clean(it, keepNewlines = true)) },
            )
        },
        origin = entry.origin?.let { ActivityText.clean(it).take(32) },
    )

    private fun clip(text: String) = ActivityText.clean(text).take(100)

    fun changesJson(changes: List<ActivityChange>): JsonArray = JsonArray(changes.map { change ->
        JsonObject(buildMap {
            put("field", JsonPrimitive(change.field))
            put("label", JsonPrimitive(change.label))
            put("old", change.old?.let(::JsonPrimitive) ?: JsonNull)
            put("new", change.new?.let(::JsonPrimitive) ?: JsonNull)
            if (change.secret) put("secret", JsonPrimitive(true))
        })
    })

    private fun java.sql.PreparedStatement.setUuid(index: Int, value: UUID?) {
        if (value == null) setNull(index, Types.OTHER) else setObject(index, value)
    }
}
