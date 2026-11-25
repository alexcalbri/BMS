package com.ideasdeveloper.bms.server.outbound.monday

import com.ideasdeveloper.bms.server.outbound.OutboundDelivery
import com.ideasdeveloper.bms.server.outbound.OutboundEvent
import com.ideasdeveloper.bms.server.outbound.OutboundEventKind
import com.ideasdeveloper.bms.server.outbound.OutboundLink
import com.ideasdeveloper.bms.server.outbound.OutboundOrigin
import com.ideasdeveloper.bms.server.outbound.OutboundPerson
import com.ideasdeveloper.bms.server.outbound.OutboundRecord
import com.ideasdeveloper.bms.server.outbound.OutboundResult
import com.ideasdeveloper.bms.server.outbound.OutboundSink
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.security.MessageDigest
import java.sql.Connection
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Envia los avisos de un modulo a un tablero de monday. Cada extension `<id>_monday` lo extiende y solo pone [token].
 * Todo va por variables GraphQL; los unicos valores dentro de una consulta son ids ya validados como digitos
 * (la subida de archivos de monday no admite variables para ellos).
 */
abstract class MondayOutboundSink(
    override val sinkId: String,
    override val moduleId: String,
    val config: MondayOutboundConfigStore,
    val api: MondayApi = HttpMondayApi(),
    private val clock: () -> Instant = { Instant.now() },
    private val maxCachedUsers: Int = MaxCachedUsers,
) : OutboundSink {
    /** Token de monday de la empresa; null si no lo guardo. */
    abstract fun token(tenant: Connection): String?

    companion object {
        const val Source = "monday"
        const val KeyPrefix = "monday:"
        private val digits = Regex("^[0-9]{1,20}$")
        private val columnId = Regex("^[A-Za-z0-9_]{1,100}$")
        private val defaultWait = Duration.ofSeconds(60)
        private val userCacheTtl = Duration.ofHours(1)
        private val missingUserTtl = Duration.ofMinutes(10)
        const val MaxCachedUsers = 5000
        private const val MaxName = 255

        fun escapeHtml(text: String): String = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

        fun noteBody(author: OutboundPerson?, text: String, mentions: List<OutboundPerson>): String {
            val lines = text.replace("\r\n", "\n").split("\n").joinToString("<br>") { escapeHtml(it) }
            val extra = mentions.map { "@" + it.name }.filter { !text.contains(it) }
            val head = author?.name?.takeIf { it.isNotBlank() }?.let { "<b>${escapeHtml(it)}</b>:<br>" }.orEmpty()
            return head + lines + if (extra.isEmpty()) "" else "<br>" + escapeHtml(extra.joinToString(" "))
        }
    }

    // Id de usuario (null = se busco y no existe) y hasta cuando vale.
    private val users = ConcurrentHashMap<String, Pair<Long?, Instant>>()

    /** El elemento ya se creo en monday pero no se pudo guardar el enlace (ni al reintentar). */
    private class LinkNotSavedException(val itemId: String) : Exception()

    private fun obj(vararg pairs: Pair<String, JsonElement>) = JsonObject(pairs.toMap())

    override fun wants(tenant: Connection, event: OutboundEvent): Boolean {
        val cfg = config.get(tenant, event.recordType) ?: return false
        if (!cfg.enabled || cfg.boardId.isNullOrEmpty()) return false
        if (OutboundOrigin.isImport(event.origin)) {
            if (event.origin == OutboundOrigin.import(Source) || !cfg.sendImports) return false
        }
        return kindEnabled(cfg, event.kind)
    }

    private fun kindEnabled(cfg: MondayOutboundConfig, kind: OutboundEventKind) = when (kind) {
        OutboundEventKind.Created -> cfg.sendCreated
        OutboundEventKind.Updated -> cfg.sendUpdated
        OutboundEventKind.Note -> cfg.sendNotes
        OutboundEventKind.File -> cfg.sendFiles
    }

    override fun deliver(delivery: OutboundDelivery): OutboundResult {
        val event = delivery.event
        val cfg = config.get(delivery.tenant, event.recordType)
        val board = cfg?.boardId
        if (cfg == null || !cfg.enabled || board.isNullOrEmpty() || !kindEnabled(cfg, event.kind)) {
            return OutboundResult.Skipped("El envio a monday esta apagado para este aviso.")
        }
        if (!board.matches(digits)) return OutboundResult.Failed("El tablero configurado no es valido.")
        val token = token(delivery.tenant)?.trim()?.takeIf { it.isNotEmpty() } ?: return OutboundResult.Failed("Falta el token de monday")
        val warnings = mutableListOf<String>()
        return try {
            when (event.kind) {
                OutboundEventKind.Created, OutboundEventKind.Updated -> sendRecord(delivery, cfg, board, token, warnings)
                OutboundEventKind.Note -> sendNote(delivery, cfg, board, token, warnings)
                OutboundEventKind.File -> sendFile(delivery, cfg, board, token, warnings)
            }
        } catch (failure: LinkNotSavedException) {
            OutboundResult.Retry("Se creo el elemento ${failure.itemId} en monday pero no se guardo el enlace")
        } catch (failure: MondayApiException) {
            val message = failure.message.orEmpty().replace(token, "***")
            when (failure.kind) {
                MondayErrorKind.Throttled -> OutboundResult.Throttled(failure.retryAfter ?: defaultWait, message)
                MondayErrorKind.Auth -> OutboundResult.Failed(message.ifEmpty { "monday rechazo el token" })
                MondayErrorKind.Transient -> OutboundResult.Retry(message, failure.retryAfter)
                MondayErrorKind.Permanent -> OutboundResult.Failed(message)
            }
        }
    }

    // ---- Elementos ----

    private fun query(token: String, query: String, vararg variables: Pair<String, JsonElement>) = api.call(token, query, JsonObject(variables.toMap()))

    /** El elemento del registro en monday sin crearlo: enlace guardado, clave de importacion o (tras un corte) la columna clave. */
    private fun findItem(delivery: OutboundDelivery, cfg: MondayOutboundConfig, board: String, token: String, record: OutboundRecord): String? {
        val event = delivery.event
        delivery.links.get(delivery.tenant, sinkId, event.recordType, event.recordId)?.externalId?.takeIf { it.matches(digits) }?.let { return it }
        val imported = record.importKey?.takeIf { it.startsWith(KeyPrefix) }?.removePrefix(KeyPrefix)?.takeIf { it.matches(digits) }
        if (imported != null) {
            val data = query(token, "query (\$ids: [ID!]) { items(ids: \$ids) { id board { id } } }", "ids" to JsonArray(listOf(JsonPrimitive(imported))))
            data["items"].asArr().firstOrNull().asObj()?.let { item ->
                val id = item["id"].asStr().takeIf { it.matches(digits) }
                if (id != null) {
                    delivery.links.put(delivery.tenant, sinkId, event.recordType, event.recordId, OutboundLink(id, item["board"].asObj()?.get("id").asStr().ifEmpty { board }))
                    return id
                }
            }
        }
        val key = cfg.keyColumn?.takeIf { it.matches(columnId) }
        // Antes de crear, siempre se busca por la clave: asi un corte entre crear y guardar el enlace no duplica el elemento.
        if (key != null) {
            val data = query(
                token,
                "query (\$board: ID!, \$col: String!, \$val: String!) { items_page_by_column_values(board_id: \$board, limit: 1, columns: [{column_id: \$col, column_values: [\$val]}]) { items { id } } }",
                "board" to JsonPrimitive(board), "col" to JsonPrimitive(key), "val" to JsonPrimitive(record.id),
            )
            data["items_page_by_column_values"].asObj()?.get("items").asArr().firstOrNull().asObj()?.get("id").asStr().takeIf { it.matches(digits) }?.let { id ->
                delivery.links.put(delivery.tenant, sinkId, event.recordType, event.recordId, OutboundLink(id, board))
                return id
            }
        }
        return null
    }

    private fun createItem(delivery: OutboundDelivery, cfg: MondayOutboundConfig, board: String, token: String, record: OutboundRecord, warnings: MutableList<String>): String {
        val event = delivery.event
        val values = columnValues(delivery, cfg, token, record, record.values.keys, warnings, clearMissing = false).toMutableMap()
        cfg.keyColumn?.takeIf { it.matches(columnId) && it !in values }?.let { values[it] = JsonPrimitive(record.id) }
        val data = query(
            token,
            "mutation (\$board: ID!, \$group: String, \$name: String!, \$values: JSON!) { create_item(board_id: \$board, group_id: \$group, item_name: \$name, column_values: \$values, create_labels_if_missing: true) { id } }",
            "board" to JsonPrimitive(board),
            "group" to (cfg.groupId?.takeIf { it.isNotBlank() }?.let { JsonPrimitive(it) } ?: kotlinx.serialization.json.JsonNull),
            "name" to JsonPrimitive(record.title.ifBlank { "(sin nombre)" }.take(MaxName)),
            "values" to JsonPrimitive(JsonObject(values).toString()),
        )
        val id = data["create_item"].asObj()?.get("id").asStr().takeIf { it.matches(digits) }
            ?: throw MondayApiException(MondayErrorKind.Permanent, "monday no devolvio el id del elemento creado.")
        // Si guardar el enlace falla se reintenta una vez; si sigue fallando, el aviso se reintenta y la columna clave encuentra el elemento.
        fun persist() {
            delivery.links.put(delivery.tenant, sinkId, event.recordType, event.recordId, OutboundLink(id, board))
            delivery.source.linked(delivery.tenant, event.recordType, event.recordId, KeyPrefix + id)
        }
        try {
            persist()
        } catch (first: Exception) {
            try {
                persist()
            } catch (second: Exception) {
                throw LinkNotSavedException(id)
            }
        }
        return id
    }

    private fun sendRecord(delivery: OutboundDelivery, cfg: MondayOutboundConfig, board: String, token: String, warnings: MutableList<String>): OutboundResult {
        val event = delivery.event
        val record = delivery.source.record(delivery.tenant, event.recordType, event.recordId) ?: return OutboundResult.Skipped("El registro ya no existe.")
        val item = findItem(delivery, cfg, board, token, record)
            ?: return createItem(delivery, cfg, board, token, record, warnings).let { OutboundResult.Sent("Elemento $it creado en monday", warnings) }
        val keys = if (event.kind == OutboundEventKind.Updated && event.changed.isNotEmpty()) event.changed.toSet() else record.values.keys + cfg.columns.keys
        val values = columnValues(delivery, cfg, token, record, keys, warnings, clearMissing = event.kind == OutboundEventKind.Updated && event.changed.isNotEmpty())
        if (values.isEmpty()) {
            val unmapped = keys.filter { it !in cfg.columns }
            if (unmapped.isNotEmpty()) warnings += "Sin columnas que actualizar: ${unmapped.joinToString(", ")} no tienen columna en monday"
            return OutboundResult.Sent("Elemento $item sin columnas que actualizar", warnings)
        }
        query(
            token,
            "mutation (\$board: ID!, \$item: ID!, \$values: JSON!) { change_multiple_column_values(board_id: \$board, item_id: \$item, column_values: \$values, create_labels_if_missing: true) { id } }",
            "board" to JsonPrimitive(board), "item" to JsonPrimitive(item), "values" to JsonPrimitive(JsonObject(values).toString()),
        )
        return OutboundResult.Sent("Elemento $item actualizado (${values.size} columna(s))", warnings)
    }

    /** Columnas a escribir para los campos [keys]; un campo sin columna o con valor imposible se omite con aviso. */
    private fun columnValues(
        delivery: OutboundDelivery, cfg: MondayOutboundConfig, token: String, record: OutboundRecord,
        keys: Collection<String>, warnings: MutableList<String>, clearMissing: Boolean,
    ): Map<String, JsonElement> {
        val labels = delivery.source.recordTypes(delivery.tenant).firstOrNull { it.id == record.type }?.fields.orEmpty().associate { it.key to it.label }
        val out = linkedMapOf<String, JsonElement>()
        keys.forEach { key ->
            val label = labels[key] ?: key
            val column = cfg.columns[key]
            val value = record.values[key]
            if (value == null) {
                // Campo vaciado: JSON null borra la columna en monday.
                if (clearMissing && column != null) out[column.id] = JsonNull
                return@forEach
            }
            if (column == null) {
                warnings += "El campo \"$label\" no tiene columna en monday"
                return@forEach
            }
            val result = MondayColumnValues.value(column.type, value, { persons -> resolveUsers(token, persons) }, cfg.phoneCountry)
            result.warning?.let { warnings += "\"$label\": $it" }
            result.json?.let { out[column.id] = it }
        }
        return out
    }

    // ---- Notas ----

    private fun sendNote(delivery: OutboundDelivery, cfg: MondayOutboundConfig, board: String, token: String, warnings: MutableList<String>): OutboundResult {
        val event = delivery.event
        val text = event.text?.takeIf { it.isNotBlank() } ?: return OutboundResult.Skipped("La nota esta vacia.")
        val record = delivery.source.record(delivery.tenant, event.recordType, event.recordId) ?: return OutboundResult.Skipped("El registro ya no existe.")
        val item = findItem(delivery, cfg, board, token, record) ?: createItem(delivery, cfg, board, token, record, warnings)
        val ids = resolveUsers(token, event.mentions)
        val byEmail = resolvedByEmail(token, event.mentions)
        event.mentions.filter { it.email == null || it.email.trim().lowercase() !in byEmail }.forEach { warnings += "No se encontro en monday a ${it.name}: queda como texto \"@${it.name}\"" }
        val mentions = JsonArray(ids.distinct().map { obj("id" to JsonPrimitive(it), "type" to JsonPrimitive("User")) })
        val updateId = query(
            token,
            "mutation (\$item: ID!, \$body: String!, \$mentions: [UpdateMention]) { create_update(item_id: \$item, body: \$body, mentions_list: \$mentions) { id } }",
            "item" to JsonPrimitive(item), "body" to JsonPrimitive(noteBody(event.author, text, event.mentions)), "mentions" to mentions,
        )["create_update"].asObj()?.get("id").asStr().takeIf { it.matches(digits) }
            ?: throw MondayApiException(MondayErrorKind.Permanent, "monday no devolvio el id de la actualizacion.")
        return OutboundResult.Sent("Nota enviada al elemento $item (actualizacion $updateId)", warnings)
    }

    // ---- Archivos ----

    private fun sendFile(delivery: OutboundDelivery, cfg: MondayOutboundConfig, board: String, token: String, warnings: MutableList<String>): OutboundResult {
        val event = delivery.event
        val limit = cfg.maxFileMb.toLong() * 1024 * 1024
        val tooBig = OutboundResult.Failed("El archivo supera ${cfg.maxFileMb} MB")
        if ((event.fileSize ?: 0) > limit) return tooBig
        val files = delivery.files ?: return OutboundResult.Retry("Los archivos del modulo aun no estan disponibles.")
        val fileId = event.fileId ?: return OutboundResult.Skipped("El aviso no trae archivo.")
        val file = delivery.source.file(delivery.tenant, files, event.recordType, event.recordId, fileId) ?: return OutboundResult.Skipped("El archivo ya no existe.")
        if (file.size > limit) return tooBig
        val record = delivery.source.record(delivery.tenant, event.recordType, event.recordId) ?: return OutboundResult.Skipped("El registro ya no existe.")
        val item = findItem(delivery, cfg, board, token, record) ?: createItem(delivery, cfg, board, token, record, warnings)
        val column = cfg.filesColumn?.takeIf { it.isNotBlank() }
        if (column != null) {
            if (!column.matches(columnId)) return OutboundResult.Failed("La columna de archivos configurada no es valida.")
            api.upload(token, "mutation (\$file: File!) { add_file_to_column(item_id: $item, column_id: \"$column\", file: \$file) { id } }", file = file, maxBytes = limit)
            return OutboundResult.Sent("Archivo ${file.name} subido a la columna de archivos", warnings)
        }
        val update = query(
            token,
            "mutation (\$item: ID!, \$body: String!) { create_update(item_id: \$item, body: \$body) { id } }",
            "item" to JsonPrimitive(item), "body" to JsonPrimitive("Archivo: " + escapeHtml(file.name)),
        )["create_update"].asObj()?.get("id").asStr().takeIf { it.matches(digits) }
            ?: throw MondayApiException(MondayErrorKind.Permanent, "monday no devolvio el id de la actualizacion.")
        api.upload(token, "mutation (\$file: File!) { add_file_to_update(update_id: $update, file: \$file) { id } }", file = file, maxBytes = limit)
        return OutboundResult.Sent("Archivo ${file.name} adjuntado a una actualizacion", warnings)
    }

    // ---- Usuarios de monday por correo ----

    private fun tokenHash(token: String): String =
        MessageDigest.getInstance("SHA-256").digest(token.toByteArray()).joinToString("") { "%02x".format(it) }

    /** Entradas guardadas en la cache de usuarios (para las pruebas). */
    fun cachedUserCount(): Int = users.size

    /** Acota la cache: al pasarse del maximo borra las caducadas y, si sigue llena, las que caducan antes (las mas viejas). */
    private fun evictUsers(now: Instant) {
        if (users.size <= maxCachedUsers) return
        users.entries.removeIf { !it.value.second.isAfter(now) }
        val excess = users.size - maxCachedUsers
        if (excess > 0) users.entries.sortedBy { it.value.second }.take(excess).forEach { users.remove(it.key) }
    }

    /** Ids de usuario de monday de las personas con correo conocido; cache por token de 1 hora. */
    private fun resolveUsers(token: String, persons: List<OutboundPerson>): List<Long> = resolvedByEmail(token, persons).values.toList()

    private fun resolvedByEmail(token: String, persons: List<OutboundPerson>): Map<String, Long> {
        val now = clock()
        val hash = tokenHash(token)
        val emails = persons.mapNotNull { it.email?.trim()?.lowercase()?.takeIf { e -> e.isNotEmpty() } }.distinct()
        val found = linkedMapOf<String, Long>()
        val missing = mutableListOf<String>()
        emails.forEach { email ->
            val cached = users["$hash:$email"]?.takeIf { it.second.isAfter(now) }
            when {
                cached == null -> missing += email
                cached.first != null -> found[email] = cached.first!!
                // Buscado hace poco y no existe: no se vuelve a preguntar hasta que caduque.
                else -> Unit
            }
        }
        if (missing.isNotEmpty()) {
            val data = query(token, "query (\$emails: [String!]) { users(emails: \$emails) { id email } }", "emails" to JsonArray(missing.map(::JsonPrimitive)))
            data["users"].asArr().mapNotNull { it.asObj() }.forEach { user ->
                val id = user["id"].asStr().toLongOrNull() ?: return@forEach
                val email = user["email"].asStr().lowercase()
                if (email in missing) {
                    found[email] = id
                    users["$hash:$email"] = id to now.plus(userCacheTtl)
                }
            }
            missing.filter { it !in found }.forEach { users["$hash:$it"] = null to now.plus(missingUserTtl) }
            evictUsers(now)
        }
        return found
    }
}
