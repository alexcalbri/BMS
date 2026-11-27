package com.ideasdeveloper.bms.server.activity

import kotlinx.serialization.json.Json
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
        agentName = ActivityNames.neutralPerson(clean(agentName)).ifEmpty { "Agente" },
        origin = Origins.Agent,
    ).rebuilt()

    /** The same actor with [display] as every screen rebuilds it from the structured fields ([ActivityDisplay]). */
    fun rebuilt(): ActivityActor = copy(display = ActivityDisplay.of(kind, name, agentName, realKind, realName).display)

    /** The same actor with another origin (an api route, a webhook...). */
    fun withOrigin(origin: String): ActivityActor = copy(origin = origin)

    /**
     * This actor as a compact JSON text a background job can keep (a nullable text/jsonb column) to rebuild it later
     * with [restore] / [restoreImport]: kind, user id, name, display, agent name and real actor. No secrets are in it.
     */
    fun toStoredJson(): String = JsonObject(buildMap {
        put("kind", JsonPrimitive(kind))
        userId?.let { put("userId", JsonPrimitive(it.toString())) }
        put("name", JsonPrimitive(name))
        put("display", JsonPrimitive(display))
        agentName?.let { put("agentName", JsonPrimitive(it)) }
        realKind?.let { put("realKind", JsonPrimitive(it)) }
        realUserId?.let { put("realUserId", JsonPrimitive(it.toString())) }
        realName?.let { put("realName", JsonPrimitive(it)) }
        origin?.let { put("origin", JsonPrimitive(it)) }
    }).toString()

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
                Kinds.BusinessOwner, ownerUserId, clean(ownerName).ifEmpty { "Business owner" }, "",
                realKind = realKind, realUserId = realUserId, realName = real, origin = Origins.App,
            ).rebuilt()
        }

        /**
         * A business owner's AI agent acting in another company of its owner group: the row keeps
         * [target] (that company's business owner, whose rights it used) as user, and [origin] (the
         * real person, of [originCompany]) as real actor: "Agente de Ana Ruiz (empresa origen)".
         */
        fun groupAgent(target: ActivityActor, origin: ActivityActor, originCompany: String, agentName: String): ActivityActor = ActivityActor(
            target.kind, target.userId, target.name, "",
            agentName = ActivityNames.neutralPerson(clean(agentName)).ifEmpty { "Agente" },
            realKind = origin.realKind ?: origin.kind,
            realUserId = origin.realUserId ?: origin.userId,
            realName = "${origin.realName ?: origin.name} (empresa ${clean(originCompany)})",
            origin = Origins.Agent,
        ).rebuilt()

        /** Automation [name]; [startedBy] when a person started it (a manual button). */
        fun automation(name: String, startedBy: ActivityActor? = null): ActivityActor =
            started(Kinds.Automation, clean(name).ifEmpty { "Automatizacion" }, startedBy, Origins.Automation)

        /** A scheduled job of the server or a module. */
        fun job(name: String): ActivityActor = ActivityActor(Kinds.Job, null, clean(name).ifEmpty { "Tarea" }, "", origin = Origins.Automation).rebuilt()

        /** An import started by [startedBy]: "Importacion (Ana Ruiz)". An import given again stays that import. */
        fun import(startedBy: ActivityActor?): ActivityActor =
            if (startedBy?.kind == Kinds.Import) startedBy.rebuilt() else started(Kinds.Import, "Importacion", startedBy, Origins.Import)

        /**
         * Kept only so callers not migrated yet compile: it returns the same as [restoreImportOrNull] (always an import,
         * never the stored person itself). Use [restoreImport] or [restoreImportOrNull].
         */
        @Deprecated(
            "A stored actor is only ever the starter of an import: use restoreImport / restoreImportOrNull, which always wrap it.",
            ReplaceWith("ActivityActor.restoreImportOrNull(stored)"),
        )
        fun restore(stored: String?): ActivityActor? = restoreImportOrNull(stored)

        /**
         * The import started by the actor stored with [toStoredJson]; null when [stored] is null, blank or not one of
         * ours (an old job that kept nothing), so the caller can fall back to what it knows. Validated like [restoreImport].
         */
        fun restoreImportOrNull(stored: String?): ActivityActor? = parseStored(stored)?.let(::import)

        /**
         * The import started by the actor stored with [toStoredJson], or by what [fallback] gives (an old job that
         * kept only a user id) when nothing usable was stored. Same rows as when it started: "Importacion (Agente de Ana)".
         * This is the way to use a stored actor: whatever the stored text says, the result is an import.
         */
        fun restoreImport(stored: String?, fallback: () -> ActivityActor? = { null }): ActivityActor = import(parseStored(stored) ?: fallback())

        /**
         * Rebuilds a stored actor, refusing anything that is not exactly what [toStoredJson] writes: known kinds and
         * origins only, texts cut to [MaxText], names neutralized, and the display rebuilt from those fields.
         */
        private fun parseStored(stored: String?): ActivityActor? {
            if (stored.isNullOrBlank() || stored.length > 4_000) return null
            return try {
                val json = Json.parseToJsonElement(stored) as? JsonObject ?: return null
                fun text(key: String): String? = (json[key] as? JsonPrimitive)?.takeIf { it !is JsonNull && it.isString }?.content
                fun id(key: String): UUID? = text(key)?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                val kind = text("kind")?.takeIf { it in Kinds.all } ?: return null
                val realKind = text("realKind")
                if (realKind != null && realKind !in Kinds.all) return null
                val origin = text("origin")
                if (origin != null && origin !in Origins.all) return null
                val name = text("name")?.let(::clean)?.takeIf { it.isNotEmpty() } ?: return null
                val agent = text("agentName")?.let(::clean)?.takeIf { it.isNotEmpty() }?.let(ActivityNames::neutral)
                val realName = text("realName")?.let(::clean)?.takeIf { it.isNotEmpty() }
                val display = ActivityDisplay.of(kind, name, agent, realKind, realName).display
                ActivityActor(kind, id("userId"), name, display, agent, realKind, id("realUserId"), realName, origin)
            } catch (_: Exception) {
                null
            }
        }

        /** An inbound integration ("Cloudbeds", "Sitio web"...), e.g. a webhook. */
        fun integration(name: String): ActivityActor = ActivityActor(Kinds.Integration, null, clean(name).ifEmpty { "Integracion" }, "", origin = Origins.Webhook).rebuilt()

        /**
         * Who an audited server-side change was made by ([com.ideasdeveloper.bms.server.admins.AuditActor]), for the
         * company's own log: the server owner or administrator "(desde el servidor)" with their central id,
         * a business owner by login, or the system.
         */
        fun fromAudit(audit: com.ideasdeveloper.bms.server.admins.AuditActor, name: String? = null): ActivityActor {
            val who = clean(name ?: audit.login).ifEmpty { audit.login }
            return when (audit.kind) {
                Kinds.ServerOwner, Kinds.ServerAdmin -> ActivityActor(audit.kind, audit.userId, who, "", origin = Origins.App).rebuilt()
                Kinds.BusinessOwner -> ActivityActor(Kinds.BusinessOwner, audit.userId, who, "", origin = Origins.App).rebuilt()
                else -> system()
            }
        }

        /** The server itself (unknown sign-in attempts, background repairs). */
        fun system(): ActivityActor = ActivityActor(Kinds.System, null, "Sistema", "Sistema")

        private fun person(kind: String, userId: UUID?, name: String): ActivityActor {
            val clean = clean(name).ifEmpty { "Usuario" }
            return ActivityActor(kind, userId, clean, "", origin = Origins.App).rebuilt()
        }

        // The person who started it is kept as the user of the row, the display names both.
        private fun started(kind: String, name: String, startedBy: ActivityActor?, origin: String): ActivityActor =
            if (startedBy == null) ActivityActor(kind, null, name, "", origin = origin).rebuilt()
            else ActivityActor(
                kind, startedBy.userId, name, "",
                agentName = startedBy.agentName,
                realKind = startedBy.realKind ?: startedBy.kind.takeIf { it != Kinds.User && it != Kinds.BusinessOwner },
                realUserId = startedBy.realUserId,
                realName = startedBy.realName ?: startedBy.name,
                origin = origin,
            ).rebuilt()

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

        val all: Set<String> = setOf(User, BusinessOwner, ServerOwner, ServerAdmin, Automation, Job, Import, Integration, System)
        val server: Set<String> = setOf(ServerOwner, ServerAdmin)
        val people: Set<String> = setOf(User, BusinessOwner, ServerOwner, ServerAdmin)
    }

    object Origins {
        const val App = "app"
        const val Agent = "agent"
        const val Api = "api"
        const val Automation = "automation"
        const val Import = "import"
        const val Webhook = "webhook"

        val all: Set<String> = setOf(App, Agent, Api, Automation, Import, Webhook)
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
        fun of(field: String, label: String, old: Any?, new: Any?): ActivityChange {
            if (ActivityChanges.looksSecret(field)) return secret(field, label)
            val before = ActivityChanges.format(old)
            val after = ActivityChanges.format(new)
            return if (ActivityChanges.valueLooksSecret(before) || ActivityChanges.valueLooksSecret(after)) secret(field, label)
            else ActivityChange(field, label, before, after)
        }
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
    // Field names that hold a secret, anywhere in the name ("smtpPassword", "wifi_passphrase", "x_api_key"...).
    private val secretName = Regex(
        "(?i)(password|passwd|passphrase|passcode|contrasen|secret|token|api_?key|apikey|credential|private_?key|signing|signature|webhook|clave)",
    )
    // Name parts ("camelCase", "snake_case" or "kebab-case" words) that are a secret on their own: "pin", "otp", "psk"...
    private val secretWords = setOf("pin", "otp", "totp", "hotp", "psk", "wpa", "wpa2", "wpa3", "hash", "salt", "private", "pwd", "firma", "cookie", "dsn", "bearer", "mfa")
    // "key" alone, or after one of these ("licenseKey", "wifi_shared_key"); "fieldKey" or "keyColumn" name things, not secrets.
    private val keyQualifiers = setOf("api", "secret", "private", "access", "signing", "encryption", "master", "license", "auth", "client", "app",
        "webhook", "ssh", "wifi", "wpa", "psk", "shared", "pre", "pass", "crypto", "hmac", "jwt", "session", "sign")
    private val wordBoundary = Regex("[^A-Za-z0-9]+|(?<=[a-z0-9])(?=[A-Z])")

    // Values that carry a credential whatever their field is called: URLs with user:password@ or a token in the query.
    private val secretValue = Regex(
        "(?i)([a-z][a-z0-9+.-]*://[^/\\s:@]+:[^/\\s@]+@)|([?&](token|key|api_?key|secret|signature|sig|password|pass|auth|access_token|code)=[^&\\s]{4,})|" +
            "(-----BEGIN [A-Z ]*PRIVATE KEY-----)|(\\b(sk|pk|rk|ghp|gho|xox[abpr])[-_][A-Za-z0-9_-]{10,})|(\\beyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\.)",
    )

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
            field in secrets || looksSecret(field) || valueLooksSecret(old) || valueLooksSecret(new) -> ActivityChange.secret(field, label)
            else -> ActivityChange(field, label, old, new)
        }
    }

    /** Every field of [labels] present in [values] as a new value (a created record). */
    fun created(values: Map<String, Any?>, labels: Map<String, String>, secrets: Set<String> = emptySet()): List<ActivityChange> =
        diff(emptyMap(), values, labels, secrets)

    /** Every field of [labels] present in [values] as an old value (a deleted record's key data). */
    fun deleted(values: Map<String, Any?>, labels: Map<String, String>, secrets: Set<String> = emptySet()): List<ActivityChange> =
        diff(values, emptyMap(), labels, secrets)

    fun looksSecret(field: String): Boolean {
        if (secretName.containsMatchIn(field)) return true
        val words = field.split(wordBoundary).filter { it.isNotEmpty() }.map { it.lowercase() }
        if (words.any { it in secretWords }) return true
        return words.withIndex().any { (index, word) -> word == "key" && (words.size == 1 || words.getOrNull(index - 1) in keyQualifiers) }
    }

    /** A value that carries a credential (a URL with user:password@ or ?token=..., a private key, a known token format). */
    fun valueLooksSecret(value: String?): Boolean = value != null && secretValue.containsMatchIn(value)

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

/**
 * The markers the log itself adds to a name ("Agente de", "(desde el servidor)", "Importacion (",
 * "Automatizacion ", "Tarea programada ") and the names of the log's own actors ("Sistema", "Importacion",
 * "Automatizacion", the integrations "Cloudbeds", "Sitio web", "Portal WiFi", "monday"). A person, agent,
 * automation or board name that contains a marker, or a person or agent named like one of those actors, could
 * pass for something it is not, so names are checked with [hasMarker] (refused where users type them) and shown
 * through [neutral] / [neutralPerson] (quoted) everywhere else, including rows stored before the check. Accents,
 * width, invisible characters and Latin look-alikes from Cyrillic and Greek ("Аgente" with a Cyrillic А) are
 * folded away first.
 */
object ActivityNames {
    private val markers = listOf(
        Regex("(?U)agente\\s+de(\\s|$)"),
        Regex("(?U)desde\\s+el\\s+servidor"),
        Regex("(?U)importacion\\s*\\("),
        Regex("(?U)automatizacion(\\s|$)"),
        Regex("(?U)tarea\\s+programada(\\s|$)"),
    )
    // Display names of the log's own actors: a person or an agent may not be called exactly that.
    private val reserved = setOf("sistema", "importacion", "automatizacion", "tarea programada", "cloudbeds", "sitio web", "portal wifi", "monday")
    private val marks = Regex("\\p{M}+")
    // Zero-width and other format characters would let "Agen\u200bte de" slip through.
    private val invisible = Regex("[\\p{Cf}\\p{Cc}]+")
    private val separators = Regex("[^\\p{L}\\p{N}]+")

    // Cyrillic and Greek letters that look like Latin ones (Unicode confusables, the letters that matter here),
    // by their own case: "Н" looks like "H", "н" does not. Fullwidth and other compatibility forms go with NFKD.
    private val lookAlikes: Map<Char, Char> = buildMap {
        // Pairs "<look-alike><latin>", separated by spaces.
        fun add(pairs: String) = pairs.split(' ').forEach { pair -> put(pair[0], pair[1]) }
        add("Аa Вb Еe Ѕs Іi Јj Кk Мm Нh Оo Рp Сc Тt Уy Хx Ӏl Ԛq Ԝw Үy") // Cyrillic capitals
        add("аa еe ѕs іi јj оo рp сc уy хx һh ӏl ԁd ԛq ԝw ѵv үy") // Cyrillic small letters
        add("Αa Βb Εe Ζz Ηh Ιi Κk Μm Νn Οo Ρp Τt Υy Χx Ϲc Ϳj") // Greek capitals
        add("αa εe ιi κk νv οo ρp τt υu χx ϲc ϳj") // Greek small letters
        add("ıi ȷj łl øo đd ħh ŧt ƀb ɑa ɡg ɩi ʟl") // Latin letters without a decomposition
    }

    private fun folded(text: String): String {
        val decomposed = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFKD).replace(marks, "").replace(invisible, "")
        val latin = buildString(decomposed.length) { decomposed.forEach { append(lookAlikes[it] ?: it) } }
        // Look-alikes may carry marks of their own once mapped ("ё"); a second pass drops them.
        return java.text.Normalizer.normalize(latin, java.text.Normalizer.Form.NFKD).replace(marks, "").lowercase()
    }

    private fun containsMarker(name: String): Boolean = folded(name).let { text -> markers.any { it.containsMatchIn(text) } }

    /** Whether [name] is exactly ("Sistema", "Sitio web"...) the display name of one of the log's own actors. */
    fun isReserved(name: String): Boolean = folded(name).replace(separators, " ").trim() in reserved

    /**
     * Whether a person or agent may not be called [name]: it contains a marker the log adds itself, or is the name
     * of one of the log's own actors (accents, width, look-alike and invisible characters ignored).
     */
    fun hasMarker(name: String): Boolean = containsMarker(name) || isReserved(name)

    /** [name] as a display shows it: in quotes when it contains a marker, so it never reads as one. */
    fun neutral(name: String): String = if (containsMarker(name)) quoted(name) else name

    /** A person's or agent's [name] as a display shows it: also quoted when it is the name of one of the log's own actors. */
    fun neutralPerson(name: String): String = if (hasMarker(name)) quoted(name) else name

    private fun quoted(name: String) = "«" + name.replace("«", "").replace("»", "").trim() + "»"
}

/**
 * Who a row shows, rebuilt from its structured columns (kind, name, agent, real person), never from the
 * stored actor_display: a name typed by a user cannot turn into "Agente de ..." or "(desde el servidor)".
 */
data class ActivityDisplay(
    val display: String,
    // Spanish labels of what kind of actor it was ("Agente IA", "Desde el servidor", "Importacion"...), for CSV and agents.
    val kindLabel: String,
    val viaAgent: Boolean,
    val fromServer: Boolean,
) {
    companion object {
        fun of(kind: String, name: String, agentName: String?, realKind: String?, realName: String?): ActivityDisplay {
            val viaAgent = !agentName.isNullOrBlank()
            val serverKinds = ActivityActor.Kinds.server
            val fromServer = kind in serverKinds || realKind in serverKinds
            val cleanName = ActivityActor.clean(name).ifEmpty { "Desconocido" }
            val ownName = ActivityNames.neutral(cleanName)
            val real = realName?.let(ActivityActor::clean)?.takeIf { it.isNotEmpty() }?.let(ActivityNames::neutralPerson)
            // A person (or the person who started an import or automation), with the agent and server markers.
            fun person(who: String) = (if (viaAgent) "Agente de " else "") + who + (if (fromServer) " (desde el servidor)" else "")
            val starter = if (real != null || viaAgent) person(real ?: "Desconocido") else null
            val display = when (kind) {
                ActivityActor.Kinds.Automation -> "Automatizacion $ownName" + (starter?.let { " ($it)" } ?: "")
                ActivityActor.Kinds.Import -> "Importacion" + (starter?.let { " ($it)" } ?: "")
                ActivityActor.Kinds.Job -> "Tarea programada $ownName"
                ActivityActor.Kinds.Integration -> ownName
                ActivityActor.Kinds.System -> "Sistema"
                else -> person(if (realKind != null && real != null) real else ActivityNames.neutralPerson(cleanName))
            }
            val labels = buildList {
                when (kind) {
                    ActivityActor.Kinds.Automation -> add("Automatizacion")
                    ActivityActor.Kinds.Import -> add("Importacion")
                    ActivityActor.Kinds.Job -> add("Tarea programada")
                    ActivityActor.Kinds.Integration -> add("Integracion")
                    ActivityActor.Kinds.System -> add("Sistema")
                }
                if (viaAgent) add("Agente IA")
                if (fromServer) add("Desde el servidor")
            }
            return ActivityDisplay(display, labels.joinToString(", "), viaAgent, fromServer)
        }
    }
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

    /**
     * Runs [block] in the open transaction of [connection], or in a new one when it is in auto-commit, so a change and its
     * row are saved (or refused) together.
     */
    fun <T> atomically(connection: Connection, block: () -> T): T {
        if (!connection.autoCommit) return block()
        connection.autoCommit = false
        try {
            val result = block()
            connection.commit()
            return result
        } catch (failure: Throwable) {
            runCatching { connection.rollback() }
            throw failure
        } finally {
            connection.autoCommit = true
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
        // Schema-qualified: a search_path set on the database (its owner can) cannot divert the rows to another table.
        return connection.prepareStatement(
            """
            INSERT INTO public.activity_log (actor_kind, actor_user_id, actor_name, actor_display, agent_name, real_actor_kind,
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
            if (change.secret || ActivityChanges.looksSecret(change.field) || ActivityChanges.valueLooksSecret(change.old) ||
                ActivityChanges.valueLooksSecret(change.new)
            ) ActivityChange.secret(clip(change.field), clip(change.label))
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
