package com.ideasdeveloper.bms.server.assistant

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID

data class AssistantConversation(
    val id: UUID,
    val userId: UUID,
    val ownerKey: String,
    val title: String,
    val state: String,
    val leaseUntil: Instant?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val archivedAt: Instant?,
) {
    companion object {
        const val Idle = "idle"
        const val Running = "running"
        const val Awaiting = "awaiting_confirmation"
        // The conversation cannot go on (see AssistantEngine, refusal after tool results).
        const val Closed = "closed"
    }
}

data class AssistantStoredMessage(
    val id: Long,
    val role: String,
    val content: JsonArray,
    val createdAt: Instant,
)

/**
 * One tool call of a paused turn, in the order of its model response. Calls that ran already keep their tool_result
 * block in [result] (status done). The others wait for the user (pending), are decided (approved, rejected), or are
 * being run right now by the turn that claimed them (executing): a call is claimed once and never run twice.
 */
data class AssistantPendingCall(
    val id: UUID,
    val conversationId: UUID,
    val position: Int,
    val toolUseId: String,
    val toolName: String,
    val arguments: JsonObject,
    val label: String,
    val moduleName: String,
    val destructive: Boolean,
    val summary: JsonArray,
    val status: String,
    val result: JsonObject?,
    val expiresAt: Instant,
    val preview: JsonArray = JsonArray(emptyList()),
    val previewError: String? = null,
    val outbound: Boolean = false,
    // Fingerprint of the card the user saw (AssistantEngine.fingerprint); null when the tool has no preview.
    val previewHash: String? = null,
) {
    companion object {
        const val Pending = "pending"
        const val Approved = "approved"
        const val Rejected = "rejected"
        const val Executing = "executing"
        const val Done = "done"
    }
}

data class AssistantUsageRow(
    val day: LocalDate,
    val userId: UUID,
    val keySource: String,
    val requests: Long,
    val usage: ModelUsage,
    val countedTokens: Long,
)

/** The turn no longer holds the conversation (its lease ended and another request took it): it must stop writing. */
class AssistantLeaseLostException : Exception("lease lost")

/**
 * Where the assistant keeps a company's conversations (tenant V018, V019). Every read of a conversation is by its id
 * AND its owner (user and owner key). Every write of a turn names the turn's lease token and is refused with
 * [AssistantLeaseLostException] when another turn holds the conversation.
 */
interface AssistantStore {
    fun createConversation(userId: UUID, ownerKey: String, title: String, now: Instant): AssistantConversation
    /** Like [createConversation], but null when the person already keeps [max] conversations (counted and inserted atomically). */
    fun createConversationCapped(userId: UUID, ownerKey: String, title: String, now: Instant, max: Int): AssistantConversation?
    fun conversation(id: UUID, userId: UUID, ownerKey: String): AssistantConversation?
    fun conversations(userId: UUID, ownerKey: String, archived: Boolean, limit: Int, before: Instant?): List<AssistantConversation>
    fun conversationCount(userId: UUID, ownerKey: String): Int
    fun updateConversation(id: UUID, userId: UUID, ownerKey: String, title: String?, archived: Boolean?, now: Instant): AssistantConversation?
    fun deleteConversation(id: UUID, userId: UUID, ownerKey: String): Boolean

    /** Takes the conversation for one turn ([fromState], or a running turn whose lease ended): its token, or null. */
    fun startTurn(id: UUID, fromState: String, leaseUntil: Instant, now: Instant): UUID?
    /** Extends the lease of the turn [token]; false when that turn no longer holds the conversation. */
    fun renew(id: UUID, token: UUID, leaseUntil: Instant): Boolean
    /** Gives the conversation back in [state], only if the turn [token] still holds it. */
    fun release(id: UUID, token: UUID, state: String, now: Instant): Boolean

    fun messages(conversationId: UUID): List<AssistantStoredMessage>
    fun messageCount(conversationId: UUID): Int
    /** Appends a message for the turn [token]; [content] must not be empty. */
    fun appendMessage(conversationId: UUID, token: UUID, role: String, content: JsonArray, model: String?, stopReason: String?, usage: ModelUsage?, now: Instant): AssistantStoredMessage
    /** Prompt size (input + cache) of the last stored model response: the estimate of a request stopped before any usage. */
    fun lastPromptTokens(conversationId: UUID): Long

    fun savePending(token: UUID, calls: List<AssistantPendingCall>)
    fun pending(conversationId: UUID): List<AssistantPendingCall>
    /** The call, only when its conversation belongs to the owner. */
    fun pendingCall(id: UUID, userId: UUID, ownerKey: String): AssistantPendingCall?
    /** Records a decision of a call still pending and not expired at [now]; false otherwise. */
    fun decide(id: UUID, status: String, now: Instant): Boolean
    /** Claims an approved call for the turn [token] (approved to executing); false when it was claimed already. */
    fun claim(callId: UUID, conversationId: UUID, token: UUID): Boolean
    /** Stores the result of a claimed call (executing to done) for the turn [token]. */
    fun finish(callId: UUID, conversationId: UUID, token: UUID, result: JsonObject)
    /**
     * In one transaction for the turn [token]: appends [content] as a user message (when not empty) and deletes the
     * conversation's pending calls.
     */
    fun completePending(conversationId: UUID, token: UUID, content: JsonArray, now: Instant): AssistantStoredMessage?
    /** Conversations with a pending call whose time is over. */
    fun expiredConversations(now: Instant): List<UUID>
    /**
     * Conversations left awaiting a confirmation with no call still pending (a crash between the last decision and the
     * resumed turn), untouched since [before].
     */
    fun stuckConversations(before: Instant): List<UUID>

    fun recordUsage(day: LocalDate, userId: UUID, keySource: String, usage: ModelUsage, requests: Long)
    fun monthUsage(month: YearMonth, keySources: Set<String>): Long
    fun usage(month: YearMonth): List<AssistantUsageRow>

    /** Deletes the conversations not updated since [before]; usage rows older than [usageBefore]. */
    fun purge(before: Instant, usageBefore: LocalDate): Int

    // ---- What needs attention on another device (tenant V022; no-ops for a store without it) ----

    /** Opens a connection on the same database (for the Core's notifications), or null. */
    val connections: (() -> Connection)? get() = null
    /** The owner (user id, owner key) of a conversation. */
    fun owner(id: UUID): Pair<UUID, String>? = null
    /** The last turn of the conversation finished at [at]; [read]: someone was watching it, so it is not unseen. */
    fun markFinished(id: UUID, read: Boolean, at: Instant) {}
    /** The owner opened the conversation: its read mark. Returns the live notification to resolve, when it is not awaiting. */
    fun markRead(id: UUID, userId: UUID, ownerKey: String, at: Instant): UUID? = null
    fun liveNotification(id: UUID): UUID? = null
    fun setLiveNotification(id: UUID, notificationId: UUID?) {}
    /** Pending confirmations and unseen finished conversations of one person, in one query. */
    fun attention(userId: UUID, ownerKey: String, now: Instant): AssistantAttention = AssistantAttention(0, 0, emptyList())
}

/** [pending]: open confirmations not expired; [unseen]: conversations finished after the owner last read them. */
data class AssistantAttention(val pending: Int, val unseen: Int, val conversationIds: List<UUID>)

/** [AssistantStore] on a company database; every call takes a pooled connection for itself. */
class JdbcAssistantStore(private val connect: () -> Connection) : AssistantStore {
    private fun <T> use(block: (Connection) -> T): T = connect().use(block)

    private fun <T> transaction(block: (Connection) -> T): T = connect().use { connection ->
        connection.autoCommit = false
        try {
            block(connection).also { connection.commit() }
        } catch (failure: Throwable) {
            runCatching { connection.rollback() }
            throw failure
        } finally {
            runCatching { connection.autoCommit = true }
        }
    }

    /** Locks the conversation row and checks the turn still holds it (inside a transaction). */
    private fun holds(connection: Connection, conversationId: UUID, token: UUID) {
        val held = connection.prepareStatement(
            "SELECT 1 FROM public.assistant_conversations WHERE id = ? AND lease_token = ? AND state = 'running' FOR UPDATE",
        ).use { query ->
            query.setObject(1, conversationId)
            query.setObject(2, token)
            query.executeQuery().use { it.next() }
        }
        if (!held) throw AssistantLeaseLostException()
    }

    override val connections: (() -> Connection)? get() = connect

    override fun owner(id: UUID): Pair<UUID, String>? = use { connection ->
        connection.prepareStatement("SELECT user_id, owner_key FROM public.assistant_conversations WHERE id = ?").use { query ->
            query.setObject(1, id)
            query.executeQuery().use { rows -> if (rows.next()) rows.getObject(1, UUID::class.java) to rows.getString(2) else null }
        }
    }

    override fun markFinished(id: UUID, read: Boolean, at: Instant) = use { connection ->
        connection.prepareStatement(
            "UPDATE public.assistant_conversations SET finished_at = ?, last_read_at = CASE WHEN ? THEN ? ELSE last_read_at END WHERE id = ?",
        ).use { update ->
            update.setTimestamp(1, Timestamp.from(at))
            update.setBoolean(2, read)
            update.setTimestamp(3, Timestamp.from(at))
            update.setObject(4, id)
            update.executeUpdate()
        }
        Unit
    }

    override fun markRead(id: UUID, userId: UUID, ownerKey: String, at: Instant): UUID? = use { connection ->
        connection.prepareStatement(
            "UPDATE public.assistant_conversations SET last_read_at = ? WHERE id = ? AND user_id = ? AND owner_key = ? " +
                "RETURNING CASE WHEN state <> 'awaiting_confirmation' THEN notification_id END",
        ).use { update ->
            update.setTimestamp(1, Timestamp.from(at))
            update.setObject(2, id)
            update.setObject(3, userId)
            update.setString(4, ownerKey)
            update.executeQuery().use { rows -> if (rows.next()) rows.getObject(1, UUID::class.java) else null }
        }
    }

    override fun liveNotification(id: UUID): UUID? = use { connection ->
        connection.prepareStatement("SELECT notification_id FROM public.assistant_conversations WHERE id = ?").use { query ->
            query.setObject(1, id)
            query.executeQuery().use { rows -> if (rows.next()) rows.getObject(1, UUID::class.java) else null }
        }
    }

    override fun setLiveNotification(id: UUID, notificationId: UUID?) = use { connection ->
        connection.prepareStatement("UPDATE public.assistant_conversations SET notification_id = ? WHERE id = ?").use { update ->
            update.setObject(1, notificationId)
            update.setObject(2, id)
            update.executeUpdate()
        }
        Unit
    }

    override fun attention(userId: UUID, ownerKey: String, now: Instant): AssistantAttention = use { connection ->
        // One indexed query over the person's own conversations (the partial index of V022); at most 200 rows are read.
        connection.prepareStatement(
            """
            SELECT c.id,
                   (SELECT count(*) FROM public.assistant_pending_calls p
                     WHERE p.conversation_id = c.id AND p.status = 'pending' AND p.expires_at > ?) AS pending,
                   (c.state IN ('idle', 'closed') AND c.finished_at IS NOT NULL
                     AND (c.last_read_at IS NULL OR c.finished_at > c.last_read_at)) AS unseen
            FROM public.assistant_conversations c
            WHERE c.user_id = ? AND c.owner_key = ? AND c.archived_at IS NULL
              AND (c.state = 'awaiting_confirmation' OR c.finished_at IS NOT NULL)
            ORDER BY c.updated_at DESC
            LIMIT 200
            """.trimIndent(),
        ).use { query ->
            query.setTimestamp(1, Timestamp.from(now))
            query.setObject(2, userId)
            query.setString(3, ownerKey)
            query.executeQuery().use { rows ->
                var pending = 0
                var unseen = 0
                val withPending = mutableListOf<UUID>()
                val withUnseen = mutableListOf<UUID>()
                while (rows.next()) {
                    val id = rows.getObject(1, UUID::class.java)
                    val count = rows.getInt(2)
                    pending += count
                    if (count > 0) withPending += id
                    if (rows.getBoolean(3)) { unseen++; withUnseen += id }
                }
                AssistantAttention(pending, unseen, (withPending + withUnseen).distinct().take(20))
            }
        }
    }

    private fun ResultSet.instant(column: String): Instant? = getTimestamp(column)?.toInstant()

    private fun ResultSet.conversation() = AssistantConversation(
        id = getObject("id", UUID::class.java),
        userId = getObject("user_id", UUID::class.java),
        ownerKey = getString("owner_key"),
        title = getString("title"),
        state = getString("state"),
        leaseUntil = instant("lease_until"),
        createdAt = instant("created_at")!!,
        updatedAt = instant("updated_at")!!,
        archivedAt = instant("archived_at"),
    )

    private val columns = "id, user_id, owner_key, title, state, lease_until, created_at, updated_at, archived_at"

    override fun createConversation(userId: UUID, ownerKey: String, title: String, now: Instant): AssistantConversation = use { connection ->
        connection.prepareStatement(
            "INSERT INTO public.assistant_conversations (id, user_id, owner_key, title, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?) RETURNING $columns",
        ).use { insert ->
            insert.setObject(1, UUID.randomUUID())
            insert.setObject(2, userId)
            insert.setString(3, ownerKey)
            insert.setString(4, title)
            insert.setTimestamp(5, Timestamp.from(now))
            insert.setTimestamp(6, Timestamp.from(now))
            insert.executeQuery().use { rows -> rows.next(); rows.conversation() }
        }
    }

    override fun createConversationCapped(userId: UUID, ownerKey: String, title: String, now: Instant, max: Int): AssistantConversation? = transaction { connection ->
        // Two creations at once of the same person take turns: the count and the insert are one step.
        connection.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))").use { lock ->
            lock.setString(1, "assistant-conversations/$userId/$ownerKey")
            lock.execute()
        }
        val count = connection.prepareStatement("SELECT count(*) FROM public.assistant_conversations WHERE user_id = ? AND owner_key = ?").use { query ->
            query.setObject(1, userId)
            query.setString(2, ownerKey)
            query.executeQuery().use { rows -> rows.next(); rows.getInt(1) }
        }
        if (count >= max) return@transaction null
        connection.prepareStatement(
            "INSERT INTO public.assistant_conversations (id, user_id, owner_key, title, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?) RETURNING $columns",
        ).use { insert ->
            insert.setObject(1, UUID.randomUUID())
            insert.setObject(2, userId)
            insert.setString(3, ownerKey)
            insert.setString(4, title)
            insert.setTimestamp(5, Timestamp.from(now))
            insert.setTimestamp(6, Timestamp.from(now))
            insert.executeQuery().use { rows -> rows.next(); rows.conversation() }
        }
    }

    override fun conversation(id: UUID, userId: UUID, ownerKey: String): AssistantConversation? = use { connection ->
        connection.prepareStatement("SELECT $columns FROM public.assistant_conversations WHERE id = ? AND user_id = ? AND owner_key = ?").use { query ->
            query.setObject(1, id)
            query.setObject(2, userId)
            query.setString(3, ownerKey)
            query.executeQuery().use { rows -> if (rows.next()) rows.conversation() else null }
        }
    }

    override fun conversations(userId: UUID, ownerKey: String, archived: Boolean, limit: Int, before: Instant?): List<AssistantConversation> = use { connection ->
        connection.prepareStatement(
            """
            SELECT $columns FROM public.assistant_conversations
            WHERE user_id = ? AND owner_key = ? AND (archived_at IS NOT NULL) = ? AND (?::timestamptz IS NULL OR updated_at < ?::timestamptz)
            ORDER BY updated_at DESC, id LIMIT ?
            """.trimIndent(),
        ).use { query ->
            query.setObject(1, userId)
            query.setString(2, ownerKey)
            query.setBoolean(3, archived)
            val at = before?.let(Timestamp::from)
            query.setTimestamp(4, at)
            query.setTimestamp(5, at)
            query.setInt(6, limit)
            query.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.conversation()) } }
        }
    }

    override fun conversationCount(userId: UUID, ownerKey: String): Int = use { connection ->
        connection.prepareStatement("SELECT count(*) FROM public.assistant_conversations WHERE user_id = ? AND owner_key = ?").use { query ->
            query.setObject(1, userId)
            query.setString(2, ownerKey)
            query.executeQuery().use { rows -> rows.next(); rows.getInt(1) }
        }
    }

    override fun updateConversation(id: UUID, userId: UUID, ownerKey: String, title: String?, archived: Boolean?, now: Instant): AssistantConversation? = use { connection ->
        connection.prepareStatement(
            """
            UPDATE public.assistant_conversations
            SET title = COALESCE(?, title),
                archived_at = CASE WHEN ?::boolean IS NULL THEN archived_at WHEN ?::boolean THEN COALESCE(archived_at, ?) ELSE NULL END,
                updated_at = ?
            WHERE id = ? AND user_id = ? AND owner_key = ?
            RETURNING $columns
            """.trimIndent(),
        ).use { update ->
            update.setString(1, title)
            if (archived == null) update.setNull(2, java.sql.Types.BOOLEAN) else update.setBoolean(2, archived)
            if (archived == null) update.setNull(3, java.sql.Types.BOOLEAN) else update.setBoolean(3, archived)
            update.setTimestamp(4, Timestamp.from(now))
            update.setTimestamp(5, Timestamp.from(now))
            update.setObject(6, id)
            update.setObject(7, userId)
            update.setString(8, ownerKey)
            update.executeQuery().use { rows -> if (rows.next()) rows.conversation() else null }
        }
    }

    override fun deleteConversation(id: UUID, userId: UUID, ownerKey: String): Boolean = use { connection ->
        connection.prepareStatement("DELETE FROM public.assistant_conversations WHERE id = ? AND user_id = ? AND owner_key = ?").use { delete ->
            delete.setObject(1, id)
            delete.setObject(2, userId)
            delete.setString(3, ownerKey)
            delete.executeUpdate() > 0
        }
    }

    override fun startTurn(id: UUID, fromState: String, leaseUntil: Instant, now: Instant): UUID? = use { connection ->
        val token = UUID.randomUUID()
        connection.prepareStatement(
            """
            UPDATE public.assistant_conversations SET state = 'running', lease_until = ?, lease_token = ?, updated_at = ?
            WHERE id = ? AND (state = ? OR (state = 'running' AND lease_until < ?))
            """.trimIndent(),
        ).use { update ->
            update.setTimestamp(1, Timestamp.from(leaseUntil))
            update.setObject(2, token)
            update.setTimestamp(3, Timestamp.from(now))
            update.setObject(4, id)
            update.setString(5, fromState)
            update.setTimestamp(6, Timestamp.from(now))
            if (update.executeUpdate() > 0) token else null
        }
    }

    override fun renew(id: UUID, token: UUID, leaseUntil: Instant): Boolean = use { connection ->
        connection.prepareStatement("UPDATE public.assistant_conversations SET lease_until = ? WHERE id = ? AND lease_token = ? AND state = 'running'").use { update ->
            update.setTimestamp(1, Timestamp.from(leaseUntil))
            update.setObject(2, id)
            update.setObject(3, token)
            update.executeUpdate() > 0
        }
    }

    override fun release(id: UUID, token: UUID, state: String, now: Instant): Boolean = use { connection ->
        connection.prepareStatement(
            "UPDATE public.assistant_conversations SET state = ?, lease_until = NULL, lease_token = NULL, updated_at = ? WHERE id = ? AND lease_token = ? AND state = 'running'",
        ).use { update ->
            update.setString(1, state)
            update.setTimestamp(2, Timestamp.from(now))
            update.setObject(3, id)
            update.setObject(4, token)
            update.executeUpdate() > 0
        }
    }

    override fun messages(conversationId: UUID): List<AssistantStoredMessage> = use { connection ->
        connection.prepareStatement("SELECT id, role, content::text, created_at FROM public.assistant_messages WHERE conversation_id = ? ORDER BY id").use { query ->
            query.setObject(1, conversationId)
            query.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) add(AssistantStoredMessage(rows.getLong(1), rows.getString(2), Json.parseToJsonElement(rows.getString(3)).jsonArray, rows.instant("created_at")!!))
                }
            }
        }
    }

    override fun messageCount(conversationId: UUID): Int = use { connection ->
        connection.prepareStatement("SELECT count(*) FROM public.assistant_messages WHERE conversation_id = ? AND role <> 'notice'").use { query ->
            query.setObject(1, conversationId)
            query.executeQuery().use { rows -> rows.next(); rows.getInt(1) }
        }
    }

    private fun insertMessage(connection: Connection, conversationId: UUID, role: String, content: JsonArray, model: String?, stopReason: String?, usage: ModelUsage?, now: Instant): AssistantStoredMessage {
        require(content.isNotEmpty()) { "empty message content" }
        val id = connection.prepareStatement(
            """
            INSERT INTO public.assistant_messages (conversation_id, role, content, model, stop_reason, input_tokens, output_tokens,
                cache_read_input_tokens, cache_creation_input_tokens, created_at)
            VALUES (?, ?, CAST(? AS jsonb), ?, ?, ?, ?, ?, ?, ?) RETURNING id
            """.trimIndent(),
        ).use { insert ->
            insert.setObject(1, conversationId)
            insert.setString(2, role)
            insert.setString(3, content.toString())
            insert.setString(4, model)
            insert.setString(5, stopReason)
            insert.setLong(6, usage?.inputTokens ?: 0)
            insert.setLong(7, usage?.outputTokens ?: 0)
            insert.setLong(8, usage?.cacheReadInputTokens ?: 0)
            insert.setLong(9, usage?.cacheCreationInputTokens ?: 0)
            insert.setTimestamp(10, Timestamp.from(now))
            insert.executeQuery().use { rows -> rows.next(); rows.getLong(1) }
        }
        connection.prepareStatement("UPDATE public.assistant_conversations SET updated_at = ? WHERE id = ?").use { update ->
            update.setTimestamp(1, Timestamp.from(now))
            update.setObject(2, conversationId)
            update.executeUpdate()
        }
        return AssistantStoredMessage(id, role, content, now)
    }

    override fun appendMessage(conversationId: UUID, token: UUID, role: String, content: JsonArray, model: String?, stopReason: String?, usage: ModelUsage?, now: Instant): AssistantStoredMessage =
        transaction { connection ->
            holds(connection, conversationId, token)
            insertMessage(connection, conversationId, role, content, model, stopReason, usage, now)
        }

    override fun lastPromptTokens(conversationId: UUID): Long = use { connection ->
        connection.prepareStatement(
            """
            SELECT input_tokens + cache_read_input_tokens + cache_creation_input_tokens FROM public.assistant_messages
            WHERE conversation_id = ? AND role = 'assistant' ORDER BY id DESC LIMIT 1
            """.trimIndent(),
        ).use { query ->
            query.setObject(1, conversationId)
            query.executeQuery().use { rows -> if (rows.next()) rows.getLong(1) else 0L }
        }
    }

    override fun savePending(token: UUID, calls: List<AssistantPendingCall>) {
        if (calls.isEmpty()) return
        transaction { connection ->
            holds(connection, calls.first().conversationId, token)
            // Rows of an earlier response never survive into this one.
            connection.prepareStatement("DELETE FROM public.assistant_pending_calls WHERE conversation_id = ?").use { delete ->
                delete.setObject(1, calls.first().conversationId)
                delete.executeUpdate()
            }
            connection.prepareStatement(
                """
                INSERT INTO public.assistant_pending_calls (id, conversation_id, position, tool_use_id, tool_name, arguments, label, module_name,
                    destructive, summary, status, result, expires_at, preview, preview_error, outbound, preview_hash)
                VALUES (?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?, ?, CAST(? AS jsonb), ?, CAST(? AS jsonb), ?, CAST(? AS jsonb), ?, ?, ?)
                """.trimIndent(),
            ).use { insert ->
                calls.forEach { call ->
                    insert.setObject(1, call.id)
                    insert.setObject(2, call.conversationId)
                    insert.setInt(3, call.position)
                    insert.setString(4, call.toolUseId)
                    insert.setString(5, call.toolName)
                    insert.setString(6, call.arguments.toString())
                    insert.setString(7, call.label)
                    insert.setString(8, call.moduleName)
                    insert.setBoolean(9, call.destructive)
                    insert.setString(10, call.summary.toString())
                    insert.setString(11, call.status)
                    insert.setString(12, call.result?.toString())
                    insert.setTimestamp(13, Timestamp.from(call.expiresAt))
                    insert.setString(14, call.preview.toString())
                    insert.setString(15, call.previewError)
                    insert.setBoolean(16, call.outbound)
                    insert.setString(17, call.previewHash)
                    insert.addBatch()
                }
                insert.executeBatch()
            }
        }
    }

    private val pendingColumns = "p.id, p.conversation_id, p.position, p.tool_use_id, p.tool_name, p.arguments::text, p.label, p.module_name, " +
        "p.destructive, p.summary::text, p.status, p.result::text, p.expires_at, p.preview::text, p.preview_error, p.outbound, p.preview_hash"

    private fun ResultSet.pendingCall() = AssistantPendingCall(
        id = getObject(1, UUID::class.java),
        conversationId = getObject(2, UUID::class.java),
        position = getInt(3),
        toolUseId = getString(4),
        toolName = getString(5),
        arguments = Json.parseToJsonElement(getString(6)).jsonObject,
        label = getString(7),
        moduleName = getString(8),
        destructive = getBoolean(9),
        summary = Json.parseToJsonElement(getString(10)).jsonArray,
        status = getString(11),
        result = getString(12)?.let { Json.parseToJsonElement(it).jsonObject },
        expiresAt = getTimestamp(13).toInstant(),
        preview = Json.parseToJsonElement(getString(14)).jsonArray,
        previewError = getString(15),
        outbound = getBoolean(16),
        previewHash = getString(17),
    )

    override fun pending(conversationId: UUID): List<AssistantPendingCall> = use { connection ->
        connection.prepareStatement("SELECT $pendingColumns FROM public.assistant_pending_calls p WHERE p.conversation_id = ? ORDER BY p.position").use { query ->
            query.setObject(1, conversationId)
            query.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.pendingCall()) } }
        }
    }

    override fun pendingCall(id: UUID, userId: UUID, ownerKey: String): AssistantPendingCall? = use { connection ->
        connection.prepareStatement(
            """
            SELECT $pendingColumns FROM public.assistant_pending_calls p
            JOIN public.assistant_conversations c ON c.id = p.conversation_id
            WHERE p.id = ? AND c.user_id = ? AND c.owner_key = ?
            """.trimIndent(),
        ).use { query ->
            query.setObject(1, id)
            query.setObject(2, userId)
            query.setString(3, ownerKey)
            query.executeQuery().use { rows -> if (rows.next()) rows.pendingCall() else null }
        }
    }

    override fun decide(id: UUID, status: String, now: Instant): Boolean = use { connection ->
        connection.prepareStatement("UPDATE public.assistant_pending_calls SET status = ?, decided_at = ? WHERE id = ? AND status = 'pending' AND expires_at > ?").use { update ->
            update.setString(1, status)
            update.setTimestamp(2, Timestamp.from(now))
            update.setObject(3, id)
            update.setTimestamp(4, Timestamp.from(now))
            update.executeUpdate() > 0
        }
    }

    override fun claim(callId: UUID, conversationId: UUID, token: UUID): Boolean = transaction { connection ->
        holds(connection, conversationId, token)
        connection.prepareStatement("UPDATE public.assistant_pending_calls SET status = 'executing' WHERE id = ? AND status = 'approved'").use { update ->
            update.setObject(1, callId)
            update.executeUpdate() > 0
        }
    }

    override fun finish(callId: UUID, conversationId: UUID, token: UUID, result: JsonObject) {
        // The result is kept even when the lease was lost meanwhile: it says truthfully what happened.
        use { connection ->
            connection.prepareStatement("UPDATE public.assistant_pending_calls SET status = 'done', result = CAST(? AS jsonb) WHERE id = ? AND status = 'executing'").use { update ->
                update.setString(1, result.toString())
                update.setObject(2, callId)
                update.executeUpdate()
            }
        }
    }

    override fun completePending(conversationId: UUID, token: UUID, content: JsonArray, now: Instant): AssistantStoredMessage? = transaction { connection ->
        holds(connection, conversationId, token)
        val message = if (content.isEmpty()) null else insertMessage(connection, conversationId, Roles.User, content, null, null, null, now)
        connection.prepareStatement("DELETE FROM public.assistant_pending_calls WHERE conversation_id = ?").use { delete ->
            delete.setObject(1, conversationId)
            delete.executeUpdate()
        }
        message
    }

    override fun expiredConversations(now: Instant): List<UUID> = use { connection ->
        connection.prepareStatement("SELECT DISTINCT conversation_id FROM public.assistant_pending_calls WHERE status = 'pending' AND expires_at <= ?").use { query ->
            query.setTimestamp(1, Timestamp.from(now))
            query.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getObject(1, UUID::class.java)) } }
        }
    }

    override fun stuckConversations(before: Instant): List<UUID> = use { connection ->
        connection.prepareStatement(
            """
            SELECT c.id FROM public.assistant_conversations c
            WHERE c.state = 'awaiting_confirmation'
              AND NOT EXISTS (SELECT 1 FROM public.assistant_pending_calls p WHERE p.conversation_id = c.id AND p.status = 'pending')
              AND COALESCE((SELECT max(p.decided_at) FROM public.assistant_pending_calls p WHERE p.conversation_id = c.id), c.updated_at) < ?
            """.trimIndent(),
        ).use { query ->
            query.setTimestamp(1, Timestamp.from(before))
            query.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getObject(1, UUID::class.java)) } }
        }
    }

    override fun recordUsage(day: LocalDate, userId: UUID, keySource: String, usage: ModelUsage, requests: Long) {
        use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO public.assistant_usage (day, user_id, key_source, requests, input_tokens, output_tokens, cache_read_input_tokens,
                    cache_creation_input_tokens, counted_tokens)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (day, user_id, key_source) DO UPDATE SET
                    requests = assistant_usage.requests + EXCLUDED.requests,
                    input_tokens = assistant_usage.input_tokens + EXCLUDED.input_tokens,
                    output_tokens = assistant_usage.output_tokens + EXCLUDED.output_tokens,
                    cache_read_input_tokens = assistant_usage.cache_read_input_tokens + EXCLUDED.cache_read_input_tokens,
                    cache_creation_input_tokens = assistant_usage.cache_creation_input_tokens + EXCLUDED.cache_creation_input_tokens,
                    counted_tokens = assistant_usage.counted_tokens + EXCLUDED.counted_tokens
                """.trimIndent(),
            ).use { upsert ->
                upsert.setObject(1, day)
                upsert.setObject(2, userId)
                upsert.setString(3, keySource)
                upsert.setLong(4, requests)
                upsert.setLong(5, usage.inputTokens)
                upsert.setLong(6, usage.outputTokens)
                upsert.setLong(7, usage.cacheReadInputTokens)
                upsert.setLong(8, usage.cacheCreationInputTokens)
                upsert.setLong(9, usage.countedTokens)
                upsert.executeUpdate()
            }
        }
    }

    override fun monthUsage(month: YearMonth, keySources: Set<String>): Long = use { connection ->
        connection.prepareStatement(
            "SELECT COALESCE(sum(counted_tokens), 0) FROM public.assistant_usage WHERE day >= ? AND day < ? AND key_source = ANY (?)",
        ).use { query ->
            query.setObject(1, month.atDay(1))
            query.setObject(2, month.plusMonths(1).atDay(1))
            query.setArray(3, connection.createArrayOf("text", keySources.toTypedArray()))
            query.executeQuery().use { rows -> rows.next(); rows.getLong(1) }
        }
    }

    override fun usage(month: YearMonth): List<AssistantUsageRow> = use { connection ->
        connection.prepareStatement(
            """
            SELECT day, user_id, key_source, requests, input_tokens, output_tokens, cache_read_input_tokens, cache_creation_input_tokens, counted_tokens
            FROM public.assistant_usage WHERE day >= ? AND day < ? ORDER BY day, user_id, key_source
            """.trimIndent(),
        ).use { query ->
            query.setObject(1, month.atDay(1))
            query.setObject(2, month.plusMonths(1).atDay(1))
            query.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) {
                        add(
                            AssistantUsageRow(
                                rows.getObject(1, LocalDate::class.java), rows.getObject(2, UUID::class.java), rows.getString(3), rows.getLong(4),
                                ModelUsage(rows.getLong(5), rows.getLong(6), rows.getLong(7), rows.getLong(8)), rows.getLong(9),
                            ),
                        )
                    }
                }
            }
        }
    }

    override fun purge(before: Instant, usageBefore: LocalDate): Int = use { connection ->
        // A turn that crashed leaves its conversation 'running' for ever: once its lease is older than the retention, it is old data like any other.
        val conversations = connection.prepareStatement(
            "DELETE FROM public.assistant_conversations WHERE updated_at < ? AND (state <> 'running' OR lease_until IS NULL OR lease_until < ?)",
        ).use { delete ->
            delete.setTimestamp(1, Timestamp.from(before))
            delete.setTimestamp(2, Timestamp.from(before))
            delete.executeUpdate()
        }
        connection.prepareStatement("DELETE FROM public.assistant_usage WHERE day < ?").use { delete ->
            delete.setObject(1, usageBefore)
            delete.executeUpdate()
        }
        conversations
    }
}
