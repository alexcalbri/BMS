package com.ideasdeveloper.bms.server.notifications

import com.ideasdeveloper.bms.server.modules.NotificationStore
import com.ideasdeveloper.bms.server.modules.NotificationTarget
import com.ideasdeveloper.bms.server.modules.StoredNotification
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.sql.Connection
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

internal class JdbcNotificationStore(private val tenant: Connection) : NotificationStore {
    private val params = MapSerializer(String.serializer(), String.serializer())
    private val columns = "id, user_id, module_id, title, body, target_screen, target_params, created_at, read_at"

    override fun insert(notification: StoredNotification) {
        tenant.prepareStatement(
            "INSERT INTO notifications (id, user_id, module_id, title, body, target_screen, target_params, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)"
        ).use { insert ->
            insert.setObject(1, notification.id)
            insert.setObject(2, notification.userId)
            insert.setString(3, notification.moduleId)
            insert.setString(4, notification.title)
            insert.setString(5, notification.text)
            insert.setString(6, notification.target?.screen)
            insert.setString(7, Json.encodeToString(params, notification.target?.params.orEmpty()))
            insert.setTimestamp(8, Timestamp.from(notification.createdAt))
            insert.executeUpdate()
        }
    }

    override fun list(userId: UUID, limit: Int, offset: Int, unreadOnly: Boolean): List<StoredNotification> =
        tenant.prepareStatement(
            "SELECT $columns FROM notifications WHERE user_id = ?" + (if (unreadOnly) " AND read_at IS NULL" else "") +
                " ORDER BY created_at DESC, id LIMIT ? OFFSET ?"
        ).use { query ->
            query.setObject(1, userId)
            query.setInt(2, limit)
            query.setInt(3, offset)
            query.executeQuery().use { rows -> buildList { while (rows.next()) add(read(rows)) } }
        }

    override fun unreadCount(userId: UUID): Int =
        tenant.prepareStatement("SELECT COUNT(*) FROM notifications WHERE user_id = ? AND read_at IS NULL").use { query ->
            query.setObject(1, userId)
            query.executeQuery().use { rows -> if (rows.next()) rows.getInt(1) else 0 }
        }

    override fun markRead(userId: UUID, id: UUID, at: Instant): Boolean =
        tenant.prepareStatement("UPDATE notifications SET read_at = ? WHERE id = ? AND user_id = ? AND read_at IS NULL").use { update ->
            update.setTimestamp(1, Timestamp.from(at))
            update.setObject(2, id)
            update.setObject(3, userId)
            update.executeUpdate() > 0
        }

    override fun markAllRead(userId: UUID, at: Instant): Int =
        tenant.prepareStatement("UPDATE notifications SET read_at = ? WHERE user_id = ? AND read_at IS NULL").use { update ->
            update.setTimestamp(1, Timestamp.from(at))
            update.setObject(2, userId)
            update.executeUpdate()
        }

    override fun deleteCreatedBefore(before: Instant, limit: Int): Int =
        tenant.prepareStatement(
            "DELETE FROM notifications WHERE id IN (SELECT id FROM notifications WHERE created_at < ? ORDER BY created_at LIMIT ?)"
        ).use { statement ->
            statement.setTimestamp(1, Timestamp.from(before))
            statement.setInt(2, limit)
            statement.executeUpdate()
        }

    private fun read(rows: java.sql.ResultSet): StoredNotification {
        val screen = rows.getString("target_screen")
        val targetParams = runCatching { Json.decodeFromString(params, rows.getString("target_params")) }.getOrDefault(emptyMap())
        return StoredNotification(
            id = rows.getObject("id", UUID::class.java),
            userId = rows.getObject("user_id", UUID::class.java),
            moduleId = rows.getString("module_id"),
            title = rows.getString("title"),
            text = rows.getString("body"),
            target = screen?.takeIf { it.isNotBlank() }?.let { NotificationTarget(it, targetParams) },
            createdAt = rows.getTimestamp("created_at").toInstant(),
            readAt = rows.getTimestamp("read_at")?.toInstant(),
        )
    }
}
