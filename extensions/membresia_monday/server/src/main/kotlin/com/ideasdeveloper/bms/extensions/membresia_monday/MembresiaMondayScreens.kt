package com.ideasdeveloper.bms.extensions.membresia_monday

import com.ideasdeveloper.bms.modules.membresia.MembresiaImportContext
import com.ideasdeveloper.bms.modules.membresia.MembresiaImportReply
import com.ideasdeveloper.bms.modules.membresia.MembresiaModule
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

/** Id de la extension (el mismo de extension.json y de MembresiaImportSource.sourceId). */
const val MembresiaMondayId = "membresia_monday"

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
object MembresiaMondayFlow {
    val NoColumn = msg("No usar")
    private const val OptionSeparator = " · "

    /** Texto de la opcion de una columna en los selectores; el id va al final para recuperarlo aunque haya titulos repetidos. */
    fun columnOption(column: MondayColumn): String = "${column.title}$OptionSeparator${column.id}"

    fun columnIdOf(option: String): String? =
        option.takeIf { it.contains(OptionSeparator) }?.substringAfterLast(OptionSeparator)?.trim()?.takeIf { it.isNotEmpty() }

    /** Problema del mapeo que impide importar, o null. Hace falta el plan y las fechas (periodo, o inicio y fin). */
    fun mappingProblem(mapping: MondayMapping, columns: List<MondayColumn>): String? {
        val ids = columns.map { it.id }.toSet()
        if (mapping.columns.values.any { it !in ids }) return tr("El mapeo usa una columna que ya no existe en el tablero.")
        val chosen = mapping.columns
        return when {
            MondayTarget.Plan !in chosen -> tr("Elige la columna del tipo de membresia: sin ella no se sabe a que plan pertenece cada miembro.")
            MondayTarget.Period !in chosen && !(MondayTarget.Start in chosen && MondayTarget.End in chosen) ->
                tr("Elige la columna del periodo, o las de inicio y fin.")
            else -> null
        }
    }

    /** Lee el tablero en segundo plano con el token guardado; el mapeo se comprueba contra las columnas reales. */
    fun startRead(access: ModuleAccessService, tenant: Connection, boardId: String, mapping: MondayMapping, author: String, permissions: ModulePermissions): UUID {
        val token = MembresiaMondaySettings.loadToken(access.secrets, tenant)
            ?: throw MembresiaMondayException(tr("Guarda primero el token de la API de monday (pantalla Importar)."))
        val board = MembresiaMondayClient.boardInfo(token, boardId)
        mappingProblem(mapping, board.columns)?.let { throw MembresiaMondayException(it) }
        // La fila del registro de actividad va en la misma transaccion que el trabajo en cola.
        return MembresiaMondayJobs.inTransaction(tenant) {
            val id = MembresiaMondayJobs.queueRead(tenant, boardId, board.name, mapping, author, starterOf(permissions))
            MembresiaMondayActivity.importQueued(tenant, permissions.activityActor, id, board.name)
            id
        }
    }

    /** Usuario de la empresa que empieza la importacion; un dueno del servidor no es usuario de la empresa. */
    fun starterOf(permissions: ModulePermissions): UUID? = permissions.userId?.takeUnless { permissions.serverOwner }

    /** Aplica la importacion en revision y lo deja en el registro de actividad; false si ya no estaba en revision. */
    fun requestApply(tenant: Connection, permissions: ModulePermissions, job: MondayJob): Boolean = MembresiaMondayJobs.inTransaction(tenant) {
        val changed = MembresiaMondayJobs.requestApply(tenant, job.id)
        if (changed) MembresiaMondayActivity.applyRequested(tenant, permissions.activityActor, job.id, job.boardName, job.total)
        changed
    }

    /** Cancela la importacion y lo deja en el registro de actividad; false si ya habia terminado. */
    fun cancel(tenant: Connection, permissions: ModulePermissions, job: MondayJob): Boolean = MembresiaMondayJobs.inTransaction(tenant) {
        val done = MembresiaMondayJobs.cancel(tenant, job.id)
        if (done) MembresiaMondayActivity.cancelled(tenant, permissions.activityActor, job.id, job.boardName, MembresiaMondayActivity.statusName(job.status), job.applied)
        done
    }

    /** Guarda el token (nuevo o cambiado) y deja en el registro solo que cambio, nunca su valor. */
    fun saveToken(secrets: SecretBox, tenant: Connection, actor: ActivityActor, token: String) = MembresiaMondayJobs.inTransaction(tenant) {
        val had = MembresiaMondaySettings.configured(tenant)
        MembresiaMondaySettings.saveToken(secrets, tenant, token)
        MembresiaMondayActivity.tokenSaved(tenant, actor, had)
    }

    /** Borra el token guardado (si habia) y lo deja en el registro. */
    fun forgetToken(secrets: SecretBox, tenant: Connection, actor: ActivityActor) = MembresiaMondayJobs.inTransaction(tenant) {
        val had = MembresiaMondaySettings.configured(tenant)
        MembresiaMondaySettings.saveToken(secrets, tenant, null)
        if (had) MembresiaMondayActivity.tokenForgotten(tenant, actor)
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

    /** Guardar o quitar el token lo hace el dueno de la empresa o quien administra los planes; leer y aplicar, membresia.import. */
    fun canManageToken(permissions: ModulePermissions): Boolean = permissions.businessOwner || permissions.has(MembresiaModule.Plans)
}

/** Pantalla de la fuente dentro de Importar. Muestra el token solo como "configurado"; nunca su valor. */
object MembresiaMondayScreens {
    private const val Route = "${MembresiaModule.UiRoute}/importar"
    private const val MaxProblems = 20

    private fun navigate(params: Map<String, String> = emptyMap()) = UiNavigate("importar", mapOf("source" to MembresiaMondayId) + params)

    private fun post(op: String, params: Map<String, String> = emptyMap(), confirm: String? = null, formId: String? = null) =
        UiRequest(Route, "POST", mapOf("source" to MembresiaMondayId, "op" to op) + params, confirm, formId)

    fun screen(context: MembresiaImportContext, params: Map<String, String>): List<UiNode> {
        if (!context.permissions.has(MembresiaModule.Import)) {
            return listOf(UiText(tr("No tienes permiso para importar datos."), tone = UiTone.Warning))
        }
        val tenant = context.tenant
        return when (params["step"]) {
            "boards" -> boardsStep(context)
            "map" -> mapStep(context, params["board"].orEmpty())
            "job" -> params["job"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }?.let { jobStep(tenant, it) } ?: home(context)
            else -> home(context)
        }
    }

    // ---- Inicio: token e historial ----

    private fun home(context: MembresiaImportContext): List<UiNode> {
        val tenant = context.tenant
        val configured = MembresiaMondaySettings.configured(tenant)
        val canManage = MembresiaMondayFlow.canManageToken(context.permissions)
        val tokenCard = UiCard(
            listOfNotNull(
                UiText(if (configured) tr("El token de monday esta configurado.") else tr("Aun no hay un token de monday guardado."), tone = if (configured) UiTone.Success else UiTone.Warning),
                if (!canManage) null else UiForm(
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
                            listOfNotNull(
                                UiButton(tr("Cargar tableros"), post(MondayOp.Boards)),
                                if (canManage) UiButton(tr("Quitar token"), post(MondayOp.Forget, confirm = tr("Se borra el token de monday guardado. Continuar?")), style = "secondary") else null,
                            ),
                        ).flatten(),
                    )
                } else {
                    null
                },
            ),
            title = "monday",
        )
        val recent = MembresiaMondayJobs.recent(tenant, 8)
        val history = UiCard(
            listOf(
                UiList(
                    recent.map { job ->
                        UiListItem(
                            title = job.boardName.ifBlank { tr("Tablero {0}", job.boardId) },
                            subtitle = if (job.message.isBlank()) tr("{0} elementos", job.total) else tr(job.message),
                            trailing = job.createdAt.toString().take(10),
                            badges = listOf(UiBadge(MembresiaMondayFlow.statusLabel(job.status), MembresiaMondayFlow.statusTone(job.status))),
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

    private fun boardsStep(context: MembresiaImportContext): List<UiNode> {
        val token = MembresiaMondaySettings.loadToken(context.access.secrets, context.tenant)
            ?: return listOf(UiText(tr("Guarda primero el token de la API de monday."), tone = UiTone.Danger), UiButton(tr("Volver"), navigate(), style = "secondary"))
        val boards = try {
            MembresiaMondayClient.boards(token)
        } catch (failure: MembresiaMondayException) {
            return listOf(UiText(failure.message.orEmpty(), tone = UiTone.Danger), UiButton(tr("Volver"), navigate(), style = "secondary"))
        }
        if (boards.isEmpty()) return listOf(UiText(tr("El token no tiene acceso a ningun tablero activo."), tone = UiTone.Warning), UiButton(tr("Volver"), navigate(), style = "secondary"))
        return listOf(
            UiCard(
                listOf(
                    UiText(tr("Elige el tablero de membresias que quieres importar.")),
                    UiList(
                        boards.map { board ->
                            UiListItem(
                                title = board.name,
                                subtitle = if (board.workspace == MembresiaMondayClient.MainWorkspace) tr("Espacio principal") else board.workspace,
                                trailing = board.items?.let { tr("{0} elementos", it) }.orEmpty(),
                                action = post(MondayOp.Map, mapOf("board" to board.id)),
                            )
                        },
                    ),
                    UiButton(tr("Volver"), navigate(), style = "secondary"),
                ),
                title = tr("Tableros de monday"),
            ),
        )
    }

    // ---- Mapeo ----

    private fun mapStep(context: MembresiaImportContext, boardId: String): List<UiNode> {
        val back = UiButton(tr("Elegir otro tablero"), post(MondayOp.Boards), style = "secondary")
        val token = MembresiaMondaySettings.loadToken(context.access.secrets, context.tenant)
            ?: return listOf(UiText(tr("Guarda primero el token de la API de monday."), tone = UiTone.Danger))
        val board = try {
            MembresiaMondayClient.boardInfo(token, boardId)
        } catch (failure: MembresiaMondayException) {
            return listOf(UiText(failure.message.orEmpty(), tone = UiTone.Danger), back)
        }
        val suggested = MembresiaMondayMapping.suggest(board.columns)
        val byId = board.columns.associateBy { it.id }
        val options = listOf(MembresiaMondayFlow.NoColumn) + board.columns.filter { it.type != "name" && it.type != "subitems" }.map(MembresiaMondayFlow::columnOption)
        val fields = listOf(UiField("board", tr("Id del tablero"), readOnly = true)) + MondayTarget.all.map { definition ->
            UiField("map:${definition.target}", tr(definition.label), type = "dropdown", options = options)
        }
        val values = mapOf("board" to board.id) + MondayTarget.all.associate { definition ->
            "map:${definition.target}" to (suggested.columns[definition.target]?.let(byId::get)?.let(MembresiaMondayFlow::columnOption) ?: MembresiaMondayFlow.NoColumn)
        }
        return listOf(
            UiCard(
                listOf(
                    UiText(tr("Tablero: {0}. Revisa a que columna va cada dato; ya van elegidas las que parecen corresponder.", board.name), UiTextStyle.Body),
                    UiText(tr("El nombre del miembro es el del elemento. Los subelementos con fecha se importan como visitas, y los que dicen invitado o guest, como visitas de invitado."), UiTextStyle.Caption),
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
        val job = MembresiaMondayJobs.find(tenant, id) ?: return listOf(UiText(tr("No se encontro la importacion."), tone = UiTone.Danger), UiButton(tr("Volver"), navigate(), style = "secondary"))
        val refresh = UiButton(tr("Actualizar"), navigate(mapOf("step" to "job", "job" to id.toString())), style = "secondary")
        val cancel = UiButton(tr("Cancelar importacion"), post(MondayOp.Cancel, mapOf("job" to id.toString()), confirm = tr("Se cancela la importacion. Lo ya importado se conserva. Continuar?")), style = "danger")
        val back = UiButton(tr("Volver"), navigate(), style = "secondary")
        val header = UiText(tr("Tablero: {0} · {1}", job.boardName.ifBlank { job.boardId }, MembresiaMondayFlow.statusLabel(job.status)), UiTextStyle.Subtitle)
        return when (job.status) {
            MondayJobStatus.Reading -> listOf(
                header,
                UiText(tr("Se esta leyendo el tablero con sus subelementos: {0} elementos leidos ({1} paginas). Un tablero grande tarda unos minutos; actualiza esta pantalla para seguir.", job.itemsRead, job.pagesRead)),
                *listOfNotNull(job.message.takeIf { it.isNotBlank() }?.let { UiText(tr(it), UiTextStyle.Caption) }).toTypedArray(),
                UiRow(listOf(refresh, cancel)),
            )
            MondayJobStatus.Preview -> previewNodes(tenant, job, header, cancel)
            MondayJobStatus.Applying -> listOf(
                header,
                progress(job),
                UiText(tr("Se importan unas 200 filas por minuto. Puedes salir de esta pantalla; la importacion sigue sola."), UiTextStyle.Caption),
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
            UiMetric(tr("Creadas"), job.created.toString()),
            UiMetric(tr("Actualizadas"), job.updated.toString()),
            UiMetric(tr("Omitidas"), job.skipped.toString()),
            UiMetric(tr("Con error"), job.failed.toString()),
        ),
        minItemWidth = 140,
    )

    private fun previewNodes(tenant: Connection, job: MondayJob, header: UiNode, cancel: UiNode): List<UiNode> {
        // El resumen se calculo una sola vez al terminar la lectura: aqui solo se lee.
        val summary = job.summary ?: return listOf(header, UiText(tr("Esta importacion no tiene resumen; vuelve a leer el tablero."), tone = UiTone.Danger), cancel)
        val nodes = mutableListOf<UiNode>(
            header,
            UiGrid(
                listOf(
                    UiMetric(tr("Miembros"), summary.members.toString()),
                    UiMetric(tr("Visitas"), summary.visits.toString()),
                    UiMetric(tr("Planes nuevos"), summary.newPlans.size.toString()),
                    UiMetric(tr("Problemas"), summary.problemCodes.values.sum().toString(), captionTone = if (summary.problemCodes.isEmpty()) UiTone.Success else UiTone.Warning),
                ),
                minItemWidth = 140,
            ),
        )
        if (summary.newPlans.isNotEmpty()) {
            nodes += UiCard(
                listOf(
                    UiText(tr("Estos tipos de membresia no existen como plan y se crean con el monto mas comun y la duracion tipica de sus periodos."), UiTextStyle.Caption),
                    UiTable(
                        listOf(UiTableColumn("name", tr("Plan")), UiTableColumn("price", tr("Precio")), UiTableColumn("days", tr("Dias")), UiTableColumn("guests", tr("Pases de invitado"))),
                        summary.newPlans.map { mapOf("name" to it.name, "price" to it.price, "days" to it.durationDays.toString(), "guests" to it.guestPasses.toString()) },
                    ),
                ),
                title = tr("Planes que se crean"),
            )
        }
        if (summary.customerFields.isNotEmpty()) {
            nodes += UiText(tr("Campos que se crean en Clientes si faltan: {0}.", summary.customerFields.joinToString()), UiTextStyle.Caption)
        }
        if (summary.problems.isNotEmpty()) {
            nodes += UiCard(
                summary.problems.take(MaxProblems).map { UiText(it) } +
                    listOfNotNull(summary.problems.size.takeIf { it > MaxProblems }?.let { UiText(tr("Y {0} problemas mas.", it - MaxProblems), UiTextStyle.Caption) }) +
                    UiText(tr("Las filas con problemas se omiten al aplicar."), UiTextStyle.Caption),
                title = tr("Problemas encontrados"),
            )
        }
        nodes += UiRow(
            listOf(
                UiButton(tr("Aplicar importacion"), post(MondayOp.Apply, mapOf("job" to job.id.toString()), confirm = tr("Se crean o actualizan {0} miembros con sus membresias y visitas. Continuar?", summary.members))),
                cancel,
            ),
        )
        return nodes
    }

    private fun problemNodes(tenant: Connection, job: MondayJob): List<UiNode> {
        if (job.skipped + job.failed == 0) return emptyList()
        val rows = MembresiaMondayJobs.problems(tenant, job.id, 50)
        return listOf(
            UiCard(
                listOf(
                    UiTable(
                        listOf(UiTableColumn("row", tr("Fila")), UiTableColumn("result", tr("Resultado")), UiTableColumn("message", tr("Motivo"))),
                        rows.map { mapOf("row" to it.rowNo.toString(), "result" to if (it.outcome == "failed") tr("Error") else tr("Omitida"), "message" to tr(it.message)) },
                    ),
                ),
                title = tr("Filas sin importar"),
            ),
        )
    }

    // ---- Acciones (POST importar?source=membresia_monday&op=...) ----

    private fun failure(message: String, params: Map<String, String> = emptyMap()) = MembresiaImportReply(message, error = true, params = params)

    fun action(context: MembresiaImportContext, op: String, values: Map<String, String>): MembresiaImportReply {
        // Guardar o quitar el token: dueno o membresia.plans; todo lo demas: membresia.import.
        val allowed = if (op == MondayOp.Token || op == MondayOp.Forget) MembresiaMondayFlow.canManageToken(context.permissions) else context.permissions.has(MembresiaModule.Import)
        if (!allowed) return failure(tr("No tienes permiso para esta operacion."))
        val tenant = context.tenant
        return try {
            when (op) {
                MondayOp.Token -> saveToken(context, values)
                MondayOp.Forget -> {
                    MembresiaMondayFlow.forgetToken(context.access.secrets, tenant, context.permissions.activityActor)
                    MembresiaImportReply(tr("Token de monday borrado."))
                }
                MondayOp.Boards -> MembresiaImportReply(tr("Elige un tablero."), params = mapOf("step" to "boards"))
                MondayOp.Map -> {
                    val board = values["board"].orEmpty().trim()
                    if (!MembresiaMondayClient.isBoardId(board)) failure(tr("Elige un tablero."), mapOf("step" to "boards"))
                    else MembresiaImportReply(tr("Revisa el mapeo de columnas."), params = mapOf("step" to "map", "board" to board))
                }
                MondayOp.Read -> read(context, values)
                MondayOp.Apply -> {
                    val id = jobId(values)
                    val job = MembresiaMondayJobs.find(tenant, id)
                    if (job != null && MembresiaMondayFlow.requestApply(tenant, context.permissions, job)) {
                        MembresiaImportReply(tr("Se esta importando."), params = mapOf("step" to "job", "job" to id.toString()))
                    } else {
                        failure(tr("Esta importacion ya no esta en revision."), mapOf("step" to "job", "job" to id.toString()))
                    }
                }
                MondayOp.Cancel -> {
                    val id = jobId(values)
                    val job = MembresiaMondayJobs.find(tenant, id)
                    val done = job != null && MembresiaMondayFlow.cancel(tenant, context.permissions, job)
                    MembresiaImportReply(if (done) tr("Importacion cancelada.") else tr("Esta importacion ya habia terminado."), error = !done, params = mapOf("step" to "job", "job" to id.toString()))
                }
                else -> failure(tr("Operacion desconocida."))
            }
        } catch (problem: MembresiaMondayException) {
            failure(problem.message.orEmpty())
        }
    }

    private fun jobId(values: Map<String, String>): UUID =
        runCatching { UUID.fromString(values["job"].orEmpty().trim()) }.getOrNull() ?: throw MembresiaMondayException(tr("No se encontro la importacion."))

    private fun saveToken(context: MembresiaImportContext, values: Map<String, String>): MembresiaImportReply {
        val tenant = context.tenant
        val typed = values["token"].orEmpty().trim().takeIf { it.isNotEmpty() && it != SecretBox.Mask }
        if (typed == null && !MembresiaMondaySettings.configured(tenant)) return failure(tr("Escribe el token de la API de monday."))
        typed?.let { MembresiaMondayFlow.saveToken(context.access.secrets, tenant, context.permissions.activityActor, it) }
        return MembresiaImportReply(tr("Token de monday guardado."), params = mapOf("step" to "boards"))
    }

    private fun read(context: MembresiaImportContext, values: Map<String, String>): MembresiaImportReply {
        val board = values["board"].orEmpty().trim()
        if (!MembresiaMondayClient.isBoardId(board)) return failure(tr("Elige un tablero."), mapOf("step" to "boards"))
        val chosen = MondayTarget.all.mapNotNull { definition ->
            values["map:${definition.target}"]?.let(MembresiaMondayFlow::columnIdOf)?.let { definition.target to it }
        }.toMap()
        val id = try {
            MembresiaMondayFlow.startRead(context.access, context.tenant, board, MondayMapping(chosen), context.author, context.permissions)
        } catch (problem: MembresiaMondayException) {
            return failure(problem.message.orEmpty(), mapOf("step" to "map", "board" to board))
        }
        return MembresiaImportReply(tr("Se esta leyendo el tablero de monday."), params = mapOf("step" to "job", "job" to id.toString()))
    }
}
