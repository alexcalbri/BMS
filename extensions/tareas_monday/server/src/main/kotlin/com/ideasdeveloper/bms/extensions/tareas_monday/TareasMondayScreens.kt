package com.ideasdeveloper.bms.extensions.tareas_monday

import com.ideasdeveloper.bms.modules.tareas.Board
import com.ideasdeveloper.bms.modules.tareas.TareasImportContext
import com.ideasdeveloper.bms.modules.tareas.TareasImportReply
import com.ideasdeveloper.bms.modules.tareas.TareasModule
import com.ideasdeveloper.bms.modules.tareas.TareasRepository
import com.ideasdeveloper.bms.modules.tareas.TareasService
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

/** Lo que comparten la pantalla y las herramientas MCP: leer un tablero con el token guardado y revisar el mapeo y el destino. */
object TareasMondayFlow {
    val NoColumn = msg("No usar")
    val NewBoard = msg("Nuevo tablero")
    private const val OptionSeparator = " · "

    /** Texto de la opcion de una columna o tablero en los selectores; el id va al final para recuperarlo aunque haya nombres repetidos. */
    fun columnOption(column: MondayColumn): String = "${column.title}$OptionSeparator${column.id}"

    fun columnIdOf(option: String): String? =
        option.takeIf { it.contains(OptionSeparator) }?.substringAfterLast(OptionSeparator)?.trim()?.takeIf { it.isNotEmpty() }

    fun boardOption(board: Board): String = "${board.name}$OptionSeparator${board.id}"

    /** Id del tablero de Tareas elegido como destino; null con "Nuevo tablero" o un valor que no es un id. */
    fun targetOf(option: String?): UUID? =
        option?.substringAfterLast(OptionSeparator, "")?.trim()?.takeIf { it.isNotEmpty() }?.let { runCatching { UUID.fromString(it) }.getOrNull() }

    /** Tableros de Tareas que quien importa ve (los mismos que ve en la app). */
    fun visibleBoards(tenant: Connection, permissions: ModulePermissions): List<Board> =
        TareasRepository.listBoards(tenant, TareasService.scope(tenant, permissions)).filter { !it.archived }

    /** Quien queda como autor de lo importado: el usuario; un dueno del servidor no es usuario de la empresa. */
    fun actorOf(permissions: ModulePermissions): UUID? = permissions.userId?.takeUnless { permissions.serverOwner }

    val NewBoardDenied = msg("Crear un tablero nuevo requiere administrar los tableros de Tareas; elige un tablero existente.")
    val TargetDenied = msg("No tienes permiso para agregar tareas a ese tablero (o no existe).")

    /**
     * Opciones de destino de quien importa: tablero nuevo solo si administra tableros; uno existente solo si puede
     * editar tareas y lo ve (los mismos permisos que tendria al crear las tareas a mano).
     */
    fun destinations(tenant: Connection, permissions: ModulePermissions): Pair<Boolean, List<Board>> {
        val scope = TareasService.scope(tenant, permissions)
        val boards = if (scope.canManage) TareasRepository.listBoards(tenant, scope).filter { !it.archived && scope.seesBoard(it) } else emptyList()
        return scope.admin to boards
    }

    /** Revisa el destino elegido contra los permisos vivos; lanza con un mensaje claro si falta alguno. */
    fun requireDestination(tenant: Connection, permissions: ModulePermissions, targetBoardId: UUID?) {
        val (canCreate, boards) = destinations(tenant, permissions)
        if (targetBoardId == null) {
            if (!canCreate) throw TareasMondayException(tr(NewBoardDenied))
        } else if (boards.none { it.id == targetBoardId }) {
            throw TareasMondayException(tr(TargetDenied))
        }
    }

    /** Aplicar o cancelar una importacion: solo quien la inicio o un administrador de tableros. */
    fun requireJobControl(tenant: Connection, permissions: ModulePermissions, job: MondayJob) {
        val actor = actorOf(permissions)
        if (actor != null && job.createdBy == actor) return
        if (TareasService.scope(tenant, permissions).admin) return
        throw TareasMondayException(tr("Solo quien inicio la importacion o un administrador de tableros puede aplicarla o cancelarla."))
    }

    /** Problema del mapeo que impide importar, o null: solo se revisa que las columnas elegidas sigan existiendo. */
    fun mappingProblem(mapping: MondayMapping, columns: List<MondayColumn>): String? {
        val ids = columns.map { it.id }.toSet()
        return if (mapping.columns.filterKeys { it != MondayTarget.Comments }.values.any { it !in ids }) tr("El mapeo usa una columna que ya no existe en el tablero.") else null
    }

    /** Lee el tablero en segundo plano con el token guardado; el destino, el mapeo y los permisos se comprueban antes de encolar. */
    fun startRead(
        access: ModuleAccessService,
        tenant: Connection,
        permissions: ModulePermissions,
        boardId: String,
        mapping: MondayMapping,
        targetBoardId: UUID?,
    ): UUID {
        requireDestination(tenant, permissions, targetBoardId)
        val token = TareasMondaySettings.loadToken(access.secrets, tenant)
            ?: throw TareasMondayException(tr("Guarda primero el token de la API de monday (pantalla Importar)."))
        val board = TareasMondayClient.boardInfo(token, boardId)
        mappingProblem(mapping, board.columns)?.let { throw TareasMondayException(it) }
        return TareasMondayJobs.queueRead(tenant, boardId, board.name, targetBoardId, mapping, actorOf(permissions), permissions.activityActor)
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
object TareasMondayScreens {
    private const val Route = "${TareasModule.UiRoute}/importar"
    private const val MaxListed = 20
    val ReimportWarning = msg("Reimportar el mismo tablero actualiza las tareas ya importadas y sobrescribe los cambios hechos en Tareas a esos campos.")

    private fun navigate(params: Map<String, String> = emptyMap()) = UiNavigate("importar", mapOf("source" to TareasMondayId) + params)

    private fun post(op: String, params: Map<String, String> = emptyMap(), confirm: String? = null, formId: String? = null) =
        UiRequest(Route, "POST", mapOf("source" to TareasMondayId, "op" to op) + params, confirm, formId)

    fun screen(context: TareasImportContext, params: Map<String, String>): List<UiNode> {
        if (!context.permissions.has(TareasImportPermission)) {
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

    private fun home(context: TareasImportContext): List<UiNode> {
        val tenant = context.tenant
        val configured = TareasMondaySettings.configured(tenant)
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
                    TareasMondayJobs.recent(tenant, 8).map { job ->
                        UiListItem(
                            title = job.boardName.ifBlank { tr("Tablero {0}", job.boardId) },
                            subtitle = job.message.ifBlank { tr("{0} elementos", job.total) },
                            trailing = job.createdAt.toString().take(10),
                            badges = listOf(UiBadge(TareasMondayFlow.statusLabel(job.status), TareasMondayFlow.statusTone(job.status))),
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

    private fun boardsStep(context: TareasImportContext): List<UiNode> {
        val back = UiButton(tr("Volver"), navigate(), style = "secondary")
        val token = TareasMondaySettings.loadToken(context.access.secrets, context.tenant)
            ?: return listOf(UiText(tr("Guarda primero el token de la API de monday."), tone = UiTone.Danger), back)
        val boards = try {
            TareasMondayClient.boards(token)
        } catch (failure: TareasMondayException) {
            return listOf(UiText(failure.message.orEmpty(), tone = UiTone.Danger), back)
        }
        if (boards.isEmpty()) return listOf(UiText(tr("El token no tiene acceso a ningun tablero activo."), tone = UiTone.Warning), back)
        return listOf(
            UiCard(
                listOf(
                    UiText(tr("Elige el tablero de monday que quieres importar. Cada tablero de monday pasa a ser un tablero de Tareas.")),
                    UiList(
                        boards.map { board ->
                            UiListItem(
                                title = board.name,
                                subtitle = workspaceLabel(board.workspace),
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

    private fun mapStep(context: TareasImportContext, boardId: String): List<UiNode> {
        val back = UiButton(tr("Elegir otro tablero"), post(MondayOp.Boards), style = "secondary")
        val token = TareasMondaySettings.loadToken(context.access.secrets, context.tenant)
            ?: return listOf(UiText(tr("Guarda primero el token de la API de monday."), tone = UiTone.Danger))
        val board = try {
            TareasMondayClient.boardInfo(token, boardId)
        } catch (failure: TareasMondayException) {
            return listOf(UiText(failure.message.orEmpty(), tone = UiTone.Danger), back)
        }
        val suggested = TareasMondayMapping.suggest(board.columns)
        val byId = board.columns.associateBy { it.id }
        val columnOptions = listOf(TareasMondayFlow.NoColumn) + board.columns.filter { it.type != "name" && it.type != "subitems" }.map(TareasMondayFlow::columnOption)
        val (canCreate, existing) = TareasMondayFlow.destinations(context.tenant, context.permissions)
        val destinations = (if (canCreate) listOf(TareasMondayFlow.NewBoard) else emptyList()) + existing.map(TareasMondayFlow::boardOption)
        if (destinations.isEmpty()) {
            return listOf(UiText(tr("No tienes permiso para crear tableros ni para agregar tareas a uno existente."), tone = UiTone.Warning), back)
        }
        val fields = listOf(
            UiField("board", tr("Id del tablero de monday"), readOnly = true),
            UiField("target", tr("Destino en Tareas"), type = "dropdown", options = destinations, help = tr("Un tablero nuevo, o uno existente al que se agregan las tareas.")),
        ) + MondayTarget.all.map { definition ->
            UiField("map:${definition.target}", tr(definition.label), type = "dropdown", options = columnOptions)
        }
        val values = mapOf("board" to board.id, "target" to destinations.first()) + MondayTarget.all.associate { definition ->
            "map:${definition.target}" to (suggested.columns[definition.target]?.let(byId::get)?.let(TareasMondayFlow::columnOption) ?: TareasMondayFlow.NoColumn)
        }
        return listOf(
            UiCard(
                listOf(
                    UiText(tr("Tablero: {0}. Revisa a que columna va cada dato; ya van elegidas las que parecen corresponder.", board.name), UiTextStyle.Body),
                    UiText(
                        tr("Los grupos de monday son los grupos del tablero. Los subelementos pasan a la lista de pasos, las actualizaciones a comentarios y los archivos a adjuntos. Las columnas sin destino se agregan al detalle de la tarea."),
                        UiTextStyle.Caption,
                    ),
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
        val job = TareasMondayJobs.find(tenant, id) ?: return listOf(UiText(tr("No se encontro la importacion."), tone = UiTone.Danger), back)
        val refresh = UiButton(tr("Actualizar"), navigate(mapOf("step" to "job", "job" to id.toString())), style = "secondary")
        val cancel = UiButton(tr("Cancelar importacion"), post(MondayOp.Cancel, mapOf("job" to id.toString()), confirm = tr("Se cancela la importacion. Lo ya importado se conserva. Continuar?")), style = "danger")
        val header = UiText(tr("Tablero: {0} · {1}", job.boardName.ifBlank { job.boardId }, TareasMondayFlow.statusLabel(job.status)), UiTextStyle.Subtitle)
        return when (job.status) {
            MondayJobStatus.Reading -> listOf(
                header,
                UiText(tr("Se esta leyendo el tablero con sus subelementos, actualizaciones y archivos. Un tablero grande tarda unos minutos; actualiza esta pantalla para seguir.")),
                UiText(tr("Elementos leidos: {0}", job.total), UiTextStyle.Caption),
                UiRow(listOf(refresh, cancel)),
            )
            MondayJobStatus.Preview -> previewNodes(job, header, cancel)
            MondayJobStatus.Applying -> listOf(
                header,
                progress(job),
                UiText(tr("Se importan unas 100 tareas por minuto. Puedes salir de esta pantalla; la importacion sigue sola."), UiTextStyle.Caption),
                UiRow(listOf(refresh, cancel)),
            )
            MondayJobStatus.Done -> listOf(header, UiText(job.message, tone = UiTone.Success), progress(job)) + problemNodes(tenant, job) + back
            MondayJobStatus.Failed -> listOf(header, UiText(job.message.ifBlank { tr("No se pudo completar la importacion.") }, tone = UiTone.Danger), progress(job), back)
            else -> listOf(header, UiText(job.message.ifBlank { tr("Importacion cancelada.") }, tone = UiTone.Warning), progress(job), back)
        }
    }

    private fun progress(job: MondayJob): UiNode = UiGrid(
        listOf(
            UiMetric(tr("Procesadas"), tr("{0} de {1}", job.applied, job.total)),
            UiMetric(tr("Creadas"), job.created.toString()),
            UiMetric(tr("Actualizadas"), job.updated.toString()),
            UiMetric(tr("Omitidas"), job.skipped.toString()),
            UiMetric(tr("Con error"), job.failed.toString()),
            UiMetric(tr("Archivos"), job.filesDone.toString(), caption = if (job.filesFailed > 0) tr("{0} con error", job.filesFailed) else "", captionTone = if (job.filesFailed > 0) UiTone.Warning else UiTone.Neutral),
        ),
        minItemWidth = 140,
    )

    private fun previewNodes(job: MondayJob, header: UiNode, cancel: UiNode): List<UiNode> {
        // El resumen se calculo y guardo una vez al terminar de leer: la pantalla solo lo muestra.
        val saved = job.summary ?: return listOf(header, UiText(tr("La vista previa de esta importacion no esta disponible; cancelala y vuelve a leer el tablero."), tone = UiTone.Danger), cancel)
        val summary = saved.toSummary()
        val nodes = mutableListOf<UiNode>(
            header,
            UiGrid(
                listOf(
                    UiMetric(tr("Tareas"), summary.tasks.toString()),
                    UiMetric(tr("Grupos"), summary.groups.size.toString()),
                    UiMetric(tr("Pasos de lista"), summary.checklistItems.toString()),
                    UiMetric(tr("Comentarios"), summary.comments.toString()),
                    UiMetric(tr("Archivos"), summary.files.toString()),
                    UiMetric(tr("Problemas"), saved.problemCount.toString(), captionTone = if (saved.problemCount == 0) UiTone.Success else UiTone.Warning),
                ),
                minItemWidth = 140,
            ),
            UiText(tr(ReimportWarning), UiTextStyle.Caption, tone = UiTone.Warning),
        )
        if (summary.groups.isNotEmpty()) {
            nodes += UiText(tr("Grupos: {0}{1}.", summary.groups.take(MaxListed).joinToString(), if (summary.groups.size > MaxListed) tr(" y {0} mas", summary.groups.size - MaxListed) else ""), UiTextStyle.Caption)
        }
        nodes += UiCard(
            listOf(
                UiText(if (summary.matchedPeople.isEmpty()) tr("Ninguna persona se empareja con un usuario de Tareas.") else tr("Se emparejan con usuarios: {0}.", summary.matchedPeople.take(MaxListed).joinToString())),
                UiText(
                    if (summary.unmatchedPeople.isEmpty()) tr("Todas las personas tienen usuario.")
                    else tr("Sin usuario (quedan anotadas en el detalle de la tarea): {0}.", summary.unmatchedPeople.take(MaxListed).joinToString()),
                    tone = if (summary.unmatchedPeople.isEmpty()) UiTone.Success else UiTone.Warning,
                ),
            ),
            title = tr("Personas"),
        )
        if (summary.problems.isNotEmpty()) {
            nodes += UiCard(
                summary.problems.take(MaxListed).map { UiText(it) } +
                    listOfNotNull(saved.problemCount.takeIf { it > MaxListed }?.let { UiText(tr("Y {0} problemas mas.", it - MaxListed), UiTextStyle.Caption) }) +
                    UiText(tr("Las filas con problemas se omiten al aplicar."), UiTextStyle.Caption),
                title = tr("Problemas encontrados"),
            )
        }
        nodes += UiRow(
            listOf(
                UiButton(tr("Aplicar importacion"), post(MondayOp.Apply, mapOf("job" to job.id.toString()), confirm = tr("Se crean o actualizan {0} tareas con sus pasos, comentarios y archivos. {1} Continuar?", summary.tasks, tr(ReimportWarning)))),
                cancel,
            ),
        )
        return nodes
    }

    private fun problemNodes(tenant: Connection, job: MondayJob): List<UiNode> {
        val rows = TareasMondayJobs.problems(tenant, job.id, 50)
        if (rows.isEmpty()) return emptyList()
        return listOf(
            UiCard(
                listOf(
                    UiTable(
                        listOf(UiTableColumn("row", tr("Fila")), UiTableColumn("title", tr("Tarea")), UiTableColumn("result", tr("Resultado")), UiTableColumn("message", tr("Motivo"))),
                        rows.map { mapOf("row" to it.rowNo.toString(), "title" to it.title, "result" to resultLabel(it.outcome), "message" to it.message) },
                    ),
                ),
                title = tr("Filas con problemas"),
            ),
        )
    }

    /** El espacio de trabajo "Espacio principal" (el que pone la API si no trae nombre) se muestra traducido; los demas son de monday. */
    private fun workspaceLabel(name: String) = if (name == TareasMondayClient.MainWorkspace) tr(name) else name

    private fun resultLabel(outcome: String) = when (outcome) {
        "failed" -> tr("Error")
        "skipped" -> tr("Omitida")
        else -> tr("Con avisos")
    }

    // ---- Acciones (POST importar?source=tareas_monday&op=...) ----

    private fun failure(message: String, params: Map<String, String> = emptyMap()) = TareasImportReply(message, error = true, params = params)

    fun action(context: TareasImportContext, op: String, values: Map<String, String>): TareasImportReply {
        if (!context.permissions.has(TareasImportPermission)) return failure(tr("No tienes permiso para importar datos."))
        val tenant = context.tenant
        return try {
            when (op) {
                MondayOp.Token -> saveToken(context, values)
                MondayOp.Forget -> {
                    TareasMondaySettings.saveToken(context.access.secrets, tenant, null, context.permissions.activityActor)
                    TareasImportReply(tr("Token de monday borrado."))
                }
                MondayOp.Boards -> TareasImportReply(tr("Elige un tablero."), params = mapOf("step" to "boards"))
                MondayOp.Map -> {
                    val board = values["board"].orEmpty().trim()
                    if (!TareasMondayClient.isBoardId(board)) failure(tr("Elige un tablero."), mapOf("step" to "boards"))
                    else TareasImportReply(tr("Revisa el destino y el mapeo de columnas."), params = mapOf("step" to "map", "board" to board))
                }
                MondayOp.Read -> read(context, values)
                MondayOp.Apply -> {
                    val id = jobId(values)
                    controlled(context, id)
                    if (TareasMondayJobs.requestApply(tenant, id, context.permissions.activityActor)) {
                        TareasImportReply(tr("Se esta importando."), params = mapOf("step" to "job", "job" to id.toString()))
                    } else {
                        failure(tr("Esta importacion ya no esta en revision."), mapOf("step" to "job", "job" to id.toString()))
                    }
                }
                MondayOp.Cancel -> {
                    val id = jobId(values)
                    controlled(context, id)
                    val done = TareasMondayJobs.cancel(tenant, id, context.permissions.activityActor)
                    TareasImportReply(if (done) tr("Importacion cancelada.") else tr("Esta importacion ya habia terminado."), error = !done, params = mapOf("step" to "job", "job" to id.toString()))
                }
                else -> failure(tr("Operacion desconocida."))
            }
        } catch (problem: TareasMondayException) {
            failure(problem.message.orEmpty())
        }
    }

    // Solo quien inicio la importacion o un administrador de tableros la aplica o cancela.
    private fun controlled(context: TareasImportContext, id: UUID) {
        val job = TareasMondayJobs.find(context.tenant, id) ?: throw TareasMondayException(tr("No se encontro la importacion."))
        TareasMondayFlow.requireJobControl(context.tenant, context.permissions, job)
    }

    private fun jobId(values: Map<String, String>): UUID =
        runCatching { UUID.fromString(values["job"].orEmpty().trim()) }.getOrNull() ?: throw TareasMondayException(tr("No se encontro la importacion."))

    private fun saveToken(context: TareasImportContext, values: Map<String, String>): TareasImportReply {
        val tenant = context.tenant
        val typed = values["token"].orEmpty().trim().takeIf { it.isNotEmpty() && it != SecretBox.Mask }
        if (typed == null && !TareasMondaySettings.configured(tenant)) return failure(tr("Escribe el token de la API de monday."))
        typed?.let { TareasMondaySettings.saveToken(context.access.secrets, tenant, it, context.permissions.activityActor) }
        return TareasImportReply(tr("Token de monday guardado."), params = mapOf("step" to "boards"))
    }

    private fun read(context: TareasImportContext, values: Map<String, String>): TareasImportReply {
        val board = values["board"].orEmpty().trim()
        if (!TareasMondayClient.isBoardId(board)) return failure(tr("Elige un tablero."), mapOf("step" to "boards"))
        val chosen = MondayTarget.all.mapNotNull { definition ->
            values["map:${definition.target}"]?.let(TareasMondayFlow::columnIdOf)?.let { definition.target to it }
        }.toMap()
        val target = TareasMondayFlow.targetOf(values["target"])
        val id = try {
            TareasMondayFlow.startRead(context.access, context.tenant, context.permissions, board, MondayMapping(chosen), target)
        } catch (problem: TareasMondayException) {
            return failure(problem.message.orEmpty(), mapOf("step" to "map", "board" to board))
        }
        return TareasImportReply(tr("Se esta leyendo el tablero de monday."), params = mapOf("step" to "job", "job" to id.toString()))
    }
}
