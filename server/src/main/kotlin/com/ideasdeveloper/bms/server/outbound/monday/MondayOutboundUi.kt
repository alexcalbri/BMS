package com.ideasdeveloper.bms.server.outbound.monday

import com.ideasdeveloper.bms.server.outbound.OutboundRecordType
import com.ideasdeveloper.bms.ui.UiAction
import com.ideasdeveloper.bms.ui.UiBadge
import com.ideasdeveloper.bms.ui.UiButton
import com.ideasdeveloper.bms.ui.UiCard
import com.ideasdeveloper.bms.ui.UiField
import com.ideasdeveloper.bms.ui.UiForm
import com.ideasdeveloper.bms.ui.UiGrid
import com.ideasdeveloper.bms.ui.UiList
import com.ideasdeveloper.bms.ui.UiListItem
import com.ideasdeveloper.bms.ui.UiMetric
import com.ideasdeveloper.bms.ui.UiNavigate
import com.ideasdeveloper.bms.ui.UiNode
import com.ideasdeveloper.bms.ui.UiRequest
import com.ideasdeveloper.bms.ui.UiRow
import com.ideasdeveloper.bms.ui.UiText
import com.ideasdeveloper.bms.ui.UiTextStyle
import com.ideasdeveloper.bms.ui.UiTone

/** Crea la accion de un boton: [op] es la operacion ("save", "suggest", "retry", "retryAll", "discard") y cada modulo pone su ruta. */
typealias MondayActionFactory = (op: String, params: Map<String, String>) -> UiAction

/** Error de validacion del formulario de configuracion; el mensaje se muestra tal cual. */
class MondayConfigException(message: String) : Exception(message)

/** Configuracion armada desde el formulario, con los avisos de lo que se descarto. */
data class MondayConfigResult(val config: MondayOutboundConfig, val warnings: List<String>)

/** Nodos reutilizables de las pantallas de configuracion y estado del envio a monday. */
object MondayOutboundUi {
    const val NoneOption = "(no enviar)"
    const val NoGroupOption = "(primer grupo del tablero)"
    const val ColumnPrefix = "col:"
    private val idInBrackets = Regex("\\[([^\\[\\]]+)]\\s*$")

    fun optionLabel(title: String, id: String) = "$title [$id]"

    /** El id al final de una opcion "Titulo [id]"; si no lleva corchetes se toma el texto entero. */
    fun optionId(option: String?): String? {
        val text = option?.trim().orEmpty()
        if (text.isEmpty() || text == NoneOption || text == NoGroupOption) return null
        return idInBrackets.find(text)?.groupValues?.get(1)?.trim() ?: text
    }

    private fun form(id: String, node: UiAction): UiAction = when (node) {
        is UiRequest -> node.copy(formId = id)
        is UiNavigate -> node.copy(formId = id)
        else -> node
    }

    /**
     * Tarjeta de configuracion de un tipo de registro. [schema] es el tablero ya elegido (null si aun no hay o no se pudo
     * leer). [suggested] son valores de columna propuestos (clave "col:<campo>" -> opcion) que pisan los guardados.
     */
    fun configCard(
        recordType: OutboundRecordType,
        config: MondayOutboundConfig?,
        boards: List<MondayBoardInfo>,
        schema: MondayBoardSchema?,
        action: MondayActionFactory,
        suggested: Map<String, String> = emptyMap(),
    ): UiNode {
        val cfg = config ?: MondayOutboundConfig(recordType.id)
        val formId = "mondayOut_${recordType.id}"
        val fields = mutableListOf(
            UiField("enabled", "Enviar a monday los ${recordType.label.lowercase()}", type = "boolean", section = "General"),
            UiField("board", "Tablero", type = "dropdown", options = boards.map { optionLabel(it.name + " - " + it.workspace, it.id) }, section = "General",
                help = "Al guardar con otro tablero se cargan sus columnas para el mapeo."),
        )
        val values = linkedMapOf(
            "enabled" to cfg.enabled.toString(),
            "board" to (cfg.boardId?.let { id -> boards.firstOrNull { it.id == id }?.let { optionLabel(it.name + " - " + it.workspace, it.id) } ?: id }.orEmpty()),
        )
        if (schema != null) {
            fields += UiField("group", "Grupo de los elementos nuevos", type = "dropdown", options = listOf(NoGroupOption) + schema.groups.map { optionLabel(it.title, it.id) }, section = "General")
            values["group"] = cfg.groupId?.let { id -> schema.groups.firstOrNull { it.id == id }?.let { optionLabel(it.title, it.id) } ?: id } ?: NoGroupOption
        }
        fields += listOf(
            UiField("sendCreated", "Enviar altas", type = "boolean", section = "Que se envia"),
            UiField("sendUpdated", "Enviar cambios", type = "boolean", section = "Que se envia"),
            UiField("sendNotes", "Enviar notas (como actualizaciones, con menciones)", type = "boolean", section = "Que se envia"),
            UiField("sendFiles", "Enviar archivos", type = "boolean", section = "Que se envia"),
            UiField("sendImports", "Enviar tambien lo que llega por importacion", type = "boolean", section = "Que se envia",
                help = "Lo que viene de monday nunca se devuelve a monday."),
            UiField("maxFileMb", "Tamano maximo de archivo (MB)", type = "number", section = "Que se envia"),
            UiField("phoneCountry", "Pais de los telefonos (2 letras, p. ej. MX)", section = "Que se envia"),
        )
        values += mapOf(
            "sendCreated" to cfg.sendCreated.toString(), "sendUpdated" to cfg.sendUpdated.toString(), "sendNotes" to cfg.sendNotes.toString(),
            "sendFiles" to cfg.sendFiles.toString(), "sendImports" to cfg.sendImports.toString(),
            "maxFileMb" to cfg.maxFileMb.toString(), "phoneCountry" to cfg.phoneCountry.orEmpty(),
        )
        val notes = mutableMapOf<String, String>()
        if (schema == null) {
            notes["Columnas"] = "Elige un tablero y guarda para poder asignar las columnas."
        } else {
            recordType.fields.forEach { field ->
                val accepted = MondayColumnValues.compatible(field.type)
                val options = schema.columns.filter { it.type in accepted }
                fields += UiField(ColumnPrefix + field.key, field.label, type = "dropdown", options = listOf(NoneOption) + options.map { optionLabel(it.title, it.id) }, section = "Columnas")
                val chosen = cfg.columns[field.key]?.let { ref -> options.firstOrNull { it.id == ref.id }?.let { optionLabel(it.title, it.id) } } ?: NoneOption
                values[ColumnPrefix + field.key] = chosen
            }
            fields += UiField("filesColumn", "Columna de archivos", type = "dropdown",
                options = listOf(NoneOption) + schema.columns.filter { it.type == "file" }.map { optionLabel(it.title, it.id) }, section = "Columnas",
                help = "Sin columna, cada archivo va como actualizacion del elemento.")
            fields += UiField("keyColumn", "Columna clave (evita duplicados tras un corte)", type = "dropdown",
                options = listOf(NoneOption) + schema.columns.filter { it.type == "text" }.map { optionLabel(it.title, it.id) }, section = "Columnas",
                help = "Recomendado: una columna de texto donde se guarda el id del registro. Si un corte interrumpe un alta, el reintento encuentra el elemento en vez de duplicarlo.")
            values["filesColumn"] = cfg.filesColumn?.let { id -> schema.columns.firstOrNull { it.id == id }?.let { optionLabel(it.title, it.id) } } ?: NoneOption
            values["keyColumn"] = cfg.keyColumn?.let { id -> schema.columns.firstOrNull { it.id == id }?.let { optionLabel(it.title, it.id) } } ?: NoneOption
        }
        values.putAll(suggested)
        val extra = mapOf("recordType" to recordType.id)
        return UiCard(
            listOfNotNull(
                UiForm(
                    formId, fields, values,
                    submit = UiButton("Guardar", form(formId, action("save", extra))),
                    sectionNotes = notes, collapsibleSections = false,
                ),
                schema?.let { UiButton("Sugerir desde la importacion", form(formId, action("suggest", extra)), style = "secondary") },
            ),
            title = "Envio a monday: ${recordType.label}",
        )
    }

    /**
     * Valores de columna propuestos con el mapeo de la ultima importacion ([importMapping]: titulo de columna -> campo),
     * emparejando por titulo sin distinguir mayusculas ni acentos y solo con columnas compatibles.
     */
    fun suggest(recordType: OutboundRecordType, schema: MondayBoardSchema, importMapping: Map<String, String>): Map<String, String> {
        val byTitle = importMapping.mapKeys { normalize(it.key) }
        val result = linkedMapOf<String, String>()
        schema.columns.forEach { column ->
            val key = byTitle[normalize(column.title)] ?: return@forEach
            val field = recordType.fields.firstOrNull { it.key == key } ?: return@forEach
            if (column.type in MondayColumnValues.compatible(field.type) && (ColumnPrefix + key) !in result) {
                result[ColumnPrefix + key] = optionLabel(column.title, column.id)
            }
        }
        return result
    }

    private fun normalize(text: String): String =
        java.text.Normalizer.normalize(text.trim().lowercase(), java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")

    /**
     * Arma la configuracion desde los valores del formulario. Lanza [MondayConfigException] con un mensaje en espanol si
     * algo no es valido; las columnas que ya no existen en el tablero (o no son compatibles) se descartan con aviso.
     */
    fun applyConfigForm(values: Map<String, String>, recordType: OutboundRecordType, schema: MondayBoardSchema?): MondayConfigResult {
        val warnings = mutableListOf<String>()
        fun flag(key: String, default: Boolean) = values[key]?.trim()?.toBooleanStrictOrNull() ?: default
        val enabled = flag("enabled", false)
        val board = optionId(values["board"])
        if (board != null && !board.matches(MondayBoards.idPattern)) throw MondayConfigException("El tablero elegido no es valido.")
        if (enabled && board == null) throw MondayConfigException("Elige un tablero para activar el envio.")
        val maxMb = values["maxFileMb"]?.trim()?.takeIf { it.isNotEmpty() }?.let {
            it.toIntOrNull() ?: throw MondayConfigException("El tamano maximo de archivo debe ser un numero entero de MB.")
        } ?: MondayOutboundConfig.DefaultMaxFileMb
        if (maxMb !in 1..MondayOutboundConfig.MaxFileMb) throw MondayConfigException("El tamano maximo de archivo debe estar entre 1 y ${MondayOutboundConfig.MaxFileMb} MB.")
        val country = values["phoneCountry"]?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }
        if (country != null && !(country.length == 2 && country.all { it in 'A'..'Z' })) throw MondayConfigException("El pais del telefono son 2 letras (p. ej. MX).")

        val columns = linkedMapOf<String, MondayColumnRef>()
        var group: String? = null
        var filesColumn: String? = null
        var keyColumn: String? = null
        if (schema != null && schema.id == board) {
            group = optionId(values["group"])?.takeIf { id -> schema.groups.any { it.id == id }.also { if (!it) warnings += "El grupo elegido ya no existe en el tablero; se usara el primero." } }
            recordType.fields.forEach { field ->
                val id = optionId(values[ColumnPrefix + field.key]) ?: return@forEach
                val column = schema.columns.firstOrNull { it.id == id }
                when {
                    column == null -> warnings += "La columna de \"${field.label}\" ya no existe en el tablero y se quito."
                    column.type !in MondayColumnValues.compatible(field.type) -> warnings += "La columna \"${column.title}\" no acepta el campo \"${field.label}\" y se quito."
                    else -> columns[field.key] = MondayColumnRef(column.id, column.type)
                }
            }
            filesColumn = optionId(values["filesColumn"])?.takeIf { id -> schema.columns.any { it.id == id && it.type == "file" }.also { if (!it) warnings += "La columna de archivos ya no existe y se quito." } }
            keyColumn = optionId(values["keyColumn"])?.takeIf { id -> schema.columns.any { it.id == id && it.type == "text" }.also { if (!it) warnings += "La columna clave ya no existe y se quito." } }
        } else if (board != null) {
            warnings += "No se pudieron leer las columnas del tablero; guarda de nuevo para asignarlas."
        }
        val config = MondayOutboundConfig(
            recordType = recordType.id, enabled = enabled, boardId = board, groupId = group,
            sendCreated = flag("sendCreated", true), sendUpdated = flag("sendUpdated", true), sendNotes = flag("sendNotes", true),
            sendFiles = flag("sendFiles", true), sendImports = flag("sendImports", false),
            columns = columns, filesColumn = filesColumn, keyColumn = keyColumn, maxFileMb = maxMb, phoneCountry = country,
        )
        return MondayConfigResult(config, warnings)
    }

    /**
     * Tarjetas de estado: resumen, fallidos con su razon y botones, pendientes con error (ya se intentaron), y ultimos
     * enviados con la lista aparte de los que llevan avisos. [pending] puede traer todos los pendientes: aqui se
     * muestran solo los que ya tuvieron algun intento.
     */
    fun statusNodes(
        summary: MondayQueueSummary,
        failed: List<MondayQueueEvent>,
        pending: List<MondayQueueEvent>,
        sent: List<MondayQueueEvent>,
        action: MondayActionFactory,
    ): List<UiNode> {
        val withErrors = pending.filter { it.attempts > 0 }
        val withWarnings = sent.filter { it.hasWarnings }
        val metrics = UiGrid(
            listOf(
                UiMetric("Pendientes", (summary.pending + summary.sending).toString(), caption = if (withErrors.isEmpty()) "" else "${withErrors.size} con error", captionTone = if (withErrors.isEmpty()) UiTone.Neutral else UiTone.Warning),
                UiMetric("Enviados", summary.sent.toString(), caption = if (withWarnings.isEmpty()) "" else "${withWarnings.size} con avisos", captionTone = UiTone.Warning),
                UiMetric("Fallidos", summary.failed.toString(), captionTone = if (summary.failed > 0) UiTone.Danger else UiTone.Neutral),
                UiMetric("Omitidos", summary.skipped.toString()),
            ),
            minItemWidth = 140,
        )
        val failedCard = UiCard(
            if (failed.isEmpty()) listOf(UiText("No hay envios fallidos.", tone = UiTone.Success))
            else failed.flatMap { event ->
                listOf(
                    UiText("${describe(event)}: ${event.detail.ifEmpty { "sin detalle" }}", tone = UiTone.Danger),
                    UiRow(
                        listOf(
                            UiButton("Reintentar", action("retry", mapOf("id" to event.id.toString())), style = "secondary"),
                            UiButton("Descartar", confirmed(action("discard", mapOf("id" to event.id.toString())), "Se descarta este envio y no se enviara a monday. Continuar?"), style = "danger"),
                        ),
                    ),
                )
            } + UiButton("Reintentar todos", action("retryAll", emptyMap()), style = "secondary"),
            title = "Fallidos",
        )
        val pendingCard = withErrors.takeIf { it.isNotEmpty() }?.let { list ->
            UiCard(
                list.flatMap { event ->
                    listOf(
                        UiText(
                            "${describe(event)}: intento ${event.attempts}, ${event.detail.ifEmpty { "sin detalle" }}" +
                                (event.nextAttemptAt?.let { " (proximo intento ${it.toString().take(16).replace('T', ' ')} UTC)" }.orEmpty()),
                            tone = UiTone.Warning,
                        ),
                        UiButton("Reintentar ahora", action("retry", mapOf("id" to event.id.toString())), style = "secondary"),
                    )
                },
                title = "Pendientes con error",
            )
        }
        val warningsCard = withWarnings.takeIf { it.isNotEmpty() }?.let { list ->
            UiCard(
                listOf(
                    UiList(list.map { UiListItem(describe(it), it.detail, it.at?.toString()?.take(16)?.replace('T', ' ').orEmpty(), listOf(UiBadge("con avisos", UiTone.Warning))) }),
                    UiText("Lo que se omitio sin fallar: un campo sin columna, una mencion sin usuario en monday, un valor que la columna no acepta.", UiTextStyle.Caption),
                ),
                title = "Enviados con avisos",
            )
        }
        val sentCard = UiCard(
            listOf(
                UiList(
                    sent.map { event ->
                        UiListItem(
                            title = describe(event),
                            subtitle = event.detail,
                            trailing = event.at?.toString()?.take(16)?.replace('T', ' ').orEmpty(),
                            badges = if (event.hasWarnings) listOf(UiBadge("con avisos", UiTone.Warning)) else emptyList(),
                        )
                    },
                    emptyText = "Todavia no se ha enviado nada.",
                ),
            ),
            title = "Ultimos enviados",
        )
        return listOfNotNull(UiCard(listOf(metrics), title = "Envio a monday"), failedCard, pendingCard, warningsCard, sentCard)
    }

    private fun confirmed(action: UiAction, question: String): UiAction = if (action is UiRequest) action.copy(confirm = question) else action

    private fun describe(event: MondayQueueEvent): String {
        val kind = when (event.kind) {
            "created" -> "Alta"
            "updated" -> "Cambio"
            "note" -> "Nota"
            "file" -> "Archivo"
            else -> event.kind
        }
        return "$kind de ${event.recordType} ${event.recordId.take(36)}"
    }
}
