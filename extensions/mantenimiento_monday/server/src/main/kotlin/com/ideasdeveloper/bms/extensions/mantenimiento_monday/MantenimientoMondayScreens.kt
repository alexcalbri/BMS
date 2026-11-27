package com.ideasdeveloper.bms.extensions.mantenimiento_monday

import com.ideasdeveloper.bms.modules.mantenimiento.MantenimientoImportContext
import com.ideasdeveloper.bms.modules.mantenimiento.MantenimientoImportReply
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

/** Lo que comparten la pantalla y las herramientas MCP: leer un tablero con el token guardado y revisar el mapeo y el destino. */
object MantenimientoMondayFlow {
    val NoColumn = msg("No usar")
    val PlansOption = msg("Plantillas preventivas (planes)")
    val OrdersOption = msg("Órdenes / proyectos")
    val CorrectiveOption = msg("Correctiva (avería)")
    val ProjectOption = msg("Proyecto (con aprobación)")
    private const val OptionSeparator = " · "

    /** Texto de la opcion de una columna en los selectores; el id va al final para recuperarlo aunque haya nombres repetidos. */
    fun columnOption(column: MondayColumn): String = "${column.title}$OptionSeparator${column.id}"

    fun columnIdOf(option: String): String? =
        option.takeIf { it.contains(OptionSeparator) }?.substringAfterLast(OptionSeparator)?.trim()?.takeIf { it.isNotEmpty() }

    fun targetOf(option: String?): String? = when (option?.trim()) {
        PlansOption, MondayDestination.Plans -> MondayDestination.Plans
        OrdersOption, MondayDestination.Orders -> MondayDestination.Orders
        else -> null
    }

    fun kindOf(option: String?): String = when (option?.trim()) {
        ProjectOption, MondayDestination.KindProject -> MondayDestination.KindProject
        else -> MondayDestination.KindCorrective
    }

    /** Quien queda como autor de lo importado: el usuario; un dueno del servidor no es usuario de la empresa. */
    fun actorOf(permissions: ModulePermissions): UUID? = permissions.userId?.takeUnless { permissions.serverOwner }

    val ManageDenied = msg("Importar crea planes y ordenes de cualquier tipo: ademas de importar datos necesitas gestionar mantenimiento.")

    /** Quien importa tambien gestiona mantenimiento (el destino son planes y ordenes de cualquier tipo). */
    fun requireDestination(permissions: ModulePermissions) {
        if (!permissions.has(MantenimientoManagePermission)) throw MantenimientoMondayException(tr(ManageDenied))
    }

    /** Aplicar una importacion exige gestionar mantenimiento; cancelarla, ademas, puede quien la inicio. */
    fun requireJobControl(permissions: ModulePermissions, job: MondayJob, apply: Boolean) {
        if (permissions.has(MantenimientoManagePermission)) return
        if (!apply && isCreator(permissions, job)) return
        throw MantenimientoMondayException(
            if (apply) tr("Aplicar una importacion exige gestionar mantenimiento.") else tr("Solo quien inicio la importacion o quien gestiona mantenimiento puede cancelarla."),
        )
    }

    fun isCreator(permissions: ModulePermissions, job: MondayJob): Boolean {
        val actor = actorOf(permissions)
        return actor != null && job.createdBy == actor
    }

    /** Ver una importacion (estado, resumen, filas con problemas): quien gestiona mantenimiento o quien la inicio. */
    fun canSee(permissions: ModulePermissions, job: MondayJob): Boolean =
        permissions.has(MantenimientoManagePermission) || isCreator(permissions, job)

    /** Problema del mapeo que impide importar, o null: solo se revisa que las columnas elegidas sigan existiendo. */
    fun mappingProblem(mapping: MondayMapping, columns: List<MondayColumn>): String? {
        val ids = columns.map { it.id }.toSet()
        return if (mapping.columns.values.any { it !in ids }) tr("El mapeo usa una columna que ya no existe en el tablero.") else null
    }

    /** Lee el tablero en segundo plano con el token guardado; el destino, el mapeo y los permisos se comprueban antes de encolar. */
    fun startRead(
        access: ModuleAccessService,
        tenant: Connection,
        permissions: ModulePermissions,
        boardId: String,
        mapping: MondayMapping,
        target: String,
        defaultKind: String,
    ): UUID {
        requireDestination(permissions)
        if (!MondayDestination.isValid(target)) throw MantenimientoMondayException(tr("Elige el destino: plantillas preventivas u ordenes / proyectos."))
        val token = MantenimientoMondaySettings.loadToken(access.secrets, tenant)
            ?: throw MantenimientoMondayException(tr("Guarda primero el token de la API de monday (pantalla Importar)."))
        val board = MantenimientoMondayClient.boardInfo(token, boardId)
        mappingProblem(mapping, board.columns)?.let { throw MantenimientoMondayException(it) }
        return enqueue(tenant, permissions, boardId, board.name, target, defaultKind, mapping)
    }

    /**
     * Pone en cola la lectura del tablero. La fila del registro de actividad va en la misma transaccion que el trabajo; si lo inicia un
     * agente de IA, su nombre queda en el trabajo para que las filas importadas digan "Agente de <usuario>".
     */
    fun enqueue(tenant: Connection, permissions: ModulePermissions, boardId: String, boardName: String, target: String, defaultKind: String, mapping: MondayMapping): UUID =
        MantenimientoMondayJobs.inTransaction(tenant) {
            val id = MantenimientoMondayJobs.queueRead(tenant, boardId, boardName, target, defaultKind, mapping, actorOf(permissions), permissions.activityActor.agentName, permissions.activityActor)
            MantenimientoMondayActivity.importQueued(tenant, permissions.activityActor, id, boardName, target)
            id
        }

    /** Confirma la importacion en revision y lo deja en el registro de actividad; false si ya no estaba en revision. */
    fun requestApply(tenant: Connection, permissions: ModulePermissions, job: MondayJob): Boolean = MantenimientoMondayJobs.inTransaction(tenant) {
        val changed = MantenimientoMondayJobs.requestApply(tenant, job.id)
        if (changed) MantenimientoMondayActivity.applyRequested(tenant, permissions.activityActor, job)
        changed
    }

    /** Cancela la importacion y lo deja en el registro de actividad; false si ya habia terminado. */
    fun cancel(tenant: Connection, permissions: ModulePermissions, job: MondayJob): Boolean = MantenimientoMondayJobs.inTransaction(tenant) {
        val done = MantenimientoMondayJobs.cancel(tenant, job.id)
        if (done) MantenimientoMondayActivity.cancelled(tenant, permissions.activityActor, job)
        done
    }

    /** Guarda el token (nuevo o cambiado) y deja en el registro solo que cambio, nunca su valor. */
    fun saveToken(secrets: SecretBox, tenant: Connection, actor: ActivityActor, token: String) = MantenimientoMondayJobs.inTransaction(tenant) {
        val had = MantenimientoMondaySettings.configured(tenant)
        MantenimientoMondaySettings.saveToken(secrets, tenant, token)
        MantenimientoMondayActivity.token(tenant, actor, if (had) "Cambio el token de la API de monday." else "Guardo el token de la API de monday.")
    }

    /** Borra el token guardado (si habia) y lo deja en el registro. */
    fun forgetToken(secrets: SecretBox, tenant: Connection, actor: ActivityActor) = MantenimientoMondayJobs.inTransaction(tenant) {
        val had = MantenimientoMondaySettings.configured(tenant)
        MantenimientoMondaySettings.saveToken(secrets, tenant, null)
        if (had) MantenimientoMondayActivity.token(tenant, actor, "Borro el token de la API de monday.")
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
object MantenimientoMondayScreens {
    private const val Route = "/modules/mantenimiento/ui/importar"
    private const val MaxListed = 20
    val ReimportWarning = msg("Reimportar el mismo tablero actualiza los planes y ordenes ya importados y sobrescribe los cambios hechos en Mantenimiento a esos campos.")

    private fun navigate(params: Map<String, String> = emptyMap()) = UiNavigate("importar", mapOf("source" to MantenimientoMondayId) + params)

    private fun post(op: String, params: Map<String, String> = emptyMap(), confirm: String? = null, formId: String? = null) =
        UiRequest(Route, "POST", mapOf("source" to MantenimientoMondayId, "op" to op) + params, confirm, formId)

    fun screen(context: MantenimientoImportContext, params: Map<String, String>): List<UiNode> {
        if (!context.permissions.has(MantenimientoImportPermission)) {
            return listOf(UiText(tr("No tienes permiso para importar datos."), tone = UiTone.Warning))
        }
        val manage = context.permissions.has(MantenimientoManagePermission)
        val denied = listOf(UiText(MantenimientoMondayFlow.ManageDenied, tone = UiTone.Warning))
        return when (params["step"]) {
            "boards" -> if (manage) boardsStep(context) else denied
            "map" -> if (manage) mapStep(context, params["board"].orEmpty()) else denied
            "job" -> {
                val id = params["job"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                if (id != null) jobStep(context, id) else if (manage) home(context) else denied
            }
            else -> if (manage) home(context) else denied
        }
    }

    // ---- Inicio: token e historial ----

    private fun home(context: MantenimientoImportContext): List<UiNode> {
        val tenant = context.tenant
        val configured = MantenimientoMondaySettings.configured(tenant)
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
                    MantenimientoMondayJobs.recent(tenant, 8).map { job ->
                        UiListItem(
                            title = job.boardName.ifBlank { tr("Tablero {0}", job.boardId) },
                            subtitle = if (job.message.isBlank()) tr("{0} · {1} elementos", MondayDestination.label(job.target), job.total) else tr(job.message),
                            trailing = job.createdAt.toString().take(10),
                            badges = listOf(UiBadge(MantenimientoMondayFlow.statusLabel(job.status), MantenimientoMondayFlow.statusTone(job.status))),
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

    private fun boardsStep(context: MantenimientoImportContext): List<UiNode> {
        val back = UiButton(tr("Volver"), navigate(), style = "secondary")
        val token = MantenimientoMondaySettings.loadToken(context.access.secrets, context.tenant)
            ?: return listOf(UiText(tr("Guarda primero el token de la API de monday."), tone = UiTone.Danger), back)
        val boards = try {
            MantenimientoMondayClient.boards(token)
        } catch (failure: MantenimientoMondayException) {
            return listOf(UiText(failure.message.orEmpty(), tone = UiTone.Danger), back)
        }
        if (boards.isEmpty()) return listOf(UiText(tr("El token no tiene acceso a ningun tablero activo."), tone = UiTone.Warning), back)
        return listOf(
            UiCard(
                listOf(
                    UiText(tr("Elige el tablero de monday que quieres importar. En el siguiente paso decides si sus elementos son plantillas preventivas (planes) u ordenes / proyectos.")),
                    UiList(
                        boards.map { board ->
                            UiListItem(
                                title = board.name,
                                subtitle = if (board.workspace == MantenimientoMondayClient.MainWorkspace) tr("Espacio principal") else board.workspace,
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

    private fun mapStep(context: MantenimientoImportContext, boardId: String): List<UiNode> {
        val back = UiButton(tr("Elegir otro tablero"), post(MondayOp.Boards), style = "secondary")
        if (!context.permissions.has(MantenimientoManagePermission)) {
            return listOf(UiText(MantenimientoMondayFlow.ManageDenied, tone = UiTone.Warning))
        }
        val token = MantenimientoMondaySettings.loadToken(context.access.secrets, context.tenant)
            ?: return listOf(UiText(tr("Guarda primero el token de la API de monday."), tone = UiTone.Danger))
        val board = try {
            MantenimientoMondayClient.boardInfo(token, boardId)
        } catch (failure: MantenimientoMondayException) {
            return listOf(UiText(failure.message.orEmpty(), tone = UiTone.Danger), back)
        }
        val suggested = MantenimientoMondayMapping.suggest(board.columns)
        val (suggestedTarget, suggestedKind) = MantenimientoMondayMapping.suggestTarget(board.name)
        val byId = board.columns.associateBy { it.id }
        val columnOptions = listOf(MantenimientoMondayFlow.NoColumn) + board.columns.filter { it.type != "name" && it.type != "subitems" }.map(MantenimientoMondayFlow::columnOption)
        val fields = listOf(
            UiField("board", tr("Id del tablero de monday"), readOnly = true),
            UiField(
                "target", tr("Destino en Mantenimiento"), type = "dropdown",
                options = listOf(MantenimientoMondayFlow.PlansOption, MantenimientoMondayFlow.OrdersOption),
                help = tr("Plantillas preventivas: cada elemento es un plan (la frecuencia sale del grupo). Órdenes / proyectos: cada elemento es una orden."),
            ),
            UiField(
                "kind", tr("Tipo de las órdenes"), type = "dropdown",
                options = listOf(MantenimientoMondayFlow.CorrectiveOption, MantenimientoMondayFlow.ProjectOption),
                help = tr("Solo para órdenes. Los proyectos usan el flujo de aprobación (pendiente, en cotización, aprobado)."),
            ),
        ) + MondayTarget.all.map { definition ->
            val only = if (definition.plans) "" else " " + tr("(solo órdenes)")
            UiField("map:${definition.target}", tr(definition.label) + only, type = "dropdown", options = columnOptions)
        }
        val values = mapOf(
            "board" to board.id,
            "target" to if (suggestedTarget == MondayDestination.Plans) MantenimientoMondayFlow.PlansOption else MantenimientoMondayFlow.OrdersOption,
            "kind" to if (suggestedKind == MondayDestination.KindProject) MantenimientoMondayFlow.ProjectOption else MantenimientoMondayFlow.CorrectiveOption,
        ) + MondayTarget.all.associate { definition ->
            "map:${definition.target}" to (suggested.columns[definition.target]?.let(byId::get)?.let(MantenimientoMondayFlow::columnOption) ?: MantenimientoMondayFlow.NoColumn)
        }
        return listOf(
            UiCard(
                listOf(
                    UiText(tr("Tablero: {0}. Elige el destino y revisa a que columna va cada dato; ya van elegidas las que parecen corresponder.", board.name), UiTextStyle.Body),
                    UiText(
                        tr("Planes: la frecuencia sale del nombre del grupo (Anuales, Trimestrales, Mensuales...), la fecha límite es la próxima fecha y los subelementos pasan a la lista de comprobación. Órdenes: el estado sale de la columna de estado o, si no hay, del grupo; los subelementos pasan a la lista de comprobación, las actualizaciones a notas y los archivos a adjuntos. Las columnas sin destino se agregan a la descripción."),
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

    private fun jobStep(context: MantenimientoImportContext, id: UUID): List<UiNode> {
        val tenant = context.tenant
        val back = UiButton(tr("Volver"), navigate(), style = "secondary")
        val job = MantenimientoMondayJobs.find(tenant, id)?.takeIf { MantenimientoMondayFlow.canSee(context.permissions, it) }
            ?: return listOf(UiText(tr("No se encontro la importacion."), tone = UiTone.Danger), back)
        val manage = context.permissions.has(MantenimientoManagePermission)
        val refresh = UiButton(tr("Actualizar"), navigate(mapOf("step" to "job", "job" to id.toString())), style = "secondary")
        val cancel = UiButton(tr("Cancelar importacion"), post(MondayOp.Cancel, mapOf("job" to id.toString()), confirm = tr("Se cancela la importacion. Lo ya importado se conserva. Continuar?")), style = "danger")
        val header = UiText(tr("Tablero: {0} · {1} · {2}", job.boardName.ifBlank { job.boardId }, MondayDestination.label(job.target), MantenimientoMondayFlow.statusLabel(job.status)), UiTextStyle.Subtitle)
        return when (job.status) {
            MondayJobStatus.Reading -> listOf(
                header,
                UiText(tr("Se esta leyendo el tablero con sus subelementos, actualizaciones y archivos. Un tablero grande tarda unos minutos; actualiza esta pantalla para seguir.")),
                UiText(tr("Elementos leidos: {0}", job.total), UiTextStyle.Caption),
                UiRow(listOf(refresh, cancel)),
            )
            MondayJobStatus.Preview -> previewNodes(job, header, cancel, manage)
            MondayJobStatus.Applying -> listOf(
                header,
                progress(job),
                UiText(tr("Se importan unas 100 filas por minuto. Puedes salir de esta pantalla; la importacion sigue sola."), UiTextStyle.Caption),
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
            UiMetric(tr("Archivos"), job.filesDone.toString(), caption = if (job.filesFailed > 0) tr("{0} con error", job.filesFailed) else "", captionTone = if (job.filesFailed > 0) UiTone.Warning else UiTone.Neutral),
        ),
        minItemWidth = 140,
    )

    private fun previewNodes(job: MondayJob, header: UiNode, cancel: UiNode, canApply: Boolean): List<UiNode> {
        // El resumen se calculo y guardo una vez al terminar de leer: la pantalla solo lo muestra.
        val saved = job.summary ?: return listOf(header, UiText(tr("La vista previa de esta importacion no esta disponible; cancelala y vuelve a leer el tablero."), tone = UiTone.Danger), cancel)
        val summary = saved.toSummary()
        val total = summary.plans + summary.orders
        val nodes = mutableListOf<UiNode>(
            header,
            UiGrid(
                listOf(
                    UiMetric(if (job.target == MondayDestination.Plans) tr("Planes") else tr("Órdenes"), total.toString()),
                    UiMetric(tr("Propiedades"), summary.properties.size.toString()),
                    UiMetric(tr("Activos"), summary.assets.size.toString()),
                    UiMetric(tr("Lista de comprobación"), summary.checklistItems.toString()),
                    UiMetric(tr("Notas"), summary.notes.toString()),
                    UiMetric(tr("Archivos"), summary.files.toString()),
                    UiMetric(tr("Problemas"), saved.problemCount.toString(), captionTone = if (saved.problemCount == 0) UiTone.Success else UiTone.Warning),
                ),
                minItemWidth = 140,
            ),
            UiText(tr(ReimportWarning), UiTextStyle.Caption, tone = UiTone.Warning),
        )
        if (summary.properties.isNotEmpty()) {
            nodes += UiText(if (summary.properties.size > MaxListed) tr("Propiedades (se crean si faltan): {0} y {1} mas.", summary.properties.take(MaxListed).joinToString(), summary.properties.size - MaxListed) else tr("Propiedades (se crean si faltan): {0}.", summary.properties.take(MaxListed).joinToString()), UiTextStyle.Caption)
        }
        if (summary.assets.isNotEmpty()) {
            nodes += UiText(if (summary.assets.size > MaxListed) tr("Activos (se crean si faltan): {0} y {1} mas.", summary.assets.take(MaxListed).joinToString(), summary.assets.size - MaxListed) else tr("Activos (se crean si faltan): {0}.", summary.assets.take(MaxListed).joinToString()), UiTextStyle.Caption)
        }
        nodes += UiCard(
            listOf(
                UiText(if (summary.matchedPeople.isEmpty()) tr("Ninguna persona se empareja con un usuario de Mantenimiento.") else tr("Se emparejan con usuarios: {0}.", summary.matchedPeople.take(MaxListed).joinToString())),
                UiText(
                    if (summary.unmatchedPeople.isEmpty()) tr("Todas las personas tienen usuario.")
                    else tr("Sin usuario (quedan anotadas en la descripcion): {0}.", summary.unmatchedPeople.take(MaxListed).joinToString()),
                    tone = if (summary.unmatchedPeople.isEmpty()) UiTone.Success else UiTone.Warning,
                ),
            ),
            title = tr("Personas"),
        )
        if (summary.problems.isNotEmpty()) {
            nodes += UiCard(
                summary.problems.take(MaxListed).map { UiText(it) } +
                    listOfNotNull(saved.problemCount.takeIf { it > MaxListed }?.let { UiText(tr("Y {0} problemas mas.", it - MaxListed), UiTextStyle.Caption) }),
                title = tr("Problemas encontrados"),
            )
        }
        nodes += UiRow(
            listOfNotNull(
                if (canApply) UiButton(tr("Aplicar importacion"), post(MondayOp.Apply, mapOf("job" to job.id.toString()), confirm = tr("Se crean o actualizan {0} elementos con su lista de comprobacion, notas y archivos. {1} Continuar?", total, tr(ReimportWarning)))) else null,
                cancel,
            ),
        )
        return nodes
    }

    private fun problemNodes(tenant: Connection, job: MondayJob): List<UiNode> {
        val rows = MantenimientoMondayJobs.problems(tenant, job.id, 50)
        if (rows.isEmpty()) return emptyList()
        return listOf(
            UiCard(
                listOf(
                    UiTable(
                        listOf(UiTableColumn("row", tr("Fila")), UiTableColumn("title", tr("Elemento")), UiTableColumn("result", tr("Resultado")), UiTableColumn("message", tr("Motivo"))),
                        rows.map { mapOf("row" to it.rowNo.toString(), "title" to it.title, "result" to resultLabel(it.outcome), "message" to tr(it.message)) },
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

    // ---- Acciones (POST importar?source=mantenimiento_monday&op=...) ----

    private fun failure(message: String, params: Map<String, String> = emptyMap()) = MantenimientoImportReply(message, error = true, params = params)

    fun action(context: MantenimientoImportContext, op: String, values: Map<String, String>): MantenimientoImportReply {
        if (!context.permissions.has(MantenimientoImportPermission)) return failure(tr("No tienes permiso para importar datos."))
        val tenant = context.tenant
        return try {
            // Token, tableros, lectura y aplicacion exigen gestionar mantenimiento; cancelar tambien puede quien inicio la importacion.
            if (op != MondayOp.Cancel) MantenimientoMondayFlow.requireDestination(context.permissions)
            when (op) {
                MondayOp.Token -> saveToken(context, values)
                MondayOp.Forget -> {
                    MantenimientoMondayFlow.forgetToken(context.access.secrets, tenant, context.permissions.activityActor)
                    MantenimientoImportReply(tr("Token de monday borrado."))
                }
                MondayOp.Boards -> MantenimientoImportReply(tr("Elige un tablero."), params = mapOf("step" to "boards"))
                MondayOp.Map -> {
                    val board = values["board"].orEmpty().trim()
                    if (!MantenimientoMondayClient.isBoardId(board)) failure(tr("Elige un tablero."), mapOf("step" to "boards"))
                    else MantenimientoImportReply(tr("Revisa el destino y el mapeo de columnas."), params = mapOf("step" to "map", "board" to board))
                }
                MondayOp.Read -> read(context, values)
                MondayOp.Apply -> {
                    val id = jobId(values)
                    val job = controlled(context, id, apply = true)
                    if (MantenimientoMondayFlow.requestApply(tenant, context.permissions, job)) {
                        MantenimientoImportReply(tr("Se esta importando."), params = mapOf("step" to "job", "job" to id.toString()))
                    } else {
                        failure(tr("Esta importacion ya no esta en revision."), mapOf("step" to "job", "job" to id.toString()))
                    }
                }
                MondayOp.Cancel -> {
                    val id = jobId(values)
                    val job = controlled(context, id, apply = false)
                    val done = MantenimientoMondayFlow.cancel(tenant, context.permissions, job)
                    MantenimientoImportReply(if (done) tr("Importacion cancelada.") else tr("Esta importacion ya habia terminado."), error = !done, params = mapOf("step" to "job", "job" to id.toString()))
                }
                else -> failure(tr("Operacion desconocida."))
            }
        } catch (problem: MantenimientoMondayException) {
            failure(problem.message.orEmpty())
        }
    }

    // Aplicar: solo quien gestiona mantenimiento; cancelar: ademas quien inicio la importacion.
    private fun controlled(context: MantenimientoImportContext, id: UUID, apply: Boolean): MondayJob {
        val job = MantenimientoMondayJobs.find(context.tenant, id) ?: throw MantenimientoMondayException(tr("No se encontro la importacion."))
        MantenimientoMondayFlow.requireJobControl(context.permissions, job, apply)
        return job
    }

    private fun jobId(values: Map<String, String>): UUID =
        runCatching { UUID.fromString(values["job"].orEmpty().trim()) }.getOrNull() ?: throw MantenimientoMondayException(tr("No se encontro la importacion."))

    private fun saveToken(context: MantenimientoImportContext, values: Map<String, String>): MantenimientoImportReply {
        val tenant = context.tenant
        val typed = values["token"].orEmpty().trim().takeIf { it.isNotEmpty() && it != SecretBox.Mask }
        if (typed == null && !MantenimientoMondaySettings.configured(tenant)) return failure(tr("Escribe el token de la API de monday."))
        typed?.let { MantenimientoMondayFlow.saveToken(context.access.secrets, tenant, context.permissions.activityActor, it) }
        return MantenimientoImportReply(tr("Token de monday guardado."), params = mapOf("step" to "boards"))
    }

    private fun read(context: MantenimientoImportContext, values: Map<String, String>): MantenimientoImportReply {
        val board = values["board"].orEmpty().trim()
        if (!MantenimientoMondayClient.isBoardId(board)) return failure(tr("Elige un tablero."), mapOf("step" to "boards"))
        val target = MantenimientoMondayFlow.targetOf(values["target"]) ?: return failure(tr("Elige el destino."), mapOf("step" to "map", "board" to board))
        val kind = MantenimientoMondayFlow.kindOf(values["kind"])
        // Solo se guardan los datos que el destino usa.
        val chosen = MondayTarget.forDestination(target).mapNotNull { definition ->
            values["map:${definition.target}"]?.let(MantenimientoMondayFlow::columnIdOf)?.let { definition.target to it }
        }.toMap()
        val id = try {
            MantenimientoMondayFlow.startRead(context.access, context.tenant, context.permissions, board, MondayMapping(chosen), target, kind)
        } catch (problem: MantenimientoMondayException) {
            return failure(problem.message.orEmpty(), mapOf("step" to "map", "board" to board))
        }
        return MantenimientoImportReply(tr("Se esta leyendo el tablero de monday."), params = mapOf("step" to "job", "job" to id.toString()))
    }
}
