package com.ideasdeveloper.bms.modules.propietarios

import com.ideasdeveloper.bms.server.i18n.tr
import com.ideasdeveloper.bms.ui.UiBadge
import com.ideasdeveloper.bms.ui.UiButton
import com.ideasdeveloper.bms.ui.UiCard
import com.ideasdeveloper.bms.ui.UiField
import com.ideasdeveloper.bms.ui.UiFieldValues
import com.ideasdeveloper.bms.ui.UiFileRef
import com.ideasdeveloper.bms.ui.UiFiles
import com.ideasdeveloper.bms.ui.UiForm
import com.ideasdeveloper.bms.ui.UiGrid
import com.ideasdeveloper.bms.ui.UiList
import com.ideasdeveloper.bms.ui.UiListItem
import com.ideasdeveloper.bms.ui.UiMetric
import com.ideasdeveloper.bms.ui.UiNavItem
import com.ideasdeveloper.bms.ui.UiNavigate
import com.ideasdeveloper.bms.ui.UiNavigation
import com.ideasdeveloper.bms.ui.UiNode
import com.ideasdeveloper.bms.ui.UiNotice
import com.ideasdeveloper.bms.ui.UiOpenModule
import com.ideasdeveloper.bms.ui.UiOpenUrl
import com.ideasdeveloper.bms.ui.UiRequest
import com.ideasdeveloper.bms.ui.UiRow
import com.ideasdeveloper.bms.ui.UiScreen
import com.ideasdeveloper.bms.ui.UiSuggest
import com.ideasdeveloper.bms.ui.UiText
import com.ideasdeveloper.bms.ui.UiTextStyle
import com.ideasdeveloper.bms.ui.UiTimeline
import com.ideasdeveloper.bms.ui.UiTimelineEntry
import com.ideasdeveloper.bms.ui.UiTone
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Pantallas nativas del modulo. Cada constructor es puro: recibe los datos ya cargados y devuelve un [UiScreen];
 * PropietariosRoutes carga los datos y PropietariosActions lee lo que envian los formularios.
 */
object PropietariosScreens {
    const val Route: String = PropietariosModule.UiRoute
    const val CustomerSuggestRoute = "$Route/suggest/customers"
    const val ListLimit = 200

    /** Quien mira y que puede hacer. */
    data class Context(
        val canManage: Boolean,
        val canAdmin: Boolean,
        // El usuario tiene propietarios.import y hay una fuente de importacion activa: aparece "Importar".
        val importEnabled: Boolean,
        // Puede ver clientes (clientes.view): sin ello no se eligen titulares ni se abre la ficha del cliente.
        val canSeeCustomers: Boolean,
        val today: LocalDate,
    )

    data class DevelopmentData(val development: Development, val totals: Map<LotStatus, StatusTotals>, val lots: List<Lot>)

    data class LotDetail(
        val lot: Lot,
        val ownerships: List<Ownership>,
        val history: List<LotHistoryEntry>,
        val attachments: List<LotAttachment>,
    )

    // ---- formato ----

    fun area(value: BigDecimal?): String = value?.let { if (it.signum() == 0) "0" else it.stripTrailingZeros().toPlainString() }.orEmpty()

    fun statusTone(status: LotStatus): String = when (status) {
        LotStatus.Disponible -> UiTone.Success
        LotStatus.Reservado -> UiTone.Warning
        LotStatus.Vendido -> UiTone.Neutral
        LotStatus.EnConstruccion -> UiTone.Info
        LotStatus.Construido -> UiTone.Info
    }

    fun historyLabel(kind: String): String = when (kind) {
        "alta" -> tr("Alta de titular")
        "traspaso" -> tr("Cambio de titularidad")
        "baja" -> tr("Baja de titular")
        "importacion" -> tr("Importación")
        else -> tr("Edición")
    }

    private fun historyTone(kind: String): String = when (kind) {
        "alta" -> UiTone.Success
        "baja" -> UiTone.Warning
        "traspaso" -> UiTone.Info
        else -> UiTone.Neutral
    }

    private fun hint(text: String, tone: String? = null) = UiText(text, UiTextStyle.Caption, tone)

    private fun metric(label: String, value: String, caption: String = "") = UiCard(listOf(UiMetric(label, value, caption)))

    // ---- navegacion ----

    fun navigation(context: Context, selected: String): UiNavigation = UiNavigation(
        title = PropietariosModule.displayName,
        items = listOfNotNull(
            UiNavItem("desarrollos", tr("Desarrollos"), UiNavigate("desarrollos")),
            UiNavItem("propietarios", tr("Propietarios"), UiNavigate("propietarios")),
            UiNavItem("buscar", tr("Buscar"), UiNavigate("buscar")),
            UiNavItem("importar", tr("Importar"), UiNavigate("importar")).takeIf { context.importEnabled },
        ),
        selected = selected,
    )

    private fun screen(context: Context, title: String, selected: String, notice: UiNotice?, body: List<UiNode>) =
        UiScreen(title = title, navigation = navigation(context, selected), notice = notice, body = body)

    // ---- piezas comunes ----

    private fun lotItem(lot: Lot, withDevelopment: Boolean): UiListItem = UiListItem(
        title = tr("Lote {0}", lot.lotNumber) + lot.propertyName.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty(),
        subtitle = listOfNotNull(
            lot.developmentName.takeIf { withDevelopment },
            lot.registerNumber.takeIf { it.isNotBlank() }?.let { tr("Registro {0}", it) },
            lot.propertyType.takeIf { it.isNotBlank() },
        ).joinToString(" · "),
        trailing = lot.areaM2?.let { "${area(it)} m²" }.orEmpty(),
        badges = listOf(UiBadge(tr(lot.status.label), statusTone(lot.status))),
        action = UiNavigate("lote", mapOf("id" to lot.id.toString())),
    )

    private fun truncatedNote(count: Int): List<UiNode> =
        if (count > ListLimit) listOf(hint(tr("Se muestran los primeros {0} resultados: afina la búsqueda para ver el resto.", ListLimit), UiTone.Warning)) else emptyList()

    private fun filesField(readOnly: Boolean) = UiField(
        "files", "Adjuntos (escrituras, planos)", type = "files", readOnly = readOnly,
        files = UiFiles(upload = "$Route/files", download = "$Route/files"),
    )

    private fun fileRefs(attachments: List<LotAttachment>) = attachments.map { UiFileRef(it.id.toString(), it.name, it.path, it.size) }

    private fun mapButton(url: String?): List<UiNode> = if (url == null) emptyList() else listOf(UiButton(tr("Ver en mapa"), UiOpenUrl(url), style = "secondary"))

    // ---- Desarrollos ----

    private fun developmentFields() = listOf(
        UiField("name", tr("Nombre"), required = true),
        UiField("location", tr("Ubicación"), placeholder = tr("Ciudad, zona o dirección")),
        UiField("map_url", tr("Enlace del mapa"), type = "url", help = tr("Opcional. Debe empezar por https://. Si lo dejas vacío se usan las coordenadas.")),
        UiField("latitude", tr("Latitud"), help = tr("Opcional, por ejemplo 9.9281.")),
        UiField("longitude", tr("Longitud"), help = tr("Opcional, por ejemplo -84.0907.")),
        UiField("notes", tr("Notas"), type = "textarea"),
    )

    private fun developmentValues(development: Development?) = mapOf(
        "name" to development?.name.orEmpty(),
        "location" to development?.location.orEmpty(),
        "map_url" to development?.mapUrl.orEmpty(),
        "latitude" to development?.latitude?.stripTrailingZeros()?.toPlainString().orEmpty(),
        "longitude" to development?.longitude?.stripTrailingZeros()?.toPlainString().orEmpty(),
        "notes" to development?.notes.orEmpty(),
    )

    fun developments(context: Context, list: List<DevelopmentSummary>, notice: UiNotice? = null, submitted: Map<String, String>? = null): UiScreen {
        val totalArea = list.fold(BigDecimal.ZERO) { sum, item -> sum + item.totalAreaM2 }
        val body = buildList<UiNode> {
            add(
                UiGrid(
                    listOf(
                        metric(tr("Desarrollos"), list.size.toString()),
                        metric(tr("Lotes"), list.sumOf { it.lots }.toString()),
                        metric(tr("Área total"), "${area(totalArea)} m²"),
                    ),
                ),
            )
            add(
                UiList(
                    items = list.map { item ->
                        UiListItem(
                            title = item.development.name,
                            subtitle = listOf(item.development.location.takeIf { it.isNotBlank() }, "${item.lots} lotes", "${area(item.totalAreaM2)} m²", "${item.owners} titulares")
                                .filterNotNull().joinToString(" · "),
                            action = UiNavigate("desarrollo", mapOf("id" to item.development.id.toString())),
                        )
                    },
                    emptyText = tr("Aún no hay desarrollos."),
                ),
            )
            if (context.canAdmin) {
                add(
                    UiCard(
                        listOf(
                            UiForm(
                                id = "development",
                                fields = developmentFields(),
                                values = developmentValues(null) + submitted.orEmpty(),
                                submit = UiButton(tr("Crear desarrollo"), UiRequest("$Route/desarrollos", params = mapOf("op" to "dev-save"), formId = "development")),
                            ),
                        ),
                        title = tr("Nuevo desarrollo"),
                        collapsible = true,
                        expanded = list.isEmpty() || submitted != null,
                    ),
                )
            }
        }
        return screen(context, tr("Desarrollos"), "desarrollos", notice, body)
    }

    fun development(context: Context, data: DevelopmentData, params: Map<String, String>, notice: UiNotice? = null, submitted: Map<String, String>? = null): UiScreen {
        val development = data.development
        val id = development.id.toString()
        val body = buildList<UiNode> {
            val details = listOfNotNull(development.location.takeIf { it.isNotBlank() }, development.notes.takeIf { it.isNotBlank() })
            details.forEach { add(hint(it)) }
            val buttons = buildList<UiNode> {
                if (context.canManage) add(UiButton(tr("Nuevo lote"), UiNavigate("lote", mapOf("development" to id))))
                addAll(mapButton(PropietariosRules.mapLink(development.mapUrl, development.latitude, development.longitude)))
                add(UiButton(tr("Todos los desarrollos"), UiNavigate("desarrollos"), style = "secondary"))
            }
            add(UiRow(buttons))
            add(
                UiGrid(
                    listOf(metric(tr("Lotes"), data.totals.values.sumOf { it.lots }.toString(), "${area(data.totals.values.fold(BigDecimal.ZERO) { sum, item -> sum + item.areaM2 })} m²")) +
                        LotStatus.entries.map { status ->
                            val totals = data.totals[status] ?: StatusTotals(0, BigDecimal.ZERO)
                            metric(tr(status.label), totals.lots.toString(), "${area(totals.areaM2)} m²")
                        },
                    minItemWidth = 150,
                ),
            )
            val filterFields = listOf(
                UiField("q", tr("Buscar"), placeholder = tr("Número de lote, registro o propiedad")),
                UiField("status", tr("Estado"), type = "dropdown", options = listOf(PropietariosActions.AllOption) + LotStatus.entries.map { it.label }),
            )
            add(
                UiForm(
                    "filters", filterFields,
                    values = mapOf("q" to params["q"].orEmpty(), "status" to (params["status"]?.takeIf { it.isNotBlank() } ?: PropietariosActions.AllOption)),
                    submit = UiButton(tr("Filtrar"), UiNavigate("desarrollo", mapOf("id" to id), formId = "filters"), style = "secondary"),
                    minFieldWidth = 220,
                ),
            )
            addAll(truncatedNote(data.lots.size))
            add(UiList(data.lots.take(ListLimit).map { lotItem(it, withDevelopment = false) }, emptyText = tr("No hay lotes con esos filtros.")))
            if (context.canAdmin) {
                add(
                    UiCard(
                        listOf(
                            UiForm(
                                id = "development",
                                fields = developmentFields(),
                                values = developmentValues(development) + submitted.orEmpty(),
                                submit = UiButton(tr("Guardar"), UiRequest("$Route/desarrollo", params = mapOf("op" to "dev-save", "id" to id), formId = "development"), style = "secondary"),
                            ),
                            UiButton(
                                tr("Archivar desarrollo"), UiRequest("$Route/desarrollo", params = mapOf("op" to "dev-archive", "id" to id), confirm = tr("Se archivará el desarrollo y todos sus lotes. ¿Continuar?")),
                                style = "danger",
                            ),
                        ),
                        title = tr("Editar desarrollo"),
                        collapsible = true,
                        expanded = submitted != null,
                    ),
                )
            }
        }
        return screen(context, development.name, "desarrollos", notice, body)
    }

    // ---- Lote ----

    private fun lotFields(readOnly: Boolean) = listOf(
        UiField("lot_number", tr("Número de lote"), required = true, readOnly = readOnly),
        UiField("register_number", tr("Número de registro"), readOnly = readOnly),
        UiField("property_name", tr("Propiedad"), readOnly = readOnly),
        UiField("property_type", tr("Tipo de propiedad"), readOnly = readOnly),
        UiField("status", tr("Estado"), type = "dropdown", options = LotStatus.entries.map { it.label }, readOnly = readOnly),
        UiField("area_m2", "Área (m²)", readOnly = readOnly),
        UiField("map_url", tr("Enlace del mapa"), type = "url", readOnly = readOnly, help = tr("Opcional. Debe empezar por https://.")),
        UiField("latitude", tr("Latitud"), readOnly = readOnly),
        UiField("longitude", tr("Longitud"), readOnly = readOnly),
        UiField("notes", tr("Notas"), type = "textarea", readOnly = readOnly),
    )

    private fun lotValues(lot: Lot?) = mapOf(
        "lot_number" to lot?.lotNumber.orEmpty(),
        "register_number" to lot?.registerNumber.orEmpty(),
        "property_name" to lot?.propertyName.orEmpty(),
        "property_type" to lot?.propertyType.orEmpty(),
        "status" to (lot?.status ?: LotStatus.Disponible).label,
        "area_m2" to area(lot?.areaM2),
        "map_url" to lot?.mapUrl.orEmpty(),
        "latitude" to lot?.latitude?.stripTrailingZeros()?.toPlainString().orEmpty(),
        "longitude" to lot?.longitude?.stripTrailingZeros()?.toPlainString().orEmpty(),
        "notes" to lot?.notes.orEmpty(),
    )

    fun newLot(context: Context, development: Development, notice: UiNotice? = null, submitted: Map<String, String>? = null): UiScreen {
        val form = UiForm(
            id = "lot",
            fields = lotFields(readOnly = false),
            values = lotValues(null) + submitted.orEmpty(),
            submit = UiButton(tr("Crear lote"), UiRequest("$Route/lote", params = mapOf("op" to "save", "development" to development.id.toString()), formId = "lot")),
        )
        return screen(
            context, tr("Nuevo lote"), "desarrollos", notice,
            listOf(hint(tr("Desarrollo: {0}", development.name)), UiCard(listOf(form), title = tr("Datos del lote"))),
        )
    }

    private fun ownerLine(ownership: Ownership): String = listOfNotNull(
        ownership.sharePercent?.let { "${it.stripTrailingZeros().toPlainString()} %" },
        ownership.startsOn?.let { "desde $it" },
        ownership.endsOn?.let { "hasta $it" },
    ).joinToString(" · ")

    fun lot(context: Context, detail: LotDetail, notice: UiNotice? = null, submitted: Map<String, String>? = null): UiScreen {
        val lot = detail.lot
        val id = lot.id.toString()
        val canEdit = context.canManage && !lot.archived
        fun request(op: String, formId: String? = null, confirm: String? = null, extra: Map<String, String> = emptyMap()) =
            UiRequest("$Route/lote", params = mapOf("op" to op, "id" to id) + extra, confirm = confirm, formId = formId)

        val current = detail.ownerships.filter { it.current }
        val past = detail.ownerships.filter { !it.current }
        val body = buildList<UiNode> {
            add(hint("${lot.developmentName}${if (lot.archived) " · Lote archivado" else ""}"))
            val buttons = buildList<UiNode> {
                add(UiButton(tr("Ver el desarrollo"), UiNavigate("desarrollo", mapOf("id" to lot.developmentId.toString())), style = "secondary"))
                addAll(mapButton(PropietariosRules.mapLink(lot.mapUrl, lot.latitude, lot.longitude)))
                if (canEdit) add(UiButton(tr("Cambiar titularidad"), UiNavigate("traspaso", mapOf("lot" to id))))
            }
            add(UiRow(buttons))

            add(
                UiCard(
                    listOf<UiNode>(
                        UiList(
                            current.map { ownership ->
                                UiListItem(
                                    title = ownership.customerName,
                                    subtitle = listOf(ownership.customerEmail.takeIf { context.canSeeCustomers && it.isNotBlank() },ownerLine(ownership).takeIf { it.isNotBlank() }).filterNotNull().joinToString(" · "),
                                    action = if (canEdit) UiNavigate("titularidad", mapOf("id" to ownership.id.toString())) else UiNavigate("propietario", mapOf("id" to ownership.customerId.toString())),
                                )
                            },
                            emptyText = tr("Este lote no tiene titulares vigentes."),
                        ),
                    ) + if (past.isEmpty()) emptyList<UiNode>() else listOf<UiNode>(
                        hint(tr("Titulares anteriores")),
                        UiList(
                            past.map { ownership ->
                                UiListItem(
                                    title = ownership.customerName,
                                    subtitle = ownerLine(ownership),
                                    action = UiNavigate("propietario", mapOf("id" to ownership.customerId.toString())),
                                )
                            },
                        ),
                    ),
                    title = tr("Titulares vigentes"),
                ),
            )

            if (canEdit && context.canSeeCustomers) {
                add(
                    UiCard(
                        listOf(
                            hint(tr("Para quitar a un titular o pasar el lote a otra persona usa Cambiar titularidad.")),
                            UiForm(
                                id = "co-owner",
                                fields = listOf(
                                    UiField("owner", tr("Cliente"), required = true, placeholder = tr("Escribe para buscar"), suggest = UiSuggest(CustomerSuggestRoute), help = tr("Elige un cliente de la lista de sugerencias.")),
                                    UiField("share", tr("Porcentaje"), help = tr("Opcional, de 0 a 100.")),
                                ),
                                values = submitted.orEmpty().filterKeys { it in setOf("owner", "owner_id", "share") },
                                submit = UiButton(tr("Añadir cotitular"), request("add-owner", formId = "co-owner"), style = "secondary"),
                            ),
                        ),
                        title = tr("Añadir cotitular"),
                        collapsible = true,
                        expanded = false,
                    ),
                )
            }

            add(
                UiCard(
                    listOf(
                        UiForm(
                            id = "lot",
                            fields = lotFields(readOnly = !canEdit),
                            values = lotValues(lot) + submitted.orEmpty().filterKeys { it in lotValues(null).keys },
                            submit = UiButton(tr("Guardar"), request("save", formId = "lot")).takeIf { canEdit },
                        ),
                    ),
                    title = tr("Datos del lote"),
                ),
            )

            add(
                UiCard(
                    listOf(
                        UiForm(
                            id = "attachments",
                            fields = listOf(filesField(readOnly = !canEdit)),
                            values = mapOf("files" to UiFieldValues.files(fileRefs(detail.attachments))),
                            submit = UiButton(tr("Guardar adjuntos"), request("files", formId = "attachments"), style = "secondary").takeIf { canEdit },
                        ),
                    ),
                    title = tr("Adjuntos"),
                ),
            )

            add(
                UiCard(
                    listOf(
                        UiTimeline(
                            detail.history.map { entry ->
                                UiTimelineEntry(
                                    title = historyLabel(entry.kind),
                                    body = entry.detail,
                                    caption = listOf(entry.author.takeIf { it.isNotBlank() }, entry.happenedOn.toString()).filterNotNull().joinToString(" · "),
                                    tone = historyTone(entry.kind),
                                )
                            },
                            emptyText = tr("Todavía no hay movimientos."),
                        ),
                    ),
                    title = tr("Historial"),
                ),
            )

            if (context.canAdmin && !lot.archived) {
                add(UiButton(tr("Archivar lote"), request("archive", confirm = tr("El lote dejará de aparecer en listas y búsquedas. ¿Archivarlo?")), style = "danger"))
            }
        }
        return screen(context, tr("Lote {0}", lot.lotNumber), "desarrollos", notice, body)
    }

    // ---- Cambio de titularidad ----

    fun transfer(context: Context, lot: Lot, current: List<Ownership>, notice: UiNotice? = null, submitted: Map<String, String>? = null): UiScreen {
        val id = lot.id.toString()
        val fields = buildList {
            add(UiField("date", tr("Fecha del cambio"), type = "date", required = true))
            for (index in 1..PropietariosRules.MaxFormOwners) {
                add(
                    UiField(
                        "owner$index", tr("Nuevo titular {0}", index), placeholder = tr("Escribe para buscar"),
                        help = if (index == 1) tr("Elige un cliente de la lista de sugerencias. Déjalo vacío para registrar solo la baja.") else "",
                        section = tr("Titular {0}", index), suggest = UiSuggest(CustomerSuggestRoute),
                    ),
                )
                add(UiField("share$index", tr("Porcentaje {0}", index), section = tr("Titular {0}", index), help = if (index == 1) tr("Opcional, de 0 a 100; entre todos de 100 como máximo.") else ""))
            }
            add(UiField("notes", tr("Notas"), type = "textarea"))
        }
        val values = mapOf("date" to context.today.toString()) + submitted.orEmpty()
        val body = buildList<UiNode> {
            add(hint("${lot.developmentName} · Lote ${lot.lotNumber}"))
            add(
                UiCard(
                    listOf(
                        UiList(
                            current.map { UiListItem(title = it.customerName, subtitle = ownerLine(it)) },
                            emptyText = tr("El lote no tiene titulares vigentes."),
                        ),
                        hint(tr("Las titularidades vigentes terminan en la fecha del cambio y quedan en el historial.")),
                    ),
                    title = tr("Titulares vigentes"),
                ),
            )
            if (context.canSeeCustomers) {
                add(
                    UiForm(
                        id = "transfer",
                        fields = fields,
                        values = values,
                        submit = UiButton(
                            tr("Registrar cambio"),
                            UiRequest("$Route/traspaso", params = mapOf("op" to "transfer", "lot" to id), formId = "transfer"),
                        ),
                    ),
                )
            } else {
                add(hint(tr("Para elegir titulares necesitas permiso para ver clientes."), UiTone.Warning))
            }
            add(UiButton(tr("Volver al lote"), UiNavigate("lote", mapOf("id" to id)), style = "secondary"))
        }
        return screen(context, tr("Cambiar titularidad"), "desarrollos", notice, body)
    }

    fun ownership(context: Context, ownership: Ownership, lot: Lot, notice: UiNotice? = null, submitted: Map<String, String>? = null): UiScreen {
        val values = mapOf(
            "share" to area(ownership.sharePercent),
            "starts_on" to ownership.startsOn?.toString().orEmpty(),
            "ends_on" to ownership.endsOn?.toString().orEmpty(),
            "notes" to ownership.notes,
        ) + submitted.orEmpty()
        val form = UiForm(
            id = "ownership",
            fields = listOf(
                UiField("share", tr("Porcentaje"), help = tr("Opcional, de 0 a 100.")),
                UiField("starts_on", tr("Inicio"), type = "date"),
                UiField("ends_on", tr("Fin"), type = "date", help = tr("Con fecha de fin el titular deja de ser vigente (en esa fecha).")),
                UiField("notes", tr("Notas"), type = "textarea"),
            ),
            values = values,
            submit = UiButton(tr("Guardar"), UiRequest("$Route/titularidad", params = mapOf("op" to "save", "id" to ownership.id.toString()), formId = "ownership")),
        )
        val body = listOf(
            hint("${lot.developmentName} · Lote ${lot.lotNumber} · ${ownership.customerName}"),
            UiCard(listOf(form), title = tr("Titularidad")),
            UiButton(tr("Volver al lote"), UiNavigate("lote", mapOf("id" to lot.id.toString())), style = "secondary"),
        )
        return screen(context, tr("Titularidad"), "desarrollos", notice, body)
    }

    // ---- Propietarios ----

    fun owners(context: Context, owners: List<OwnerSummary>, query: String, notice: UiNotice? = null): UiScreen {
        val body = buildList<UiNode> {
            add(
                UiForm(
                    "filters", listOf(UiField("q", tr("Buscar"), placeholder = tr("Nombre o correo"))), values = mapOf("q" to query),
                    submit = UiButton(tr("Buscar"), UiNavigate("propietarios", formId = "filters"), style = "secondary"),
                    minFieldWidth = 260,
                ),
            )
            addAll(truncatedNote(owners.size))
            add(
                UiList(
                    owners.take(ListLimit).map { owner ->
                        UiListItem(
                            title = owner.name,
                            subtitle = if (context.canSeeCustomers) owner.email else "",
                            trailing = "${owner.lots} ${if (owner.lots == 1) "lote" else "lotes"} · ${area(owner.totalAreaM2)} m²",
                            action = UiNavigate("propietario", mapOf("id" to owner.customerId.toString())),
                        )
                    },
                    emptyText = tr("No hay propietarios con lotes vigentes."),
                ),
            )
        }
        return screen(context, tr("Propietarios"), "propietarios", notice, body)
    }

    fun owner(context: Context, customer: CustomerRef, owned: List<OwnedLot>, notice: UiNotice? = null): UiScreen {
        fun item(entry: OwnedLot) = UiListItem(
            title = "Lote ${entry.lot.lotNumber} · ${entry.lot.developmentName}",
            subtitle = listOfNotNull(
                entry.lot.registerNumber.takeIf { it.isNotBlank() }?.let { tr("Registro {0}", it) },
                entry.lot.areaM2?.let { "${area(it)} m²" },
                ownerLine(entry.ownership).takeIf { it.isNotBlank() },
            ).joinToString(" · "),
            badges = listOf(UiBadge(tr(entry.lot.status.label), statusTone(entry.lot.status))),
            action = UiNavigate("lote", mapOf("id" to entry.lot.id.toString())),
        )
        val current = owned.filter { it.ownership.current }
        val past = owned.filter { !it.ownership.current }
        val totalArea = current.map { it.lot }.distinctBy { it.id }.fold(BigDecimal.ZERO) { sum, lot -> sum + (lot.areaM2 ?: BigDecimal.ZERO) }
        val body = buildList<UiNode> {
            if (context.canSeeCustomers && customer.email.isNotBlank()) add(hint(customer.email))
            if (context.canSeeCustomers) {
                add(UiButton(tr("Abrir en Clientes"), UiOpenModule("clientes", "customer", mapOf("id" to customer.id.toString())), style = "secondary"))
            }
            add(UiGrid(listOf(metric(tr("Lotes vigentes"), current.size.toString()), metric(tr("Área total"), "${area(totalArea)} m²"))))
            add(UiCard(listOf(UiList(current.map(::item), emptyText = tr("No tiene lotes vigentes."))), title = tr("Lotes vigentes")))
            if (past.isNotEmpty()) add(UiCard(listOf(UiList(past.map(::item))), title = tr("Lotes anteriores")))
            add(UiButton(tr("Todos los propietarios"), UiNavigate("propietarios"), style = "secondary"))
        }
        return screen(context, customer.name, "propietarios", notice, body)
    }

    // ---- Buscar ----

    fun search(context: Context, query: String, lots: List<Lot>, notice: UiNotice? = null): UiScreen {
        val body = buildList<UiNode> {
            add(
                UiForm(
                    "search", listOf(UiField("q", tr("Número de lote o de registro"), required = true, placeholder = tr("Por ejemplo 24 o 1-234567"))), values = mapOf("q" to query),
                    submit = UiButton(tr("Buscar"), UiNavigate("buscar", formId = "search")),
                    minFieldWidth = 320,
                ),
            )
            if (query.isNotBlank()) {
                addAll(truncatedNote(lots.size))
                add(UiList(lots.take(ListLimit).map { lotItem(it, withDevelopment = true) }, emptyText = tr("Ningún lote coincide con \"{0}\".", query)))
            } else {
                add(hint(tr("Busca por número de lote o de registro en todos los desarrollos.")))
            }
        }
        return screen(context, tr("Buscar"), "buscar", notice, body)
    }
}
