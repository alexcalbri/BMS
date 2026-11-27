package com.ideasdeveloper.bms.app.features.activitylog.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

// Registro de actividad de la empresa (ACTIVITY_LOG.md). Todos los DTOs estan en este archivo; todos los campos son opcionales.

@Serializable
// Un cambio campo a campo: valor anterior y nuevo ya recortados y sin secretos por el servidor.
data class ActivityChange(
    val field: String = "",
    val label: String = "",
    val old: JsonElement? = null,
    val new: JsonElement? = null,
)

@Serializable
// Una fila del registro. Los textos (actorDisplay, summary, labels) llegan en espanol desde el servidor y se muestran tal cual.
data class ActivityRow(
    val id: JsonElement? = null,
    val occurredAt: String? = null,
    val companyCode: String? = null,
    val actorKind: String? = null,
    val actorUserId: String? = null,
    val actorName: String? = null,
    val actorDisplay: String? = null,
    val agentName: String? = null,
    val realActorKind: String? = null,
    val realActorName: String? = null,
    val moduleId: String? = null,
    val moduleName: String? = null,
    val entityType: String? = null,
    val entityId: String? = null,
    val entityLabel: String? = null,
    val action: String? = null,
    val actionLabel: String? = null,
    val summary: String? = null,
    val changes: List<ActivityChange> = emptyList(),
    val origin: String? = null,
)

@Serializable
// GET /companies/me/activity y GET /server/activity
data class ActivityPage(
    val items: List<ActivityRow> = emptyList(),
    val page: Int = 0,
    val pageSize: Int = 0,
    val hasMore: Boolean = false,
)

// Filtros de ambas rutas; los vacios no se envian. [user] es el id del usuario (o texto libre) y [company] solo cuenta en la ruta del servidor.
data class ActivityFilters(
    val company: String = "",
    val user: String = "",
    val module: String = "",
    val action: String = "",
    val query: String = "",
    val from: String = "",
    val to: String = "",
)

// Acciones conocidas (ids de ACTIVITY_LOG.md seccion 2) y la clave de texto en espanol de su etiqueta.
val ActivityActionIds: List<Pair<String, String>> = listOf(
    "created" to "Creado",
    "updated" to "Modificado",
    "deleted" to "Eliminado",
    "archived" to "Archivado",
    "restored" to "Restaurado",
    "status_changed" to "Cambio de estado",
    "assigned" to "Asignado",
    "commented" to "Comentario",
    "attachment_added" to "Adjunto agregado",
    "attachment_removed" to "Adjunto eliminado",
    "settings_changed" to "Cambio de ajustes",
    "imported" to "Importacion",
    "signed_in" to "Inicio de sesion",
    "sign_in_failed" to "Inicio de sesion fallido",
    "signed_out" to "Cierre de sesion",
    "permission_changed" to "Cambio de permisos",
)
