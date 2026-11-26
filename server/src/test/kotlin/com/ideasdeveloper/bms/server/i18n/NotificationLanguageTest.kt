package com.ideasdeveloper.bms.server.i18n

import com.ideasdeveloper.bms.server.extensions.ExtensionNotifier
import com.ideasdeveloper.bms.server.modules.ModuleNotifier
import com.ideasdeveloper.bms.server.modules.NotificationService
import com.ideasdeveloper.bms.server.modules.NotificationStore
import com.ideasdeveloper.bms.server.modules.NotificationText
import com.ideasdeveloper.bms.server.modules.StoredNotification
import java.lang.reflect.Proxy
import java.sql.Connection
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class NotificationLanguageTest {
    init {
        I18n.register("notification-test", "en", mapOf("Te asignaron el lead {0}" to "You were assigned the lead {0}", "Revisalo hoy" to "Check it today"))
    }

    private val tenant: Connection = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(Connection::class.java)) { _, _, _ -> null } as Connection

    private class Rows : NotificationStore {
        val rows = mutableListOf<StoredNotification>()
        override fun insert(notification: StoredNotification) { rows += notification }
        override fun list(userId: UUID, limit: Int, offset: Int, unreadOnly: Boolean) = rows.filter { it.userId == userId }
        override fun unreadCount(userId: UUID) = 0
        override fun markRead(userId: UUID, id: UUID, at: Instant) = false
        override fun markAllRead(userId: UUID, at: Instant) = 0
        override fun deleteCreatedBefore(before: Instant, limit: Int) = 0
    }

    private val ana = UUID.randomUUID()
    private val luis = UUID.randomUUID()
    private val rows = Rows()
    private val languages = mapOf(ana to "en", luis to "es")
    private val notifier = ModuleNotifier("crm", NotificationService({ rows })) { _, userId -> languages[userId] ?: I18n.Source }

    @Test
    fun eachRecipientGetsTheNotificationInTheirLanguage() {
        notifier.notifyEach(tenant, listOf(ana, luis)) {
            NotificationText(tr("Te asignaron el lead {0}", "Ana Perez"), tr("Revisalo hoy"))
        }
        assertEquals("You were assigned the lead Ana Perez", rows.rows.single { it.userId == ana }.title)
        assertEquals("Check it today", rows.rows.single { it.userId == ana }.text)
        assertEquals("Te asignaron el lead Ana Perez", rows.rows.single { it.userId == luis }.title)
        assertEquals("crm", rows.rows.first().moduleId)
        // The caller's language is restored afterwards.
        assertEquals("es", I18n.language())
    }

    @Test
    fun theRequestLanguageDoesNotLeakIntoOtherRecipients() {
        I18n.withLanguage("en") { notifier.notify(tenant, luis) { NotificationText(tr("Revisalo hoy")) } }
        assertEquals("Revisalo hoy", rows.rows.single().title)
    }

    @Test
    fun thePlainOverloadStoresTheTextAsGiven() {
        notifier.notify(tenant, ana, "Texto ya escrito", "detalle")
        assertEquals("Texto ya escrito", rows.rows.single().title)
    }

    @Test
    fun extensionNotifiersWithoutTheBuilderStillWork() {
        val seen = mutableListOf<String>()
        val notifier = ExtensionNotifier { _, _, _, title, text -> seen += "$title|$text" }
        notifier.notify(tenant, "crm", ana) { NotificationText("Hola", "Mundo") }
        assertEquals(listOf("Hola|Mundo"), seen)
    }
}
