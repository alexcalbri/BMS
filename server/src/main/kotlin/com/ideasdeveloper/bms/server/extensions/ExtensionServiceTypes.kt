package com.ideasdeveloper.bms.server.extensions

import com.ideasdeveloper.bms.server.admins.AuditActor
import com.ideasdeveloper.bms.server.i18n.I18n
import com.ideasdeveloper.bms.server.i18n.tr
import com.ideasdeveloper.bms.server.i18n.trIn
import com.ideasdeveloper.bms.server.modules.NotificationText
import com.ideasdeveloper.bms.server.modules.ModuleAccessService
import com.ideasdeveloper.bms.server.modules.ModuleRegistry
import com.ideasdeveloper.bms.server.security.SecretBox
import com.ideasdeveloper.bms.server.security.SecretsUnavailableException
import java.sql.Connection
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory

/**
 * Tells a user about something in a company through the Core's notification center. Both overloads are
 * abstract on purpose: the builder one must render in the recipient's language (I18N.md §7), which only
 * the access-backed implementation ([from]) can resolve. `ExtensionNotifier { tenant, module, user, title, text -> }`
 * still compiles (see the factory below) for tests and simple uses; there the builder runs in [I18n.Source]
 * unless a `recipientLanguage` is given.
 */
interface ExtensionNotifier {
    fun notify(tenant: Connection, moduleId: String, userId: UUID, title: String, text: String)

    /** Like [notify], with title and text written by [build] in the recipient's language (I18N.md §7). */
    fun notify(tenant: Connection, moduleId: String, userId: UUID, build: () -> NotificationText)

    companion object {
        fun from(access: ModuleAccessService): ExtensionNotifier = object : ExtensionNotifier {
            override fun notify(tenant: Connection, moduleId: String, userId: UUID, title: String, text: String) {
                access.notificationsFor(moduleId).notify(tenant, userId, title, text)
            }

            override fun notify(tenant: Connection, moduleId: String, userId: UUID, build: () -> NotificationText) {
                access.notificationsFor(moduleId).notify(tenant, userId, build = build)
            }
        }
    }
}

/** Source-compatible replacement of the former SAM constructor: a notifier from a plain `send` function. */
fun ExtensionNotifier(
    recipientLanguage: (Connection, UUID) -> String = { _, _ -> I18n.Source },
    send: (tenant: Connection, moduleId: String, userId: UUID, title: String, text: String) -> Unit,
): ExtensionNotifier = object : ExtensionNotifier {
    override fun notify(tenant: Connection, moduleId: String, userId: UUID, title: String, text: String) =
        send(tenant, moduleId, userId, title, text)

    override fun notify(tenant: Connection, moduleId: String, userId: UUID, build: () -> NotificationText) {
        val written = I18n.withLanguage(recipientLanguage(tenant, userId)) { build() }
        send(tenant, moduleId, userId, written.title, written.text)
    }
}

/**
 * Runs the pending tenant migrations of an extension in a company (CompanyProvisioningService,
 * with the provisioning role: the runtime connection cannot create tables); idempotent.
 */
fun interface ExtensionMigrationRunner {
    fun run(companyCode: String, extension: ServerExtension)
}

/**
 * Who acts. [role] is the login of who acts (stored as installed_by); [audit] is who the audit log
 * names; [companyCode] is the company of a business owner. A server administrator has
 * [serverAdmin] set with the companies of their scope (null: all); [serverOwner] stays false for them.
 */
data class ExtensionActor(
    val role: String,
    val serverOwner: Boolean,
    val companyCode: String? = null,
    val audit: AuditActor = if (serverOwner) AuditActor.owner(role, null) else AuditActor.businessOwner(role),
    val serverAdmin: ServerAdminScope? = null,
    // Who acts for the company activity log; null: derived from [audit].
    val activity: com.ideasdeveloper.bms.server.activity.ActivityActor? = null,
) {
    /** The activity log actor: the session's person, or the server person "(desde el servidor)". */
    val activityActor: com.ideasdeveloper.bms.server.activity.ActivityActor
        get() = activity ?: com.ideasdeveloper.bms.server.activity.ActivityActor.fromAudit(audit)
}

/**
 * Company scope of a server administrator acting on extensions; [companies] null means every
 * company. The server workspace is never part of it.
 */
data class ServerAdminScope(val companies: Set<String>?) {
    fun includes(companyCode: String): Boolean =
        !com.ideasdeveloper.bms.server.admins.isServerWorkspace(companyCode) && (companies == null || companyCode in companies)
}
