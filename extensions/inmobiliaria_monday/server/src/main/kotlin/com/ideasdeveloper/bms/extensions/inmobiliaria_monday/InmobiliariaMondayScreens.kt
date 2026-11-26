package com.ideasdeveloper.bms.extensions.inmobiliaria_monday

import com.ideasdeveloper.bms.modules.crm.CrmActor
import com.ideasdeveloper.bms.modules.crm.CrmExtensionActionResult
import com.ideasdeveloper.bms.modules.crm.CrmHost
import com.ideasdeveloper.bms.modules.crm.CrmImportKind
import com.ideasdeveloper.bms.modules.crm.CrmScreens
import com.ideasdeveloper.bms.server.i18n.msg
import com.ideasdeveloper.bms.server.i18n.tr
import com.ideasdeveloper.bms.ui.UiButton
import com.ideasdeveloper.bms.ui.UiCard
import com.ideasdeveloper.bms.ui.UiField
import com.ideasdeveloper.bms.ui.UiFieldCondition
import com.ideasdeveloper.bms.ui.UiForm
import com.ideasdeveloper.bms.ui.UiMetric
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
import java.util.UUID

/** Pantalla unica de la extension (seccion oculta del CRM, permiso crm.settings) y sus acciones. Sin tildes, como el resto del CRM. */
object InmobiliariaMondayScreens {
    const val Import = "inmobiliaria_monday_import"
    private const val Route = CrmScreens.UiRoute
    private const val NoImport = "(no importar)"
    private const val PreviewRows = 10
    private const val ShownProblems = 30
    private val optionKey = Regex("\\[([a-z0-9_]+)]$")
    private val boardId = Regex("\\[([0-9]{1,20})]$")

    private fun request(op: String, params: Map<String, String> = emptyMap(), formId: String? = null, confirm: String? = null) =
        UiRequest("$Route/$Import", params = params + ("op" to op), confirm = confirm, formId = formId)

    private fun openScreen(params: Map<String, String> = emptyMap()) = UiNavigate(Import, params)

    private fun message(text: String, tone: String = UiTone.Warning): List<UiNode> = listOf(UiText(text, tone = tone))

    // ---- Pantalla ----

    fun screen(context: CrmScreens.Context, params: Map<String, String>): List<UiNode> {
        if (!context.permissions.has(CrmScreens.Settings)) return message(tr("No tienes permiso para importar desde monday."))
        val tenant = context.tenant
        InmobiliariaMondayService.unavailableReason(tenant)?.let { return message(it) }
        val job = params["job"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        return try {
            if (job != null) jobScreen(tenant, job) else mainScreen(tenant, params)
        } catch (failure: InmobiliariaMondayException) {
            message(failure.message.orEmpty())
        }
    }

    private fun mainScreen(tenant: Connection, params: Map<String, String>): List<UiNode> {
        val token = InmobiliariaMondayService.token(tenant)
        val nodes = mutableListOf<UiNode>(
            UiText(tr("Importar desde monday a Inmobiliaria"), UiTextStyle.Subtitle),
            UiText(
                tr("Trae un tablero de monday (listados o deals) a la extension Inmobiliaria: se lee, se revisa el mapeo de columnas y recien entonces se aplica. Reimportar el mismo tablero actualiza lo ya importado, no lo duplica."),
                UiTextStyle.Caption,
            ),
            tokenCard(token),
        )
        if (token != null) {
            if (params["boards"] == "1") nodes += boardsCard(tenant) else nodes += UiButton(tr("Cargar tableros"), openScreen(mapOf("boards" to "1")), style = "secondary")
        }
        nodes += historyCard(tenant)
        return nodes
    }

    private fun tokenCard(token: String?): UiNode = UiCard(
        listOfNotNull(
            UiForm(
                "mondayToken",
                listOf(UiField("token", tr("Token de la API de monday"), type = "password", required = token == null, help = tr("monday > tu avatar > Developers > Mi token. Se guarda sellado para la empresa y no se vuelve a mostrar."))),
                mapOf("token" to CrmHost.maskSecret(token)),
                submit = UiButton(tr("Guardar y cargar tableros"), request("token", formId = "mondayToken")),
            ),
            token?.let { UiButton(tr("Quitar token"), request("forget", confirm = tr("Se borra el token de monday guardado. Continuar?")), style = "secondary") },
        ),
        title = tr("Token de monday"),
    )

    private fun boardsCard(tenant: Connection): UiNode {
        val boards = try {
            InmobiliariaMondayService.boards(tenant)
        } catch (failure: InmobiliariaMondayException) {
            return UiCard(listOf(UiText(failure.message.orEmpty(), tone = UiTone.Danger)), title = tr("Tableros de monday"))
        }
        if (boards.isEmpty()) return UiCard(listOf(UiText(tr("El token no tiene acceso a ningun tablero activo."), tone = UiTone.Warning)), title = tr("Tableros de monday"))
        val kinds = InmobiliariaMondayService.requireTarget(tenant).importKinds
        val kindOptions = kinds.map { "${it.label} [${it.id}]" }
        val kindLabels = kinds.map { "${tr(it.label)} [${it.id}]" }
        val boardOptions = boards.map { "${it.workspace} > ${it.name}${it.items?.let { n -> " ($n)" }.orEmpty()} [${it.id}]" }
        val propertyOption = kinds.firstOrNull { it.id == MondayMapping.KindProperty }?.let { "${it.label} [${it.id}]" }
        return UiCard(
            listOf(
                UiForm(
                    "mondayStart",
                    listOfNotNull(
                        UiField("board", tr("Tablero"), type = "dropdown", required = true, options = boardOptions),
                        UiField("kind", tr("Que contiene"), type = "select", required = true, options = kindOptions, optionLabels = kindLabels.takeIf { it != kindOptions }),
                        propertyOption?.let {
                            UiField("newListings", tr("Es un tablero de nuevos listados (las propiedades entran como Captacion)"), type = "boolean", visibleWhen = UiFieldCondition("kind", listOf(it)))
                        },
                    ),
                    mapOf("kind" to kindOptions.first()),
                    submit = UiButton(tr("Leer tablero"), request("start", formId = "mondayStart")),
                ),
            ),
            title = tr("Elegir tablero"),
        )
    }

    private fun historyCard(tenant: Connection): UiNode {
        val jobs = InmobiliariaMondayStore.recent(tenant)
        if (jobs.isEmpty()) return UiCard(listOf(UiText(tr("Aun no hay importaciones."), UiTextStyle.Caption)), title = tr("Importaciones"))
        return UiCard(
            jobs.map { job ->
                UiRow(
                    listOf(
                        UiText(tr("{0} - {1} - {2}", boardTitle(job), kindLabel(job.kind), stateLabel(job.state))),
                        UiButton(tr("Abrir"), openScreen(mapOf("job" to job.id.toString())), style = "secondary"),
                    ),
                )
            },
            title = tr("Importaciones"),
        )
    }

    private fun kindLabel(kind: String) = if (kind == MondayMapping.KindDeal) tr("Transacciones") else tr("Propiedades")

    private fun boardTitle(job: MondayJob) = job.boardName.ifEmpty { tr("Tablero {0}", job.boardId) }

    fun stateLabel(state: String) = when (state) {
        MondayJobState.Reading -> tr("leyendo")
        MondayJobState.Ready -> tr("lista para revisar")
        MondayJobState.Applying -> tr("aplicando")
        MondayJobState.Done -> tr("terminada")
        MondayJobState.Failed -> tr("fallida")
        else -> state
    }

    // ---- Importacion ----

    private fun jobScreen(tenant: Connection, id: UUID): List<UiNode> {
        val job = InmobiliariaMondayService.job(tenant, id)
        val params = mapOf("job" to id.toString())
        val back = UiButton(tr("Volver"), openScreen(), style = "secondary")
        val header = listOf<UiNode>(
            UiText(tr("{0} - {1}", boardTitle(job), kindLabel(job.kind)), UiTextStyle.Subtitle),
            UiText(tr("Estado: {0}. Lanzada por {1}.", stateLabel(job.state), job.createdByName.ifEmpty { tr("un usuario") }), UiTextStyle.Caption),
        )
        return when (job.state) {
            MondayJobState.Reading -> header + listOf(
                UiText(tr("Se esta leyendo el tablero de monday ({0} elementos hasta ahora). Un tablero grande tarda unos minutos; actualiza esta pantalla para seguir.", job.rowsRead)),
                UiRow(listOf(UiButton(tr("Actualizar"), openScreen(params), style = "secondary"), UiButton(tr("Cancelar"), request("cancel", params, confirm = tr("Se descarta la lectura. Continuar?")), style = "danger"))),
            )
            MondayJobState.Failed -> header + listOf(
                UiText(tr(job.error.ifEmpty { msg("No se pudo leer el tablero.") }), tone = UiTone.Danger),
                UiRow(listOf(UiButton(tr("Descartar"), request("discard", params), style = "secondary"), back)),
            )
            MondayJobState.Ready -> header + readyScreen(tenant, job, params)
            MondayJobState.Applying -> header + listOf(
                counts(job, InmobiliariaMondayStore.rowCounts(tenant, id)),
                UiText(tr("Se esta aplicando en segundo plano, de a {0} filas por lote.", MondayMapping.BatchSize)),
                UiRow(listOf(UiButton(tr("Actualizar"), openScreen(params), style = "secondary"), UiButton(tr("Cancelar"), request("cancel", params, confirm = tr("Se detiene la aplicacion; lo ya aplicado se conserva. Continuar?")), style = "danger"))),
            )
            else -> header + doneScreen(tenant, job, params, back)
        }
    }

    private fun counts(job: MondayJob, byState: Map<String, Int>): UiNode = UiRow(
        listOf(
            UiMetric(tr("Creadas"), job.created.toString()),
            UiMetric(tr("Actualizadas"), job.updated.toString()),
            UiMetric(tr("Omitidas"), job.skipped.toString()),
            UiMetric(tr("Con problema"), (byState[MondayRowState.Failed] ?: job.failed).toString()),
            UiMetric(tr("Pendientes"), (byState[MondayRowState.Pending] ?: 0).toString()),
        ),
    )

    private fun doneScreen(tenant: Connection, job: MondayJob, params: Map<String, String>, back: UiButton): List<UiNode> {
        val nodes = mutableListOf<UiNode>(counts(job, InmobiliariaMondayStore.rowCounts(tenant, job.id)))
        nodes += if (job.failed == 0) UiText(tr("La importacion termino sin problemas."), tone = UiTone.Success)
        else UiText(tr("La importacion termino con {0} fila(s) con problema. Corrige el dato en monday o en Inmobiliaria y vuelve a importar: lo ya importado se actualiza, no se duplica.", job.failed), tone = UiTone.Warning)
        job.problems.take(ShownProblems).forEach { nodes += UiText(it, UiTextStyle.Caption, tone = UiTone.Warning) }
        if (job.problems.size > ShownProblems) nodes += UiText(tr("Y {0} problema(s) mas.", job.problems.size - ShownProblems), UiTextStyle.Caption)
        nodes += UiRow(listOf(UiButton(tr("Descartar el historial"), request("discard", params), style = "secondary"), back))
        return nodes
    }

    private fun readyScreen(tenant: Connection, job: MondayJob, params: Map<String, String>): List<UiNode> {
        val kind = InmobiliariaMondayService.kind(tenant, job.kind)
        val missing = MondayMapping.missingRequired(kind, job.mapping)
        val nodes = mutableListOf<UiNode>(
            UiText(tr("Se leyeron {0} elementos. Revisa a que campo va cada columna y la vista previa; despues aplica la importacion.", job.rowsRead)),
            mappingForm(job, kind, params),
        )
        if (job.error.isNotEmpty()) nodes += UiText(tr(job.error), tone = UiTone.Warning)
        if (missing.isNotEmpty()) nodes += UiText(tr("Falta mapear: {0}.", missing.joinToString { tr(it) }), tone = UiTone.Danger)
        nodes += previewCard(tenant, job, kind)
        nodes += UiRow(
            listOf(
                UiButton(tr("Aplicar importacion"), request("apply", params, confirm = tr("Se importan {0} elementos a Inmobiliaria. Continuar?", job.rowsRead)), enabled = missing.isEmpty()),
                UiButton(tr("Cancelar"), request("cancel", params, confirm = tr("Se descarta la lectura. Continuar?")), style = "danger"),
            ),
        )
        return nodes
    }

    private fun fieldOption(field: com.ideasdeveloper.bms.modules.crm.CrmImportField) = "${field.label}${if (field.required) " *" else ""} [${field.key}]"

    private fun fieldOptionLabel(field: com.ideasdeveloper.bms.modules.crm.CrmImportField) = "${tr(field.label)}${if (field.required) " *" else ""} [${field.key}]"

    private fun mappingForm(job: MondayJob, kind: CrmImportKind, params: Map<String, String>): UiNode {
        val options = listOf(NoImport) + kind.fields.map(::fieldOption)
        val optionLabels = (listOf(tr(NoImport)) + kind.fields.map(::fieldOptionLabel)).takeIf { it != options }
        val byKey = kind.fields.associateBy { it.key }
        val fields = job.columns.mapIndexed { index, column ->
            UiField("c$index", column.title + if (column.type.isEmpty()) "" else " (${column.type})", type = "dropdown", options = options, optionLabels = optionLabels)
        }
        val values = job.columns.mapIndexed { index, column ->
            "c$index" to (job.mapping[column.title]?.let { byKey[it] }?.let(::fieldOption) ?: NoImport)
        }.toMap()
        return UiCard(
            listOf(
                UiForm(
                    "mondayMapping", fields, values,
                    submit = UiButton(tr("Guardar mapeo"), request("saveMapping", params, formId = "mondayMapping"), style = "secondary"),
                    minFieldWidth = 320,
                ),
            ),
            title = tr("Mapeo de columnas (* obligatorio)"),
        )
    }

    private fun previewCard(tenant: Connection, job: MondayJob, kind: CrmImportKind): UiNode {
        val mapped = kind.fields.filter { it.key in job.mapping.values }
        val rows = InmobiliariaMondayStore.rows(tenant, job.id, null, PreviewRows).map { row ->
            MondayMapping.values(row.data, job.mapping, kind, job.newListings)
        }
        return UiCard(
            listOf(
                if (mapped.isEmpty()) UiText(tr("Mapea al menos una columna para ver la vista previa."), UiTextStyle.Caption)
                else UiTable(mapped.map { UiTableColumn(it.key, tr(it.label)) }, rows, emptyText = tr("Sin filas.")),
            ),
            title = tr("Vista previa (primeras {0} filas)", PreviewRows),
        )
    }

    // ---- Acciones ----

    fun action(context: CrmScreens.Context, actor: CrmActor, op: String, params: Map<String, String>, values: Map<String, String>): CrmExtensionActionResult {
        val tenant = context.tenant
        if (!context.permissions.has(CrmScreens.Settings)) return result(UiTone.Warning, tr("No tienes permiso para importar desde monday."), params)
        val job = params["job"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        val keep = if (job != null) mapOf("job" to job.toString()) else emptyMap()
        return try {
            when (op) {
                "token" -> {
                    val typed = values["token"].orEmpty().trim().takeIf { it.isNotEmpty() && !CrmHost.isSecretPlaceholder(it) }
                    if (typed == null && InmobiliariaMondayService.token(tenant) == null) return result(UiTone.Danger, tr("Escribe el token de la API de monday."), emptyMap())
                    typed?.let { InmobiliariaMondayService.saveToken(tenant, it) }
                    result(UiTone.Success, tr("Token de monday guardado."), mapOf("boards" to "1"))
                }
                "forget" -> {
                    InmobiliariaMondayService.saveToken(tenant, null)
                    result(UiTone.Success, tr("Token de monday borrado."), emptyMap())
                }
                "start" -> start(tenant, actor, values)
                "saveMapping" -> {
                    val id = job ?: throw InmobiliariaMondayException(tr("Falta la importacion."))
                    val current = InmobiliariaMondayService.job(tenant, id)
                    InmobiliariaMondayService.saveMapping(tenant, id, mappingFrom(current, values))
                    result(UiTone.Success, tr("Mapeo guardado."), keep)
                }
                "apply" -> {
                    InmobiliariaMondayService.apply(tenant, actor, job ?: throw InmobiliariaMondayException(tr("Falta la importacion.")))
                    result(UiTone.Success, tr("Se esta aplicando la importacion en segundo plano."), keep)
                }
                "cancel" -> {
                    val id = job ?: throw InmobiliariaMondayException(tr("Falta la importacion."))
                    val text = InmobiliariaMondayService.cancel(tenant, id)
                    result(UiTone.Success, text, if (InmobiliariaMondayStore.find(tenant, id) == null) emptyMap() else keep)
                }
                "discard" -> {
                    InmobiliariaMondayService.discard(tenant, job ?: throw InmobiliariaMondayException(tr("Falta la importacion.")))
                    result(UiTone.Success, tr("Importacion descartada."), emptyMap())
                }
                else -> result(UiTone.Warning, tr("Esa accion no existe."), params - "op")
            }
        } catch (failure: InmobiliariaMondayException) {
            result(UiTone.Warning, failure.message.orEmpty(), params - "op")
        }
    }

    private fun result(tone: String, text: String, params: Map<String, String>) = CrmExtensionActionResult(Import, UiNotice(text, tone), params)

    private fun start(tenant: Connection, actor: CrmActor, values: Map<String, String>): CrmExtensionActionResult {
        val boardText = values["board"].orEmpty()
        val id = boardId.find(boardText)?.groupValues?.get(1) ?: throw InmobiliariaMondayException(tr("Elige un tablero."))
        val kind = optionKey.find(values["kind"].orEmpty())?.groupValues?.get(1) ?: throw InmobiliariaMondayException(tr("Elige que contiene el tablero."))
        val name = boardText.replace(boardId, "").trim().replace(Regex("\\s\\(\\d+\\)$"), "").substringAfter(" > ").take(300)
        val job = InmobiliariaMondayService.start(tenant, actor, id, name, kind, values["newListings"] == "true", emptyMap())
        return result(UiTone.Success, tr("Se esta leyendo el tablero de monday."), mapOf("job" to job.toString()))
    }

    /** Mapeo (columna -> campo) de los desplegables del formulario; cada desplegable lleva el indice de la columna. */
    fun mappingFrom(job: MondayJob, values: Map<String, String>): Map<String, String> {
        val mapping = linkedMapOf<String, String>()
        job.columns.forEachIndexed { index, column ->
            val key = optionKey.find(values["c$index"].orEmpty())?.groupValues?.get(1) ?: return@forEachIndexed
            mapping[column.title] = key
        }
        return mapping
    }
}
