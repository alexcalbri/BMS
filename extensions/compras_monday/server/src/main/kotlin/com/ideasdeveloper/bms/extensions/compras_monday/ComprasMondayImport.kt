package com.ideasdeveloper.bms.extensions.compras_monday

import com.ideasdeveloper.bms.server.activity.ActivityActor
import com.ideasdeveloper.bms.modules.compras.Actor
import com.ideasdeveloper.bms.modules.compras.ComprasImportContext
import com.ideasdeveloper.bms.modules.compras.ComprasImportEngine
import com.ideasdeveloper.bms.modules.compras.ComprasImportSource
import com.ideasdeveloper.bms.modules.compras.ComprasModule
import com.ideasdeveloper.bms.modules.compras.ComprasMoney
import com.ideasdeveloper.bms.modules.compras.ComprasRepository
import com.ideasdeveloper.bms.modules.compras.ComprasScreens
import com.ideasdeveloper.bms.modules.compras.ComprasValidationException
import com.ideasdeveloper.bms.modules.compras.ImportOutcome
import com.ideasdeveloper.bms.server.i18n.msg
import com.ideasdeveloper.bms.server.i18n.tr
import com.ideasdeveloper.bms.server.modules.McpTool
import com.ideasdeveloper.bms.server.modules.ModuleAccessService
import com.ideasdeveloper.bms.server.modules.NotificationText
import com.ideasdeveloper.bms.ui.UiButton
import com.ideasdeveloper.bms.ui.UiField
import com.ideasdeveloper.bms.ui.UiFieldValues
import com.ideasdeveloper.bms.ui.UiForm
import com.ideasdeveloper.bms.ui.UiList
import com.ideasdeveloper.bms.ui.UiListItem
import com.ideasdeveloper.bms.ui.UiNavigate
import com.ideasdeveloper.bms.ui.UiNode
import com.ideasdeveloper.bms.ui.UiNotice
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
import org.slf4j.LoggerFactory

/**
 * Importacion de solicitudes de compra desde tableros de monday. Lee un tablero por la API GraphQL en trabajos cortos (una pagina de
 * 100 elementos por corrida), deja las filas preparadas en las tablas de la extension y las aplica con [ComprasImportEngine]
 * (idempotente por clave). Solo se importan los grupos que el usuario elige (en monday son las propiedades). El token se guarda
 * sellado, se usa solo en el servidor y nunca se muestra ni pasa por las herramientas MCP.
 */
object ComprasMondayImport : ComprasImportSource {
    const val SourceId = "monday"
    const val ExtensionId = "compras_monday"

    /** Permiso del modulo que habilita importar (el mismo de la pantalla Importar y de las herramientas MCP). */
    const val ImportPermission = "compras.import"
    private const val ImportRoute = ComprasScreens.UiRoute + "/import"
    private const val PreviewRows = 10
    private val NoColumn = msg("(ninguna)")
    private const val ApplyBatch = 25
    private const val RunMillis = 40_000L
    private val log = LoggerFactory.getLogger(ComprasMondayImport::class.java)
    private val optionId = Regex("\\(([A-Za-z0-9_-]{1,100})\\)\\s*$")

    override val id = SourceId
    override val label = "Monday"
    override val extensionId = ExtensionId

    override val mcpTools: List<McpTool> get() = ComprasMondayMcpTools.tools

    // ---- Estado de la extension en la empresa ----

    /** La extension esta instalada y activa en la empresa (sus tablas existen solo donde se instalo). */
    fun enabled(tenant: Connection): Boolean = try {
        tenant.prepareStatement("SELECT status FROM tenant_extensions WHERE extension_id = ?").use { query ->
            query.setString(1, ExtensionId)
            query.executeQuery().use { rows -> rows.next() && rows.getString(1) == "enabled" }
        }
    } catch (failure: SQLException) {
        log.warn("No se pudo consultar el estado de la extension de monday ({}).", failure.sqlState)
        false
    }

    override fun isEnabled(ctx: ComprasImportContext): Boolean = enabled(ctx.tenant)

    /** Modulos de la empresa: sirven para reconstruir los permisos vivos de quien lanzo la importacion (los entrega el Core en routes). */
    @Volatile
    private var modules: ModuleAccessService? = null

    fun bind(access: ModuleAccessService) {
        modules = access
    }

    private fun requireEnabled(tenant: Connection) {
        if (!enabled(tenant)) throw ComprasValidationException(tr("La extension de monday no esta activa en esta empresa."))
    }

    private fun requireToken(ctx: ComprasImportContext): String =
        ComprasMondayStore.loadToken(ctx) ?: throw ComprasValidationException(tr("Guarda primero el token de la API de monday en la app (Compras > Importar)."))

    // ---- Operaciones (las usan la pantalla y las herramientas MCP) ----

    /** Guarda el token (vacio o el texto enmascarado no cambia nada); en el registro de actividad queda que cambio, nunca su valor. */
    fun saveToken(ctx: ComprasImportContext, typed: String) {
        requireEnabled(ctx.tenant)
        val token = typed.trim().takeIf { it.isNotEmpty() && it != "********" }
            ?: throw ComprasValidationException(tr("Escribe el token de la API de monday."))
        val actor = ctx.actor ?: throw ComprasValidationException(tr("No se pudo identificar al usuario."))
        ComprasMondayStore.transaction(ctx.tenant) {
            val had = ComprasMondayStore.hasToken(ctx.tenant)
            ComprasMondayStore.saveToken(ctx, token)
            ComprasMondayActivity.token(ctx.tenant, actor.activity, if (had) "Cambio el token de la API de monday." else "Guardo el token de la API de monday.")
        }
    }

    fun forgetToken(ctx: ComprasImportContext) {
        requireEnabled(ctx.tenant)
        val actor = ctx.actor ?: throw ComprasValidationException(tr("No se pudo identificar al usuario."))
        ComprasMondayStore.transaction(ctx.tenant) {
            val had = ComprasMondayStore.hasToken(ctx.tenant)
            ComprasMondayStore.saveToken(ctx, null)
            if (had) ComprasMondayActivity.token(ctx.tenant, actor.activity, "Borro el token de la API de monday.")
        }
    }

    /**
     * Pone en cola la lectura de un tablero. [info] es lo que monday dijo del tablero (nombre, grupos y columnas): los grupos y las
     * columnas elegidos deben existir en el. [mapping] lleva columnas por campo ([MondayFields]); [currency] vacia usa la de Compras.
     * Hay una sola importacion en marcha por empresa (la base lo garantiza).
     */
    fun startImport(ctx: ComprasImportContext, info: MondayBoardInfo, groups: List<String>, mapping: Map<String, String>, currency: String): UUID {
        requireEnabled(ctx.tenant)
        val actor = ctx.actor ?: throw ComprasValidationException(tr("No se pudo identificar al usuario."))
        val chosen = groups.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (chosen.isEmpty()) throw ComprasValidationException(tr("Elige al menos un grupo del tablero."))
        if (chosen.any { id -> info.groups.none { it.id == id } }) throw ComprasValidationException(tr("Uno de los grupos no existe en el tablero."))
        val columns = MondayFields.All.associateWith { field -> mapping[field].orEmpty().trim() }.filterValues { it.isNotEmpty() }
        if (columns.values.any { id -> info.columns.none { it.id == id } }) throw ComprasValidationException(tr("Una de las columnas elegidas no existe en el tablero."))
        val money = currency.trim().ifEmpty { ComprasRepository.settings(ctx.tenant).defaultCurrency }
        val stored = columns + (MondayFields.Currency to ComprasMoney.currency(money))
        ComprasMondayStore.activeJob(ctx.tenant)?.let {
            throw ComprasValidationException(tr("Ya hay una importacion de monday en curso ({0}). Cancelala o espera a que termine.", tr(statusLabel(it.status))))
        }
        // La fila del registro de actividad va en la misma transaccion que el trabajo en cola.
        return ComprasMondayStore.transaction(ctx.tenant) {
            val id = ComprasMondayStore.insertJob(ctx.tenant, info.id, info.name, chosen, stored, actor.userId, actor.name)
            ComprasMondayActivity.importQueued(ctx.tenant, actor.activity, id, info.name, chosen.size)
            id
        }
    }

    /** Pasa una importacion leida a aplicarse. [jobId] nulo toma la que esta en curso. */
    fun applyJob(ctx: ComprasImportContext, jobId: UUID?): MondayJob {
        requireEnabled(ctx.tenant)
        val job = (jobId?.let { ComprasMondayStore.job(ctx.tenant, it) } ?: ComprasMondayStore.activeJob(ctx.tenant))
            ?: throw ComprasValidationException(tr("No hay una importacion de monday para aplicar."))
        if (job.status != "ready") throw ComprasValidationException(tr("La importacion no esta lista para aplicarse ({0}).", tr(statusLabel(job.status))))
        if (job.readCount == 0) throw ComprasValidationException(tr("Ningun elemento de los grupos elegidos; no hay nada que importar."))
        val actor = ctx.actor ?: throw ComprasValidationException(tr("No se pudo identificar al usuario."))
        ComprasMondayStore.transaction(ctx.tenant) {
            // Si confirma un agente de IA se guarda su nombre: las filas importadas dicen "Importacion (Agente de <usuario>)".
            if (!ComprasMondayStore.startApplying(ctx.tenant, job.id, actor.userId, actor.name, actor.activity.agentName, actor.activity)) {
                throw ComprasValidationException(tr("La importacion cambio de estado; actualiza la pantalla."))
            }
            ComprasMondayActivity.applyRequested(ctx.tenant, actor.activity, job)
        }
        return job
    }

    fun cancelJob(ctx: ComprasImportContext, jobId: UUID?) {
        requireEnabled(ctx.tenant)
        val job = (jobId?.let { ComprasMondayStore.job(ctx.tenant, it) } ?: ComprasMondayStore.activeJob(ctx.tenant))
            ?: throw ComprasValidationException(tr("No hay una importacion de monday en curso."))
        val actor = ctx.actor ?: throw ComprasValidationException(tr("No se pudo identificar al usuario."))
        ComprasMondayStore.transaction(ctx.tenant) {
            if (!ComprasMondayStore.transition(ctx.tenant, job.id, MondayJob.ActiveStatuses, "cancelled")) {
                throw ComprasValidationException(tr("La importacion ya no esta en curso."))
            }
            ComprasMondayActivity.cancelled(ctx.tenant, actor.activity, job)
        }
    }

    fun statusLabel(status: String): String = when (status) {
        "reading" -> msg("leyendo monday")
        "ready" -> msg("lista para importar")
        "applying" -> msg("importando solicitudes")
        "done" -> msg("terminada")
        "failed" -> msg("con error")
        "cancelled" -> msg("cancelada")
        else -> status
    }

    // ---- Op de la pantalla Importar ----

    override fun action(ctx: ComprasImportContext, op: String, values: Map<String, String>): UiNotice? {
        // Ninguna op (ni olvidar el token o cancelar) corre si la extension no esta activa en la empresa.
        requireEnabled(ctx.tenant)
        return run(ctx, op.removePrefix("$SourceId:"), values)
    }

    private fun run(ctx: ComprasImportContext, op: String, values: Map<String, String>): UiNotice? = when (op) {
        "token" -> {
            saveToken(ctx, values["token"].orEmpty())
            UiNotice(tr("Token de monday guardado."), UiTone.Success)
        }
        "forget" -> {
            forgetToken(ctx)
            UiNotice(tr("Token de monday borrado."), UiTone.Success)
        }
        "read" -> {
            // El nombre, los grupos y las columnas salen de monday, no del formulario: de el solo se toma el id del tablero y lo elegido.
            val info = try {
                ComprasMondayApi.boardInfo(requireToken(ctx), values["board_id"].orEmpty().trim())
            } catch (failure: ComprasMondayException) {
                throw ComprasValidationException(failure.message.orEmpty())
            }
            val mapping = MondayFields.All.associateWith { field -> optionIdOf(values["map_$field"].orEmpty()).orEmpty() }
            startImport(ctx, info, UiFieldValues.options(values["groups"].orEmpty()).mapNotNull { optionIdOf(it) }, mapping, values["currency"].orEmpty())
            UiNotice(tr("Se esta leyendo el tablero de monday. Actualiza esta pantalla para ver el avance."), UiTone.Success)
        }
        "apply" -> {
            val job = applyJob(ctx, null)
            UiNotice(tr("Se importaran {0} elementos de {1} en segundo plano.", job.readCount, job.boardName), UiTone.Success)
        }
        "cancel" -> {
            cancelJob(ctx, null)
            UiNotice(tr("Importacion cancelada. Las solicitudes ya importadas se conservan."), UiTone.Success)
        }
        else -> null
    }

    /** Id al final de una opcion "Titulo (id)"; una opcion sin ese formato (o "(ninguna)") no es una columna. */
    fun optionIdOf(option: String): String? =
        if (option == NoColumn) null else optionId.find(option.trim())?.groupValues?.get(1)

    private fun optionLabel(title: String, id: String) = "${title.take(80)} ($id)"

    /** El espacio "Espacio principal" (el que pone la API cuando no trae nombre) se muestra traducido; los demas nombres son de monday. */
    private fun workspaceLabel(name: String) = if (name == ComprasMondayParse.MainWorkspace) tr(name) else name

    // ---- Tarjeta de la pantalla Importar ----

    override fun screen(ctx: ComprasImportContext, params: Map<String, String>): List<UiNode> {
        val tenant = ctx.tenant
        if (!enabled(tenant)) return emptyList()
        val hasToken = ComprasMondayStore.hasToken(tenant)
        val nodes = mutableListOf<UiNode>(tokenForm(hasToken))
        if (hasToken) {
            nodes += UiRow(
                listOf(
                    UiButton(tr("Cargar tableros"), UiNavigate("import", mapOf(SourceId to "boards")), style = "secondary"),
                    UiButton(tr("Quitar token"), UiRequest(ImportRoute, params = mapOf("op" to "$SourceId:forget"), confirm = tr("Se borra el token de monday guardado. Continuar?")), style = "secondary"),
                ),
            )
            when (params[SourceId]) {
                "boards" -> nodes += guarded { boardsNodes(ctx) }
                "pick" -> nodes += guarded { pickNodes(ctx, params["board"].orEmpty()) }
            }
        }
        ComprasMondayStore.latestJob(tenant)?.let { nodes += jobNodes(tenant, it) }
        return nodes
    }

    private fun guarded(block: () -> List<UiNode>): List<UiNode> = try {
        block()
    } catch (failure: ComprasMondayException) {
        listOf(UiText(failure.message.orEmpty(), tone = UiTone.Danger))
    } catch (failure: ComprasValidationException) {
        listOf(UiText(failure.message.orEmpty(), tone = UiTone.Danger))
    }

    private fun tokenForm(hasToken: Boolean): UiNode = UiForm(
        "mondayToken",
        listOf(
            UiField(
                "token", tr("Token de la API de monday"), type = "password", required = !hasToken,
                help = if (hasToken) tr("Hay un token guardado (no se muestra). Escribe otro solo para cambiarlo.")
                else tr("monday > tu avatar > Developers > Mi token. Se guarda sellado para la empresa."),
            ),
        ),
        submit = UiButton(tr("Guardar token"), UiRequest(ImportRoute, params = mapOf("op" to "$SourceId:token"), formId = "mondayToken")),
    )

    private fun boardsNodes(ctx: ComprasImportContext): List<UiNode> {
        val boards = ComprasMondayApi.boards(requireToken(ctx))
        if (boards.isEmpty()) return listOf(UiText(tr("El token no tiene acceso a ningun tablero activo."), tone = UiTone.Warning))
        return listOf(
            UiText(tr("Elige el tablero a importar. Despues eliges los grupos (propiedades) y mapeas las columnas."), UiTextStyle.Caption),
            UiList(
                boards.take(300).map { board ->
                    UiListItem(
                        board.name,
                        workspaceLabel(board.workspace) + board.items?.let { tr(" - {0} elementos", it) }.orEmpty(),
                        action = UiNavigate("import", mapOf(SourceId to "pick", "board" to board.id)),
                    )
                },
            ),
        )
    }

    private fun pickNodes(ctx: ComprasImportContext, boardId: String): List<UiNode> {
        val info = ComprasMondayApi.boardInfo(requireToken(ctx), boardId)
        val suggestion = ComprasMondayMapping.suggest(info.columns)
        val byId = info.columns.associateBy { it.id }
        val columnOptions = listOf(NoColumn) + info.columns.map { optionLabel(it.title, it.id) }
        val mappingFields = MondayFields.All.map { field ->
            UiField(
                "map_$field", MondayFields.label(field), type = "dropdown", options = columnOptions, section = tr("Columnas"),
                help = if (field == MondayFields.Department) tr("Sin columna, el departamento sale del nombre del tablero.") else "",
            )
        }
        val fields = listOf(
            UiField("board_id", tr("Tablero (id)"), readOnly = true),
            UiField("board_name", tr("Tablero"), readOnly = true),
            UiField(
                "groups", tr("Grupos a importar"), type = "dropdown", multiple = true, required = true,
                options = info.groups.map { optionLabel(it.title, it.id) },
                help = tr("Cada grupo es una propiedad (por ejemplo Costa Azul, Vista Mar, Bosque). Solo se importan los elegidos."),
            ),
            UiField("currency", tr("Moneda"), help = tr("Codigo de 3 letras; vacio usa la moneda de Compras.")),
        ) + mappingFields
        val values = buildMap<String, String> {
            put("board_id", info.id)
            put("board_name", info.name)
            put("currency", ComprasRepository.settings(ctx.tenant).defaultCurrency)
            MondayFields.All.forEach { field -> put("map_$field", suggestion[field]?.let { byId[it] }?.let { optionLabel(it.title, it.id) } ?: NoColumn) }
        }
        return listOf(
            UiText(tr("Tablero: {0}", info.name), UiTextStyle.Subtitle),
            UiForm(
                "mondayRead", fields, values,
                submit = UiButton(tr("Leer tablero"), UiRequest(ImportRoute, params = mapOf("op" to "$SourceId:read"), formId = "mondayRead")),
            ),
        )
    }

    private fun jobNodes(tenant: Connection, job: MondayJob): List<UiNode> {
        val nodes = mutableListOf<UiNode>(
            UiText(tr("Importacion de {0}: {1}", job.boardName, tr(statusLabel(job.status))), UiTextStyle.Subtitle),
            UiText(
                tr("Leidos: {0}. Creadas: {1}. Ya existian: {2}. Con error: {3}.", job.readCount, job.createdCount, job.existingCount, job.failedCount),
                UiTextStyle.Caption,
            ),
        )
        if (job.status == "failed" && job.error.isNotEmpty()) nodes += UiText(job.error, tone = UiTone.Danger)
        if (job.readCount > 0) {
            val fallback = job.createdByName.ifEmpty { "Importacion de monday" }
            val rows = ComprasMondayStore.previewRows(tenant, job.id, PreviewRows).mapNotNull { row ->
                val item = ComprasMondayParse.decode(row.payload) ?: return@mapNotNull null
                val request = ComprasMondayMapping.convert(item, job.boardName, job.mapping, fallback) { null }.request
                mapOf(
                    "title" to item.name,
                    "status" to (request?.status?.label ?: tr("Sin importar")),
                    "amount" to ((request?.estimatedAmount ?: request?.quotes?.firstOrNull()?.amount)?.let { ComprasMoney.format(it, request?.currency ?: "") } ?: ""),
                    "result" to rowResult(row.status),
                )
            }
            nodes += UiTable(
                listOf(UiTableColumn("title", tr("Titulo")), UiTableColumn("status", tr("Estado en Compras")), UiTableColumn("amount", tr("Monto")), UiTableColumn("result", tr("Resultado"))),
                rows, emptyText = tr("Sin filas."),
            )
            ComprasMondayStore.problemRows(tenant, job.id, PreviewRows).forEach { row ->
                val title = ComprasMondayParse.decode(row.payload)?.name ?: row.itemId
                nodes += UiText(tr("{0}: {1}", title, row.problem.ifEmpty { tr("con error") }), tone = UiTone.Warning)
            }
        }
        val buttons = mutableListOf<UiNode>(UiButton(tr("Actualizar"), UiNavigate("import"), style = "secondary"))
        if (job.status == "ready" && job.readCount > 0) {
            buttons += UiButton(
                tr("Importar {0} elementos", job.readCount), UiRequest(ImportRoute, params = mapOf("op" to "$SourceId:apply"), confirm = tr("Se crearan solicitudes en Compras con lo que se ve aqui. Continuar?")),
            )
        }
        if (job.active) buttons += UiButton(tr("Cancelar"), UiRequest(ImportRoute, params = mapOf("op" to "$SourceId:cancel")), style = "danger")
        nodes += UiRow(buttons)
        return nodes
    }

    private fun rowResult(status: String) = when (status) {
        "applied" -> tr("Creada")
        "existing" -> tr("Ya existia")
        "failed" -> tr("Con error")
        else -> tr("Pendiente")
    }

    // ---- Trabajo periodico ----

    /**
     * Avanza lo pendiente dentro de [RunMillis]: varias paginas de lectura por importacion y hasta [budget] filas aplicadas. Cada
     * importacion se trabaja con un candado (dos corridas no aplican las mismas filas) y cada paso es idempotente: tras un reinicio
     * sigue donde quedo. Un fallo de una importacion no detiene a las demas.
     */
    override fun runPending(ctx: ComprasImportContext, budget: Int) {
        val tenant = ctx.tenant
        if (!enabled(tenant)) return
        val deadline = System.nanoTime() + RunMillis * 1_000_000
        try {
            ComprasMondayStore.purgeOld(tenant)
            for (summary in ComprasMondayStore.jobsWithStatus(tenant, "reading")) {
                if (System.nanoTime() > deadline) break
                ComprasMondayStore.withJobLock(tenant, summary.id) {
                    // Con el candado se vuelve a leer: otra corrida pudo cambiarla.
                    ComprasMondayStore.job(tenant, summary.id)?.takeIf { it.status == "reading" }?.let { readPages(ctx, it, deadline) }
                }
            }
            var remaining = budget.coerceAtLeast(0)
            for (summary in ComprasMondayStore.jobsWithStatus(tenant, "applying")) {
                if (remaining <= 0 || System.nanoTime() > deadline) break
                ComprasMondayStore.withJobLock(tenant, summary.id) {
                    ComprasMondayStore.job(tenant, summary.id)?.takeIf { it.status == "applying" }?.let { remaining -= applyRows(ctx, it, remaining, deadline) }
                }
            }
        } catch (failure: SQLException) {
            // La base no respondio: lo pendiente queda como esta y se reintenta en la proxima corrida.
            log.warn("Importacion de monday: error de base de datos en {} ({}).", ctx.companyCode, failure.sqlState)
        }
    }

    /** Lee paginas mientras haya tiempo. Un fallo pasajero se reintenta en la proxima corrida (hasta [ComprasMondayRetry.MaxAttempts] seguidos). */
    private fun readPages(ctx: ComprasImportContext, start: MondayJob, deadline: Long) {
        val tenant = ctx.tenant
        var job = start
        var pageSize = ComprasMondayApi.PageSize
        while (System.nanoTime() < deadline) {
            try {
                if (job.pages >= ComprasMondayRetry.MaxPages) throw ComprasMondayException(tr("El tablero tiene demasiadas paginas (mas de {0}).", ComprasMondayRetry.MaxPages))
                val token = ComprasMondayStore.loadToken(ctx) ?: throw ComprasMondayException(tr("Se borro el token de monday; guardalo de nuevo y repite la importacion."))
                val page = try {
                    ComprasMondayApi.page(token, job.boardId, job.cursor, pageSize)
                } catch (heavy: ComprasMondayException) {
                    if (!heavy.tooHeavy) throw heavy
                    // Respuesta demasiado grande o compleja: pagina mas chica dentro de la misma corrida; a 10 ya no hay mas que bajar.
                    val smaller = ComprasMondayRetry.smallerPage(pageSize) ?: throw ComprasMondayException(tr(ComprasMondayRetry.TooHeavyMessage))
                    log.warn("La lectura de monday en {} baja la pagina de {} a {} elementos.", ctx.companyCode, pageSize, smaller)
                    pageSize = smaller
                    continue
                }
                val kept = ComprasMondayParse.filterByGroups(page.items, job.groups.toSet())
                if (job.readCount + kept.size > ComprasMondayApi.MaxItems) throw ComprasMondayException(tr("El tablero tiene mas de {0} elementos en los grupos elegidos.", ComprasMondayApi.MaxItems))
                if (page.cursor != null && !ComprasMondayParse.isValidCursor(page.cursor)) throw ComprasMondayException(tr("monday devolvio un cursor no valido; vuelve a iniciar la importacion."))
                // Falso: se cancelo mientras se leia o otra corrida ya guardo esta pagina.
                if (!ComprasMondayStore.saveReadPage(tenant, job, kept, page.cursor) || page.cursor == null) return
                job = job.copy(cursor = page.cursor, readCount = job.readCount + kept.size, pages = job.pages + 1, attempts = 0)
            } catch (failure: ComprasMondayException) {
                log.warn("La lectura de monday fallo en {}: {}", ctx.companyCode, failure.message)
                failRead(tenant, job, failure.message.orEmpty(), failure.retryable)
                return
            } catch (failure: SQLException) {
                throw failure
            } catch (failure: Exception) {
                log.warn("La lectura de monday fallo en {} ({}).", ctx.companyCode, failure.javaClass.simpleName, failure)
                failRead(tenant, job, tr("No se pudo leer el tablero de monday."), true)
                return
            }
        }
    }

    private fun failRead(tenant: Connection, job: MondayJob, message: String, retryable: Boolean) {
        val status = ComprasMondayRetry.statusAfterFailure(job.attempts + 1, retryable)
        ComprasMondayStore.recordReadFailure(tenant, job, if (status == "failed") message else "$message (se reintenta)", status)
    }

    /**
     * Quien aplica: el usuario que pulso Importar, con sus permisos de HOY. Si ya no esta activo o perdio el permiso, no se importa
     * nada (null). Sin acceso a los modulos tampoco: falla cerrado.
     */
    private fun liveImporter(ctx: ComprasImportContext, job: MondayJob): Actor? {
        val userId = job.appliedBy ?: return null
        val permissions = modules?.userAccess(ctx.tenant, ctx.companyCode, userId, false)?.permissions(ComprasModule.id) ?: return null
        if (!permissions.businessOwner && !permissions.has(ImportPermission)) return null
        return Actor(userId, job.appliedByName.ifEmpty { "Importacion de monday" }, permissions, startedBy(job, permissions.activityActor))
    }

    /** Aplica hasta [limit] filas pendientes (por lotes, hasta el plazo); devuelve cuantas proceso. Al terminar avisa a quien la lanzo. */
    private fun applyRows(ctx: ComprasImportContext, job: MondayJob, limit: Int, deadline: Long): Int {
        val tenant = ctx.tenant
        val importer = liveImporter(ctx, job)
        if (importer == null) {
            ComprasMondayStore.transaction(tenant) {
                if (ComprasMondayStore.transition(tenant, job.id, setOf("applying"), "failed", "El usuario que inicio la importacion ya no tiene permiso")) {
                    // Si ya habia creado solicitudes, queda la fila de totales (las de cada solicitud ya estan).
                    val done = ComprasMondayStore.job(tenant, job.id) ?: job
                    if (done.createdCount + done.existingCount + done.failedCount > 0) {
                        ComprasMondayActivity.total(tenant, importActivity(ctx, job), job.id, job.boardName, done.createdCount, done.existingCount, done.failedCount, "Se detuvo: quien la confirmo ya no tiene permiso.")
                    }
                }
            }
            return 0
        }
        val token by lazy { ComprasMondayStore.loadToken(ctx) }
        // Enlaces de los archivos del lote, pedidos de 50 en 50 (no uno por archivo); un asset que falte se pide solo.
        var links: Map<String, Pair<String, Long?>> = emptyMap()
        val opener: (String) -> java.io.InputStream? = { asset -> token?.let { ComprasMondayApi.openAsset(it, asset, deadline, links[asset]) } }
        var done = 0
        while (done < limit && System.nanoTime() < deadline) {
            val rows = ComprasMondayStore.pendingRows(tenant, job.id, minOf(limit - done, ApplyBatch))
            if (rows.isEmpty()) break
            links = prefetchLinks(token, rows, deadline)
            for (row in rows) {
                if (System.nanoTime() > deadline) break
                done++
                applyRow(ctx, job, importer, row, opener)
            }
        }
        ComprasMondayStore.refreshCounts(tenant, job.id)
        if (ComprasMondayStore.pendingCount(tenant, job.id) == 0) {
            // El cierre y la fila de totales se confirman juntos; el aviso a quien la lanzo va despues.
            val finished = ComprasMondayStore.transaction(tenant) {
                if (ComprasMondayStore.transition(tenant, job.id, setOf("applying"), "done")) {
                    val closed = ComprasMondayStore.job(tenant, job.id) ?: job
                    ComprasMondayActivity.total(tenant, importActivity(ctx, closed), closed.id, closed.boardName, closed.createdCount, closed.existingCount, closed.failedCount)
                    closed
                } else {
                    null
                }
            }
            if (finished != null) notifyDone(ctx, finished)
        }
        return done
    }

    /** Pide en grupo los enlaces de los archivos de las filas del lote; si falla, cada archivo pedira el suyo al abrirse. */
    private fun prefetchLinks(token: String?, rows: List<MondayRow>, deadline: Long): Map<String, Pair<String, Long?>> {
        if (token == null) return emptyMap()
        // Con menos de 5 s de corrida no se empieza una consulta: cada archivo pedira su enlace al abrirse.
        val leftMillis = (deadline - System.nanoTime()) / 1_000_000L
        if (leftMillis < 5_000L) return emptyMap()
        val ids = rows.flatMap { row -> ComprasMondayParse.decode(row.payload)?.files.orEmpty().map { it.asset } }
        if (ids.isEmpty()) return emptyMap()
        return try {
            ComprasMondayApi.assetLinks(token, ids, minOf(60_000L, leftMillis))
        } catch (failure: ComprasMondayException) {
            log.warn("No se pidieron en grupo los enlaces de {} archivos de monday ({}).", ids.size, failure.javaClass.simpleName)
            emptyMap()
        }
    }

    private fun applyRow(ctx: ComprasImportContext, job: MondayJob, importer: Actor, row: MondayRow, opener: (String) -> java.io.InputStream?) {
        val tenant = ctx.tenant
        val item = ComprasMondayParse.decode(row.payload)
        if (item == null) {
            ComprasMondayStore.markRow(tenant, job.id, row.itemId, "failed", "La fila preparada no se pudo leer.")
            return
        }
        val conversion = ComprasMondayMapping.convert(item, job.boardName, job.mapping, importer.name, opener)
        val request = conversion.request
        if (request == null) {
            ComprasMondayStore.markRow(tenant, job.id, row.itemId, "failed", conversion.problems.joinToString("; "))
            return
        }
        try {
            val result = ComprasImportEngine.apply(ctx, importer, request)
            val problems = (conversion.problems + result.problems).joinToString("; ")
            val status = when (result.outcome) {
                ImportOutcome.Created, ImportOutcome.Partial -> "applied"
                ImportOutcome.Existing -> "existing"
                ImportOutcome.Failed -> "failed"
            }
            ComprasMondayStore.markRow(tenant, job.id, row.itemId, status, if (status == "failed") problems.ifEmpty { "No se pudo importar." } else problems)
        } catch (failure: ComprasValidationException) {
            ComprasMondayStore.markRow(tenant, job.id, row.itemId, "failed", failure.message.orEmpty())
        } catch (failure: SQLException) {
            // Problema de base de datos: la fila sigue pendiente y se reintenta en la proxima corrida.
            throw failure
        } catch (failure: Exception) {
            log.warn("No se pudo importar el elemento {} de monday ({}).", row.itemId, failure.javaClass.simpleName, failure)
            ComprasMondayStore.markRow(tenant, job.id, row.itemId, "failed", "Error inesperado al importar.")
        }
    }

    /** Quien cuenta como actor de las filas de la importacion: el usuario que la confirmo (o la inicio), con su agente si lo hubo. */
    private fun startedBy(job: MondayJob, user: ActivityActor): ActivityActor =
        ActivityActor.restore(job.startedActor) ?: job.appliedByAgent?.let(user::viaAgent) ?: user

    /** "Importacion (<quien>)" para la fila de totales: con los permisos vivos de quien confirmo; si ya no se pueden leer, su nombre guardado. */
    private fun importActivity(ctx: ComprasImportContext, job: MondayJob): ActivityActor {
        ActivityActor.restore(job.startedActor)?.let { return ActivityActor.import(it) }
        val userId = job.appliedBy ?: job.createdBy
        val live = userId?.let { modules?.userAccess(ctx.tenant, ctx.companyCode, it, false)?.permissions(ComprasModule.id)?.activityActor }
        val user = live ?: userId?.let { ActivityActor.user(it, job.appliedByName.ifEmpty { job.createdByName }) }
        return ActivityActor.import(user?.let { startedBy(job, it) })
    }

    // Aviso a quien lanzo la importacion (nunca falla por el aviso).
    private fun notifyDone(ctx: ComprasImportContext, job: MondayJob) {
        val userId = job.appliedBy ?: job.createdBy ?: return
        try {
            ctx.notifier?.notify(ctx.tenant, userId) {
                NotificationText(
                    tr("Importacion de monday terminada"),
                    tr("{0}: {1} creadas, {2} ya existian y {3} con error.", job.boardName, job.createdCount, job.existingCount, job.failedCount),
                )
            }
        } catch (failure: Exception) {
            log.warn("No se pudo crear el aviso de la importacion de monday ({}).", failure.javaClass.simpleName)
        }
    }
}
