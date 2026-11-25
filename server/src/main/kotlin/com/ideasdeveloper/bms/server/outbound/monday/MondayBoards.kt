package com.ideasdeveloper.bms.server.outbound.monday

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

data class MondayBoardInfo(val id: String, val name: String, val workspace: String, val items: Int?)

data class MondayColumnInfo(val id: String, val title: String, val type: String)

data class MondayGroupInfo(val id: String, val title: String)

data class MondayBoardSchema(val id: String, val name: String, val columns: List<MondayColumnInfo>, val groups: List<MondayGroupInfo>)

/** Lectura de tableros y de la estructura de uno (columnas y grupos) para configurar el envio. Sin depender de ningun modulo. */
object MondayBoards {
    const val MainWorkspace = "Espacio principal"
    val idPattern = Regex("^[0-9]{1,20}$")

    /** Tipos de columna que no se pueden escribir desde fuera (se ocultan del mapeo). */
    private val readOnlyTypes = setOf("formula", "mirror", "auto_number", "creation_log", "last_updated", "item_id", "board_relation", "dependency", "subtasks", "button", "color_picker")

    private const val ListQuery = "query (\$page: Int!) { boards(limit: 100, page: \$page, state: active) { id name type items_count workspace { name } } }"
    private const val SchemaQuery = "query (\$ids: [ID!]) { boards(ids: \$ids) { id name groups { id title } columns { id title type } } }"

    /** Tableros activos que el token puede leer, por espacio y nombre (sin tableros de subelementos ni documentos). */
    fun listBoards(api: MondayApi, token: String): List<MondayBoardInfo> {
        val boards = mutableListOf<MondayBoardInfo>()
        for (page in 1..50) {
            val data = api.call(token, ListQuery, JsonObject(mapOf("page" to JsonPrimitive(page))))
            boards += parseBoards(data)
            if (data["boards"].asArr().size < 100) break
        }
        return boards.sortedWith(compareBy<MondayBoardInfo>({ it.workspace != MainWorkspace }, { it.workspace.lowercase() }, { it.name.lowercase() }))
    }

    fun parseBoards(data: JsonObject): List<MondayBoardInfo> = data["boards"].asArr().mapNotNull { it.asObj() }
        .filter { it["type"].asStr().ifEmpty { "board" } == "board" }
        .mapNotNull { board ->
            val id = board["id"].asStr().takeIf { it.matches(idPattern) } ?: return@mapNotNull null
            MondayBoardInfo(id, board["name"].asStr().ifEmpty { id }, board["workspace"].asObj()?.get("name").asStr().ifEmpty { MainWorkspace }, board["items_count"].asStr().toIntOrNull())
        }

    /** Columnas (escribibles) y grupos de un tablero; null si monday no lo devuelve (id mal o sin permiso). */
    fun boardSchema(api: MondayApi, token: String, boardId: String): MondayBoardSchema? {
        if (!boardId.matches(idPattern)) return null
        val data = api.call(token, SchemaQuery, JsonObject(mapOf("ids" to JsonArray(listOf(JsonPrimitive(boardId))))))
        return parseSchema(data)
    }

    fun parseSchema(data: JsonObject): MondayBoardSchema? {
        val board = data["boards"].asArr().firstOrNull().asObj() ?: return null
        return MondayBoardSchema(
            id = board["id"].asStr(),
            name = board["name"].asStr(),
            columns = board["columns"].asArr().mapNotNull { it.asObj() }.mapNotNull { column ->
                val id = column["id"].asStr().ifEmpty { return@mapNotNull null }
                val type = column["type"].asStr()
                if (type in readOnlyTypes) null else MondayColumnInfo(id, column["title"].asStr().ifEmpty { id }, type)
            },
            groups = board["groups"].asArr().mapNotNull { it.asObj() }.mapNotNull { group ->
                val id = group["id"].asStr().ifEmpty { return@mapNotNull null }
                MondayGroupInfo(id, group["title"].asStr().ifEmpty { id })
            },
        )
    }
}
