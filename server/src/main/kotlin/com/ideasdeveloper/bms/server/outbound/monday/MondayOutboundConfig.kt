package com.ideasdeveloper.bms.server.outbound.monday

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.sql.Connection

/** Columna de monday a la que va un campo: su id y su tipo (status, date, people...). */
data class MondayColumnRef(val id: String, val type: String)

/** Configuracion del envio a monday de un tipo de registro (una fila de ext_<id>__outbound). Sin fila = apagado. */
data class MondayOutboundConfig(
    val recordType: String,
    val enabled: Boolean = false,
    val boardId: String? = null,
    val groupId: String? = null,
    val sendCreated: Boolean = true,
    val sendUpdated: Boolean = true,
    val sendNotes: Boolean = true,
    val sendFiles: Boolean = true,
    val sendImports: Boolean = false,
    /** Clave del campo -> columna de monday. */
    val columns: Map<String, MondayColumnRef> = emptyMap(),
    val filesColumn: String? = null,
    val keyColumn: String? = null,
    val maxFileMb: Int = DefaultMaxFileMb,
    val phoneCountry: String? = null,
) {
    companion object {
        const val DefaultMaxFileMb = 25
        const val MaxFileMb = 500
    }
}

/** Lee y guarda la configuracion de salida de la empresa; las pruebas usan una version en memoria. */
interface MondayOutboundConfigStore {
    fun get(tenant: Connection, recordType: String): MondayOutboundConfig?

    fun all(tenant: Connection): List<MondayOutboundConfig>

    /** Inserta o actualiza la fila del tipo de registro. */
    fun save(tenant: Connection, config: MondayOutboundConfig)

    /** Borra toda la configuracion (al desinstalar la extension). */
    fun delete(tenant: Connection)
}

/** Version JDBC sobre la tabla `ext_<id>__outbound` de la extension. El nombre se valida: va en el SQL. */
class JdbcMondayOutboundConfigStore(private val table: String) : MondayOutboundConfigStore {
    init {
        require(tablePattern.matches(table)) { "Nombre de tabla de salida no valido." }
    }

    companion object {
        private val tablePattern = Regex("^ext_[a-z][a-z0-9_]{0,62}__outbound$")
        private val json = Json { ignoreUnknownKeys = true }
        private const val Select = "SELECT record_type, enabled, board_id, group_id, send_created, send_updated, send_notes, send_files, " +
            "send_imports, columns::text AS columns, files_column, key_column, max_file_mb, phone_country FROM "

        fun parseColumns(text: String?): Map<String, MondayColumnRef> {
            val root = runCatching { json.parseToJsonElement(text.orEmpty()) }.getOrNull().asObj() ?: return emptyMap()
            return root.mapNotNull { (field, value) ->
                val ref = value.asObj() ?: return@mapNotNull null
                val id = ref["id"].asStr()
                if (id.isEmpty()) null else field to MondayColumnRef(id, ref["type"].asStr())
            }.toMap()
        }

        fun columnsJson(columns: Map<String, MondayColumnRef>): String = JsonObject(
            columns.mapValues { (_, ref) -> JsonObject(mapOf("id" to JsonPrimitive(ref.id), "type" to JsonPrimitive(ref.type))) },
        ).toString()
    }

    private fun java.sql.ResultSet.config() = MondayOutboundConfig(
        recordType = getString("record_type"),
        enabled = getBoolean("enabled"),
        boardId = getString("board_id"),
        groupId = getString("group_id"),
        sendCreated = getBoolean("send_created"),
        sendUpdated = getBoolean("send_updated"),
        sendNotes = getBoolean("send_notes"),
        sendFiles = getBoolean("send_files"),
        sendImports = getBoolean("send_imports"),
        columns = parseColumns(getString("columns")),
        filesColumn = getString("files_column"),
        keyColumn = getString("key_column"),
        maxFileMb = getInt("max_file_mb"),
        phoneCountry = getString("phone_country")?.trim()?.takeIf { it.isNotEmpty() },
    )

    override fun get(tenant: Connection, recordType: String): MondayOutboundConfig? =
        tenant.prepareStatement("$Select$table WHERE record_type = ?").use { statement ->
            statement.setString(1, recordType)
            statement.executeQuery().use { rows -> if (rows.next()) rows.config() else null }
        }

    override fun all(tenant: Connection): List<MondayOutboundConfig> =
        tenant.prepareStatement("$Select$table ORDER BY record_type").use { statement ->
            statement.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.config()) } }
        }

    override fun save(tenant: Connection, config: MondayOutboundConfig) {
        tenant.prepareStatement(
            "INSERT INTO $table (record_type, enabled, board_id, group_id, send_created, send_updated, send_notes, send_files, send_imports, " +
                "columns, files_column, key_column, max_file_mb, phone_country, updated_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, CURRENT_TIMESTAMP) " +
                "ON CONFLICT (record_type) DO UPDATE SET enabled = EXCLUDED.enabled, board_id = EXCLUDED.board_id, group_id = EXCLUDED.group_id, " +
                "send_created = EXCLUDED.send_created, send_updated = EXCLUDED.send_updated, send_notes = EXCLUDED.send_notes, " +
                "send_files = EXCLUDED.send_files, send_imports = EXCLUDED.send_imports, columns = EXCLUDED.columns, " +
                "files_column = EXCLUDED.files_column, key_column = EXCLUDED.key_column, max_file_mb = EXCLUDED.max_file_mb, " +
                "phone_country = EXCLUDED.phone_country, updated_at = CURRENT_TIMESTAMP",
        ).use { statement ->
            statement.setString(1, config.recordType)
            statement.setBoolean(2, config.enabled)
            statement.setString(3, config.boardId)
            statement.setString(4, config.groupId)
            statement.setBoolean(5, config.sendCreated)
            statement.setBoolean(6, config.sendUpdated)
            statement.setBoolean(7, config.sendNotes)
            statement.setBoolean(8, config.sendFiles)
            statement.setBoolean(9, config.sendImports)
            statement.setString(10, columnsJson(config.columns))
            statement.setString(11, config.filesColumn)
            statement.setString(12, config.keyColumn)
            statement.setInt(13, config.maxFileMb)
            statement.setString(14, config.phoneCountry)
            statement.executeUpdate()
        }
    }

    override fun delete(tenant: Connection) {
        tenant.prepareStatement("DELETE FROM $table").use { it.executeUpdate() }
    }
}
