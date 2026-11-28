package com.ideasdeveloper.bms.server.outbound.monday

import com.ideasdeveloper.bms.server.modules.McpPreviewRow
import com.ideasdeveloper.bms.server.modules.McpSchema
import com.ideasdeveloper.bms.server.modules.McpTool
import com.ideasdeveloper.bms.server.modules.McpToolCall
import com.ideasdeveloper.bms.server.modules.McpToolException
import com.ideasdeveloper.bms.server.outbound.OutboundSource
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.sql.Connection

/**
 * Herramientas MCP del envio a monday, reutilizables por cualquier modulo: el modulo pone el [prefix] del nombre y el
 * [permission] (el mismo de exportar). Ninguna devuelve ni acepta el token; cada una corre con los permisos en vivo del
 * usuario que conecto el agente. Configurar, reintentar y descartar dejan su fila en el registro de actividad de la empresa
 * con el actor de la llamada ([MondayOutboundActivity]).
 */
object MondayOutboundMcp {
    fun tools(
        prefix: String,
        permission: String,
        sink: MondayOutboundSink,
        source: OutboundSource,
        queueView: MondayQueueView,
        token: (Connection) -> String?,
        /** Razon por la que la extension no esta activa en la empresa (null si lo esta): se comprueba antes de cada herramienta. */
        inactive: (Connection) -> String?,
    ): List<McpTool> {
        fun guard(call: McpToolCall) {
            inactive(call.tenant)?.let { throw McpToolException(it) }
        }

        fun requireToken(call: McpToolCall): String = token(call.tenant)?.takeIf { it.isNotBlank() }
            ?: throw McpToolException("Falta el token de monday: guardalo en la configuracion de la extension.")

        fun <T> monday(block: () -> T): T = try {
            block()
        } catch (failure: MondayApiException) {
            throw McpToolException(failure.message.orEmpty())
        }

        fun eventJson(event: MondayQueueEvent): JsonElement = buildJsonObject {
            put("id", event.id)
            put("kind", event.kind)
            put("recordType", event.recordType)
            put("recordId", event.recordId)
            put("status", event.status)
            put("attempts", event.attempts)
            put("detail", event.detail)
            put("nextAttemptAt", event.nextAttemptAt?.toString())
        }

        fun configJson(cfg: MondayOutboundConfig): JsonElement = buildJsonObject {
            put("recordType", cfg.recordType)
            put("enabled", cfg.enabled)
            put("boardId", cfg.boardId)
            put("groupId", cfg.groupId)
            put("sendCreated", cfg.sendCreated)
            put("sendUpdated", cfg.sendUpdated)
            put("sendNotes", cfg.sendNotes)
            put("sendFiles", cfg.sendFiles)
            put("sendImports", cfg.sendImports)
            put("filesColumn", cfg.filesColumn)
            put("keyColumn", cfg.keyColumn)
            put("maxFileMb", cfg.maxFileMb)
            put("phoneCountry", cfg.phoneCountry)
            put("columns", JsonObject(cfg.columns.mapValues { (_, ref) -> JsonObject(mapOf("id" to JsonPrimitive(ref.id), "type" to JsonPrimitive(ref.type))) }))
        }

        val status = McpTool(
            name = prefix + "outbound_status",
            title = "Estado del envio a monday",
            description = "Resumen de la cola de envio a monday (pendientes, enviados, fallidos, omitidos), la configuracion por tipo de registro y los envios fallidos con su razon. " +
                "Usalo antes de reintentar o descartar.",
            permission = permission,
            readOnly = true,
            handler = { call ->
                guard(call)
                buildJsonObject {
                    val summary = queueView.summary(call.tenant)
                    put("pending", summary.pending + summary.sending)
                    put("sent", summary.sent)
                    put("failed", summary.failed)
                    put("skipped", summary.skipped)
                    put("configuration", JsonArray(sink.config.all(call.tenant).map(::configJson)))
                    put("failedEvents", JsonArray(queueView.events(call.tenant, setOf("failed"), 20).map(::eventJson)))
                    // Pendientes que ya se intentaron y fallaron: se reintentan solos con espera creciente.
                    put("pendingWithErrors", JsonArray(queueView.events(call.tenant, setOf("pending"), 100).filter { it.attempts > 0 }.take(20).map(::eventJson)))
                }
            },
        )

        val boards = McpTool(
            name = prefix + "outbound_boards",
            title = "Tableros de monday",
            description = "Sin argumentos lista los tableros de monday que el token puede leer. Con board_id devuelve sus columnas (id, titulo, tipo) y grupos, " +
                "para elegir el mapeo con ${prefix}outbound_configure.",
            permission = permission,
            readOnly = true,
            inputSchema = McpSchema.obj(mapOf("board_id" to McpSchema.string("Id numerico del tablero; vacio lista los tableros."))),
            handler = { call ->
                guard(call)
                val apiToken = requireToken(call)
                val boardId = call.string("board_id", 20)
                monday {
                    if (boardId.isEmpty()) {
                        buildJsonObject {
                            put("boards", JsonArray(MondayBoards.listBoards(sink.api, apiToken).map { board ->
                                buildJsonObject { put("id", board.id); put("name", board.name); put("workspace", board.workspace); put("items", board.items) }
                            }))
                        }
                    } else {
                        if (!boardId.matches(MondayBoards.idPattern)) throw McpToolException("board_id debe ser numerico.")
                        val schema = MondayBoards.boardSchema(sink.api, apiToken, boardId) ?: throw McpToolException("monday no devolvio el tablero $boardId (revisa el id y los permisos del token).")
                        buildJsonObject {
                            put("id", schema.id)
                            put("name", schema.name)
                            put("columns", JsonArray(schema.columns.map { c -> buildJsonObject { put("id", c.id); put("title", c.title); put("type", c.type) } }))
                            put("groups", JsonArray(schema.groups.map { g -> buildJsonObject { put("id", g.id); put("title", g.title) } }))
                        }
                    }
                }
            },
        )

        val configure = McpTool(
            name = prefix + "outbound_configure",
            title = "Configurar el envio a monday",
            description = "Configura el envio de un tipo de registro a un tablero de monday. Solo cambia lo que mandes; lo demas queda como esta. " +
                "columns es un objeto campo -> id de columna (cadena vacia quita el mapeo); las columnas deben existir en el tablero y aceptar el tipo del campo " +
                "(consultalas con ${prefix}outbound_boards). Activar (enabled=true) exige tablero. No toca el token.",
            permission = permission,
            // Decides which board of monday receives the company's records, and which of their fields.
            outbound = true,
            preview = { call ->
                guard(call)
                val recordTypeId = call.requiredString("record_type", 63)
                val recordType = source.recordTypes(call.tenant).firstOrNull { it.id == recordTypeId }
                    ?: throw McpToolException("Tipo de registro desconocido: $recordTypeId.")
                val current = sink.config.get(call.tenant, recordTypeId) ?: MondayOutboundConfig(recordTypeId)
                fun shown(value: String?) = value?.takeIf { it.isNotBlank() } ?: "(vacio)"
                fun flag(key: String, saved: Boolean) = if (call.has(key)) call.boolean(key) else saved
                fun yesNo(value: Boolean) = if (value) "Si" else "No"
                val enabled = flag("enabled", current.enabled)
                val board = if (call.has("board_id")) call.string("board_id", 20) else current.boardId.orEmpty()
                val created = flag("send_created", current.sendCreated)
                val updated = flag("send_updated", current.sendUpdated)
                val notes = flag("send_notes", current.sendNotes)
                val files = flag("send_files", current.sendFiles)
                val imports = flag("send_imports", current.sendImports)
                val columns = current.columns.mapValues { it.value.id }.toMutableMap()
                (call.arguments["columns"] as? JsonObject)?.forEach { (field, value) ->
                    val id = (value as? JsonPrimitive)?.content?.trim().orEmpty()
                    if (id.isEmpty()) columns.remove(field) else columns[field] = id
                }
                // A new board makes the old mapping useless (the tool clears it, as the screen does).
                if (call.has("board_id") && board != current.boardId.orEmpty() && !call.has("columns")) columns.clear()
                listOf(
                    McpPreviewRow("Tipo de registro", "${recordType.label} (${recordType.id})"),
                    McpPreviewRow("Envio a monday", "${yesNo(current.enabled)} -> ${yesNo(enabled)}"),
                    McpPreviewRow("Tablero de monday (id)", "${shown(current.boardId)} -> ${shown(board)}"),
                    McpPreviewRow(
                        "Que registros se envian",
                        "Los registros de tipo ${recordType.label} " + listOfNotNull(
                            "nuevos".takeIf { created }, "modificados".takeIf { updated }, "con sus notas (como actualizaciones)".takeIf { notes },
                            "con sus archivos (hasta ${if (call.has("max_file_mb")) call.int("max_file_mb", current.maxFileMb, 1..MondayOutboundConfig.MaxFileMb) else current.maxFileMb} MB)".takeIf { files },
                            "y los importados".takeIf { imports },
                        ).joinToString(", ").ifEmpty { "(nada)" } + if (enabled) ", desde ahora." else ": mientras este apagado no se envia nada.",
                    ),
                    McpPreviewRow("Campos que se envian (campo -> columna)", columns.entries.sortedBy { it.key }.joinToString("; ") { "${it.key} -> ${it.value}" }.ifEmpty { "(ninguno)" }),
                    McpPreviewRow("Que se envia y a donde", "Esos campos de cada registro salen del servidor hacia el tablero indicado de monday, con el token guardado."),
                )
            },
            inputSchema = McpSchema.obj(
                mapOf(
                    "record_type" to McpSchema.string("Tipo de registro, p. ej. el de los leads."),
                    "enabled" to McpSchema.boolean("Activar o apagar el envio."),
                    "board_id" to McpSchema.string("Id numerico del tablero."),
                    "group_id" to McpSchema.string("Id del grupo de los elementos nuevos; vacio usa el primero."),
                    "send_created" to McpSchema.boolean("Enviar altas."),
                    "send_updated" to McpSchema.boolean("Enviar cambios."),
                    "send_notes" to McpSchema.boolean("Enviar notas como actualizaciones."),
                    "send_files" to McpSchema.boolean("Enviar archivos."),
                    "send_imports" to McpSchema.boolean("Enviar tambien lo importado (nunca lo que viene de monday)."),
                    "files_column" to McpSchema.string("Id de la columna de archivos; vacio los manda como actualizacion."),
                    "key_column" to McpSchema.string("Id de una columna de texto para la clave del registro."),
                    "max_file_mb" to McpSchema.integer("Tamano maximo de archivo en MB.", 1, MondayOutboundConfig.MaxFileMb),
                    "phone_country" to McpSchema.string("Pais de los telefonos, 2 letras."),
                    "columns" to buildJsonObject {
                        put("type", "object")
                        put("description", "Mapeo campo -> id de columna de monday.")
                        put("additionalProperties", buildJsonObject { put("type", "string") })
                    },
                ),
                required = listOf("record_type"),
            ),
            handler = { call ->
                guard(call)
                val recordTypeId = call.requiredString("record_type", 63)
                val recordType = source.recordTypes(call.tenant).firstOrNull { it.id == recordTypeId }
                    ?: throw McpToolException("Tipo de registro desconocido: $recordTypeId.")
                val current = sink.config.get(call.tenant, recordTypeId) ?: MondayOutboundConfig(recordTypeId)
                val values = linkedMapOf(
                    "enabled" to current.enabled.toString(), "board" to current.boardId.orEmpty(), "group" to current.groupId.orEmpty(),
                    "sendCreated" to current.sendCreated.toString(), "sendUpdated" to current.sendUpdated.toString(),
                    "sendNotes" to current.sendNotes.toString(), "sendFiles" to current.sendFiles.toString(), "sendImports" to current.sendImports.toString(),
                    "filesColumn" to current.filesColumn.orEmpty(), "keyColumn" to current.keyColumn.orEmpty(),
                    "maxFileMb" to current.maxFileMb.toString(), "phoneCountry" to current.phoneCountry.orEmpty(),
                )
                current.columns.forEach { (field, ref) -> values[MondayOutboundUi.ColumnPrefix + field] = ref.id }
                fun overlay(argument: String, key: String) { if (call.has(argument)) values[key] = call.string(argument, 100) }
                overlay("enabled", "enabled"); overlay("board_id", "board"); overlay("group_id", "group")
                overlay("send_created", "sendCreated"); overlay("send_updated", "sendUpdated"); overlay("send_notes", "sendNotes")
                overlay("send_files", "sendFiles"); overlay("send_imports", "sendImports"); overlay("files_column", "filesColumn")
                overlay("key_column", "keyColumn"); overlay("max_file_mb", "maxFileMb"); overlay("phone_country", "phoneCountry")
                (call.arguments["columns"] as? JsonObject)?.forEach { (field, value) ->
                    if (recordType.fields.none { it.key == field }) throw McpToolException("El campo '$field' no existe en $recordTypeId.")
                    values[MondayOutboundUi.ColumnPrefix + field] = (value as? JsonPrimitive)?.content?.trim().orEmpty().ifEmpty { MondayOutboundUi.NoneOption }
                }
                // Al cambiar de tablero, el mapeo anterior ya no vale.
                if (call.has("board_id") && call.string("board_id", 20) != current.boardId.orEmpty() && !call.has("columns")) {
                    values.keys.filter { it.startsWith(MondayOutboundUi.ColumnPrefix) }.forEach { values.remove(it) }
                    values["filesColumn"] = ""
                    values["keyColumn"] = ""
                    values["group"] = ""
                }
                val board = MondayOutboundUi.optionId(values["board"])
                val schema = if (board != null && board.matches(MondayBoards.idPattern)) {
                    monday { MondayBoards.boardSchema(sink.api, requireToken(call), board) }
                } else null
                val result = try {
                    MondayOutboundUi.applyConfigForm(values, recordType, schema)
                } catch (failure: MondayConfigException) {
                    throw McpToolException(failure.message.orEmpty())
                }
                MondayOutboundActivity.saveConfig(sink, call.tenant, call.activityActor, result.config)
                buildJsonObject {
                    put("saved", true)
                    put("configuration", configJson(result.config))
                    put("warnings", JsonArray(result.warnings.map(::JsonPrimitive)))
                }
            },
        )

        val retry = McpTool(
            name = prefix + "outbound_retry",
            title = "Reintentar envios a monday",
            description = "Devuelve a la cola un envio fallido (con id) o todos los fallidos (sin id). Se entregan en la proxima pasada del trabajo.",
            permission = permission,
            // Puts failed notices back in the queue: the records they carry are sent to monday again.
            outbound = true,
            preview = { call ->
                guard(call)
                val id = call.string("id", 20)
                val failed = queueView.events(call.tenant, setOf("failed"), 200)
                if (id.isEmpty()) {
                    listOf(
                        McpPreviewRow("Envios fallidos que se reenviaran", failed.size.toString() + if (failed.size >= 200) " o mas" else ""),
                        McpPreviewRow("Cuales", failed.take(20).joinToString("; ") { "${it.recordType} ${it.recordId}" }.ifEmpty { "(ninguno)" }),
                        McpPreviewRow("Que se envia y a donde", "Los registros de esos envios vuelven a salir hacia monday en la proxima pasada del trabajo."),
                    )
                } else {
                    val event = failed.firstOrNull { it.id.toString() == id } ?: throw McpToolException("No hay un envio fallido con el id $id.")
                    listOf(
                        McpPreviewRow("Envio", "${event.id}: ${event.kind} de ${event.recordType} ${event.recordId}"),
                        McpPreviewRow("Fallo anterior", event.detail.ifBlank { "-" }),
                        McpPreviewRow("Que se envia y a donde", "Ese registro vuelve a salir hacia monday en la proxima pasada del trabajo."),
                    )
                }
            },
            inputSchema = McpSchema.obj(mapOf("id" to McpSchema.string("Id del envio fallido; vacio reintenta todos los fallidos."))),
            handler = { call ->
                guard(call)
                val id = call.string("id", 20)
                buildJsonObject {
                    if (id.isEmpty()) put("retried", MondayOutboundActivity.retry(sink, queueView, call.tenant, call.activityActor, null))
                    else {
                        val number = id.toLongOrNull() ?: throw McpToolException("id debe ser numerico.")
                        if (MondayOutboundActivity.retry(sink, queueView, call.tenant, call.activityActor, number) == 0) throw McpToolException("No hay un envio fallido con el id $id.")
                        put("retried", 1)
                    }
                }
            },
        )

        val discard = McpTool(
            name = prefix + "outbound_discard",
            title = "Descartar un envio a monday",
            description = "Descarta un envio fallido: no se enviara nunca a monday. No borra nada en BMS ni en monday.",
            permission = permission,
            destructive = true,
            preview = { call ->
                guard(call)
                val number = call.requiredString("id", 20).toLongOrNull() ?: throw McpToolException("id debe ser numerico.")
                val event = queueView.events(call.tenant, setOf("failed", "pending"), 500).firstOrNull { it.id == number } ?: throw McpToolException("No hay un envio con el id $number.")
                listOf(
                    McpPreviewRow("Envio que se descarta", "${event.id}: ${event.kind} de ${event.recordType} ${event.recordId}"),
                    McpPreviewRow("Estado y motivo", "${event.status}: ${event.detail.ifBlank { "-" }}"),
                    McpPreviewRow("Efecto", "No se enviara nunca a monday. No borra nada en BMS ni en monday."),
                )
            },
            inputSchema = McpSchema.obj(mapOf("id" to McpSchema.string("Id del envio fallido.")), required = listOf("id")),
            handler = { call ->
                guard(call)
                val number = call.requiredString("id", 20).toLongOrNull() ?: throw McpToolException("id debe ser numerico.")
                if (!MondayOutboundActivity.discard(sink, queueView, call.tenant, call.activityActor, number)) throw McpToolException("No hay un envio con el id $number.")
                buildJsonObject { put("discarded", true) }
            },
        )
        return listOf(status, boards, configure, retry, discard)
    }
}
