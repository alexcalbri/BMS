package com.ideasdeveloper.bms.extensions.propietarios_monday

import com.ideasdeveloper.bms.modules.propietarios.PropietariosImportContext
import com.ideasdeveloper.bms.modules.propietarios.PropietariosImportReply
import com.ideasdeveloper.bms.modules.propietarios.PropietariosModule
import com.ideasdeveloper.bms.server.i18n.msg
import com.ideasdeveloper.bms.server.i18n.tr
import com.ideasdeveloper.bms.server.modules.ModuleAccessService
import com.ideasdeveloper.bms.server.modules.ModulePermissions
import com.ideasdeveloper.bms.server.security.SecretBox
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
import com.ideasdeveloper.bms.ui.UiTable
import com.ideasdeveloper.bms.ui.UiTableColumn
import com.ideasdeveloper.bms.ui.UiText
import com.ideasdeveloper.bms.ui.UiTextStyle
import com.ideasdeveloper.bms.ui.UiTone
import java.sql.Connection
import java.sql.SQLException
import java.util.UUID

/** Pasos y operaciones del flujo de la pantalla Importar de esta fuente. */
object MondayOp {
    const val Token = "token"
    const val Forget = "forget"
    const val Boards = "boards"
    const val Map = "map"
    const val Read = "read"
    const val Apply = "apply"
    const val Cancel = "cancel"
}

/** Un desarrollo existente (no archivado) que se puede elegir como destino. */
data class MondayDevelopment(val id: UUID, val name: String)

/** Lo que comparten la pantalla y las herramientas MCP: leer un tablero con el token guardado y revisar el mapeo y el destino. */
object PropietariosMondayFlow {
    val NoColumn = msg("No usar")
    val NewDevelopment = msg("Desarrollo nuevo (con el nombre del tablero)")
    const val GroupOption = "Titulo del grupo"
    const val ItemOption = "Nombre del elemento"
    private const val OptionSeparator = " · "

    /** Texto de la opcion de una columna en los selectores; el id va al final para recuperarlo aunque haya nombres repetidos. */
    fun columnOption(column: MondayColumn): String = "${column.title}$OptionSeparator${column.id}"

    /** Texto de la opcion que corresponde al valor de mapeo de un dato (columna, grupo o elemento). */
    fun optionOf(value: String?, byId: Map<String, MondayColumn>): String = when (value) {
        null -> NoColumn
        PropietariosMondayMapping.GroupSource -> "$GroupOption${OptionSeparator}${PropietariosMondayMapping.GroupSource}"
        PropietariosMondayMapping.ItemSource -> "$ItemOption${OptionSeparator}${PropietariosMondayMapping.ItemSource}"
        else -> byId[value]?.let(::columnOption) ?: NoColumn
    }

    fun columnIdOf(option: String): String? =
        option.takeIf { it.contains(OptionSeparator) }?.substringAfterLast(OptionSeparator)?.trim()?.takeIf { it.isNotEmpty() }

    fun developmentOption(development: MondayDevelopment): String = "${development.name}$OptionSeparator${development.id}"

    /** Id del desarrollo elegido como destino; null con "Desarrollo nuevo" o un valor que no es un id. */
    fun targetOf(option: String?): UUID? =
        option?.substringAfterLast(OptionSeparator, "")?.trim()?.takeIf { it.isNotEmpty() }?.let { runCatching { UUID.fromString(it) }.getOrNull() }

    /** Desarrollos no archivados: todo quien puede importar ve todos los desarrollos (decision del modulo). */
    fun developments(tenant: Connection): List<MondayDevelopment> = try {
        tenant.prepareStatement("SELECT id, name FROM propietarios_developments WHERE archived_at IS NULL ORDER BY lower(name)").use { query ->
            query.executeQuery().use { rows -> buildList { while (rows.next()) add(MondayDevelopment(rows.getObject(1, UUID::class.java), rows.getString(2))) } }
        }
    } catch (failure: SQLException) {
        emptyList()
    }

    /** Quien queda como autor de lo importado: el usuario; un dueno del servidor no es usuario de la empresa. */
    fun actorOf(permissions: ModulePermissions): UUID? = permissions.userId?.takeUnless { permissions.serverOwner }

    val TargetDenied = msg("Ese desarrollo no existe o esta archivado.")

    /** Revisa el destino elegido: nuevo (null) o un desarrollo existente no archivado. */
    fun requireDestination(tenant: Connection, targetDevelopmentId: UUID?) {
        if (targetDevelopmentId != null && developments(tenant).none { it.id == targetDevelopmentId }) throw PropietariosMondayException(tr(TargetDenied))
    }

    /** Aplicar o cancelar una importacion: solo quien la inicio o el dueno del negocio. */
    fun requireJobControl(permissions: ModulePermissions, job: MondayJob) {
        val actor = actorOf(permissions)
        if (actor != null && job.createdBy == actor) return
        if (permissions.businessOwner || permissions.serverOwner) return
        throw PropietariosMondayException(tr("Solo quien inicio la importacion o el dueno del negocio puede aplicarla o cancelarla."))
    }

    /** Problema del mapeo que impide importar, o null: solo se revisa que las columnas elegidas sigan existiendo. */
    fun mappingProblem(mapping: MondayMapping, columns: List<MondayColumn>): String? {
        val ids = columns.map { it.id }.toSet() + PropietariosMondayMapping.GroupSource + PropietariosMondayMapping.ItemSource
        return if (mapping.columns.values.any { it !in ids }) tr("El mapeo usa una columna que ya no existe en el tablero.") else null
    }

    /** Lee el tablero en segundo plano con el token guardado; el destino y el mapeo se comprueban antes de encolar. */
    fun startRead(
        access: ModuleAccessService,
        tenant: Connection,
        permissions: ModulePermissions,
        boardId: String,
        mapping: MondayMapping,
        targetDevelopmentId: UUID?,
    ): UUID {
        requireDestination(tenant, targetDevelopmentId)
        val token = PropietariosMondaySettings.loadToken(access.secrets, tenant)
            ?: throw PropietariosMondayException(tr("Guarda primero el token de la API de monday (pantalla Importar)."))
        val board = PropietariosMondayClient.boardInfo(token, boardId)
        mappingProblem(mapping, board.columns)?.let { throw PropietariosMondayException(it) }
        return PropietariosMondayJobs.queueRead(tenant, boardId, board.name, targetDevelopmentId, mapping, actorOf(permissions))
    }

    fun statusLabel(status: String): String = when (status) {
        MondayJobStatus.Reading -> tr("Leyendo")
        MondayJobStatus.Preview -> tr("Por revisar")
        MondayJobStatus.Applying -> tr("Aplicando")
        MondayJobStatus.Done -> tr("Terminada")
        MondayJobStatus.Failed -> tr("Con error")
        MondayJobStatus.Cancelled -> tr("Cancelada")
        else -> status
    }

    fun statusTone(status: String): String = when (status) {
        MondayJobStatus.Done -> UiTone.Success
        MondayJobStatus.Failed -> UiTone.Danger
        MondayJobStatus.Cancelled -> UiTone.Neutral
        MondayJobStatus.Preview -> UiTone.Warning
        else -> UiTone.Info
    }
}

/** Pantalla de la fuente dentro de Importar. Muestra el token solo como "configurado"; nunca su valor. */
object PropietariosMondayScreens {
    private const val Route = "${PropietariosModule.UiRoute}/importar"
    private const val MaxListed = 20
    val ReimportWarning = msg("Reimportar el mismo tablero actualiza los lotes ya importados y sobrescribe los cambios hechos en Propietarios a esos campos.")

    private fun navigate(params: Map<String, String> = emptyMap()) = UiNavigate("importar", mapOf("source" to PropietariosMondayId) + params)

    private fun post(op: String, params: Map<String, String> = emptyMap(), confirm: String? = null, formId: String? = null) =
        UiRequest(Route, "POST", mapOf("source" to PropietariosMondayId, "op" to op) + params, confirm, formId)

    fun screen(context: PropietariosImportContext, params: Map<String, String>): List<UiNode> {
        if (!context.permissions.has(PropietariosImportPermission)) {
            return listOf(UiText(tr("No tienes permiso para importar datos."), tone = UiTone.Warning))
        }
        return when (params["step"]) {
            "boards" -> boardsStep(context)
            "map" -> mapStep(context, params["board"].orEmpty())
            "job" -> params["job"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }?.let { jobStep(context.tenant, it) } ?: home(context)
            else -> home(context)
        }
    }

    // ---- Inicio: token e historial ----

    private fun home(context: PropietariosImportContext): List<UiNode> {
        val tenant = context.tenant
        val configured = PropietariosMondaySettings.configured(tenant)
        val tokenCard = UiCard(
            listOfNotNull(
                UiText(if (configured) tr("El token de monday esta configurado.") else tr("Aun no hay un token de monday guardado."), tone = if (configured) UiTone.Success else UiTone.Warning),
                // El campo nunca se rellena: el token guardado no se vuelve a mostrar.
                UiForm(
                    "mondayToken",
                    listOf(
                        UiField(
                            "token", tr("Token de la API de monday"), type = "password", required = !configured,
                            help = tr("monday > tu avatar > Developers > Mi token. Se guarda sellado para la empresa y no se vuelve a mostrar."),
                        ),
                    ),
                    submit = UiButton(if (configured) tr("Cambiar token") else tr("Guardar token"), post(MondayOp.Token, formId = "mondayToken")),
                ),
                if (configured) {
                    UiRow(
                        listOf(
                            UiButton(tr("Cargar tableros"), post(MondayOp.Boards)),
                            UiButton(tr("Quitar token"), post(MondayOp.Forget, confirm = tr("Se borra el token de monday guardado. Continuar?")), style = "secondary"),
                        ),
                    )
                } else {
                    null
                },
            ),
            title = "monday",
        )
        val history = UiCard(
            listOf(
                UiList(
                    PropietariosMondayJobs.recent(tenant, 8).map { job ->
                        UiListItem(
                            title = job.boardName.ifBlank { tr("Tablero {0}", job.boardId) },
                            subtitle = if (job.message.isBlank()) tr("{0} elementos", job.total) else tr(job.message),
                            trailing = job.createdAt.toString().take(10),
                            badges = listOf(UiBadge(PropietariosMondayFlow.statusLabel(job.status), PropietariosMondayFlow.statusTone(job.status))),
                            action = navigate(mapOf("step" to "job", "job" to job.id.toString())),
                        )
                    },
                    emptyText = tr("Todavia no hay importaciones."),
                ),
            ),
            title = tr("Importaciones"),
        )
        return listOf(tokenCard, history)
    }

    // ---- Tableros ----

    private fun boardsStep(context: PropietariosImportContext): List<UiNode> {
        val back = UiButton(tr("Volver"), navigate(), style = "secondary")
        val token = PropietariosMondaySettings.loadToken(context.access.secrets, context.tenant)
            ?: return listOf(UiText(tr("Guarda primero el token de la API de monday."), tone = UiTone.Danger), back)
        val boards = try {
            PropietariosMondayClient.boards(token)
        } catch (failure: PropietariosMondayException) {
            return listOf(UiText(failure.message.orEmpty(), tone = UiTone.Danger), back)
        }
        if (boards.isEmpty()) return listOf(UiText(tr("El token no tiene acceso a ningun tablero activo."), tone = UiTone.Warning), back)
        return listOf(
            UiCard(
                listOf(
                    UiText(tr("Elige el tablero de monday que quieres importar. Cada elemento del tablero pasa a ser un lote con su propietario.")),
                    UiList(
                        boards.map { board ->
                            UiListItem(
                                title = board.name,
                                subtitle = if (board.workspace == PropietariosMondayClient.MainWorkspace) tr("Espacio principal") else board.workspace,
                                trailing = board.items?.let { tr("{0} elementos", it) }.orEmpty(),
                                action = post(MondayOp.Map, mapOf("board" to board.id)),
                            )
                        },
                    ),
                    back,
                ),
                title = tr("Tableros de monday"),
            ),
        )
    }

    // ---- Destino y mapeo ----

    private fun mapStep(context: PropietariosImportContext, boardId: String): List<UiNode> {
        val back = UiButton(tr("Elegir otro tablero"), post(MondayOp.Boards), style = "secondary")
        val token = PropietariosMondaySettings.loadToken(context.access.secrets, context.tenant)
            ?: return listOf(UiText(tr("Guarda primero el token de la API de monday."), tone = UiTone.Danger))
        val board = try {
            PropietariosMondayClient.boardInfo(token, boardId)
        } catch (failure: PropietariosMondayException) {
            return listOf(UiText(failure.message.orEmpty(), tone = UiTone.Danger), back)
        }
        val suggested = PropietariosMondayMapping.suggest(board.columns)
        val byId = board.columns.associateBy { it.id }
        val usable = board.columns.filter { it.type != "name" && it.type != "subitems" }.map(PropietariosMondayFlow::columnOption)
        val columnOptions = listOf(PropietariosMondayFlow.NoColumn) + usable
        val ownerOptions = listOf(
            PropietariosMondayFlow.optionOf(PropietariosMondayMapping.GroupSource, byId),
            PropietariosMondayFlow.optionOf(PropietariosMondayMapping.ItemSource, byId),
        ) + usable
        val destinations = listOf(PropietariosMondayFlow.NewDevelopment) + PropietariosMondayFlow.developments(context.tenant).map(PropietariosMondayFlow::developmentOption)
        val fields = listOf(
            UiField("board", tr("Id del tablero de monday"), readOnly = true),
            UiField("target", tr("Desarrollo destino"), type = "dropdown", options = destinations, help = tr("Un desarrollo nuevo con el nombre del tablero, o uno existente al que se agregan los lotes.")),
        ) + MondayTarget.all.map { definition ->
            UiField("map:${definition.target}", tr(definition.label), type = "dropdown", options = if (definition.target == MondayTarget.Owner) ownerOptions else columnOptions)
        }
        val values = mapOf("board" to board.id, "target" to destinations.first()) + MondayTarget.all.associate { definition ->
            "map:${definition.target}" to PropietariosMondayFlow.optionOf(suggested.columns[definition.target], byId)
        }
        return listOf(
            UiCard(
                listOf(
                    UiText(tr("Tablero: {0}. Revisa a que columna va cada dato; ya van elegidas las que parecen corresponder.", board.name), UiTextStyle.Body),
                    UiText(tr("Cada elemento es un lote. El propietario sale del titulo del grupo, del nombre del elemento o de una columna. Si hay varios correos separados por coma o punto y coma, cada uno es un titular. Los archivos pasan a adjuntos del lote y las columnas sin destino se agregan a las notas como \"Columna: valor\"."), UiTextStyle.Caption),
                    UiForm(
                        "mondayMapping", fields, values,
                        submit = UiButton(tr("Leer tablero"), post(MondayOp.Read, mapOf("board" to board.id), formId = "mondayMapping")),
                    ),
                    back,
                ),
                title = tr("Destino y mapeo de columnas"),
            ),
        )
    }

    // ---- Trabajo ----

    private fun jobStep(tenant: Connection, id: UUID): List<UiNode> {
        val back = UiButton(tr("Volver"), navigate(), style = "secondary")
        val job = PropietariosMondayJobs.find(tenant, id) ?: return listOf(UiText(tr("No se encontro la importacion."), tone = UiTone.Danger), back)
        val refresh = UiButton(tr("Actualizar"), navigate(mapOf("step" to "job", "job" to id.toString())), style = "secondary")
        val cancel = UiButton(tr("Cancelar importacion"), post(MondayOp.Cancel, mapOf("job" to id.toString()), confirm = tr("Se cancela la importacion. Lo ya importado se conserva. Continuar?")), style = "danger")
        val header = UiText(tr("Tablero: {0} · {1}", job.boardName.ifBlank { job.boardId }, PropietariosMondayFlow.statusLabel(job.status)), UiTextStyle.Subtitle)
        return when (job.status) {
            MondayJobStatus.Reading -> listOf(
                header,
                UiText(tr("Se esta leyendo el tablero. Un tablero grande tarda unos minutos; actualiza esta pantalla para seguir.")),
                UiText(tr("Elementos leidos: {0}", job.total), UiTextStyle.Caption),
                UiRow(listOf(refresh, cancel)),
            )
            MondayJobStatus.Preview -> previewNodes(job, header, cancel)
            MondayJobStatus.Applying -> listOf(
                header,
                progress(job),
                UiText(tr("Se importan unos 100 lotes por minuto. Puedes salir de esta pantalla; la importacion sigue sola."), UiTextStyle.Caption),
                UiRow(listOf(refresh, cancel)),
            )
            MondayJobStatus.Done -> listOf(header, UiText(tr(job.message), tone = UiTone.Success), progress(job)) + problemNodes(tenant, job) + back
            MondayJobStatus.Failed -> listOf(header, UiText(if (job.message.isBlank()) tr("No se pudo completar la importacion.") else tr(job.message), tone = UiTone.Danger), progress(job), back)
            else -> listOf(header, UiText(if (job.message.isBlank()) tr("Importacion cancelada.") else tr(job.message), tone = UiTone.Warning), progress(job), back)
        }
    }

    private fun progress(job: MondayJob): UiNode = UiGrid(
        listOf(
            UiMetric(tr("Procesadas"), tr("{0} de {1}", job.applied, job.total)),
            UiMetric(tr("Creados"), job.created.toString()),
            UiMetric(tr("Actualizados"), job.updated.toString()),
            UiMetric(tr("Omitidos"), job.skipped.toString()),
            UiMetric(tr("Con error"), job.failed.toString()),
            UiMetric(tr("Archivos"), job.filesDone.toString(), caption = if (job.filesFailed > 0) tr("{0} con error", job.filesFailed) else "", captionTone = if (job.filesFailed > 0) UiTone.Warning else UiTone.Neutral),
        ),
        minItemWidth = 140,
    )

    private fun previewNodes(job: MondayJob, header: UiNode, cancel: UiNode): List<UiNode> {
        // El resumen se calculo y guardo una vez al terminar de leer: la pantalla solo lo muestra.
        val summary = job.summary ?: return listOf(header, UiText(tr("La vista previa de esta importacion no esta disponible; cancelala y vuelve a leer el tablero."), tone = UiTone.Danger), cancel)
        val nodes = mutableListOf<UiNode>(
            header,
            UiGrid(
                listOf(
                    UiMetric(tr("Lotes"), summary.lots.toString()),
                    UiMetric(tr("Desarrollo"), if (job.targetDevelopmentId == null) tr("Nuevo: {0}", job.boardName.ifBlank { job.boardId }) else tr("Existente")),
                    UiMetric(tr("Titulares"), summary.owners.toString()),
                    UiMetric(tr("Sin correo"), summary.ownersWithoutEmailCount.toString(), captionTone = if (summary.ownersWithoutEmailCount == 0) UiTone.Success else UiTone.Warning),
                    UiMetric(tr("Area total (m2)"), summary.totalAreaM2),
                    UiMetric(tr("Lotes duplicados"), summary.duplicateLots.size.toString(), captionTone = if (summary.duplicateLots.isEmpty()) UiTone.Success else UiTone.Warning),
                    UiMetric(tr("Archivos"), summary.files.toString()),
                    UiMetric(tr("Problemas"), summary.problemCount.toString(), captionTone = if (summary.problemCount == 0) UiTone.Success else UiTone.Warning),
                ),
                minItemWidth = 140,
            ),
            UiText(tr(ReimportWarning), UiTextStyle.Caption, tone = UiTone.Warning),
        )
        if (summary.developments.isNotEmpty()) {
            nodes += UiText(tr("Desarrollos: {0}.", summary.developments.take(MaxListed).joinToString()), UiTextStyle.Caption)
        }
        if (summary.ownersWithoutEmail.isNotEmpty()) {
            nodes += UiText(
                if (summary.ownersWithoutEmailCount > MaxListed) tr("Titulares sin correo (se emparejan por nombre): {0} y {1} mas.", summary.ownersWithoutEmail.take(MaxListed).joinToString(), summary.ownersWithoutEmailCount - MaxListed) else tr("Titulares sin correo (se emparejan por nombre): {0}.", summary.ownersWithoutEmail.take(MaxListed).joinToString()),
                UiTextStyle.Caption, tone = UiTone.Warning,
            )
        }
        if (summary.duplicateLots.isNotEmpty()) {
            nodes += UiText(tr("Lotes repetidos en el tablero: {0}.", summary.duplicateLots.take(MaxListed).joinToString()), UiTextStyle.Caption, tone = UiTone.Warning)
        }
        if (summary.problems.isNotEmpty()) {
            nodes += UiCard(
                summary.problems.take(MaxListed).map { UiText(it) } +
                    listOfNotNull(summary.problemCount.takeIf { it > MaxListed }?.let { UiText(tr("Y {0} problemas mas.", it - MaxListed), UiTextStyle.Caption) }) +
                    UiText(tr("Las filas con problemas se omiten o se recortan al aplicar."), UiTextStyle.Caption),
                title = tr("Problemas encontrados"),
            )
        }
        nodes += UiRow(
            listOf(
                UiButton(tr("Aplicar importacion"), post(MondayOp.Apply, mapOf("job" to job.id.toString()), confirm = tr("Se crean o actualizan {0} lotes con sus titulares y archivos. {1} Continuar?", summary.lots, tr(ReimportWarning)))),
                cancel,
            ),
        )
        return nodes
    }

    private fun problemNodes(tenant: Connection, job: MondayJob): List<UiNode> {
        val rows = PropietariosMondayJobs.problems(tenant, job.id, 50)
        if (rows.isEmpty()) return emptyList()
        return listOf(
            UiCard(
                listOf(
                    UiTable(
                        listOf(UiTableColumn("row", tr("Fila")), UiTableColumn("lot", tr("Lote")), UiTableColumn("result", tr("Resultado")), UiTableColumn("message", tr("Motivo"))),
                        rows.map { mapOf("row" to it.rowNo.toString(), "lot" to it.title, "result" to resultLabel(it.outcome), "message" to tr(it.message)) },
                    ),
                ),
                title = tr("Filas con problemas"),
            ),
        )
    }

    private fun resultLabel(outcome: String) = when (outcome) {
        "failed" -> tr("Error")
        "skipped" -> tr("Omitida")
        else -> tr("Con avisos")
    }

    // ---- Acciones (POST importar?source=propietarios_monday&op=...) ----

    private fun failure(message: String, params: Map<String, String> = emptyMap()) = PropietariosImportReply(message, error = true, params = params)

    fun action(context: PropietariosImportContext, op: String, values: Map<String, String>): PropietariosImportReply {
        if (!context.permissions.has(PropietariosImportPermission)) return failure(tr("No tienes permiso para importar datos."))
        val tenant = context.tenant
        return try {
            when (op) {
                MondayOp.Token -> saveToken(context, values)
                MondayOp.Forget -> {
                    PropietariosMondaySettings.saveToken(context.access.secrets, tenant, null)
                    PropietariosImportReply(tr("Token de monday borrado."))
                }
                MondayOp.Boards -> PropietariosImportReply(tr("Elige un tablero."), params = mapOf("step" to "boards"))
                MondayOp.Map -> {
                    val board = values["board"].orEmpty().trim()
                    if (!PropietariosMondayClient.isBoardId(board)) failure(tr("Elige un tablero."), mapOf("step" to "boards"))
                    else PropietariosImportReply(tr("Revisa el destino y el mapeo de columnas."), params = mapOf("step" to "map", "board" to board))
                }
                MondayOp.Read -> read(context, values)
                MondayOp.Apply -> {
                    val id = jobId(values)
                    controlled(context, id)
                    if (PropietariosMondayJobs.requestApply(tenant, id)) {
                        PropietariosImportReply(tr("Se esta importando."), params = mapOf("step" to "job", "job" to id.toString()))
                    } else {
                        failure(tr("Esta importacion ya no esta en revision."), mapOf("step" to "job", "job" to id.toString()))
                    }
                }
                MondayOp.Cancel -> {
                    val id = jobId(values)
                    controlled(context, id)
                    val done = PropietariosMondayJobs.cancel(tenant, id)
                    PropietariosImportReply(if (done) tr("Importacion cancelada.") else tr("Esta importacion ya habia terminado."), error = !done, params = mapOf("step" to "job", "job" to id.toString()))
                }
                else -> failure(tr("Operacion desconocida."))
            }
        } catch (problem: PropietariosMondayException) {
            failure(problem.message.orEmpty())
        }
    }

    // Solo quien inicio la importacion o el dueno del negocio la aplica o cancela.
    private fun controlled(context: PropietariosImportContext, id: UUID) {
        val job = PropietariosMondayJobs.find(context.tenant, id) ?: throw PropietariosMondayException(tr("No se encontro la importacion."))
        PropietariosMondayFlow.requireJobControl(context.permissions, job)
    }

    private fun jobId(values: Map<String, String>): UUID =
        runCatching { UUID.fromString(values["job"].orEmpty().trim()) }.getOrNull() ?: throw PropietariosMondayException(tr("No se encontro la importacion."))

    private fun saveToken(context: PropietariosImportContext, values: Map<String, String>): PropietariosImportReply {
        val tenant = context.tenant
        val typed = values["token"].orEmpty().trim().takeIf { it.isNotEmpty() && it != SecretBox.Mask }
        if (typed == null && !PropietariosMondaySettings.configured(tenant)) return failure(tr("Escribe el token de la API de monday."))
        typed?.let { PropietariosMondaySettings.saveToken(context.access.secrets, tenant, it) }
        return PropietariosImportReply(tr("Token de monday guardado."), params = mapOf("step" to "boards"))
    }

    private fun read(context: PropietariosImportContext, values: Map<String, String>): PropietariosImportReply {
        val board = values["board"].orEmpty().trim()
        if (!PropietariosMondayClient.isBoardId(board)) return failure(tr("Elige un tablero."), mapOf("step" to "boards"))
        val chosen = MondayTarget.all.mapNotNull { definition ->
            values["map:${definition.target}"]?.let(PropietariosMondayFlow::columnIdOf)?.let { definition.target to it }
        }.toMap()
        val target = PropietariosMondayFlow.targetOf(values["target"])
        val id = try {
            PropietariosMondayFlow.startRead(context.access, context.tenant, context.permissions, board, MondayMapping(chosen), target)
        } catch (problem: PropietariosMondayException) {
            return failure(problem.message.orEmpty(), mapOf("step" to "map", "board" to board))
        }
        return PropietariosImportReply(tr("Se esta leyendo el tablero de monday."), params = mapOf("step" to "job", "job" to id.toString()))
    }
}
