package com.ideasdeveloper.bms.extensions.eventos_monday

import com.ideasdeveloper.bms.modules.eventos.EventosImportContext
import com.ideasdeveloper.bms.modules.eventos.EventosImportReply
import com.ideasdeveloper.bms.server.activity.ActivityActor
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

/** Lo que comparten la pantalla y las herramientas MCP: leer un tablero con el token guardado y revisar el mapeo. */
object EventosMondayFlow {
    val NoColumn = msg("No usar")
    const val SpacePrefix = "space:"
    const val ManagePermission = "eventos.manage"
    private const val OptionSeparator = " · "
    const val MaxGroups = 30

    /** Texto de la opcion de una columna en los selectores; el id va al final para recuperarlo aunque haya nombres repetidos. */
    fun columnOption(column: MondayColumn): String = "${column.title}$OptionSeparator${column.id}"

    fun columnIdOf(option: String): String? =
        option.takeIf { it.contains(OptionSeparator) }?.substringAfterLast(OptionSeparator)?.trim()?.takeIf { it.isNotEmpty() }

    /** Grupos del tablero que se muestran para fijar su espacio (los primeros [MaxGroups]). */
    fun shownGroups(groups: List<String>): List<String> = groups.take(MaxGroups)

    /** Quien queda como autor de lo importado: el usuario; un dueno del servidor no es usuario de la empresa. */
    fun actorOf(permissions: ModulePermissions): UUID? = permissions.userId?.takeUnless { permissions.serverOwner }

    /** Aplicar o cancelar una importacion: solo quien la inicio o quien gestiona los eventos. */
    fun requireJobControl(permissions: ModulePermissions, job: MondayJob) {
        val actor = actorOf(permissions)
        if (actor != null && job.createdBy == actor) return
        if (permissions.has(ManagePermission)) return
        throw EventosMondayException(tr("Solo quien inicio la importacion o quien gestiona los eventos puede aplicarla o cancelarla."))
    }

    /** Problema del mapeo que impide importar, o null: solo se revisa que las columnas elegidas sigan existiendo. */
    fun mappingProblem(mapping: MondayMapping, columns: List<MondayColumn>): String? {
        val ids = columns.map { it.id }.toSet()
        return if (mapping.columns.values.any { it !in ids }) tr("El mapeo usa una columna que ya no existe en el tablero.") else null
    }

    /** Lee el tablero en segundo plano con el token guardado; el mapeo se comprueba antes de encolar. */
    fun startRead(
        access: ModuleAccessService,
        tenant: Connection,
        permissions: ModulePermissions,
        boardId: String,
        mapping: MondayMapping,
    ): UUID {
        val token = EventosMondaySettings.loadToken(access.secrets, tenant)
            ?: throw EventosMondayException(tr("Guarda primero el token de la API de monday (pantalla Importar)."))
        val board = EventosMondayClient.boardInfo(token, boardId)
        mappingProblem(mapping, board.columns)?.let { throw EventosMondayException(it) }
        // La fila del registro de actividad va en la misma transaccion que el trabajo en cola.
        return EventosMondayJobs.inTransaction(tenant) {
            val id = EventosMondayJobs.queueRead(tenant, boardId, board.name, mapping, actorOf(permissions), permissions.activityActor)
            EventosMondayActivity.importQueued(tenant, permissions.activityActor, id, board.name)
            id
        }
    }

    /** Aplica la importacion en revision y lo deja en el registro de actividad; false si ya no estaba en revision. */
    fun requestApply(tenant: Connection, permissions: ModulePermissions, job: MondayJob): Boolean = EventosMondayJobs.inTransaction(tenant) {
        val changed = EventosMondayJobs.requestApply(tenant, job.id, actorOf(permissions), permissions.serverOwner)
        if (changed) EventosMondayActivity.applyRequested(tenant, permissions.activityActor, job.id, job.boardName, job.total)
        changed
    }

    /** Cancela la importacion y lo deja en el registro de actividad; false si ya habia terminado. */
    fun cancel(tenant: Connection, permissions: ModulePermissions, job: MondayJob): Boolean = EventosMondayJobs.inTransaction(tenant) {
        val done = EventosMondayJobs.cancel(tenant, job.id)
        if (done) EventosMondayActivity.cancelled(tenant, permissions.activityActor, job.id, job.boardName, EventosMondayActivity.statusName(job.status), job.applied)
        done
    }

    /** Guarda el token (nuevo o cambiado) y deja en el registro solo que cambio, nunca su valor. */
    fun saveToken(secrets: SecretBox, tenant: Connection, actor: ActivityActor, token: String) = EventosMondayJobs.inTransaction(tenant) {
        val had = EventosMondaySettings.configured(tenant)
        EventosMondaySettings.saveToken(secrets, tenant, token)
        EventosMondayActivity.tokenSaved(tenant, actor, had)
    }

    /** Borra el token guardado (si habia) y lo deja en el registro. */
    fun forgetToken(secrets: SecretBox, tenant: Connection, actor: ActivityActor) = EventosMondayJobs.inTransaction(tenant) {
        val had = EventosMondaySettings.configured(tenant)
        EventosMondaySettings.saveToken(secrets, tenant, null)
        if (had) EventosMondayActivity.tokenForgotten(tenant, actor)
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
object EventosMondayScreens {
    private const val Route = "/modules/eventos/ui/importar"
    private const val MaxListed = 20
    val ReimportWarning = msg("Reimportar el mismo tablero actualiza los eventos ya importados y sobrescribe los cambios hechos en Eventos a esos campos.")

    private fun navigate(params: Map<String, String> = emptyMap()) = UiNavigate("importar", mapOf("source" to EventosMondayId) + params)

    private fun post(op: String, params: Map<String, String> = emptyMap(), confirm: String? = null, formId: String? = null) =
        UiRequest(Route, "POST", mapOf("source" to EventosMondayId, "op" to op) + params, confirm, formId)

    fun screen(context: EventosImportContext, params: Map<String, String>): List<UiNode> {
        if (!context.permissions.has(EventosImportPermission)) {
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

    private fun home(context: EventosImportContext): List<UiNode> {
        val tenant = context.tenant
        val configured = EventosMondaySettings.configured(tenant)
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
                    EventosMondayJobs.recent(tenant, 8).map { job ->
                        UiListItem(
                            title = job.boardName.ifBlank { tr("Tablero {0}", job.boardId) },
                            subtitle = job.message.ifBlank { tr("{0} elementos", job.total) },
                            trailing = job.createdAt.toString().take(10),
                            badges = listOf(UiBadge(EventosMondayFlow.statusLabel(job.status), EventosMondayFlow.statusTone(job.status))),
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

    private fun boardsStep(context: EventosImportContext): List<UiNode> {
        val back = UiButton(tr("Volver"), navigate(), style = "secondary")
        val token = EventosMondaySettings.loadToken(context.access.secrets, context.tenant)
            ?: return listOf(UiText(tr("Guarda primero el token de la API de monday."), tone = UiTone.Danger), back)
        val boards = try {
            EventosMondayClient.boards(token)
        } catch (failure: EventosMondayException) {
            return listOf(UiText(failure.message.orEmpty(), tone = UiTone.Danger), back)
        }
        if (boards.isEmpty()) return listOf(UiText(tr("El token no tiene acceso a ningun tablero activo."), tone = UiTone.Warning), back)
        return listOf(
            UiCard(
                listOf(
                    UiText(tr("Elige el tablero de monday que quieres importar. Cada elemento pasa a ser un evento privado con su cliente.")),
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

    // ---- Mapeo ----

    private fun mapStep(context: EventosImportContext, boardId: String): List<UiNode> {
        val back = UiButton(tr("Elegir otro tablero"), post(MondayOp.Boards), style = "secondary")
        val token = EventosMondaySettings.loadToken(context.access.secrets, context.tenant)
            ?: return listOf(UiText(tr("Guarda primero el token de la API de monday."), tone = UiTone.Danger))
        val board = try {
            EventosMondayClient.boardInfo(token, boardId)
        } catch (failure: EventosMondayException) {
            return listOf(UiText(failure.message.orEmpty(), tone = UiTone.Danger), back)
        }
        val groups = EventosMondayFlow.shownGroups(board.groups)
        val suggested = EventosMondayMapping.suggest(board.columns, groups)
        val byId = board.columns.associateBy { it.id }
        val columnOptions = listOf(EventosMondayFlow.NoColumn) + board.columns.filter { it.type != "name" && it.type != "subitems" }.map(EventosMondayFlow::columnOption)
        val fields = listOf(UiField("board", tr("Id del tablero de monday"), readOnly = true)) +
            MondayTarget.all.map { definition -> UiField("map:${definition.target}", tr(definition.label), type = "dropdown", options = columnOptions) } +
            groups.map { group ->
                UiField("${EventosMondayFlow.SpacePrefix}$group", tr("Espacio del grupo \"{0}\"", group), help = tr("Se guarda como espacio del evento; vacio = sin espacio."))
            }
        val values = mapOf("board" to board.id) + MondayTarget.all.associate { definition ->
            "map:${definition.target}" to (suggested.columns[definition.target]?.let(byId::get)?.let(EventosMondayFlow::columnOption) ?: EventosMondayFlow.NoColumn)
        } + groups.associate { group -> "${EventosMondayFlow.SpacePrefix}$group" to suggested.groups[group].orEmpty() }
        return listOf(
            UiCard(
                listOf(
                    UiText(tr("Tablero: {0}. Revisa a que columna va cada dato; ya van elegidas las que parecen corresponder.", board.name), UiTextStyle.Body),
                    UiText(
                        tr("Cada elemento pasa a ser un evento privado y su contacto un cliente (se reutiliza por correo). El grupo fija el espacio; el grupo \"Booked\" fuerza el estado confirmado. Las columnas sin destino se agregan a las notas del evento."),
                        UiTextStyle.Caption,
                    ),
                    UiForm(
                        "mondayMapping", fields, values,
                        submit = UiButton(tr("Leer tablero"), post(MondayOp.Read, mapOf("board" to board.id), formId = "mondayMapping")),
                    ),
                    back,
                ),
                title = tr("Mapeo de columnas"),
            ),
        )
    }

    // ---- Trabajo ----

    private fun jobStep(tenant: Connection, id: UUID): List<UiNode> {
        val back = UiButton(tr("Volver"), navigate(), style = "secondary")
        val job = EventosMondayJobs.find(tenant, id) ?: return listOf(UiText(tr("No se encontro la importacion."), tone = UiTone.Danger), back)
        val refresh = UiButton(tr("Actualizar"), navigate(mapOf("step" to "job", "job" to id.toString())), style = "secondary")
        val cancel = UiButton(tr("Cancelar importacion"), post(MondayOp.Cancel, mapOf("job" to id.toString()), confirm = tr("Se cancela la importacion. Lo ya importado se conserva. Continuar?")), style = "danger")
        val header = UiText(tr("Tablero: {0} · {1}", job.boardName.ifBlank { job.boardId }, EventosMondayFlow.statusLabel(job.status)), UiTextStyle.Subtitle)
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
                UiText(tr("Se importan unos 100 eventos por minuto. Puedes salir de esta pantalla; la importacion sigue sola."), UiTextStyle.Caption),
                UiRow(listOf(refresh, cancel)),
            )
            MondayJobStatus.Done -> listOf(header, UiText(job.message, tone = UiTone.Success), progress(job)) + problemNodes(tenant, job) + back
            MondayJobStatus.Failed -> listOf(header, UiText(job.message.ifBlank { tr("No se pudo completar la importacion.") }, tone = UiTone.Danger), progress(job), back)
            else -> listOf(header, UiText(job.message.ifBlank { tr("Importacion cancelada.") }, tone = UiTone.Warning), progress(job), back)
        }
    }

    private fun progress(job: MondayJob): UiNode = UiGrid(
        listOf(
            UiMetric(tr("Procesados"), tr("{0} de {1}", job.applied, job.total)),
            UiMetric(tr("Creados"), job.created.toString()),
            UiMetric(tr("Actualizados"), job.updated.toString()),
            UiMetric(tr("Omitidos"), job.skipped.toString()),
            UiMetric(tr("Con error"), job.failed.toString()),
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
                    UiMetric(tr("Eventos"), summary.events.toString()),
                    UiMetric(tr("Clientes nuevos"), summary.newCustomers.toString()),
                    UiMetric(tr("Espacios"), summary.bySpace.size.toString()),
                    UiMetric(tr("Problemas"), saved.problemCount.toString(), captionTone = if (saved.problemCount == 0) UiTone.Success else UiTone.Warning),
                ),
                minItemWidth = 140,
            ),
            UiText(tr(ReimportWarning), UiTextStyle.Caption, tone = UiTone.Warning),
        )
        if (summary.bySpace.isNotEmpty()) {
            nodes += UiText(tr("Por espacio: {0}.", summary.bySpace.entries.take(MaxListed).joinToString { "${it.key.ifBlank { tr("(sin espacio)") }} ${it.value}" }), UiTextStyle.Caption)
        }
        nodes += UiCard(
            listOf(
                UiText(if (summary.matchedPeople.isEmpty()) tr("Ningun responsable se empareja con un usuario de Eventos.") else tr("Se emparejan con usuarios: {0}.", summary.matchedPeople.take(MaxListed).joinToString())),
                UiText(
                    if (summary.unmatchedPeople.isEmpty()) tr("Todos los responsables tienen usuario.")
                    else tr("Sin usuario (quedan anotados en las notas del evento): {0}.", summary.unmatchedPeople.take(MaxListed).joinToString()),
                    tone = if (summary.unmatchedPeople.isEmpty()) UiTone.Success else UiTone.Warning,
                ),
            ),
            title = tr("Responsables"),
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
                UiButton(tr("Aplicar importacion"), post(MondayOp.Apply, mapOf("job" to job.id.toString()), confirm = tr("Se crean o actualizan {0} eventos privados con sus clientes. {1} Continuar?", summary.events, tr(ReimportWarning)))),
                cancel,
            ),
        )
        return nodes
    }

    private fun problemNodes(tenant: Connection, job: MondayJob): List<UiNode> {
        val rows = EventosMondayJobs.problems(tenant, job.id, 50)
        if (rows.isEmpty()) return emptyList()
        return listOf(
            UiCard(
                listOf(
                    UiTable(
                        listOf(UiTableColumn("row", tr("Fila")), UiTableColumn("title", tr("Evento")), UiTableColumn("result", tr("Resultado")), UiTableColumn("message", tr("Motivo"))),
                        rows.map { mapOf("row" to it.rowNo.toString(), "title" to it.title, "result" to resultLabel(it.outcome), "message" to it.message) },
                    ),
                ),
                title = tr("Filas con problemas"),
            ),
        )
    }

    /** El espacio de trabajo "Espacio principal" (el que pone la API si no trae nombre) se muestra traducido; los demas son de monday. */
    private fun workspaceLabel(name: String) = if (name == EventosMondayClient.MainWorkspace) tr(name) else name

    private fun resultLabel(outcome: String) = when (outcome) {
        "failed" -> tr("Error")
        "skipped" -> tr("Omitida")
        else -> tr("Con avisos")
    }

    // ---- Acciones (POST importar?source=eventos_monday&op=...) ----

    private fun failure(message: String, params: Map<String, String> = emptyMap()) = EventosImportReply(message, error = true, params = params)

    fun action(context: EventosImportContext, op: String, values: Map<String, String>): EventosImportReply {
        if (!context.permissions.has(EventosImportPermission)) return failure(tr("No tienes permiso para importar datos."))
        val tenant = context.tenant
        return try {
            when (op) {
                MondayOp.Token -> saveToken(context, values)
                MondayOp.Forget -> {
                    EventosMondayFlow.forgetToken(context.access.secrets, tenant, context.permissions.activityActor)
                    EventosImportReply(tr("Token de monday borrado."))
                }
                MondayOp.Boards -> EventosImportReply(tr("Elige un tablero."), params = mapOf("step" to "boards"))
                MondayOp.Map -> {
                    val board = values["board"].orEmpty().trim()
                    if (!EventosMondayClient.isBoardId(board)) failure(tr("Elige un tablero."), mapOf("step" to "boards"))
                    else EventosImportReply(tr("Revisa el mapeo de columnas."), params = mapOf("step" to "map", "board" to board))
                }
                MondayOp.Read -> read(context, values)
                MondayOp.Apply -> {
                    val id = jobId(values)
                    val job = controlled(context, id)
                    if (EventosMondayFlow.requestApply(tenant, context.permissions, job)) {
                        EventosImportReply(tr("Se esta importando."), params = mapOf("step" to "job", "job" to id.toString()))
                    } else {
                        failure(tr("Esta importacion ya no esta en revision."), mapOf("step" to "job", "job" to id.toString()))
                    }
                }
                MondayOp.Cancel -> {
                    val id = jobId(values)
                    val job = controlled(context, id)
                    val done = EventosMondayFlow.cancel(tenant, context.permissions, job)
                    EventosImportReply(if (done) tr("Importacion cancelada.") else tr("Esta importacion ya habia terminado."), error = !done, params = mapOf("step" to "job", "job" to id.toString()))
                }
                else -> failure(tr("Operacion desconocida."))
            }
        } catch (problem: EventosMondayException) {
            failure(problem.message.orEmpty())
        }
    }

    // Solo quien inicio la importacion o quien gestiona los eventos la aplica o cancela.
    private fun controlled(context: EventosImportContext, id: UUID): MondayJob {
        val job = EventosMondayJobs.find(context.tenant, id) ?: throw EventosMondayException(tr("No se encontro la importacion."))
        EventosMondayFlow.requireJobControl(context.permissions, job)
        return job
    }

    private fun jobId(values: Map<String, String>): UUID =
        runCatching { UUID.fromString(values["job"].orEmpty().trim()) }.getOrNull() ?: throw EventosMondayException(tr("No se encontro la importacion."))

    private fun saveToken(context: EventosImportContext, values: Map<String, String>): EventosImportReply {
        val tenant = context.tenant
        val typed = values["token"].orEmpty().trim().takeIf { it.isNotEmpty() && it != SecretBox.Mask }
        if (typed == null && !EventosMondaySettings.configured(tenant)) return failure(tr("Escribe el token de la API de monday."))
        typed?.let { EventosMondayFlow.saveToken(context.access.secrets, tenant, context.permissions.activityActor, it) }
        return EventosImportReply(tr("Token de monday guardado."), params = mapOf("step" to "boards"))
    }

    private fun read(context: EventosImportContext, values: Map<String, String>): EventosImportReply {
        val board = values["board"].orEmpty().trim()
        if (!EventosMondayClient.isBoardId(board)) return failure(tr("Elige un tablero."), mapOf("step" to "boards"))
        val chosen = MondayTarget.all.mapNotNull { definition ->
            values["map:${definition.target}"]?.let(EventosMondayFlow::columnIdOf)?.let { definition.target to it }
        }.toMap()
        // El espacio de cada grupo (vacio = sin espacio); los titulos se recortan al largo del espacio del evento.
        val groups = values.filterKeys { it.startsWith(EventosMondayFlow.SpacePrefix) }.entries.take(EventosMondayFlow.MaxGroups)
            .associate { it.key.removePrefix(EventosMondayFlow.SpacePrefix).take(200) to it.value.trim().take(200) }
        val id = try {
            EventosMondayFlow.startRead(context.access, context.tenant, context.permissions, board, MondayMapping(chosen, groups))
        } catch (problem: EventosMondayException) {
            return failure(problem.message.orEmpty(), mapOf("step" to "map", "board" to board))
        }
        return EventosImportReply(tr("Se esta leyendo el tablero de monday."), params = mapOf("step" to "job", "job" to id.toString()))
    }
}
