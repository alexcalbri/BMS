package com.ideasdeveloper.bms.extensions.tareas_monday

import com.ideasdeveloper.bms.server.net.PinnedHost
import com.ideasdeveloper.bms.server.net.PinnedHostException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.slf4j.LoggerFactory
import java.io.InputStream
import java.net.URI

data class MondayBoard(val id: String, val name: String, val workspace: String, val items: Int?)

/** Un tablero con sus columnas, para sugerir el mapeo. */
data class MondayBoardInfo(val id: String, val name: String, val columns: List<MondayColumn>)

/** Una pagina de elementos: [cursor] vacio significa que ya no hay mas. */
data class MondayPage(val items: List<MondayItem>, val cursor: String)

/** Enlace temporal de descarga de un archivo de monday y su tamano, si lo informa. */
data class MondayAssetLink(val url: String, val size: Long?)

/**
 * Cliente de la API GraphQL de monday (copiado del de la extension "monday", con sus mismas defensas): el servidor se
 * resuelve una vez y la conexion va a esa IP validada, con tope de tamano y de tiempo. El token solo viaja en la
 * cabecera Authorization; ningun mensaje de error ni registro lo incluye.
 */
object TareasMondayClient {
    const val MainWorkspace = "Espacio principal"
    const val MaxItems = 20_000
    // Sin limite practico por archivo: el tope real es el disco del servidor. 100 GB evita desbordes al sumar.
    const val MaxFileBytes = 100L * 1024 * 1024 * 1024
    const val PageSize = 25
    /** Tope de lectura de una respuesta: pasado esto se trata como complejidad y la pagina se reduce. */
    private const val MaxBytes = 5 * 1024 * 1024
    private const val BoardsBudgetMillis = 30_000L
    private const val MondayHost = "api.monday.com"
    private val json = Json { ignoreUnknownKeys = true }
    private val idPattern = Regex("^[0-9]{1,20}$")
    private val log = LoggerFactory.getLogger(TareasMondayClient::class.java)

    private fun JsonElement?.obj() = this as? JsonObject
    private fun JsonElement?.arr() = (this as? JsonArray).orEmpty()
    private fun JsonElement?.str() = (this as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()

    fun isBoardId(value: String): Boolean = idPattern.matches(value)

    // ---- Presupuesto de tiempo (deadline = System.nanoTime() limite de la corrida) ----

    /** Tiempo minimo que debe quedar para empezar una llamada o una descarga. */
    const val MinCallMillis = 5_000L

    fun hasTime(deadline: Long, now: Long = System.nanoTime()): Boolean = deadline - now >= MinCallMillis * 1_000_000L

    /** El tiempo de espera de una llamada: el normal o lo que queda de la corrida, el menor (sin deadline, el normal). */
    fun timeoutFor(defaultMillis: Long, deadline: Long?, now: Long = System.nanoTime()): Long =
        if (deadline == null) defaultMillis else minOf(defaultMillis, ((deadline - now) / 1_000_000L).coerceAtLeast(1_000L))

    /** Servidores de los que se bajan archivos: monday.com y su almacenamiento S3 (files-monday-com*.amazonaws.com). */
    fun isAllowedFileHost(host: String?): Boolean {
        val name = host?.trim()?.lowercase()?.trimEnd('.').orEmpty()
        if (name.isEmpty()) return false
        return name.endsWith(".monday.com") || (name.startsWith("files-monday-com") && name.endsWith(".amazonaws.com"))
    }

    // ---- Tableros ----

    /** Tableros activos que el token puede leer, por espacio de trabajo y nombre (sin tableros de subelementos ni documentos). */
    fun boards(token: String): List<MondayBoard> {
        requireToken(token)
        val boards = mutableListOf<MondayBoard>()
        // Tope de 30 s para todo el listado: si se acaba, se devuelve lo leido (la lista puede estar incompleta).
        val deadline = System.nanoTime() + BoardsBudgetMillis * 1_000_000L
        for (page in 1..50) {
            if (boardsBudgetExpired(page, deadline)) {
                log.warn("El listado de tableros de monday se corto por tiempo despues de {} paginas", page - 1)
                break
            }
            val data = query(token, "query { boards(limit: 100, page: $page, state: active) { id name type items_count workspace { name } } }", deadline)
            boards += parseBoards(data)
            if (data["boards"].arr().size < 100) break
        }
        return boards.sortedWith(compareBy<MondayBoard>({ it.workspace != MainWorkspace }, { it.workspace.lowercase() }, { it.name.lowercase() }))
    }

    /** Corta el listado de tableros: desde la segunda pagina, si ya se acabo el presupuesto total. */
    fun boardsBudgetExpired(page: Int, deadline: Long, now: Long = System.nanoTime()): Boolean = page > 1 && now >= deadline

    fun parseBoards(data: JsonObject): List<MondayBoard> = data["boards"].arr().mapNotNull { it.obj() }
        .filter { it["type"].str().ifEmpty { "board" } == "board" }
        .mapNotNull { board ->
            val id = board["id"].str().takeIf { it.matches(idPattern) } ?: return@mapNotNull null
            MondayBoard(id, board["name"].str().ifEmpty { id }, board["workspace"].obj()?.get("name").str().ifEmpty { MainWorkspace }, board["items_count"].str().toIntOrNull())
        }

    /** Nombre y columnas de un tablero. */
    fun boardInfo(token: String, boardId: String): MondayBoardInfo {
        requireToken(token)
        requireBoard(boardId)
        val data = query(token, "query { boards(ids: [$boardId]) { name columns { id title type } } }")
        val board = data["boards"].arr().firstOrNull().obj() ?: throw notFound(boardId)
        return MondayBoardInfo(boardId, board["name"].str().ifEmpty { boardId }, parseColumns(board["columns"]))
    }

    fun parseColumns(columns: JsonElement?): List<MondayColumn> = columns.arr().mapNotNull { it.obj() }.mapNotNull { column ->
        val id = column["id"].str().ifEmpty { return@mapNotNull null }
        MondayColumn(id, column["title"].str().ifEmpty { id }, column["type"].str())
    }

    // ---- Elementos (una pagina por llamada; el cursor lo guarda el trabajo) ----

    private const val ValueFields = "id type text value column { title }"
    // Sin actualizaciones: van en una segunda consulta por lote de ids ([updatesQuery]) y solo si el mapeo las pide.
    private const val ItemFields = "id name group { title } column_values { $ValueFields } subitems { id name column_values { $ValueFields } }"
    // Las respuestas no llevan adjuntos: monday rechaza `assets` en el tipo Reply con esta version de la API
    // ("Cannot query field assets on type Reply") y la consulta entera fallaba.
    private const val ReplyFields = "id text_body created_at creator { name email }"
    private const val UpdateFields = "$ReplyFields assets { id name }"
    const val UpdatesLimit = 100

    /** Consulta de actualizaciones (con sus respuestas) de un lote de elementos; solo ids numericos y `limit` igual al tamano del lote. */
    fun updatesQuery(ids: Collection<String>): String {
        val clean = ids.filter { idPattern.matches(it) }.distinct()
        return "query { items(ids: [${clean.joinToString()}], limit: ${clean.size}) { id " +
            "updates(limit: $UpdatesLimit) { $UpdateFields replies { $ReplyFields } } } }"
    }

    /** Elementos cuyas notas pueden estar incompletas: monday no devolvio el elemento o devolvio tantas actualizaciones como el tope. */
    fun incompleteNotes(ids: Collection<String>, updates: Map<String, List<MondayUpdate>>): Set<String> =
        ids.filter { idPattern.matches(it) }.filter { id -> updates[id].let { it == null || it.size >= UpdatesLimit } }.toSet()

    /**
     * Actualizaciones y respuestas de los elementos [ids], por id de elemento, en una sola consulta por lote (el lote es
     * la pagina leida). Un limite de complejidad baja el tamano de pagina en el runner.
     */
    fun updatesFor(token: String, ids: Collection<String>, deadline: Long? = null): Map<String, List<MondayUpdate>> {
        requireToken(token)
        val clean = ids.filter { idPattern.matches(it) }.distinct()
        if (clean.isEmpty()) return emptyMap()
        val data = query(token, updatesQuery(clean), deadline)
        return data["items"].arr().mapNotNull { it.obj() }.associate { item ->
            item["id"].str() to item["updates"].arr().mapNotNull { it.obj() }.map(::parseUpdate)
        }
    }

    /** Primera pagina ([cursor] nulo) o la siguiente de un tablero, de [pageSize] elementos (sin actualizaciones). */
    fun itemsPage(token: String, boardId: String, cursor: String?, pageSize: Int = PageSize, deadline: Long? = null): MondayPage {
        requireToken(token)
        requireBoard(boardId)
        val size = pageSize.coerceIn(TareasMondayReading.MinPageSize, 100)
        val page = if (cursor.isNullOrEmpty()) {
            val data = query(token, "query { boards(ids: [$boardId]) { items_page(limit: $size) { cursor items { $ItemFields } } } }", deadline)
            (data["boards"].arr().firstOrNull().obj() ?: throw notFound(boardId))["items_page"].obj()
        } else {
            // El cursor se escapa como cadena JSON, que tambien es una cadena GraphQL valida.
            query(token, "query { next_items_page(limit: $size, cursor: ${JsonPrimitive(cursor)}) { cursor items { $ItemFields } } }", deadline)["next_items_page"].obj()
        }
        return MondayPage(page?.get("items").arr().mapNotNull { it.obj() }.map(::parseItem), page?.get("cursor").str())
    }

    fun parseItem(item: JsonObject): MondayItem = MondayItem(
        id = item["id"].str(),
        name = item["name"].str(),
        group = item["group"].obj()?.get("title").str(),
        cells = parseCells(item["column_values"]),
        subitems = item["subitems"].arr().mapNotNull { it.obj() }.map { sub ->
            MondaySubitem(sub["id"].str(), sub["name"].str(), parseCells(sub["column_values"]))
        },
        updates = item["updates"].arr().mapNotNull { it.obj() }.map(::parseUpdate),
    )

    fun parseUpdate(update: JsonObject): MondayUpdate = MondayUpdate(
        id = update["id"].str(),
        body = update["text_body"].str(),
        createdAt = update["created_at"].str(),
        authorName = update["creator"].obj()?.get("name").str(),
        authorEmail = update["creator"].obj()?.get("email").str(),
        assets = update["assets"].arr().mapNotNull { it.obj() }.map { MondayAsset(it["id"].str(), it["name"].str()) },
        replies = update["replies"].arr().mapNotNull { it.obj() }.map { parseUpdate(it) },
    )

    private fun parseCells(values: JsonElement?): List<MondayCell> = values.arr().mapNotNull { it.obj() }.map { value ->
        MondayCell(
            columnId = value["id"].str(),
            title = value["column"].obj()?.get("title").str(),
            type = value["type"].str(),
            text = value["text"].str(),
            // El valor crudo (JSON como texto) trae fechas, personas y archivos; sin recortar espacios ni contenido.
            value = (value["value"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
        )
    }

    // ---- Personas y archivos ----

    /** Nombre y correo de las personas de monday por id (sin duplicados; maximo 50 por consulta). */
    fun users(token: String, ids: Collection<String>, deadline: Long? = null): List<MondayPerson> {
        requireToken(token)
        return ids.filter { idPattern.matches(it) }.distinct().chunked(50).flatMap { chunk ->
            val data = query(token, "query { users(ids: [${chunk.joinToString()}]) { id name email } }", deadline)
            data["users"].arr().mapNotNull { it.obj() }.mapNotNull { user ->
                val id = user["id"].str().takeIf { it.matches(idPattern) } ?: return@mapNotNull null
                MondayPerson(id, user["name"].str(), user["email"].str().lowercase())
            }
        }
    }

    /** Enlaces de descarga de archivos (caducan en menos de una hora: se piden al aplicar la fila). */
    fun assetLinks(token: String, ids: Collection<String>, deadline: Long? = null): Map<String, MondayAssetLink> {
        requireToken(token)
        return ids.filter { idPattern.matches(it) }.distinct().chunked(50).flatMap { chunk ->
            val data = query(token, "query { assets(ids: [${chunk.joinToString()}]) { id name public_url file_size } }", deadline)
            data["assets"].arr().mapNotNull { it.obj() }.mapNotNull { asset ->
                val id = asset["id"].str().takeIf { it.matches(idPattern) } ?: return@mapNotNull null
                val url = asset["public_url"].str().ifEmpty { return@mapNotNull null }
                id to MondayAssetLink(url, asset["file_size"].str().toLongOrNull())
            }
        }.toMap()
    }

    /** Descarga un enlace de monday (https en un servidor publico) y entrega su contenido a [store]. */
    fun download(url: String, deadline: Long? = null, store: (contentType: String, body: InputStream) -> Unit) {
        val uri = runCatching { URI.create(url) }.getOrNull()
        if (uri == null || uri.scheme != "https" || uri.host.isNullOrBlank()) throw TareasMondayException("enlace de descarga invalido")
        if (!isAllowedFileHost(uri.host)) throw TareasMondayException("el archivo no esta en un servidor de monday")
        // Una sola resolucion: se valida y la conexion va a esa misma IP (anti DNS rebinding).
        val validated = try {
            PinnedHost.resolveValidated(uri.host)
        } catch (failure: PinnedHostException) {
            throw TareasMondayException(if (failure.message.orEmpty().startsWith("No se encontro")) "no se encontro ${uri.host}" else "el enlace apunta a una red privada")
        }
        val response = try {
            PinnedHost.openPinnedHttps(
                uri, validated, uri.host, timeoutMillis = timeoutFor(30_000, deadline).toInt(), connectTimeoutMillis = timeoutFor(15_000, deadline).toInt(),
                totalMillis = timeoutFor(5 * 60_000L, deadline), maxBodyBytes = MaxFileBytes + 1,
            )
        } catch (failure: InterruptedException) {
            Thread.currentThread().interrupt()
            throw failure
        } catch (failure: Exception) {
            throw TareasMondayException("no se pudo descargar")
        }
        // use cierra la conexion aunque store falle.
        response.use {
            if (it.status !in 200..299) throw TareasMondayException("la descarga respondio HTTP ${it.status}")
            store(it.header("Content-Type") ?: "application/octet-stream", it.body)
        }
    }

    // ---- Red ----

    private fun requireToken(token: String) {
        if (token.isBlank()) throw TareasMondayException("Escribe el token de la API de monday.")
    }

    private fun requireBoard(boardId: String) {
        if (!isBoardId(boardId)) throw TareasMondayException("El id del tablero debe ser un numero.")
    }

    private fun notFound(boardId: String) = TareasMondayException("monday no devolvio el tablero $boardId (revisa el id y los permisos del token).")

    private fun query(token: String, query: String, deadline: Long? = null): JsonObject {
        val payload = JsonObject(mapOf("query" to JsonPrimitive(query))).toString().toByteArray(Charsets.UTF_8)
        // Se resuelve una vez y la conexion va a esa IP validada (anti DNS rebinding).
        val (status, text) = try {
            val validated = PinnedHost.resolveValidated(MondayHost)
            PinnedHost.openPinnedHttps(
                URI.create("https://$MondayHost/v2"), validated, MondayHost, "POST",
                mapOf("Authorization" to token.trim(), "API-Version" to "2024-10", "Content-Type" to "application/json"),
                payload, timeoutMillis = timeoutFor(30_000, deadline).toInt(), totalMillis = timeoutFor(60_000, deadline), maxBodyBytes = MaxBytes + 1L,
            ).use { response -> response.status to response.body.readNBytes(MaxBytes + 1).decodeToString() }
        } catch (failure: InterruptedException) {
            Thread.currentThread().interrupt()
            throw failure
        } catch (failure: Exception) {
            // Solo el tipo de fallo: el mensaje de la excepcion podria incluir la solicitud.
            log.warn("No se pudo contactar monday: {}", failure.javaClass.simpleName)
            throw TareasMondayException("No se pudo contactar monday.", transient = true)
        }
        if (text.length > MaxBytes) throw TareasMondayException("monday respondio algo demasiado grande (mas de 5 MB).", kind = MondayErrorKind.TooLarge)
        val body = runCatching { json.parseToJsonElement(text) }.getOrNull().obj()
        if (status == 401 || status == 403) throw TareasMondayException("monday rechazo el token.")
        body?.get("errors").arr().firstOrNull()?.let {
            val code = it.obj()?.get("extensions").obj()?.get("code").str()
            val message = it.obj()?.get("message").str()
            val kind = TareasMondayReading.classify(status, code, message)
            throw TareasMondayException("monday: ${message.take(TareasMondayReading.MaxMessage)}", transient = kind == MondayErrorKind.Transient, kind = kind)
        }
        if (status == 429 || status >= 500) {
            throw TareasMondayException("monday respondio HTTP $status.", transient = true, kind = TareasMondayReading.classify(status, "", ""))
        }
        return body?.get("data").obj() ?: throw TareasMondayException("monday respondio HTTP $status.")
    }
}
