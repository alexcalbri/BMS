package com.ideasdeveloper.bms.modules.crm

import com.ideasdeveloper.bms.server.i18n.I18n
import com.ideasdeveloper.bms.server.i18n.msg
import com.ideasdeveloper.bms.server.i18n.tr
import com.ideasdeveloper.bms.server.modules.MailException
import com.ideasdeveloper.bms.server.modules.MailSender
import com.ideasdeveloper.bms.server.modules.ModuleNotifier
import com.ideasdeveloper.bms.server.modules.NotificationTarget
import com.ideasdeveloper.bms.server.modules.NotificationText
import com.ideasdeveloper.bms.server.modules.SmtpSettings
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

data class CrmUserSettings(
    val userId: UUID,
    val email: String,
    val notifyNewLeads: Boolean,
    val notifyAssigned: Boolean,
    val dailyDigest: Boolean,
    // "Avisarme por correo cuando me mencionen" (on by default; the email goes to the profile email of the user).
    val notifyMentioned: Boolean = true,
    // Email with each new inbound message of an assigned lead (on by default; the in-app notice is always created).
    val notifyNewMessage: Boolean = true,
    // Email when the follow-up of an assigned lead is due (on by default; the in-app notice is always created).
    val notifyFollowUp: Boolean = true,
)

/** Email notifications of the CRM. Messages are queued in crm_outbox and sent by a job. */
object CrmNotify {
    private const val MaxAttempts = 5

    fun userSettings(tenant: Connection): Map<UUID, CrmUserSettings> =
        tenant.prepareStatement("SELECT user_id, email, notify_new_leads, notify_assigned, daily_digest, notify_mentioned, notify_new_message, notify_followup FROM crm_user_settings").use { query ->
            query.executeQuery().use { rows ->
                buildMap {
                    while (rows.next()) {
                        val id = rows.getObject(1, UUID::class.java)
                        put(id, CrmUserSettings(id, rows.getString(2), rows.getBoolean(3), rows.getBoolean(4), rows.getBoolean(5), rows.getBoolean(6), rows.getBoolean(7), rows.getBoolean(8)))
                    }
                }
            }
        }

    private val UserLabels = linkedMapOf(
        "email" to "Correo de avisos", "notifyNewLeads" to "Avisar de leads nuevos", "notifyAssigned" to "Avisar de asignaciones", "dailyDigest" to "Resumen diario",
        "notifyMentioned" to "Avisar de menciones", "notifyNewMessage" to "Avisar de mensajes nuevos", "notifyFollowUp" to "Avisar de seguimientos",
    )

    private fun userValues(settings: CrmUserSettings?) = mapOf(
        "email" to settings?.email, "notifyNewLeads" to settings?.notifyNewLeads, "notifyAssigned" to settings?.notifyAssigned, "dailyDigest" to settings?.dailyDigest,
        "notifyMentioned" to settings?.notifyMentioned, "notifyNewMessage" to settings?.notifyNewMessage, "notifyFollowUp" to settings?.notifyFollowUp,
    )

    /** [userName]: the user the notices are for, for the activity log. */
    fun saveUserSettings(tenant: Connection, settings: CrmUserSettings, actor: com.ideasdeveloper.bms.server.activity.ActivityActor, userName: String = "") = CrmAudit.atomically(tenant) {
        if (settings.email.isNotEmpty() && !CrmContacts.isEmail(settings.email)) throw CrmValidationException(tr("Escribe un correo valido o dejalo vacio."))
        val before = userSettings(tenant)[settings.userId]
        tenant.prepareStatement(
            """
            INSERT INTO crm_user_settings (user_id, email, notify_new_leads, notify_assigned, daily_digest, notify_mentioned, notify_new_message, notify_followup)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (user_id) DO UPDATE SET email = EXCLUDED.email, notify_new_leads = EXCLUDED.notify_new_leads,
                notify_assigned = EXCLUDED.notify_assigned, daily_digest = EXCLUDED.daily_digest, notify_mentioned = EXCLUDED.notify_mentioned,
                notify_new_message = EXCLUDED.notify_new_message, notify_followup = EXCLUDED.notify_followup
            """.trimIndent()
        ).use { upsert ->
            upsert.setObject(1, settings.userId)
            upsert.setString(2, settings.email)
            upsert.setBoolean(3, settings.notifyNewLeads)
            upsert.setBoolean(4, settings.notifyAssigned)
            upsert.setBoolean(5, settings.dailyDigest)
            upsert.setBoolean(6, settings.notifyMentioned)
            upsert.setBoolean(7, settings.notifyNewMessage)
            upsert.setBoolean(8, settings.notifyFollowUp)
            upsert.executeUpdate()
        }
        val who = userName.ifBlank { "un usuario" }
        // A user without saved settings starts from the defaults (what the screens show), so the first save logs only what they changed.
        val start = before ?: CrmUserSettings(settings.userId, "", false, true, false)
        CrmAudit.settingsChanged(tenant, actor, "Avisos de $who", "Cambio los avisos del CRM de $who.", userValues(start), userValues(settings), UserLabels)
    }

    /** The hour the daily digest goes out (0-23). */
    fun setDigestHour(tenant: Connection, hour: Int, actor: com.ideasdeveloper.bms.server.activity.ActivityActor) = CrmAudit.atomically(tenant) {
        val before = tenant.prepareStatement("SELECT digest_hour FROM crm_settings WHERE id = 1").use { query -> query.executeQuery().use { rows -> if (rows.next()) rows.getInt(1) else null } }
        tenant.prepareStatement("UPDATE crm_settings SET digest_hour = ? WHERE id = 1").use { it.setInt(1, hour); it.executeUpdate() }
        CrmAudit.settingsChanged(tenant, actor, "Resumen diario", "Cambio la hora del resumen diario.", mapOf("hour" to before), mapOf("hour" to hour), mapOf("hour" to "Hora del resumen"))
    }

    // Staff emails and notices are written in the language of each recipient (user, else company, else Spanish).
    internal fun <T> forRecipient(tenant: Connection, userId: UUID, block: () -> T): T {
        val language = try {
            CrmCustomerEvents.access?.recipientLanguage(tenant, userId)
        } catch (_: Exception) {
            null
        }
        return if (language == null) block() else I18n.withLanguage(language, block)
    }

    /** Email of "Nuevo mensaje": the one the user wrote in Equipo, only when they did not turn it off. Null: no email. */
    fun newMessageEmailTarget(settings: CrmUserSettings?): String? =
        settings?.takeIf { it.notifyNewMessage }?.email?.trim()?.takeIf { it.isNotEmpty() }

    /** Email of "Seguimiento pendiente": the one the user wrote in Equipo, only when they did not turn it off. Null: no email. */
    fun followUpEmailTarget(settings: CrmUserSettings?): String? =
        settings?.takeIf { it.notifyFollowUp }?.email?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * A new inbound message of [leadId] (already stored): every assigned user of the lead gets "Nuevo mensaje de ..."
     * in the notification center (it opens the lead) and, if notify_new_message is on and they have an email in Equipo,
     * an email (outbox kind "new_message"). Users that are not assigned get nothing. Never fails the caller.
     */
    fun newMessage(tenant: Connection, notifier: ModuleNotifier?, leadId: UUID, message: String, channel: String) {
        runCatching {
            val lead = CrmRepository.lead(tenant, leadId) ?: return
            if (lead.ownerUserIds.isEmpty()) return
            val settings = userSettings(tenant)
            val text = excerpt(message)
            lead.ownerUserIds.distinct().forEach { userId ->
                runCatching {
                    notifier?.notify(tenant, userId, NotificationTarget("lead", mapOf("id" to leadId.toString()))) { NotificationText(tr("Nuevo mensaje de {0}", lead.name), text) }
                }.onFailure { log.warn("No se pudo crear el aviso de mensaje nuevo ({}).", it.javaClass.simpleName) }
                runCatching {
                    newMessageEmailTarget(settings[userId])?.let { to ->
                        forRecipient(tenant, userId) {
                            val intro = tr("Escribio {0} por {1}.\n\nMensaje:\n{2}", lead.name, CrmInbox.shownLabel(channel), message.trim().take(1000))
                            enqueue(tenant, "new_message", to, leadEmail(tr("Nuevo mensaje de {0}", lead.name), intro, tenant, lead), lead.id)
                        }
                    }
                }.onFailure { log.warn("No se pudo preparar el correo de mensaje nuevo ({}).", it.javaClass.simpleName) }
            }
        }.onFailure { log.warn("No se pudo avisar un mensaje nuevo ({}).", it.javaClass.simpleName) }
    }

    /**
     * The rule of [followUpsDue] for one lead (the SQL applies the same one): the date arrived (today or before), the
     * hour passed, the lead is open and that date was not noticed yet. A new date re-arms it.
     */
    fun followUpDue(followUpOn: LocalDate?, notifiedOn: LocalDate?, open: Boolean, gate: CrmExit.Gate): Boolean =
        open && gate.hourPassed && followUpOn != null && !followUpOn.isAfter(gate.today) && notifiedOn != followUpOn

    /**
     * Follow-ups due: open leads whose follow-up date arrived (today or before in the company's zone, once the digest
     * hour passed) and were not noticed for that date. Each is marked with one atomic UPDATE ... RETURNING (two
     * runs never notice the same date twice; a new date re-arms it), then every assigned user gets "Seguimiento
     * pendiente: ..." in the notification center and, if notify_followup is on and they have an email, an email.
     * Returns how many leads were noticed.
     */
    fun followUpsDue(tenant: Connection, notifier: ModuleNotifier?, now: Instant): Int {
        val hour = tenant.prepareStatement("SELECT digest_hour FROM crm_settings WHERE id = 1").use { query ->
            query.executeQuery().use { rows -> if (rows.next()) rows.getInt(1) else return 0 }
        }
        val gate = CrmZone.gate(CrmZone.of(tenant), hour, now)
        if (!gate.hourPassed) return 0
        val due = tenant.prepareStatement(
            """
            UPDATE crm_leads SET follow_up_notified_on = follow_up_on
            WHERE follow_up_on <= ? AND follow_up_notified_on IS DISTINCT FROM follow_up_on
                AND closed_at IS NULL AND archived_at IS NULL
                AND EXISTS (SELECT 1 FROM crm_lead_owners o WHERE o.lead_id = crm_leads.id)
            RETURNING id
            """.trimIndent()
        ).use { update ->
            update.setObject(1, gate.today)
            update.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getObject(1, UUID::class.java)) } }
        }
        if (due.isEmpty()) return 0
        val settings = userSettings(tenant)
        due.forEach { leadId ->
            runCatching {
                val lead = CrmRepository.lead(tenant, leadId) ?: return@runCatching
                fun since() = lead.followUpOn?.let { if (it.isBefore(gate.today)) tr("Venció el {0}.", it) else tr("Es para hoy.") }.orEmpty()
                lead.ownerUserIds.distinct().forEach { userId ->
                    runCatching {
                        notifier?.notify(tenant, userId, NotificationTarget("lead", mapOf("id" to leadId.toString()))) { NotificationText(tr("Seguimiento pendiente: {0}", lead.name), since()) }
                    }.onFailure { log.warn("No se pudo crear el aviso de seguimiento ({}).", it.javaClass.simpleName) }
                    runCatching {
                        followUpEmailTarget(settings[userId])?.let { to ->
                            forRecipient(tenant, userId) {
                                enqueue(tenant, "follow_up", to, leadEmail(tr("Seguimiento pendiente: {0}", lead.name), tr("Tienes un seguimiento pendiente. {0}", since()), tenant, lead), lead.id)
                            }
                        }
                    }.onFailure { log.warn("No se pudo preparar el correo de seguimiento ({}).", it.javaClass.simpleName) }
                }
            }.onFailure { log.warn("No se pudo avisar un seguimiento ({}).", it.javaClass.simpleName) }
        }
        return due.size
    }

    /** Which leads a company user sees, as [CrmRepository.scope] does for a signed-in user. */
    fun userScope(tenant: Connection, userId: UUID): CrmScope {
        val owner = tenant.prepareStatement("SELECT 1 FROM business_owner WHERE user_id = ?").use { query ->
            query.setObject(1, userId)
            query.executeQuery().use { it.next() }
        }
        val granted = tenant.prepareStatement("SELECT permission_id FROM application_user_permissions WHERE user_id = ?").use { query ->
            query.setObject(1, userId)
            query.executeQuery().use { rows -> buildSet { while (rows.next()) add(rows.getString(1)) } }
        }
        return CrmScope(
            userId = userId,
            seesAll = owner || CrmScreens.SeeAll in granted,
            reception = owner || CrmScreens.Reception in granted,
            interestsEnabled = CrmRepository.interestsEnabled(tenant),
            interests = CrmRepository.userInterests(tenant)[userId].orEmpty(),
        )
    }

    fun enqueue(tenant: Connection, kind: String, to: String, email: CrmEmail, leadId: UUID? = null) {
        tenant.prepareStatement("INSERT INTO crm_outbox (id, lead_id, kind, to_email, subject, html, text) VALUES (?, ?, ?, ?, ?, ?, ?)").use { insert ->
            insert.setObject(1, UUID.randomUUID())
            insert.setObject(2, leadId)
            insert.setString(3, kind)
            insert.setString(4, to)
            insert.setString(5, email.subject.take(300))
            insert.setString(6, email.html)
            insert.setString(7, email.text)
            insert.executeUpdate()
        }
    }

    // New lead: users that asked for it and see every lead (administrators and brokers).
    fun newLead(tenant: Connection, leadId: UUID) {
        val lead = CrmRepository.lead(tenant, leadId) ?: return
        userSettings(tenant).values.filter { it.notifyNewLeads && it.email.isNotEmpty() }.forEach { user ->
            val scope = userScope(tenant, user.userId)
            if (!scope.seesAll || !scope.canSee(lead.interestId, lead.ownerUserIds)) return@forEach
            forRecipient(tenant, user.userId) {
                enqueue(tenant, "new_lead", user.email, leadEmail(tr("Lead nuevo: {0}", lead.name), tr("Entro un lead nuevo en el CRM."), tenant, lead), lead.id)
            }
        }
    }

    /**
     * Users were added to the assigned ones of a lead: each one except [byUserId] (who assigned) gets "Te asignaron
     * un lead" in the notification center of the Core (it opens the lead) and, when they turned it on
     * (notify_assigned), the email too. A failing notification never fails the change that caused it.
     */
    fun assigned(tenant: Connection, notifier: ModuleNotifier?, leadId: UUID, added: Collection<UUID>, byUserId: UUID?) {
        val targets = notifyTargets(added, byUserId)
        if (targets.isEmpty()) return
        runCatching {
            val lead = CrmRepository.lead(tenant, leadId) ?: return
            // One read of the users' settings for the whole batch.
            val settings = userSettings(tenant)
            targets.forEach { userId ->
                runCatching {
                    notifier?.notify(tenant, userId, NotificationTarget("lead", mapOf("id" to leadId.toString()))) { NotificationText(tr(AssignedTitle), lead.name) }
                }.onFailure { log.warn("No se pudo crear el aviso de asignacion de un lead ({}).", it.javaClass.simpleName) }
                runCatching { forRecipient(tenant, userId) { assignedEmail(tenant, lead, settings[userId]) } }
                    .onFailure { log.warn("No se pudo preparar el correo de asignacion de un lead ({}).", it.javaClass.simpleName) }
            }
        }.onFailure { log.warn("No se pudieron avisar las asignaciones de un lead ({}).", it.javaClass.simpleName) }
    }

    private val log = LoggerFactory.getLogger(CrmNotify::class.java)

    val AssignedTitle = msg("Te asignaron un lead")

    /** Whom to tell about an assignment: the added users, each once, never the one who did it (self-assignment). */
    fun notifyTargets(added: Collection<UUID>, byUserId: UUID?): List<UUID> = added.distinct().filter { it != byUserId }

    // Email of "Te asignaron un lead", only for users that asked for it and have an email.
    private fun assignedEmail(tenant: Connection, lead: CrmLead, settings: CrmUserSettings?) {
        val user = settings?.takeIf { it.notifyAssigned && it.email.isNotEmpty() } ?: return
        enqueue(tenant, "assigned", user.email, leadEmail(tr("Te asignaron a {0}", lead.name), tr("Te asignaron este lead en el CRM."), tenant, lead), lead.id)
    }

    val MentionedTitle = msg("Te mencionaron en un lead")
    private const val MentionExcerpt = 140

    /** The note as the notice shows it: one line, cut to [max] characters. */
    fun excerpt(body: String, max: Int = MentionExcerpt): String {
        val line = body.trim().replace(Regex("\\s+"), " ")
        return if (line.length > max) line.take(max - 3).trimEnd() + "..." else line
    }

    /** Text of "Te mencionaron en un lead": the lead, who wrote the note and the note cut short. */
    fun mentionText(leadName: String, by: String, body: String): String = "$leadName - $by: ${excerpt(body)}"

    /**
     * Where the email of a mention goes: the profile email of the user, only when they did not turn it off
     * (notify_mentioned, on by default: a user without a settings row gets it) and have an email. Null: no email.
     */
    fun mentionEmailTarget(user: CrmUser, settings: CrmUserSettings?): String? =
        user.email.trim().takeIf { it.isNotEmpty() && (settings?.notifyMentioned ?: true) }

    /**
     * [mentioned] were mentioned in a note of the lead ([CrmMentions.addNote] returns the ones to tell: never the writer, never
     * twice for the same note): each gets "Te mencionaron en un lead" in the notification center of the Core (it opens
     * the lead; the text is the note cut short) and, if they did not turn it off, an email to their profile email.
     * A failing notification never fails the note that caused it.
     */
    fun mentioned(tenant: Connection, notifier: ModuleNotifier?, leadId: UUID, body: String, by: CrmActor, mentioned: Collection<CrmUser>) {
        if (mentioned.isEmpty()) return
        runCatching {
            val lead = CrmRepository.lead(tenant, leadId) ?: return
            val settings = userSettings(tenant)
            val text = mentionText(lead.name, by.name, body)
            mentioned.distinctBy { it.id }.forEach { user ->
                runCatching {
                    notifier?.notify(tenant, user.id, NotificationTarget("lead", mapOf("id" to leadId.toString()))) { NotificationText(tr(MentionedTitle), text) }
                }.onFailure { log.warn("No se pudo crear el aviso de mencion en un lead ({}).", it.javaClass.simpleName) }
                runCatching {
                    mentionEmailTarget(user, settings[user.id])?.let { to ->
                        forRecipient(tenant, user.id) {
                            val intro = tr("{0} te menciono en una nota de este lead.\n\nNota:\n{1}", by.name, body.trim().take(1000))
                            enqueue(tenant, "mentioned", to, leadEmail(tr("Te mencionaron en {0}", lead.name), intro, tenant, lead), lead.id)
                        }
                    }
                }.onFailure { log.warn("No se pudo preparar el correo de mencion en un lead ({}).", it.javaClass.simpleName) }
            }
        }.onFailure { log.warn("No se pudieron avisar las menciones de un lead ({}).", it.javaClass.simpleName) }
    }

    private fun leadEmail(subject: String, intro: String, tenant: Connection, lead: CrmLead): CrmEmail {
        val interest = lead.interestId?.let { id -> CrmRepository.interests(tenant).firstOrNull { it.id == id }?.name }
        val lines = listOfNotNull(
            intro,
            "",
            tr("Nombre: {0}", lead.name),
            lead.email.takeIf { it.isNotEmpty() }?.let { tr("Correo: {0}", it) },
            lead.phone.takeIf { it.isNotEmpty() }?.let { tr("Telefono: {0}", it) },
            interest?.let { tr("Interes: {0}", it) },
            lead.source.takeIf { it.isNotEmpty() }?.let { tr("Origen: {0}", it) },
            lead.message.takeIf { it.isNotBlank() }?.let { tr("\nMensaje:\n{0}", it.take(1000)) },
        )
        return CrmMail.plain(subject, lines.joinToString("\n"))
    }

    /**
     * Daily digest at the company's digest hour: each user that asked for it receives their open
     * leads with a follow-up due (overdue or today; every assigned user gets them); users that see every
     * lead also get the count of open leads without assigned users. Sent once per day.
     */
    fun digestIfDue(tenant: Connection, now: LocalDateTime) {
        val (hour, sentOn) = tenant.prepareStatement("SELECT digest_hour, digest_sent_on FROM crm_settings WHERE id = 1").use { query ->
            query.executeQuery().use { rows -> if (rows.next()) rows.getInt(1) to rows.getObject(2, LocalDate::class.java) else return }
        }
        val today = now.toLocalDate()
        if (now.hour < hour || sentOn == today) return
        val names = CrmNames(CrmRepository.users(tenant), CrmRepository.interests(tenant))
        userSettings(tenant).values.filter { it.dailyDigest && it.email.isNotEmpty() }.forEach { user ->
            val scope = userScope(tenant, user.userId)
            val due = CrmRepository.followUps(tenant, scope, CrmLeadFilter(ownerUserId = user.userId), today, 50)
            val unassigned = if (scope.seesAll) CrmRepository.count(tenant, scope, CrmLeadFilter(unassigned = true, openOnly = true)) else 0
            if (due.isEmpty() && unassigned == 0) return@forEach
            forRecipient(tenant, user.userId) {
            val lines = buildList {
                add(tr("Resumen de tus leads, {0}.", today))
                add("")
                if (due.isEmpty()) add(tr("No tienes seguimientos vencidos ni para hoy.")) else {
                    add(tr("Seguimientos ({0}):", due.size))
                    due.forEach { add("- ${it.name} - ${it.followUpOn}${if (it.followUpOn!!.isBefore(today)) tr(" (vencido)") else ""}") }
                }
                if (unassigned > 0) {
                    add("")
                    add(tr("Leads abiertos sin asignados: {0}.", unassigned))
                }
                add("")
                add(tr("Usuario: {0}", names.user(user.userId)))
            }
            enqueue(tenant, "digest", user.email, CrmMail.plain(tr("Tus leads de hoy"), lines.joinToString("\n")))
            }
        }
        tenant.prepareStatement("UPDATE crm_settings SET digest_sent_on = ? WHERE id = 1").use { it.setObject(1, today); it.executeUpdate() }
    }

    // Ultima vez que se aviso, por empresa: el job corre cada minuto, el aviso sale como mucho cada hora.
    private val noSmtpWarnedAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** Avisa en el log (1 vez por hora y empresa) que hay correos en cola y ningun SMTP con que enviarlos. */
    fun warnNoSmtp(tenant: Connection, companyCode: String, now: Long = System.currentTimeMillis()) {
        val last = noSmtpWarnedAt[companyCode]
        if (last != null && now - last < 3_600_000L) return
        val pending = tenant.prepareStatement("SELECT COUNT(*) FROM crm_outbox WHERE sent_at IS NULL AND attempts < ?").use { query ->
            query.setInt(1, MaxAttempts)
            query.executeQuery().use { rows -> if (rows.next()) rows.getInt(1) else 0 }
        }
        if (pending == 0) return
        noSmtpWarnedAt[companyCode] = now
        org.slf4j.LoggerFactory.getLogger(CrmNotify::class.java).warn("CRM {}: {} correo(s) en cola y no hay SMTP (ni de la empresa ni del servidor).", companyCode, pending)
    }

    /** Sends up to [limit] queued emails; failed ones are retried up to [MaxAttempts] times. */
    fun sendQueued(tenant: Connection, smtp: SmtpSettings?, limit: Int): Int {
        if (smtp == null) return 0
        data class Pending(val id: UUID, val to: String, val subject: String, val html: String, val text: String)
        val pending = tenant.prepareStatement(
            "SELECT id, to_email, subject, html, text FROM crm_outbox WHERE sent_at IS NULL AND attempts < ? ORDER BY created_at LIMIT ?"
        ).use { query ->
            query.setInt(1, MaxAttempts)
            query.setInt(2, limit)
            query.executeQuery().use { rows ->
                buildList { while (rows.next()) add(Pending(rows.getObject(1, UUID::class.java), rows.getString(2), rows.getString(3), rows.getString(4), rows.getString(5))) }
            }
        }
        var sent = 0
        pending.forEach { mail ->
            val error = try {
                MailSender.sendHtml(smtp, mail.to, mail.subject, mail.html, mail.text)
                sent++
                null
            } catch (failure: MailException) {
                org.slf4j.LoggerFactory.getLogger(CrmNotify::class.java).warn("CRM: no se pudo enviar un aviso por correo: {}", failure.cause?.message ?: failure.message)
                failure.message.orEmpty()
            }
            tenant.prepareStatement(
                "UPDATE crm_outbox SET attempts = attempts + 1, sent_at = CASE WHEN ? THEN CURRENT_TIMESTAMP END, last_error = ? WHERE id = ?"
            ).use { update ->
                update.setBoolean(1, error == null)
                update.setString(2, error.orEmpty().take(500))
                update.setObject(3, mail.id)
                update.executeUpdate()
            }
        }
        // Sent mail is kept 30 days.
        tenant.prepareStatement("DELETE FROM crm_outbox WHERE sent_at < CURRENT_TIMESTAMP - INTERVAL '30 days'").use { it.executeUpdate() }
        return sent
    }
}
