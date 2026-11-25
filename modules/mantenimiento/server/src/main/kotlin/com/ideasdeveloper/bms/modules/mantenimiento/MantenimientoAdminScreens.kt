package com.ideasdeveloper.bms.modules.mantenimiento

import com.ideasdeveloper.bms.ui.UiButton
import com.ideasdeveloper.bms.ui.UiCard
import com.ideasdeveloper.bms.ui.UiColumn
import com.ideasdeveloper.bms.ui.UiDivider
import com.ideasdeveloper.bms.ui.UiField
import com.ideasdeveloper.bms.ui.UiForm
import com.ideasdeveloper.bms.ui.UiNode
import com.ideasdeveloper.bms.ui.UiNotice
import com.ideasdeveloper.bms.ui.UiRequest
import com.ideasdeveloper.bms.ui.UiRow
import com.ideasdeveloper.bms.ui.UiScreen
import com.ideasdeveloper.bms.ui.UiStatus
import com.ideasdeveloper.bms.ui.UiText
import com.ideasdeveloper.bms.ui.UiTextStyle
import com.ideasdeveloper.bms.ui.UiTone
import java.util.UUID

/** Los tableros de Tareas que el usuario que configura puede elegir (con etiqueta única) y si puede cambiar la integración. */
data class TareasChoice(val boards: List<Pair<UUID, String>>, val editable: Boolean, val reason: String = "") {
    /** Etiquetas únicas: dos tableros con el mismo nombre se distinguen con un número. */
    val labels: List<Pair<UUID, String>>
        get() {
            val used = mutableSetOf<String>()
            return boards.map { (id, name) ->
                var candidate = name
                var number = 2
                while (!used.add(candidate.lowercase())) candidate = "$name #${number++}"
                id to candidate
            }
        }
}

/** Pantalla "Ajustes": propiedades y ajustes del módulo. Solo para quien gestiona. */
object MantenimientoAdminScreens {
    const val Route: String = MantenimientoModule.UiRoute
    const val NoBoard = "Sin tablero (integración apagada)"

    private fun hint(text: String, tone: String? = null) = UiText(text, UiTextStyle.Caption, tone)

    private fun request(op: String, extra: Map<String, String> = emptyMap(), confirm: String? = null, formId: String? = null) =
        UiRequest("$Route/admin", params = mapOf("op" to op) + extra, confirm = confirm, formId = formId)

    /** La etiqueta del tablero configurado dentro de [choice]; null si el usuario ya no lo ve. */
    fun configuredLabel(settings: MantenimientoSettings, choice: TareasChoice): String? =
        settings.tareasBoardId?.let { id -> choice.labels.firstOrNull { it.first == id }?.second }

    fun admin(
        context: MantenimientoScreens.Context,
        properties: List<Property>,
        settings: MantenimientoSettings,
        tareas: TareasChoice,
        notice: UiNotice? = null,
        syncGivenUp: Int = 0,
    ): UiScreen {
        val body = listOfNotNull(propertiesCard(properties), remindersCard(settings), tareasCard(settings, tareas), syncGivenUpNotice(settings, syncGivenUp))
        return UiScreen(
            title = "Ajustes",
            navigation = MantenimientoScreens.navigation(context.scope, "admin", context.importEnabled),
            notice = notice,
            body = body,
        )
    }

    /** Aviso de las órdenes que no se pudieron publicar en Tareas tras varios intentos; null si no hay o la integración está apagada. */
    fun syncGivenUpNotice(settings: MantenimientoSettings, count: Int): UiNode? =
        if (count <= 0 || settings.tareasBoardId == null) null
        else UiCard(
            listOf(
                UiText(
                    "$count ${if (count == 1) "orden quedó" else "órdenes quedaron"} sin sincronizar con Tareas tras varios intentos. " +
                        "Se reintentan al guardar los ajustes de Tareas o al editar la orden.",
                    UiTextStyle.Caption,
                    UiTone.Warning,
                ),
            ),
        )

    private fun propertiesCard(properties: List<Property>): UiNode {
        val active = properties.filter { !it.archived }
        val rows = properties.map { property ->
            val params = mapOf("prop" to property.id.toString())
            val activeIndex = active.indexOfFirst { it.id == property.id }
            UiColumn(
                listOf(
                    UiForm(
                        id = "prop-${property.id}",
                        fields = listOf(UiField("name", "Nombre de la propiedad", required = true)),
                        values = mapOf("name" to property.name),
                        submit = UiButton("Renombrar", request("prop-rename", params, formId = "prop-${property.id}"), style = "secondary"),
                    ),
                    UiRow(
                        listOfNotNull(
                            UiButton("Subir", request("prop-up", params), style = "secondary").takeIf { !property.archived && activeIndex > 0 },
                            UiButton("Bajar", request("prop-down", params), style = "secondary").takeIf { !property.archived && activeIndex in 0 until active.lastIndex },
                            if (property.archived) UiButton("Restaurar", request("prop-restore", params), style = "secondary")
                            else UiButton("Archivar", request("prop-archive", params, confirm = "¿Archivar la propiedad ${property.name}? Sus órdenes y activos se conservan."), style = "danger"),
                        ),
                        spacing = 8,
                    ),
                ),
                spacing = 8,
            )
        }
        val children = buildList<UiNode> {
            add(hint("Cada propiedad (edificio, sede, terreno) agrupa activos, planes y órdenes."))
            if (rows.isEmpty()) add(hint("Aún no hay propiedades: añade la primera."))
            rows.forEachIndexed { index, row ->
                if (index > 0) add(UiDivider())
                add(row)
            }
            add(UiDivider())
            add(UiText("Nueva propiedad", UiTextStyle.Subtitle))
            add(
                UiForm(
                    id = "prop-new",
                    fields = listOf(UiField("name", "Nombre", required = true)),
                    submit = UiButton("Añadir propiedad", request("prop-add", formId = "prop-new"), style = "secondary"),
                ),
            )
        }
        return UiCard(children, title = "Propiedades")
    }

    private fun remindersCard(settings: MantenimientoSettings): UiNode = UiCard(
        listOf(
            UiForm(
                id = "settings",
                fields = listOf(
                    UiField(
                        "due_soon", "Días para el aviso de \"por vencer\"", type = "number",
                        help = "El responsable recibe un aviso cuando faltan estos días para la fecha límite (0 a 30).",
                    ),
                ),
                values = mapOf("due_soon" to settings.dueSoonDays.toString()),
                submit = UiButton("Guardar", request("settings-save", formId = "settings"), style = "secondary"),
            ),
        ),
        title = "Avisos",
    )

    private fun tareasCard(settings: MantenimientoSettings, tareas: TareasChoice): UiNode {
        val current = configuredLabel(settings, tareas)
        val on = settings.tareasBoardId != null
        val children = buildList<UiNode> {
            add(hint("Opcional. Cada orden con responsable crea una tarea enlazada en el tablero elegido y, al completar la orden, la tarea se completa."))
            add(
                UiRow(
                    listOf(
                        UiStatus(if (on) "Activa" else "Apagada", if (on) UiTone.Success else UiTone.Neutral),
                        hint(if (on) "Tablero: ${current ?: "uno que tu usuario no ve"}" else "Sin tablero elegido."),
                    ),
                    spacing = 8,
                ),
            )
            if (tareas.editable) {
                add(
                    UiForm(
                        id = "tareas",
                        fields = listOf(
                            UiField(
                                "board", "Tablero de Tareas", type = "dropdown", options = listOf(NoBoard) + tareas.labels.map { it.second },
                                help = "Las tareas se publican con los permisos de tu usuario en Tareas.",
                            ),
                        ),
                        values = mapOf("board" to (current ?: NoBoard)),
                        submit = UiButton("Guardar integración", request("tareas-save", formId = "tareas"), style = "secondary"),
                    ),
                )
            } else {
                add(hint(tareas.reason.ifBlank { "Tareas no está disponible para tu usuario." }, UiTone.Warning))
                if (on) add(UiButton("Apagar integración", request("tareas-off", confirm = "¿Apagar la integración con Tareas?"), style = "danger"))
            }
        }
        return UiCard(children, title = "Integración con Tareas")
    }
}
