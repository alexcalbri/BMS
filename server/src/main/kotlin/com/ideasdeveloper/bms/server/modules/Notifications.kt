package com.ideasdeveloper.bms.server.modules

import com.ideasdeveloper.bms.server.i18n.I18n
import com.ideasdeveloper.bms.server.notifications.JdbcNotificationStore
import kotlinx.serialization.Serializable
import java.sql.Connection
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Pantalla que se abre al hacer clic en una notificacion: [screen] es una pantalla del modulo que la
 * creo (la misma que usa UiOpenModule) y [params] sus parametros.
 */
@Serializable
data class NotificationTarget(val screen: String, val params: Map<String, String> = emptyMap())

// Lo que un modulo entrega al Core para avisar a un usuario.
data class NewNotification(
    val userId: UUID,
    // Modulo de origen: el panel lo muestra con su nombre y abre [target] en ese modulo.
    val moduleId: String,
    val title: String,
    val text: String = "",
    val target: NotificationTarget? = null,
)

// Una notificacion guardada de un usuario.
data class StoredNotification(
    val id: UUID,
    val userId: UUID,
    val moduleId: String,
    val title: String,
    val text: String,
    val target: NotificationTarget?,
    val createdAt: Instant,
    val readAt: Instant?,
)

data class NotificationPage(val items: List<StoredNotification>, val unread: Int, val hasMore: Boolean)

/**
 * Almacen de notificaciones de la base de una empresa. Cada operacion recibe el usuario y solo ve sus
 * filas, para que ninguna consulta del servicio pueda mezclar usuarios.
 */
interface NotificationStore {
    fun insert(notification: StoredNotification)
    // Mas nuevas primero; pide uno mas que [limit] para saber si hay otra pagina.
    fun list(userId: UUID, limit: Int, offset: Int, unreadOnly: Boolean): List<StoredNotification>
    fun unreadCount(userId: UUID): Int
    // Falso cuando no existe, es de otro usuario o ya estaba leida.
    fun markRead(userId: UUID, id: UUID, at: Instant): Boolean
    fun markAllRead(userId: UUID, at: Instant): Int
    // Borra hasta [limit] de las creadas antes de [before] (las mas viejas primero, de cualquier usuario); devuelve cuantas.
    fun deleteCreatedBefore(before: Instant, limit: Int): Int
}

/**
 * Servicio del Core para el centro de notificaciones (`access.notifications`). Es la API generica del
 * Core y del puente de agentes (leer y marcar las propias); los modulos crean notificaciones con su
 * notificador ligado ([ModuleNotifier]: `access.notificationsFor(moduleId)`, `ModuleJobContext.notifier`
 * o `McpToolCall.notifier`), que fija el modulo de origen, y nunca tocan la tabla:
 *
 *     access.notificationsFor("crm").notify(tenant, userId, "Te asignaron un lead", "Ana Perez", NotificationTarget("lead", mapOf("id" to leadId)))
 *
 * Se llama con la conexion a la base de la empresa (la del hilo de la ruta, herramienta o tarea). Una
 * notificacion con datos invalidos se ajusta (titulo y texto se recortan); un titulo vacio se rechaza.
 */
class NotificationService(
    private val store: (Connection) -> NotificationStore = ::JdbcNotificationStore,
    private val clock: () -> Instant = Instant::now,
    // La limpieza va por lotes para no bloquear la base de una empresa con muchas filas.
    private val purgeBatchSize: Int = PurgeBatchSize,
    private val maxPurgeBatches: Int = MaxPurgeBatches,
) {
    /** Crea la notificacion y devuelve su id. */
    fun notify(tenant: Connection, notification: NewNotification): UUID {
        val title = notification.title.trim().take(MaxTitleLength)
        require(title.isNotEmpty()) { "El titulo de la notificacion es obligatorio." }
        require(notification.moduleId.isNotBlank()) { "El modulo de origen es obligatorio." }
        val target = notification.target?.takeIf { it.screen.isNotBlank() }?.let {
            NotificationTarget(it.screen.trim().take(MaxScreenLength), it.params.entries.take(MaxParams).associate { (key, value) -> key.take(MaxParamLength) to value.take(MaxParamLength) })
        }
        val id = UUID.randomUUID()
        store(tenant).insert(
            StoredNotification(id, notification.userId, notification.moduleId.trim().take(MaxModuleLength), title, notification.text.trim().take(MaxTextLength), target, clock(), null)
        )
        return id
    }

    /** Las notificaciones del usuario, mas nuevas primero, en paginas de [limit] a partir de [offset]. */
    fun list(tenant: Connection, userId: UUID, limit: Int = DefaultPageSize, offset: Int = 0, unreadOnly: Boolean = false): NotificationPage {
        val size = limit.coerceIn(1, MaxPageSize)
        val rows = store(tenant).list(userId, size + 1, offset.coerceAtLeast(0), unreadOnly)
        return NotificationPage(rows.take(size), store(tenant).unreadCount(userId), rows.size > size)
    }

    fun unreadCount(tenant: Connection, userId: UUID): Int = store(tenant).unreadCount(userId)

    /** Marca una notificacion propia como leida; falso si no existe, es de otro usuario o ya estaba leida. */
    fun markRead(tenant: Connection, userId: UUID, id: UUID): Boolean = store(tenant).markRead(userId, id, clock())

    /** Marca todas las del usuario como leidas y devuelve cuantas eran nuevas. */
    fun markAllRead(tenant: Connection, userId: UUID): Int = store(tenant).markAllRead(userId, clock())

    /**
     * Limpia las de mas de [RetentionDays] dias (las leidas y las no leidas), en lotes de [PurgeBatchSize]
     * y como mucho [MaxPurgeBatches] lotes por pasada (el resto se limpia en la siguiente); devuelve cuantas.
     */
    fun purgeExpired(tenant: Connection): Int {
        val before = clock().minus(Duration.ofDays(RetentionDays))
        val rows = store(tenant)
        var removed = 0
        repeat(maxPurgeBatches) {
            val batch = rows.deleteCreatedBefore(before, purgeBatchSize)
            removed += batch
            if (batch < purgeBatchSize) return removed
        }
        return removed
    }

    companion object {
        const val RetentionDays = 90L
        const val PurgeBatchSize = 5_000
        const val MaxPurgeBatches = 20
        const val DefaultPageSize = 30
        const val MaxPageSize = 100
        const val MaxTitleLength = 160
        const val MaxTextLength = 1_000
        const val MaxModuleLength = 64
        const val MaxScreenLength = 120
        const val MaxParams = 20
        const val MaxParamLength = 200
    }
}

/**
 * Notificador de un modulo: crea notificaciones siempre con el id del propio modulo como origen, de modo
 * que un modulo no puede hacerse pasar por otro. Se obtiene con `access.notificationsFor(moduleId)`, o ya
 * ligado en `ModuleJobContext.notifier` y `McpToolCall.notifier`.
 *
 * Las notificaciones se guardan ya escritas (I18N.md §7): para que cada destinatario la lea en su idioma,
 * usa la sobrecarga que recibe un constructor, que se ejecuta bajo el idioma del destinatario:
 *
 *     notifier.notify(tenant, userId, target) { NotificationText(tr("Te asignaron un lead"), lead.name) }
 */
class ModuleNotifier internal constructor(
    val moduleId: String,
    private val service: NotificationService,
    // Idioma de un destinatario (ModuleAccessService.recipientLanguage): el suyo, el de la empresa o "es".
    private val recipientLanguage: (Connection, UUID) -> String = { _, _ -> I18n.Source },
) {
    /** Crea la notificacion para [userId] y devuelve su id; el clic abre [target] en este modulo. */
    fun notify(tenant: Connection, userId: UUID, title: String, text: String = "", target: NotificationTarget? = null): UUID =
        service.notify(tenant, NewNotification(userId, moduleId, title, text, target))

    /**
     * Crea la notificacion para [userId] escrita por [build] en el idioma del destinatario (el suyo, si no
     * el de la empresa, si no "es"); dentro de [build] `tr(...)` ya devuelve ese idioma.
     */
    fun notify(tenant: Connection, userId: UUID, target: NotificationTarget? = null, build: () -> NotificationText): UUID {
        val text = I18n.withLanguage(recipientLanguage(tenant, userId)) { build() }
        return service.notify(tenant, NewNotification(userId, moduleId, text.title, text.text, target))
    }

    /** [notify] with [build] for each of [userIds], each one in its own language; returns the ids in the same order. */
    fun notifyEach(tenant: Connection, userIds: Collection<UUID>, target: NotificationTarget? = null, build: () -> NotificationText): List<UUID> =
        userIds.map { userId -> notify(tenant, userId, target, build) }
}

/** Title and text of a notification built for one recipient (see [ModuleNotifier.notify]). */
data class NotificationText(val title: String, val text: String = "")
