package com.ideasdeveloper.bms.modules.crm

import com.ideasdeveloper.bms.server.i18n.I18n
import com.ideasdeveloper.bms.server.i18n.localizedFor
import com.ideasdeveloper.bms.server.i18n.msg
import com.ideasdeveloper.bms.server.i18n.tr
import com.ideasdeveloper.bms.server.i18n.trIn
import com.ideasdeveloper.bms.ui.UiButton
import com.ideasdeveloper.bms.ui.UiCard
import com.ideasdeveloper.bms.ui.UiField
import com.ideasdeveloper.bms.ui.UiForm
import com.ideasdeveloper.bms.ui.UiList
import com.ideasdeveloper.bms.ui.UiListItem
import com.ideasdeveloper.bms.ui.UiMetric
import com.ideasdeveloper.bms.ui.UiGrid
import com.ideasdeveloper.bms.ui.UiNavigate
import com.ideasdeveloper.bms.ui.UiNode
import com.ideasdeveloper.bms.ui.UiRequest
import com.ideasdeveloper.bms.ui.UiRow
import com.ideasdeveloper.bms.ui.UiTable
import com.ideasdeveloper.bms.ui.UiTableColumn
import com.ideasdeveloper.bms.ui.UiText
import com.ideasdeveloper.bms.ui.UiTextStyle
import com.ideasdeveloper.bms.ui.UiTone
import java.time.ZoneId
import java.util.UUID

/** Imports (sources, mapping, preview, progress) and bulk actions on leads. */
object CrmImportScreens {
    private const val UiRoute = CrmScreens.UiRoute
    private val Default = msg("Primera etapa abierta")
    private val Keep = msg("Sin cambios")
    private val statusLabels = mapOf(
        "mapping" to msg("Por mapear"), "running" to msg("Importando"), "done" to msg("Terminada"), "canceled" to msg("Cancelada"), "failed" to msg("Fallo"),
    )
    private val sourceLabels = mapOf("file" to msg("Archivo"))

    // The texts an extension's import source defines are in the extension's catalog, not the host's (I18N.md §2).
    private fun sourceLabel(job: CrmImportJob): String? {
        val source = CrmImportSources.of(job.source) ?: return sourceLabels[job.source]?.let { tr(it) }
        return trIn(I18n.namespaceOf(source.javaClass), source.label)
    }

    // El estado "reading" lo pone una fuente externa de lectura en segundo plano: ella lo nombra.
    private fun statusLabel(job: CrmImportJob): String? {
        if (job.status != "reading") return statusLabels[job.status]?.let { tr(it) }
        val source = CrmImportSources.of(job.source) ?: return tr("Leyendo")
        return trIn(I18n.namespaceOf(source.javaClass), source.readingLabel)
    }

    // ---- Sources and jobs ----

    fun imports(context: CrmScreens.Context, params: Map<String, String>): List<UiNode> {
        val jobs = CrmImport.jobs(context.tenant).map { job ->
            UiListItem(
                title = job.name,
                subtitle = tr("{0} - {1} - {2}/{3} filas", sourceLabel(job), statusLabel(job), job.processed, job.total) +
                    if (job.processed > 0) tr(" ({0} nuevos, {1} actualizados, {2} por revisar, {3} omitidos)", job.created, job.updated, job.conflicts, job.skipped) else "",
                trailing = job.createdAt.atZone(ZoneId.systemDefault()).toLocalDate().toString(),
                action = UiNavigate("importjob", mapOf("job" to job.id.toString())),
            )
        }
        // Las fuentes externas ponen aqui sus tarjetas (o el aviso para instalar su extension).
        val sourceCards: List<UiNode> = CrmImportSources.all.flatMap { localizedFor(it, it.importCards(context)) }
        return listOfNotNull(
            UiText(tr("Cada importacion pasa por mapeo de columnas, vista previa y luego se aplica por lotes. Se busca a cada persona por correo o telefono; si el correo y el telefono son de clientes distintos la fila queda por revisar. Importar de nuevo actualiza los leads ya importados.")),
            UiGrid(
                listOf(
                    *sourceCards.toTypedArray(),
                    UiCard(
                        listOf(
                            UiForm(
                                "importUrl",
                                listOf(UiField("url", tr("Direccion https del CSV o XLSX"), type = "url", required = true, help = tr("Por ejemplo Google Sheets > Publicar en la web > CSV, o un enlace de descarga directa."))),
                                submit = UiButton(tr("Leer archivo"), UiRequest(UiRoute + "/import", params = mapOf("op" to "url"), formId = "importUrl")),
                            ),
                        ),
                        title = tr("Archivo por enlace"),
                    ),
                    UiCard(
                        listOf(
                            UiForm(
                                "importText",
                                listOf(UiField("name", tr("Nombre de la importacion")), UiField("csv", tr("Contenido CSV"), type = "textarea", required = true, help = tr("Copia y pega las filas con el encabezado (coma, punto y coma o tabulador)."))),
                                submit = UiButton(tr("Leer texto"), UiRequest(UiRoute + "/import", params = mapOf("op" to "text"), formId = "importText")),
                            ),
                        ),
                        title = tr("Pegar CSV"),
                    ),
                ),
                minItemWidth = 300,
            ),
            *CrmImportSources.all.mapNotNull { source -> source.importExtras(context, params)?.let { localizedFor(source, it) } }.toTypedArray(),
            UiCard(listOf(UiList(jobs, emptyText = tr("Sin importaciones."))), title = tr("Importaciones")),
        )
    }

    fun job(context: CrmScreens.Context, params: Map<String, String>): List<UiNode> {
        val job = params["job"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }?.let { CrmImport.job(context.tenant, it) }
            ?: return listOf(UiText(tr("Esa importacion ya no existe."), tone = UiTone.Warning))
        val id = mapOf("job" to job.id.toString())
        val header = listOf(
            UiRow(listOf(UiButton(tr("Volver"), UiNavigate("import"), style = "secondary"), UiText("${job.name} (${sourceLabel(job)}) - ${statusLabel(job)}", UiTextStyle.Subtitle))),
            UiGrid(
                listOf(
                    UiCard(listOf(UiMetric(tr("Filas"), job.total.toString(), caption = tr("{0} procesadas", job.processed)))),
                    UiCard(listOf(UiMetric(tr("Nuevos"), job.created.toString()))),
                    UiCard(listOf(UiMetric(tr("Actualizados"), job.updated.toString()))),
                    UiCard(listOf(UiMetric(tr("Por revisar"), job.conflicts.toString(), captionTone = if (job.conflicts > 0) UiTone.Danger else UiTone.Neutral))),
                    UiCard(listOf(UiMetric(tr("Omitidos / error"), "${job.skipped} / ${job.errors}"))),
                ),
                minItemWidth = 160,
            ),
        )
        // Los estados de lectura en segundo plano (reading, failed) los pinta la fuente externa del job.
        CrmImportSources.of(job.source)?.let { source -> source.statusScreen(job, header.take(1))?.let { return localizedFor(source, it) } }
        if (job.status == "failed") return header.take(1) + listOf(UiText(job.error.ifEmpty { tr("No se pudo leer el origen.") }, tone = UiTone.Danger))
        if (job.status != "mapping") {
            val problems = (CrmImport.rows(context.tenant, job, "conflict") + CrmImport.rows(context.tenant, job, "error")).take(50).map { (rowNo, row, message) ->
                UiListItem(tr("Fila {0}: {1}", rowNo, row.values.firstOrNull().orEmpty()), message)
            }
            return header + listOfNotNull(
                UiRow(listOf(UiButton(tr("Cancelar"), UiRequest(UiRoute + "/importjob", params = id + ("op" to "cancel"), confirm = tr("Se detiene la importacion; los leads ya importados quedan. Continuar?")), style = "danger"))).takeIf { job.status == "running" },
                UiText(tr("Se aplican 300 filas por minuto. Vuelve a abrir esta pantalla para ver el avance."), UiTextStyle.Caption).takeIf { job.status == "running" },
                UiCard(listOf(UiList(problems, emptyText = tr("Sin filas por revisar."))), title = tr("Filas por revisar o con error")),
            )
        }
        val fieldOptions = CrmImportTargets.options(context.fields, CrmImportTargets.customerFields(context.tenant))
        // The values are the Spanish labels (read back by [mapping]); the app shows these ones.
        val shownOptions = CrmImportTargets.options(context.fields, CrmImportTargets.customerFields(context.tenant), shown = true).values.toList()
        val columnFields = job.columns.map { column ->
            val type = job.columnTypes[column]?.takeIf { it.isNotEmpty() }?.let { CrmImportSources.of(job.source)?.columnLabel(it) }.orEmpty()
            UiField("col:$column", column + type, type = "dropdown", options = fieldOptions.values.toList(), optionLabels = shownOptions, section = tr("Columnas"))
        }
        // Columns proposed as new fields: the user confirms them by saving the mapping.
        val toCreate = job.columns.filter { job.mapping.columns[it] in setOf(CrmImportTargets.NewCrm, CrmImportTargets.NewCustomer) }
        val columnValues = job.columns.associate { column -> "col:$column" to (fieldOptions[job.mapping.columns[column].orEmpty()] ?: fieldOptions.getValue("")) }
        fun valueFields(field: String, prefix: String, section: String, options: List<String>, mapped: Map<String, String>) =
            CrmImport.distinctValues(context.tenant, job, field).map { value -> UiField("$prefix:$value", value, type = "dropdown", options = options, optionLabels = context.optionLabels(options), section = section) } to
                CrmImport.distinctValues(context.tenant, job, field).associate { value ->
                    "$prefix:$value" to (mapped[value] ?: options.firstOrNull { it.equals(value, ignoreCase = true) } ?: options.first())
                }
        val (stageFields, stageValues) = valueFields("stage", "stage", tr("Etapas"), listOf(Default) + context.stages.map { it.name }, job.mapping.stages)
        val (ownerFields, ownerValues) = valueFields("owner", "owner", tr("Personas asignadas (cada persona de la columna)"), listOf(CrmForms.Unassigned) + context.users.map { it.name }, job.mapping.owners)
        val (interestFields, interestValues) = if (context.interestsEnabled) {
            valueFields("interest", "interest", tr("Intereses"), listOf(CrmForms.NoInterest) + context.interests.map { it.name }, job.mapping.interests)
        } else emptyList<UiField>() to emptyMap()
        val presets = CrmImport.presets(context.tenant)
        val sample = CrmImport.rows(context.tenant, job, limit = 8)
        val sampleColumns = job.columns.take(8)
        return header + listOfNotNull(
            presets.takeIf { it.isNotEmpty() }?.let {
                UiCard(
                    listOf(
                        UiForm(
                            "preset", listOf(UiField("preset", tr("Usar un mapeo guardado"), type = "dropdown", options = it.keys.toList())),
                            submit = UiButton(tr("Aplicar"), UiRequest(UiRoute + "/importjob", params = id + ("op" to "preset"), formId = "preset"), style = "secondary"),
                        ),
                    ),
                )
            },
            UiCard(
                listOf(
                    UiText(
                        if (context.interestsEnabled) tr("Elige a que campo del CRM va cada columna y como se traducen sus valores. Las etapas e intereses que no elijas se buscan por nombre.")
                        else tr("Elige a que campo del CRM va cada columna y como se traducen sus valores. Las etapas e intereses que no elijas se buscan por nombre. (Los intereses estan desactivados: se ignoran.)"),
                    ),
                    UiText(
                        tr("Datos de la persona (documento de identidad, fecha de nacimiento, direccion...) van como campo del cliente: los ven Clientes y los demas modulos. Lo propio del negocio va como campo del CRM."),
                        UiTextStyle.Caption,
                    ),
                    UiForm(
                        "mapping", columnFields + stageFields + ownerFields + interestFields + UiField("presetName", tr("Guardar este mapeo como (opcional)"), section = tr("Mapeo guardado")),
                        columnValues + stageValues + ownerValues + interestValues,
                        submit = UiButton(tr("Guardar mapeo y ver vista previa"), UiRequest(UiRoute + "/importjob", params = id + ("op" to "map"), formId = "mapping")),
                        minFieldWidth = 240,
                    ),
                ),
                title = tr("Mapeo"),
            ),
            toCreate.takeIf { it.isNotEmpty() }?.let { columns ->
                UiCard(
                    listOf(
                        UiText(tr("Estas columnas no tienen un campo en el CRM. Al guardar el mapeo se crean los campos marcados con \"Crear campo...\"; elige \"No importar\" para las que no quieras.")),
                        UiList(
                            columns.map { column ->
                                val target = if (job.mapping.columns[column] == CrmImportTargets.NewCustomer) tr("campo del cliente") else tr("campo del CRM")
                                val (type, multiple) = CrmImportSources.fieldType(job.source, job.columnTypes[column])
                                UiListItem(column, tr("Se crearia como {0} de tipo {1}{2}", target, type.label, if (multiple) tr(" (varias opciones)") else ""))
                            },
                        ),
                    ),
                    title = tr("Campos que faltan"),
                )
            },
            *(CrmImportSources.of(job.source)?.let { source -> localizedFor(source, source.mappingNotes(job)) }.orEmpty()).toTypedArray(),
            UiCard(
                listOf(UiTable(sampleColumns.map { UiTableColumn(it, it) }, sample.map { (_, row, _) -> sampleColumns.associateWith { row[it].orEmpty().take(60) } })),
                title = tr("Primeras filas"),
            ),
            UiRow(
                listOf(
                    UiButton(tr("Importar {0} filas", job.total), UiRequest(UiRoute + "/importjob", params = id + ("op" to "start"), confirm = tr("Se aplica el mapeo guardado a todas las filas. Continuar?"))),
                    UiButton(tr("Descartar"), UiRequest(UiRoute + "/importjob", params = id + ("op" to "cancel")), style = "secondary"),
                ),
            ),
        )
    }

    /** Reads the mapping form back into a mapping. */
    fun mapping(context: CrmScreens.Context, job: CrmImportJob, values: Map<String, String>): CrmImportMapping {
        val byLabel = CrmImportTargets.options(context.fields, CrmImportTargets.customerFields(context.tenant)).entries.associate { it.value to it.key }
        fun section(prefix: String, ignore: String) = values.filterKeys { it.startsWith("$prefix:") }
            .mapKeys { it.key.removePrefix("$prefix:") }
            .filterValues { it.isNotBlank() && it != ignore && it != Keep }
        return CrmImportMapping(
            columns = job.columns.associateWith { column -> byLabel[values["col:$column"]].orEmpty() }.filterValues { it.isNotEmpty() },
            stages = section("stage", Default),
            owners = section("owner", CrmForms.Unassigned),
            interests = section("interest", CrmForms.NoInterest),
        )
    }

    // ---- Bulk actions ----

    val ActionStage = msg("Mover a etapa")
    val ActionAddOwner = msg("Anadir asignado")
    val ActionRemoveOwner = msg("Quitar asignado")
    val ActionClearOwners = msg("Sin asignar")
    val ActionArchive = msg("Archivar")
    val ActionRestore = msg("Restaurar")
    val ActionDelete = msg("Eliminar")

    /**
     * Bulk actions of the list view of "Leads": one checkbox per lead of the current page and the action to
     * apply (the renderer has no selection mode, so this is a form). Null without crm.leads.manage.
     * [keep] are the filters of the view, sent back so the list stays as it was.
     */
    fun bulkCard(context: CrmScreens.Context, leads: List<CrmLead>, page: Int, total: Int, keep: Map<String, String>): UiNode? {
        if (!context.permissions.has(CrmScreens.Manage) || leads.isEmpty()) return null
        val actions = listOfNotNull(
            ActionStage, ActionAddOwner.takeIf { context.canAssign }, ActionRemoveOwner.takeIf { context.canAssign }, ActionClearOwners.takeIf { context.canAssign },
            ActionArchive, ActionRestore,
            ActionDelete.takeIf { context.permissions.has(CrmScreens.Delete) },
        )
        val selection = leads.map { lead ->
            UiField("lead:${lead.id}", "${lead.name} - ${context.stage(lead.stageId)?.name?.let { context.stageLabel(it) }.orEmpty()} - ${CrmAssignees.label(context.names, lead.ownerUserIds)}", type = "boolean", section = tr("Leads (pagina {0}, {1} en total)", page, total))
        }
        val fields = listOf(
            UiField("all", tr("Seleccionar todos los visibles ({0})", leads.size), type = "boolean", section = tr("Seleccion")),
        ) + selection + listOf(
            UiField("action", tr("Accion"), type = "dropdown", required = true, options = actions, section = tr("Accion")),
            UiField("stage", tr("Etapa"), type = "dropdown", options = context.stages.map { it.name }, optionLabels = context.optionLabels(context.stages.map { it.name }), section = tr("Accion"), visibleWhen = com.ideasdeveloper.bms.ui.UiFieldCondition("action", listOf(ActionStage))),
            UiField("owner", tr("Usuario"), type = "dropdown", options = (context.users + CrmBulkActions.legacyOf(context, leads)).map { it.name }, section = tr("Accion"), visibleWhen = com.ideasdeveloper.bms.ui.UiFieldCondition("action", listOf(ActionAddOwner, ActionRemoveOwner))),
        )
        return UiCard(
            listOf(
                UiText(tr("Marca los leads de esta pagina y elige la accion. Los filtros de arriba se mantienen."), UiTextStyle.Caption),
                UiForm(
                    "bulk", fields, mapOf("action" to actions.first()),
                    submit = UiButton(
                        tr("Aplicar a los seleccionados"),
                        UiRequest(UiRoute + "/leads", params = keep + ("op" to "bulk") + ("page" to page.toString()), formId = "bulk", confirm = tr("Se aplica la accion a los leads marcados. Continuar?")),
                    ),
                    minFieldWidth = 320,
                ),
            ),
            title = tr("Acciones en lote"),
            collapsible = true,
            expanded = false,
        )
    }
}
