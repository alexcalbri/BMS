package com.ideasdeveloper.bms.modules.clientes

import com.ideasdeveloper.bms.server.i18n.tr
import com.ideasdeveloper.bms.server.modules.CustomerTimeline
import com.ideasdeveloper.bms.server.modules.CustomerTimelineEntry
import com.ideasdeveloper.bms.server.modules.ModuleAccessService
import com.ideasdeveloper.bms.server.modules.ModulePermissions
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.sql.Connection
import java.time.OffsetDateTime
import java.util.UUID

/** Who created, edited or archived each customer and what changed (customer_activity). */
object ClientesActivity {
    // Who made a change: the user's name, plus the agent when an AI agent made it.
    fun author(tenant: Connection, permissions: ModulePermissions, access: ModuleAccessService, agentName: String? = null): Pair<UUID?, String> {
        val identity = access.userIdentity(tenant, permissions)
        val name = identity?.name ?: if (permissions.serverOwner) "Dueno del servidor" else "Usuario"
        val shown = (if (permissions.serverOwner) "$name (dueno del servidor)" else name) + (agentName?.let { " (agente $it)" } ?: "")
        // Server owners are central users: their id does not exist in this company's users.
        return permissions.userId?.takeUnless { permissions.serverOwner } to shown
    }

    // Kinds that belong to one customer and kinds of the company (customer_id NULL); V008 checks them.
    val customerKinds = setOf("created", "updated", "archived", "restored", "note_deleted", "history_deleted", "merged", "separated", "user_linked", "user_unlinked")
    val companyKinds = setOf("field_created", "field_deleted", "linked", "unlinked")

    /** Title shown for an activity kind in the customer's history and in "Registro de Clientes". */
    fun title(kind: String): String = when (kind) {
        "created" -> tr("Cliente creado")
        "archived" -> tr("Cliente archivado")
        "restored" -> tr("Cliente restaurado")
        "note_deleted" -> tr("Nota borrada")
        "history_deleted" -> tr("Entrada del historial borrada")
        "merged" -> tr("Cliente fusionado")
        "separated" -> tr("Clientes separados")
        "field_created" -> tr("Campo personalizado creado")
        "field_deleted" -> tr("Campo personalizado borrado")
        "linked" -> tr("Empresa vinculada")
        "unlinked" -> tr("Empresa desvinculada")
        "user_linked" -> tr("Cuenta de usuario enlazada")
        "user_unlinked" -> tr("Cuenta de usuario desenlazada")
        else -> tr("Datos del cliente modificados")
    }

    /** Records an event of one customer. */
    fun record(tenant: Connection, customerId: UUID, kind: String, body: String, author: Pair<UUID?, String>) =
        insert(tenant, customerId, kind, body, author)

    /** Records an event of the company, not of one customer (custom field created or deleted, company linked or unlinked). */
    fun recordCompany(tenant: Connection, kind: String, body: String, author: Pair<UUID?, String>) =
        insert(tenant, null, kind, body, author)

    private fun insert(tenant: Connection, customerId: UUID?, kind: String, body: String, author: Pair<UUID?, String>) {
        tenant.prepareStatement(
            "INSERT INTO customer_activity (id, customer_id, kind, body, user_id, author) VALUES (?, ?, ?, ?, ?, ?)"
        ).use { insert ->
            insert.setObject(1, UUID.randomUUID())
            insert.setObject(2, customerId)
            insert.setString(3, kind)
            insert.setString(4, body.take(5_000))
            insert.setObject(5, author.first)
            insert.setString(6, author.second)
            insert.executeUpdate()
        }
    }

    class CompanyEvent(val at: OffsetDateTime, val kind: String, val body: String, val author: String)

    /** Events of the company (customer_id NULL), newest first, for "Registro de Clientes". */
    fun companyEvents(tenant: Connection, limit: Int): List<CompanyEvent> = tenant.prepareStatement(
        "SELECT kind, body, author, created_at FROM customer_activity WHERE customer_id IS NULL ORDER BY created_at DESC LIMIT ?"
    ).use { query ->
        query.setInt(1, limit)
        query.executeQuery().use { rows ->
            buildList {
                while (rows.next()) {
                    add(CompanyEvent(rows.getObject("created_at", OffsetDateTime::class.java), rows.getString("kind"), rows.getString("body"), rows.getString("author")))
                }
            }
        }
    }

    /** "Campo: antes -> despues" for each changed value, or null when nothing changed. */
    fun changes(before: CustomerResponse, after: CreateCustomerRequest, fieldLabels: Map<String, String>): String? {
        fun shown(value: String) = value.ifBlank { "(vacio)" }
        val lines = buildList {
            if (before.displayName != after.displayName.trim()) add("Nombre: ${shown(before.displayName)} -> ${shown(after.displayName.trim())}")
            if (before.primaryEmail != after.primaryEmail.trim()) add("Correo: ${shown(before.primaryEmail)} -> ${shown(after.primaryEmail.trim())}")
            if (before.primaryPhone != after.primaryPhone.trim()) add("Telefono: ${shown(before.primaryPhone)} -> ${shown(after.primaryPhone.trim())}")
            (before.dynamicFields.keys + after.dynamicFields.keys).sorted().forEach { key ->
                val old = text(before.dynamicFields[key])
                val new = text(after.dynamicFields[key])
                if (old != new) add("${fieldLabels[key] ?: key}: ${shown(old)} -> ${shown(new)}")
            }
        }
        return lines.takeIf { it.isNotEmpty() }?.joinToString("\n")
    }

    private fun text(value: JsonElement?): String = when (value) {
        null, JsonNull -> ""
        is JsonPrimitive -> value.contentOrNull.orEmpty()
        else -> value.toString()
    }

    // Clientes' own part of the customer's history, like any other module's (CustomerTimeline): the
    // changes of the record and what the customer bought or hired in this company (customer_history,
    // written by modules or by hand), each with its link to the record that has the details.
    val timeline = CustomerTimeline(permission = "clientes.view") { query ->
        val recorded = if (ClientesSharing.supportsSharing(query.tenant)) {
            // Rows written by other modules show only while that module is on and the user may see its events:
            // the visible set is computed once (a few distinct module ids) and the filter and the limit run in SQL.
            val visible = query.access.visibleCustomerEventModules(query.tenant, query.permissions, ClientesProfile.historyModules(query.tenant, query.customerId))
            ClientesProfile.history(query.tenant, query.customerId, visible, query.limit)
                .map { row ->
                CustomerTimelineEntry(row.at.toInstant(), row.kind, row.title, row.detail, row.author).apply { link = row.link }
            }
        } else {
            emptyList()
        }
        recorded + query.tenant.prepareStatement(
            "SELECT kind, body, author, created_at FROM customer_activity WHERE customer_id = ? ORDER BY created_at DESC LIMIT ?"
        ).use { statement ->
            statement.setObject(1, query.customerId)
            statement.setInt(2, query.limit)
            statement.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) {
                        val kind = rows.getString("kind")
                        add(
                            CustomerTimelineEntry(
                                at = rows.getObject("created_at", OffsetDateTime::class.java).toInstant(),
                                kind = kind,
                                title = title(kind),
                                detail = rows.getString("body"),
                                author = rows.getString("author"),
                            )
                        )
                    }
                }
            }
        }
    }
}
